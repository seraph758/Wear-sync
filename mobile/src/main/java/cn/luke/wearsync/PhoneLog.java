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
    // 🔥 修改后的动态命名保存方法
public static File saveToFile(Context context) {
    // 1. 获取当前系统时间，格式化为：月日-时分（24小时制，如 0101-0730 代表 1月1号 7点30分）
    java.text.SimpleDateFormat dateFormat = new java.text.SimpleDateFormat("MMdd-HHmm", java.util.Locale.getDefault());
    String timeStamp = dateFormat.format(new java.util.Date());
    
    // 2. 拼接出动态文件名，例如：wearsync-0710-1357.txt
    String fileName = "wearsync-" + timeStamp + ".txt";
    
    // 3. 依然保存在不需要危险权限的公共缓存目录
    File logFile = new File(context.getExternalCacheDir(), fileName);
    
    java.io.FileWriter writer = null;
    try {
        writer = new java.io.FileWriter(logFile, false); // false 表示写新文件，不追加
        for (String line : getLogBuffer()) {
            writer.write(line + "\n");
        }
        writer.flush();
        
        // 🌟 顺手打个日志，方便在界面调试时确认生成的文件名
        Log.d("PhoneLog", "成功生成动态日志文件: " + fileName);
        return logFile;
    } catch (java.io.IOException e) {
        Log.e("PhoneLog", "动态命名保存日志文件失败", e);
        return null;
    } finally {
        if (writer != null) {
            try { writer.close(); } catch (java.io.IOException ignored) {}
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
