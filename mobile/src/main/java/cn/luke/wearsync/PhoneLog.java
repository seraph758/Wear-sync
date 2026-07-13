package cn.luke.wearsync;

import android.os.Environment;
import android.util.Log;
import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class PhoneLog {
    private static final String TAG = "PhoneLog";
    private static final List<String> logBuffer = new ArrayList<>();
    private static final int MAX_BUFFER_SIZE = 2000;

    public static boolean DEBUG = true;

    // 🎯 锁定根目录：/storage/emulated/0/Download/WearSync
    private static final File baseDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "WearSync");
    public static final File logDir = new File(baseDir, "Log");
    public static final File filesDir = new File(baseDir, "Files");

    static {
        initDirectories();
    }

    public static synchronized void initDirectories() {
        try {
            if (!baseDir.exists()) baseDir.mkdirs();
            if (!logDir.exists()) logDir.mkdirs();
            if (!filesDir.exists()) filesDir.mkdirs();
            Log.d(TAG, "WearSync 专属目录初始化成功: " + baseDir.getAbsolutePath());
        } catch (Exception e) {
            Log.e(TAG, "初始化创建目录失败", e);
        }
    }

    // 获取格式化时间戳的内部工具
    private static String getSystemTime() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(new Date());
    }

    // ================================================================
    // 📱 [PHONE] 本地端业务日志拦截生成器（完美匹配你要求的自动化格式）
    // ================================================================

    public static void d(String tag, String msg) {
        Log.d(tag, msg);
        append("[PHONE] [" + getSystemTime() + "] D/" + tag + ": " + msg);
    }

    public static void w(String tag, String msg) {
        Log.w(tag, msg);
        append("[PHONE] [" + getSystemTime() + "] W/" + tag + ": " + msg);
    }

    public static void e(String tag, String msg) {
        Log.e(tag, msg);
        append("[PHONE] [" + getSystemTime() + "] E/" + tag + ": " + msg);
    }

    public static void e(String tag, String msg, Throwable tr) {
        Log.e(tag, msg, tr);
        append("[PHONE] [" + getSystemTime() + "] E/" + tag + ": " + msg + " (Exception: " + tr.getMessage() + ")");
    }

    public static void i(String tag, String msg) {
        Log.i(tag, msg);
        append("[PHONE] [" + getSystemTime() + "] I/" + tag + ": " + msg);
    }

    public static void v(String tag, String msg) {
        Log.v(tag, msg);
        append("[PHONE] [" + getSystemTime() + "] V/" + tag + ": " + msg);
    }

    // ================================================================
    // ⌚ [WEAR] 手表端无线流日志拦截接收器
    // ================================================================
    
    public static void rawAppend(String line) {
        if (line == null || line.trim().isEmpty()) return;
        
        // 判定手表发过来的行数据是否已经自带了 [WEAR] 标签
        if (line.contains("[WEAR]")) {
            append(line);
        } else {
            // 如果只有裸日志，贴心地替它补全标准格式头
            append("[WEAR] [" + getSystemTime() + "] " + line);
        }
    }

    // ==========================================
    // 底层不加逻辑的纯净数据写入大舱
    // ==========================================

    public static synchronized void append(String finalLine) {
        if (logBuffer.size() >= MAX_BUFFER_SIZE) {
            logBuffer.remove(0);
        }
        logBuffer.add(finalLine);

        // 实时追加到本地 current_log.txt
        appendToFile(finalLine);
    }

    public static synchronized List<String> getLogBuffer() {
        return new ArrayList<>(logBuffer);
    }

    public static synchronized void clear() {
        logBuffer.clear();
    }

    private static void appendToFile(String line) {
        try {
            if (!logDir.exists()) logDir.mkdirs();
            File file = new File(logDir, "current_log.txt");
            try (FileOutputStream fos = new FileOutputStream(file, true)) {
                fos.write((line + "\n").getBytes());
            }
        } catch (Exception e) {
            Log.e(TAG, "实时追加日志到本地文件失败", e);
        }
    }

    public static synchronized File exportBackupFile() {
        try {
            if (!logDir.exists()) logDir.mkdirs();
            File backupFile = new File(logDir, "WearSync_Backup_" + System.currentTimeMillis() + ".txt");
            File currentFile = new File(logDir, "current_log.txt");

            if (currentFile.exists()) {
                try (java.nio.channels.FileChannel source = new java.io.FileInputStream(currentFile).getChannel();
                     java.nio.channels.FileChannel destination = new FileOutputStream(backupFile).getChannel()) {
                    destination.transferFrom(source, 0, source.size());
                }
                return backupFile;
            } else {
                try (FileOutputStream fos = new FileOutputStream(backupFile)) {
                    for (String line : getLogBuffer()) {
                        fos.write((line + "\n").getBytes());
                    }
                }
                return backupFile;
            }
        } catch (Exception e) {
            Log.e(TAG, "备份日志失败", e);
            return null;
        }
    }

    public static synchronized File exportBackupFile(android.content.Context context) {
        return exportBackupFile();
    }
}
