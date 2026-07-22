package cn.luke.wearsync;

import android.content.Context;
import android.os.Environment;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import timber.log.Timber;

/**
 * 手机端日志管理器（Timber 代理版）
 * ✅ 所有 d/w/e/clear/exportBackupFile 方法签名保持不变
 * ✅ 外部调用方零改动
 */
public class PhoneLog {

    private static final String TAG = "PhoneLog_Core";
    public static boolean DEBUG = true;

    // 日志存储目录：/storage/emulated/0/Download/WearSync/Log/
    private static File logDir;
    private static File phoneLogFile;
    private static File wearLogFile;

    /**
     * ⚠️ 必须在 Application.onCreate() 中首先调用
     */
    public static void init(boolean isDebug, Context context) {
        DEBUG = isDebug;

        // 1. 初始化日志目录和文件
        logDir = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS), "WearSync/Log");
        if (!logDir.exists()) logDir.mkdirs();

        phoneLogFile = new File(logDir, "phone_log.txt");
        wearLogFile = new File(logDir, "wear_log.txt");

        // 2. 根据环境种植不同的日志树
        if (isDebug) {
            Timber.plant(new Timber.DebugTree());
        } else {
            Timber.plant(new PhoneFileTree(phoneLogFile));
            Timber.plant(new WearFileTree(wearLogFile));
        }

        Timber.i("PhoneLog 初始化完成 | DEBUG=%b | logDir=%s", isDebug, logDir.getAbsolutePath());
    }

    // ==================== 对外 API（签名完全不变）====================

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
     * 接收手表端发来的日志 → 写入 wear_log.txt
     * ✅ 此方法签名不变，WearSyncListenerService 无需改动
     */
    public static void appendFromRemote(String line) {
        if (line == null || line.trim().isEmpty()) return;

        String finalLine = line;
        if (!line.contains("[WEAR]") && !line.contains("[TEST]")) {
            finalLine = "[WEAR] [" + getSystemTime() + "] " + line;
        }

        // 通过特定 tag 路由到 WearFileTree
        Timber.tag("WEAR_LOG").i(finalLine);
    }

    /** 清空所有日志文件 */
    public static synchronized void clear() {
        try {
            if (phoneLogFile != null && phoneLogFile.exists()) {
                //noinspection ResultOfMethodCallIgnored
                phoneLogFile.delete();
                phoneLogFile.createNewFile();
            }
            if (wearLogFile != null && wearLogFile.exists()) {
                //noinspection ResultOfMethodCallIgnored
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
     * ✅ 返回值类型不变，UI 层调用方无需改动
     */
    public static synchronized File exportBackupFile() {
        try {
            long ts = System.currentTimeMillis();
            File phoneBackup = new File(logDir, "Phone_Backup_" + ts + ".txt");
            File wearBackup = new File(logDir, "Wear_Backup_" + ts + ".txt");

            copyFile(phoneLogFile, phoneBackup);
            copyFile(wearLogFile, wearBackup);

            Timber.i("备份成功: %s, %s", phoneBackup.getName(), wearBackup.getName());
            return phoneBackup; // 保持原接口兼容
        } catch (Exception e) {
            Log.e(TAG, "备份失败", e);
            return null;
        }
    }

    // ==================== 内部实现 ====================

    private static String getSystemTime() {
        return new SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(new Date());
    }

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

    /** 手机日志 → phone_log.txt */
    private static class PhoneFileTree extends Timber.Tree {
        private final File file;

        PhoneFileTree(File file) { this.file = file; }

        @Override
        protected void log(int priority, String tag, String message, Throwable t) {
            // WEAR_LOG tag 的日志不走这棵树
            if ("WEAR_LOG".equals(tag)) return;

            String level = getLevelChar(priority);
            String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS",
                    Locale.getDefault()).format(new Date());
            String line = String.format("%s %s/%s: %s", timestamp, level, tag, message);

            writeToFile(file, line, t);
        }
    }

    /** 手表日志 → wear_log.txt */
    private static class WearFileTree extends Timber.Tree {
        private final File file;

        WearFileTree(File file) { this.file = file; }

        @Override
        protected void log(int priority, String tag, String message, Throwable t) {
            // 只处理 WEAR_LOG tag
            if (!"WEAR_LOG".equals(tag)) return;

            String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS",
                    Locale.getDefault()).format(new Date());
            String line = String.format("%s %s", timestamp, message);

            writeToFile(file, line, t);
        }
    }

    /** 通用文件写入（追加模式） */
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
            // 这里只能用原生 Log，避免死循环
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
