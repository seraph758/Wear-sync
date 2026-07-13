package cn.luke.wearsync;

import android.util.Log;
import java.io.OutputStream;

public class WearLog {
    // 🚀 完美修复：重新加回全局 DEBUG 变量，解决 WearSyncListenerService 编译报错
    // 默认保持为 true。如果手机端发来指令想同步，或者你想在本地彻底关闭原生调试日志，都可以继续使用它
    public static boolean DEBUG = true; 

    // 手表端保持一个与手机建立的专属管道流
    private static OutputStream logOutputStream = null;

    // 提供给手表端通讯管理类，通道建立时传入
    public static synchronized void setLogOutputStream(OutputStream os) {
        logOutputStream = os;
    }

    public static void d(String tag, String msg) {
        // 只有 DEBUG 为 true 时才进行原生打印和无线传输
        if (DEBUG) {
            Log.d(tag, msg); 
            sendToPhone("D", tag, msg);
        }
    }

    public static void w(String tag, String msg) {
        if (DEBUG) {
            Log.w(tag, msg);
            sendToPhone("W", tag, msg);
        }
    }

    public static void e(String tag, String msg) {
        Log.e(tag, msg);
        sendToPhone("E", tag, msg);
    }

    public static void e(String tag, String msg, Throwable tr) {
        Log.e(tag, msg, tr);
        sendToPhone("E", tag, msg + "\n" + Log.getStackTraceString(tr));
    }

    // 🚀 按需传输核心：手机关闭开关时，手表绝对不费电
    private static void sendToPhone(String level, String tag, String msg) {
        if (logOutputStream == null) {
            return;
        }

        try {
            String line = "[WEAR] " + level + "/" + tag + ": " + msg + "\n";
            synchronized (WearLog.class) {
                if (logOutputStream != null) {
                    logOutputStream.write(line.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    logOutputStream.flush();
                }
            }
        } catch (Exception e) {
            Log.e("WearLog", "无线日志管道写入异常，已自动熔断关闭传输", e);
            // 🎯 核心修复：发生断连或流异常时，同步锁内外必须全部强制切断，回归零功耗
            synchronized (WearLog.class) {
                logOutputStream = null;
            }
        }
    }
}
