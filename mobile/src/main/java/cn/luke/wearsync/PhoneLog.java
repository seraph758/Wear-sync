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

/**
 * 手机端日志管理器（Timber 代理版）
 */
public class PhoneLog {
    private static final String TAG = "PhoneLog_Core";
    public static boolean DEBUG = true;
    private static File logDir;
    private static File phoneLogFile;
    private static File wearLogFile;

    public static void init(boolean isDebug, Context context) {
        DEBUG = isDebug;
        logDir = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS), "WearSync/Log");
        if (!logDir.exists()) logDir.mkdirs();
        phoneLogFile = new File(logDir, "phone_log.txt");
        wearLogFile = new File(logDir, "wear_log.txt");

        if (isDebug) {
            Timber.plant(new Timber.DebugTree());
        } else {
            Timber.plant(new PhoneFileTree(phoneLogFile));
            Timber.plant(new WearFileTree(wearLogFile));
        }
        Timber.i("PhoneLog 初始化完成 | DEBUG=%b | logDir=%s", isDebug, logDir.getAbsolutePath());
    }

    // ==================== 对外 API ====================
    public static void d(String tag, String msg) {
        if (!DEBUG) return;
        Timber.tag(tag).d(msg);
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

    /**
     * 🎯 判断一行日志是否来自手表
     */
    public static boolean isWearLog(String line) {
        return line != null && line.contains("[WEAR]");
    }

    /**
     * 接收手表端发来的日志
     */
    public static void appendFromRemote(String line) {
        if (line == null || line.trim().isEmpty()) return;
        String finalLine = formatWearLogLine(line);
        Timber.tag("WEAR_LOG").i(finalLine);
    }

    /** 清空所有日志文件 */
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
     * 导出备份文件
     */
    public static synchronized File exportBackupFile() {
        try {
            long ts = System.currentTimeMillis();
            File phoneBackup = new File(logDir, "Phone_Backup_" + ts + ".txt");
            File wearBackup = new File(logDir, "Wear_Backup_" + ts + ".txt");
            copyFile(phoneLogFile, phoneBackup);
            copyFile(wearLogFile, wearBackup);
            Timber.i("备份成功: %s, %s", phoneBackup.getName(), wearBackup.getName());
            return phoneBackup;
        } catch (Exception e) {
            Log.e(TAG, "备份失败", e);
            return null;
        }
    }

    /**
     * ✅ 【核心修复】获取最近10分钟的日志内容（手机+手表合并）
     * 同时读取 phone_log.txt 和 wear_log.txt，合并后按时间戳排序返回
     */
    public static List<String> getLatestTenMinutesLogs() {
        List<String> result = new ArrayList<>();
        long tenMinAgo = System.currentTimeMillis() - 10 * 60 * 1000L;
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);

        // 1. 读取手机日志
        readLogsFromFile(phoneLogFile, tenMinAgo, sdf, result);

        // 2. ✅ 新增：读取手表日志
        readLogsFromFile(wearLogFile, tenMinAgo, sdf, result);

        // 3. ✅ 新增：按时间戳排序，保证双端日志交错显示时顺序正确
        Collections.sort(result, (a, b) -> {
            try {
                String timeA = a.substring(0, 19);
                String timeB = b.substring(0, 19);
                return timeA.compareTo(timeB);
            } catch (Exception e) {
                return 0;
            }
        });

        return result;
    }

    /**
     * ✅ 新增：从指定日志文件中读取最近10分钟的日志
     */
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
                    // 无法解析时间戳的行，保守保留
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
            if (!line.startsWith("[WEAR]")) {
                return "[WEAR] " + line;
            }
            return line;
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

