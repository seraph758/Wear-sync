package cn.luke.wearsync;

import android.content.Context;
import android.util.Log;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class PhoneLog {
    public static boolean DEBUG = true; 
    private static final int MAX_LOG_LINES = 150;
    private static final List<String> logBuffer = Collections.synchronizedList(new ArrayList<>());

    public static List<String> getLogBuffer() {
        return new ArrayList<>(logBuffer);
    }

    public static void clear() {
        logBuffer.clear();
    }

    private static void appendToBuffer(String level, String tag, String msg) {
        String time = new java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.getDefault()).format(new java.util.Date());
        logBuffer.add("[" + time + "] " + level + "/" + tag + ": " + msg);
        if (logBuffer.size() > MAX_LOG_LINES) {
            logBuffer.remove(0);
        }
    }

    // 🔥 新增：保存日志到本地文件，并返回文件对象（不需要申请危险的外置存储权限）
    public static File saveToFile(Context context) {
        String fileName = "wearsync_core_log.txt";
        // 存在应用的外部缓存目录，这样用户和分享流都能直接访问
        File logFile = new File(context.getExternalCacheDir(), fileName);
        
        FileWriter writer = null;
        try {
            writer = new FileWriter(logFile, false); // false 表示每次保存都覆盖旧文件，只留最新现场
            for (String line : getLogBuffer()) {
                writer.write(line + "\n");
            }
            writer.flush();
            return logFile;
        } catch (IOException e) {
            Log.e("PhoneLog", "保存日志文件失败", e);
            return null;
        } finally {
            if (writer != null) {
                try { writer.close(); } catch (IOException ignored) {}
            }
        }
    }

    public static void d(String tag, String msg) {
        if (DEBUG) { Log.d(tag, msg); appendToBuffer("D", tag, msg); }
    }

    public static void w(String tag, String msg) {
        if (DEBUG) { Log.w(tag, msg); appendToBuffer("W", tag, msg); }
    }

    public static void e(String tag, String msg) {
        Log.e(tag, msg); appendToBuffer("E", tag, msg);
    }

    public static void e(String tag, String msg, Throwable tr) {
        Log.e(tag, msg, tr); appendToBuffer("E", tag, msg + "\n" + Log.getStackTraceString(tr));
    }
}
