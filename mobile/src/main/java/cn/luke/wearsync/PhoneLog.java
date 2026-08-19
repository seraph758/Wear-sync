package cn.luke.wearsync;

import android.content.Context;
import android.os.Environment;
import android.util.Log;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import timber.log.Timber;
import android.media.MediaScannerConnection;

public class PhoneLog {
    private static final String TAG = "PhoneLog_Core";
    public static boolean DEBUG = true;
    private static File logDir;
    private static File phoneLogFile;
    private static File wearLogFile;
    private static File backupDir;

    public static void init(boolean isDebug, Context context) {
        DEBUG = isDebug;
        logDir = new File(context.getCacheDir(), "WearSync/Log");
        backupDir = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS), "WearSync/Log");
        if (!logDir.exists()) logDir.mkdirs();
        phoneLogFile = new File(logDir, "phone_log.txt");
        wearLogFile = new File(logDir, "wear_log.txt");

        // ✅ 【核心修复】无论 Debug/Release 都注册文件树，确保日志写入文件
        Timber.plant(new PhoneFileTree(phoneLogFile));
        Timber.plant(new WearFileTree(wearLogFile));

        // Debug 模式下额外注册控制台树，方便 Logcat 查看
        if (isDebug) {
            Timber.plant(new Timber.DebugTree());
        }

        Timber.i("PhoneLog 初始化完成 | DEBUG=%b | logDir=%s", isDebug, logDir.getAbsolutePath());
    }

    // ==================== 对外 API ====================
    public static void d(String tag, String msg) {
        if (!DEBUG) return;
        Timber.tag(tag).d(msg);
    }
    public static void w(String tag, String msg, Throwable tr) {
        Log.w(tag, msg, tr);
    }
    public static void i(String tag, String msg) {
        if (!DEBUG) return;
        Timber.tag(tag).i(msg);
    }

    public static void w(String tag, String msg) {
        if (!DEBUG) return;
        Timber.tag(tag).w(msg);
    }

    public static void e(String tag, String msg) {
        if (!DEBUG) return;
        Timber.tag(tag).e(msg);
    }

    public static void e(String tag, String msg, Throwable tr) {
        if (!DEBUG) return;
        Timber.tag(tag).e(tr, msg);
    }

    public static boolean isWearLog(String line) {
        return line != null && line.contains("[WEAR]");
    }

    public static void appendFromRemote(String line) {
        if (line == null || line.trim().isEmpty()) return;
        String finalLine = formatWearLogLine(line);
        Timber.tag("WEAR_LOG").i(finalLine);
    }

    public static synchronized void clear() {
        try {
            if (phoneLogFile != null && phoneLogFile.exists()) {
                phoneLogFile.delete();
                phoneLogFile.createNewFile();
            }
            if (wearLogFile != null && wearLogFile.exists()) {
                wearLogFile.delete();
                wearLogFile.createNewFile();
            }
            Timber.i("日志已清空");
        } catch (IOException e) {
            Log.e(TAG, "清空日志失败", e);
        }
    }

    /**
     * 备份文件并通知系统媒体库刷新
     */
    public static synchronized File exportBackupFile(Context context) {
        try {
            if (!backupDir.exists()) backupDir.mkdirs();
            String timeStr = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
                    .format(new Date());
            File phoneBackup = new File(backupDir, "Phone_Backup_" + timeStr + ".txt");
            File wearBackup = new File(backupDir, "Wear_Backup_" + timeStr + ".txt");
            
            copyFile(phoneLogFile, phoneBackup);
            copyFile(wearLogFile, wearBackup);
            Timber.i("备份成功: %s, %s", phoneBackup.getName(), wearBackup.getName());
    
            // 核心修复：手动通知系统媒体库刷新这两个新文件
            if (context != null) {
                String[] paths = new String[]{
                        phoneBackup.getAbsolutePath(), 
                        wearBackup.getAbsolutePath()
                };
                MediaScannerConnection.scanFile(
                        context.getApplicationContext(),
                        paths,
                        null, // 默认为 null，系统会自动根据扩展名判定 mimeType
                        (path, uri) -> Log.d(TAG, "系统媒体库已刷新: " + path + " -> " + uri)
                );
            }
    
            return phoneBackup;
        } catch (Exception e) {
            Log.e(TAG, "备份失败", e);
            return null;
        }
    }

    public static List<String> getLatestTenMinutesLogs() {
        List<String> result = new ArrayList<>();
        long tenMinAgo = System.currentTimeMillis() - 10 * 60 * 1000L;
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
        
        readLogsFromFile(phoneLogFile, tenMinAgo, sdf, result);
        readLogsFromFile(wearLogFile, tenMinAgo, sdf, result);
    
        // ✅ 修正：改为正序排列，最新的日志在最后面
        Collections.sort(result, (a, b) -> {
            String timeA = a.length() > 19 ? a.substring(0, 19) : a;
            String timeB = b.length() > 19 ? b.substring(0, 19) : b;
            // 改为 a 在前，b 在后，实现正序排列
            return timeA.compareTo(timeB); 
        });
        return result;
    }


    private static void readLogsFromFile(File file, long tenMinAgo, SimpleDateFormat sdf, List<String> result) {
        if (file == null || !file.exists()) return;
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                try {
                    String timeStr = line.substring(0, 19);
                    long logTime = sdf.parse(timeStr).getTime();
                    if (logTime >= tenMinAgo) {
                        result.add(line);
                    }
                } catch (Exception e) {
                    result.add(line);
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "读取日志文件失败: " + file.getName(), e);
        }
    }

    // ==================== 日志格式化 ====================
    private static String formatWearLogLine(String rawLine) {
        String line = rawLine.trim();
        Pattern pattern = Pattern.compile("(\\[WEAR\\]\\s*)?(\\[\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3}\\])\\s*");
        Matcher matcher = pattern.matcher(line);

        if (matcher.find()) {
            String contentAfterTimestamp = line.substring(matcher.end()).trim();
            return "[WEAR] " + contentAfterTimestamp;
        } else {
            return line.startsWith("[WEAR]") ? line : "[WEAR] " + line;
        }
    }

    // ==================== 内部实现 ====================
    private static void copyFile(File src, File dst) throws IOException {
        if (src == null || !src.exists()) {
            if (dst != null && !dst.exists()) dst.createNewFile();
            return;
        }
        try (FileInputStream fis = new FileInputStream(src);
             FileChannel in = fis.getChannel();
             FileOutputStream fos = new FileOutputStream(dst);
             FileChannel out = fos.getChannel()) {
            out.transferFrom(in, 0, in.size());
        }
    }

    // ==================== 自定义日志树 ====================
    private static class PhoneFileTree extends Timber.Tree {
        private final File file;
        PhoneFileTree(File file) { this.file = file; }

        @Override
        protected void log(int priority, String tag, String message, Throwable t) {
            if ("WEAR_LOG".equals(tag)) return;
            String level = getLevelChar(priority);
            String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(new Date());
            String line = String.format("%s %s/%s: %s", timestamp, level, tag, message);
            writeToFile(file, line, t);
        }
    }

    private static class WearFileTree extends Timber.Tree {
        private final File file;
        WearFileTree(File file) { this.file = file; }

        @Override
        protected void log(int priority, String tag, String message, Throwable t) {
            if (!"WEAR_LOG".equals(tag)) return;
            String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(new Date());
            String line = String.format("%s %s", timestamp, message);
            writeToFile(file, line, t);
        }
    }

    private static synchronized void writeToFile(File file, String line, Throwable t) {
        try {
            if (!file.exists()) file.createNewFile();
            try (FileOutputStream fos = new FileOutputStream(file, true)) {
                fos.write((line + "\n").getBytes());
                if (t != null) {
                    fos.write((Log.getStackTraceString(t) + "\n").getBytes());
                }
            }
        } catch (IOException e) {
            Log.e("PhoneLog_FileIO", "写入日志文件失败: " + file.getName(), e);
        }
    }

    private static String getLevelChar(int priority) {
        switch (priority) {
            case Log.VERBOSE: return "V";
            case Log.DEBUG:   return "D";
            case Log.INFO:    return "I";
            case Log.WARN:    return "W";
            case Log.ERROR:   return "E";
            default:          return "?";
        }
    }
}

