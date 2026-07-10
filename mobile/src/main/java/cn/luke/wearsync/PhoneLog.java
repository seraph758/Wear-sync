package cn.luke.wearsync;

import android.content.Context;
import android.util.Log;
import java.io.File;
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

    // 手机本地产生的一律加上 [PHONE] 钢印
    private static void appendToBuffer(String level, String tag, String msg) {
        String time = new java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.getDefault()).format(new java.util.Date());
        logBuffer.add("[PHONE] [" + time + "] " + level + "/" + tag + ": " + msg);
        if (logBuffer.size() > MAX_LOG_LINES) {
            logBuffer.remove(0);
        }
    }

    // 🚀 新增：接收无线通道传过来的手表日志，原封不动推入缓冲区（因为手表发来时自带 [WEAR] 标记）
    public static void rawAppend(String fullLine) {
        if (fullLine == null || fullLine.trim().isEmpty()) return;
        logBuffer.add(fullLine);
        if (logBuffer.size() > MAX_LOG_LINES) {
            logBuffer.remove(0);
        }
    }

    // 🚀 升级版：自动在文件最顶端添加简体中文日志格式注释说明书
    public static File saveToFile(Context context) {
        java.text.SimpleDateFormat dateFormat = new java.text.SimpleDateFormat("MMdd-HHmm", java.util.Locale.getDefault());
        String timeStamp = dateFormat.format(new java.util.Date());
        String fileName = "wearsync-" + timeStamp + ".txt";
        File logFile = new File(context.getExternalCacheDir(), fileName);

        java.io.FileWriter writer = null;
        try {
            writer = new java.io.FileWriter(logFile, false);

            // ====== 顶端通用日志格式注释（简体中文） ======
            writer.write("================================================================\n");
            writer.write(" WearSync 自动化联调日志文件说明（Log Format Info）\n");
            writer.write(" 生成时间: " + new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(new java.util.Date()) + "\n");
            writer.write("----------------------------------------------------------------\n");
            writer.write(" 【日志分流与标签说明】\n");
            writer.write(" 1. 开头包含 [PHONE] ➔ 代表手机本地端产生的业务或系统日志。\n");
            writer.write(" 2. 开头包含 [WEAR]  ➔ 代表手表无线网络端实时上报传回的日志。\n");
            writer.write(" \n");
            writer.write(" 【标准单行数据格式】\n");
            writer.write(" [端属性标记] [发生时间.毫秒] 日志级别/业务标签(TAG): 具体执行日志内容\n");
            writer.write(" \n");
            writer.write(" 【日志级别提示】\n");
            writer.write(" D/ 代表 Debug（调试常规流） | W/ 代表 Warn（警告提示） | E/ 代表 Error（核心报错）\n");
            writer.write("================================================================\n\n");

            for (String line : getLogBuffer()) {
                writer.write(line + "\n");
            }
            writer.flush();
            Log.d("PhoneLog", "成功生成带注释的动态日志文件: " + fileName);
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
