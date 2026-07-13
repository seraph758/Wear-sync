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

    // 🎯 开关状态常量兼容（修复 BuildConfig.DEBUG 或其他地方 Unresolved reference: DEBUG）
    public static boolean DEBUG = true;

    // 🎯 锁定根目录：/storage/emulated/0/Download/WearSync
    private static final File baseDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "WearSync");
    public static final File logDir = new File(baseDir, "Log");
    public static final File filesDir = new File(baseDir, "Files");

    // 静态代码块：类加载时自动创建你所需要的全部目录
    static {
        initDirectories();
    }

    /**
     * 提前强制创建 Log 和 Files 目录
     */
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

    // ==========================================
    // 🚀 核心补全：传统 Log 兼容层 (带 Tag 打印)
    // 补上这些方法后，MainFragment 的报错会瞬间消失！
    // ==========================================

    public static void d(String tag, String msg) {
        Log.d(tag, msg); // 同时输出到系统 Logcat
        append("[" + tag + "] D: " + msg); // 捕获到专属大日志舱
    }

    public static void w(String tag, String msg) {
        Log.w(tag, msg);
        append("[" + tag + "] W: " + msg);
    }

    public static void e(String tag, String msg) {
        Log.e(tag, msg);
        append("[" + tag + "] E: " + msg);
    }

    public static void i(String tag, String msg) {
        Log.i(tag, msg);
        append("[" + tag + "] I: " + msg);
    }

    public static void v(String tag, String msg) {
        Log.v(tag, msg);
        append("[" + tag + "] V: " + msg);
    }

    // ==========================================
    // 底层数据流动逻辑
    // ==========================================

    /**
     * 只有当你主动调用 append 时，日志才会被塞进缓冲区和文件
     */
    // 🎯 核心补全：兼容接收手表原始日志行的 rawAppend 方法
    // 补上它后，PhoneSyncListenerService 接收到的手表日志就能完美顺畅地流入并保存了！
    public static void rawAppend(String line) {
        append(line);
    }

    public static synchronized void append(String message) {
        String timeStamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(new Date());
        String formattedLine = "[" + timeStamp + "] " + message;

        if (logBuffer.size() >= MAX_BUFFER_SIZE) {
            logBuffer.remove(0);
        }
        logBuffer.add(formattedLine);

        // 实时追加到本地 current_log.txt 中，确保手表传过来的日志瞬间落地
        appendToFile(formattedLine);
    }

    public static synchronized List<String> getLogBuffer() {
        return new ArrayList<>(logBuffer);
    }

    public static synchronized void clear() {
        logBuffer.clear();
    }

    /**
     * 实时将单条日志追加写入到本地 file
     */
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

    /**
     * 手动保存/备份一份当前的日志
     */
    public static synchronized File exportBackupFile() {
        try {
            if (!logDir.exists()) logDir.mkdirs();
            File backupFile = new File(logDir, "WearSync_Backup_" + System.currentTimeMillis() + ".txt");
            File currentFile = new File(logDir, "current_log.txt");

            if (currentFile.exists()) {
                // 原生 Java 管道流复制，兼容性最好
                try (java.nio.channels.FileChannel source = new java.io.FileInputStream(currentFile).getChannel();
                     java.nio.channels.FileChannel destination = new FileOutputStream(backupFile).getChannel()) {
                    destination.transferFrom(source, 0, source.size());
                }
                return backupFile;
            } else {
                // 如果实时文件意外不存在，直接拿内存 Buffer 生成
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
    // 🎯 1. 兼容悬浮窗里的传参调用（直接无视传入的参数）
    public static synchronized File exportBackupFile(android.content.Context context) {
        return exportBackupFile();
    }

    // 🎯 2. 兼容 MainFragment 传入 Exception 异常对象的 3 参数 e 打印
    public static void e(String tag, String msg, Throwable tr) {
        android.util.Log.e(tag, msg, tr);
        append("[" + tag + "] E: " + msg + " (Exception: " + tr.getMessage() + ")");
    }

}
