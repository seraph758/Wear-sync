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
            // 🎯 修复：处理 mkdirs() 的布尔返回值，避免 Lint 警告 "Result of 'File.mkdirs()' is ignored"
            if (!baseDir.exists() && !baseDir.mkdirs()) {
                Log.w(TAG, "创建 baseDir 失败");
            }
            if (!logDir.exists() && !logDir.mkdirs()) {
                Log.w(TAG, "创建 logDir 失败");
            }
            if (!filesDir.exists() && !filesDir.mkdirs()) {
                Log.w(TAG, "创建 filesDir 失败");
            }
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
    // 📱 [PHONE] 本地端业务日志拦截生成器
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
    @SuppressWarnings("unused")
    // 🎯 修复：保留这些方法不删，以便你其他代码可能调用它们
    public static void i(String tag, String msg) {
        if (!DEBUG) return;
        Log.i(tag, msg);
        append("[PHONE] [" + getSystemTime() + "] I/" + tag + ": " + msg);
    }
    @SuppressWarnings("unused")
    public static void v(String tag, String msg) {
        if (!DEBUG) return;
        Log.v(tag, msg);
        append("[PHONE] [" + getSystemTime() + "] V/" + tag + ": " + msg);
    }

    // ================================================================
    // ⌚ [WEAR] 手表端无线流日志拦截接收器
    // ================================================================

    // 🎯 修复：将方法名重命名为你的 Service 调用的 appendFromRemote，彻底解决未动用警告
    public static void appendFromRemote(String line) {
        if (line == null || line.trim().isEmpty()) return;

        if (line.contains("[WEAR]")) {
            append(line);
        } else {
            append("[WEAR] [" + getSystemTime() + "] " + line);
        }
    }

    // ==========================================
    // 底层纯净数据写入大舱
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
            if (!logDir.exists() && !logDir.mkdirs()) {
                Log.w(TAG, "创建日志目录失败");
            }
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
            if (!logDir.exists() && !logDir.mkdirs()) {
                Log.w(TAG, "创建日志目录失败");
            }
            File backupFile = new File(logDir, "WearSync_Backup_" + System.currentTimeMillis() + ".txt");
            File currentFile = new File(logDir, "current_log.txt");

            // 🎯 修复：提取了公共的 backupFile 生成，并使用 try-with-resources 安全闭合文件通道，
            // 彻底根除 "FileInputStream used without 'try'-with-resources" 的流泄漏隐患
            if (currentFile.exists()) {
                try (FileInputStream fis = new FileInputStream(currentFile);
                     FileChannel source = fis.getChannel();
                     FileOutputStream fos = new FileOutputStream(backupFile);
                     FileChannel destination = fos.getChannel()) {
                    destination.transferFrom(source, 0, source.size());
                }
            } else {
                try (FileOutputStream fos = new FileOutputStream(backupFile)) {
                    for (String line : getLogBuffer()) {
                        fos.write((line + "\n").getBytes());
                    }
                }
            }
            return backupFile;
        } catch (Exception e) {
            Log.e(TAG, "备份日志失败", e);
            return null;
        }
    }

    // 🎯 修复：给未使用的 context 参数打上 @SuppressWarnings 标记，告诉 Lint 这是故意预留的重载
    public static synchronized File exportBackupFile(@SuppressWarnings("unused") android.content.Context context) {
        return exportBackupFile();
    }
}