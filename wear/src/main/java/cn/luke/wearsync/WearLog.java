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
        // 🔍 核心防费电拦截：
        // 如果手机端把开关关掉了，或者悬浮窗没开，这个 logOutputStream 就必然是 null。
        // 代码在这里会以微秒级速度直接返回，绝对不会唤醒手表的蓝牙或 Wi-Fi 芯片，耗电量为 0！
        if (logOutputStream == null) {
            return;
        }

        try {
            // 只有当手机端打开了开关、通道畅通时，才会执行无线传输
            String line = "[WEAR] " + level + "/" + tag + ": " + msg + "\n";
            synchronized (WearLog.class) {
                if (logOutputStream != null) {
                    logOutputStream.write(line.getBytes("UTF-8"));
                    logOutputStream.flush(); 
                }
            }
        } catch (Exception e) {
            // 一旦手表走远断连、或者手机把开关关闭了，写入失败会自动熔断，立刻置空，恢复零功耗状态
            Log.e("WearLog", "无线日志管道写入异常，已自动熔断关闭传输", e);
            logOutputStream = null;
        }
    }
}
