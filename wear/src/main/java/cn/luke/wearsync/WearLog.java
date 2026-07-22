package cn.luke.wearsync;

import android.util.Log;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public class WearLog {
    // 全局开关：false 时所有级别日志彻底静默，零功耗
    public static volatile boolean DEBUG = true;

    private static OutputStream logOutputStream = null;

    public static synchronized void setLogOutputStream(OutputStream os) {
        logOutputStream = os;
    }

    public static void d(String tag, String msg) {
        if (!DEBUG) return; // ✅ 统一守卫
        Log.d(tag, msg);
        sendToPhone("D", tag, msg);
    }

    public static void i(String tag, String msg) {
        if (!DEBUG) return; // ✅ 统一守卫
        Log.i(tag, msg);
        sendToPhone("I", tag, msg);
    }

    public static void w(String tag, String msg) {
        if (!DEBUG) return; // ✅ 统一守卫
        Log.w(tag, msg);
        sendToPhone("W", tag, msg);
    }

    // 🔴 关键修复：e() 也必须受 DEBUG 控制
    public static void e(String tag, String msg) {
        if (!DEBUG) return; // ✅ 补上缺失的守卫
        Log.e(tag, msg);
        sendToPhone("E", tag, msg);
    }

    // 🔴 关键修复：带 Throwable 的 e() 同样需要守卫
    public static void e(String tag, String msg, Throwable tr) {
        if (!DEBUG) return; // ✅ 补上缺失的守卫
        Log.e(tag, msg, tr);
        sendToPhone("E", tag, msg + "\n" + Log.getStackTraceString(tr));
    }

    private static void sendToPhone(String level, String tag, String msg) {
        // DEBUG 为 false 时根本不会进入此方法，无需重复判断
        if (logOutputStream == null) return;

        try {
            String line = "[WEAR] " + level + "/" + tag + ": " + msg + "\n";
            synchronized (WearLog.class) {
                if (logOutputStream != null) {
                    logOutputStream.write(line.getBytes(StandardCharsets.UTF_8));
                    logOutputStream.flush();
                }
            }
        } catch (Exception e) {
            // ⚠️ 注意：这里用原生 Log.e 而非 WearLog.e，避免递归调用
            Log.e("WearLog", "无线日志管道写入异常，已自动熔断", e);
            synchronized (WearLog.class) {
                logOutputStream = null;
            }
        }
    }
}
