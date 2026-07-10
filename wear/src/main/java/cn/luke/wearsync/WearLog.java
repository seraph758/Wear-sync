package cn.luke.wearsync;

import android.util.Log;
import java.io.OutputStream;

public class WearLog {
    private static OutputStream logOutputStream = null;

    // 当手机建立无线连接后，由此传入专属管道流
    public static synchronized void setLogOutputStream(OutputStream os) {
        logOutputStream = os;
    }

    public static void d(String tag, String msg) {
        Log.d(tag, msg); 
        sendToPhone("D", tag, msg);
    }

    public static void w(String tag, String msg) {
        Log.w(tag, msg);
        sendToPhone("W", tag, msg);
    }

    public static void e(String tag, String msg) {
        Log.e(tag, msg);
        sendToPhone("E", tag, msg);
    }

    public static void e(String tag, String msg, Throwable tr) {
        Log.e(tag, msg, tr);
        sendToPhone("E", tag, msg + "\n" + Log.getStackTraceString(tr));
    }

    // 🚀 按需传输防费电核心：手机没开开关或悬浮窗没开时，耗电量严格为 0
    private static void sendToPhone(String level, String tag, String msg) {
        if (logOutputStream == null) {
            return;
        }

        try {
            // 自带 [WEAR] 强身份钢印甩给手机端
            String line = "[WEAR] " + level + "/" + tag + ": " + msg + "\n";
            synchronized (WearLog.class) {
                if (logOutputStream != null) {
                    logOutputStream.write(line.getBytes("UTF-8"));
                    logOutputStream.flush(); 
                }
            }
        } catch (Exception e) {
            Log.e("WearLog", "无线日志流写入异常，已自动熔断恢复省电模式", e);
            logOutputStream = null;
        }
    }
}
