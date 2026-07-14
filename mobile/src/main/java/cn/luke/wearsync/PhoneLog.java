package cn.luke.wearsync;

import android.os.Environment;
import android.util.Log;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.channels.FileChannel;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class PhoneLog {
    private static final String TAG = "PhoneLog_Core";
    private static final List<String> logBuffer = new ArrayList<>();
    private static final int MAX_BUFFER_SIZE = 2000;

    public static boolean DEBUG = true;

    private static final File baseDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "WearSync");
    public static final File logDir = new File(baseDir, "Log");
    public static final File filesDir = new File(baseDir, "Files");

    static {
        initDirectories();
    }

    public static synchronized void initDirectories() {
        try {
            if (!baseDir.exists() && !baseDir.mkdirs()) Log.w(TAG, "创建 baseDir 失败");
            if (!logDir.exists() && !logDir.mkdirs()) Log.w(TAG, "创建 logDir 失败");
            if (!filesDir.exists() && !filesDir.mkdirs()) Log.w(TAG, "创建 filesDir 失败");
        } catch (Exception e) {
            Log.e(TAG, "初始化创建目录失败", e);
        }
    }

    private static String getSystemTime() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(new Date());
    }

    // ================================================================
    // 🪵 统一的日志吞入入口（无论是本地还是远程，都走这里）
    // ================================================================

    public static void d(String tag, String msg) {
        if (!DEBUG) return;
        Log.d(tag, msg);
        append("[PHONE] [" + getSystemTime() + "] D/" + tag + ": " + msg);
    }

    public static void w(String tag, String msg) {
        if (!DEBUG) return;
        Log.w(tag, msg);
        append("[PHONE] [" + getSystemTime() + "] W/" + tag + ": " + msg);
    }

    public static void e(String tag, String msg) {
        if (!DEBUG) return;
        Log.e(tag, msg);
        append("[PHONE] [" + getSystemTime() + "] E/" + tag + ": " + msg);
    }

    public static void e(String tag, String msg, Throwable tr) {
        if (!DEBUG) return;
        Log.e(tag, msg, tr);
        append("[PHONE] [" + getSystemTime() + "] E/" + tag + ": " + msg + " (Exception: " + tr.getMessage() + ")");
    }

    public static void appendFromRemote(String line) {
        if (line == null || line.trim().isEmpty()) return;
        // 🔬 全链路追踪：记录手机端真的收到了流数据
        Log.d("PhoneLog_Trace", "📥 [流接收成功] 底层收到了来自手表的裸行: " + line);
        
        if (line.contains("[WEAR]")) {
            append(line);
        } else {
            append("[WEAR] [" + getSystemTime() + "] " + line);
        }
    }

    private static synchronized void append(String finalLine) {
        if (logBuffer.size() >= MAX_BUFFER_SIZE) {
            logBuffer.remove(0);
        }
        logBuffer.add(finalLine);
        appendToFile(finalLine);
    }

    // ================================================================
    // 🎯 核心改动：统一收拢！统一输出最近 10 分钟内的日志大池子
    // ================================================================
    public static synchronized List<String> getLatestTenMinutesLogs() {
        long currentTime = System.currentTimeMillis();
        long tenMinutesAgo = currentTime - 10 * 60 * 1000;
        SimpleDateFormat timeFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault());
        List<String> filteredList = new ArrayList<>();

        for (String line : logBuffer) {
            try {
                if (line.contains("[") && line.contains("]")) {
                    int firstBracket = line.indexOf('[', 1);
                    int firstCloseBracket = line.indexOf(']', firstBracket);
                    if (firstBracket != -1 && firstCloseBracket != -1) {
                        String timeStr = line.substring(firstBracket + 1, firstCloseBracket);
                        long logTime = timeFormat.parse(timeStr).getTime();
                        if (logTime >= tenMinutesAgo) {
                            filteredList.add(line);
                        }
                        continue;
                    }
                }
            } catch (Exception e) {
                // 解析失败说明是没有时间戳的异常行，选择保留
            }
            filteredList.add(line);
        }
        return filteredList;
    }

    public static synchronized void clear() {
        logBuffer.clear();
    }

    private static void appendToFile(String line) {
        try {
            File file = new File(logDir, "current_log.txt");
            try (FileOutputStream fos = new FileOutputStream(file, true)) {
                fos.write((line + "\n").getBytes());
            }
        } catch (Exception e) {
            Log.e(TAG, "追加到本地失败", e);
        }
    }

    public static synchronized File exportBackupFile() {
        try {
            File backupFile = new File(logDir, "WearSync_Backup_" + System.currentTimeMillis() + ".txt");
            File currentFile = new File(logDir, "current_log.txt");

            if (currentFile.exists()) {
                try (FileInputStream fis = new FileInputStream(currentFile);
                     FileChannel source = fis.getChannel();
                     FileOutputStream fos = new FileOutputStream(backupFile);
                     FileChannel destination = fos.getChannel()) {
                    destination.transferFrom(source, 0, source.size());
                }
            } else {
                try (FileOutputStream fos = new FileOutputStream(backupFile)) {
                    for (String line : logBuffer) {
                        fos.write((line + "\n").getBytes());
                    }
                }
            }
            return backupFile;
        } catch (Exception e) {
            Log.e(TAG, "备份失败", e);
            return null;
        }
    }
}
