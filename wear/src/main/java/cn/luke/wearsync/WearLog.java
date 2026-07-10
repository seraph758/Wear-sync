package cn.luke.wearsync;

import android.util.Log;
import java.io.OutputStream;

public class WearLog {
    // 🔒 手錶端只需要保留一個與手機建立的專屬管道流。
    // 手機端打開開關時，通道建立，它就不為 null；手機端關閉開關或懸浮窗關閉時，它會自動被置為 null。
    private static OutputStream logOutputStream = null;

    // 提供給手錶端通訊管理類（如 WearableListenerService），通道建立時傳入
    public static synchronized void setLogOutputStream(OutputStream os) {
        logOutputStream = os;
    }

    public static void d(String tag, String msg) {
        // 原生 Logcat 依然打印，方便用電腦聯調
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

    // 🚀 按需傳輸核心：手機關閉開關時，手錶絕對不費電
    private static void sendToPhone(String level, String tag, String msg) {
        // 🔍 核心防費電攔截：
        // 如果手機端把開關關掉了，或者懸浮窗沒開，這個 logOutputStream 就必然是 null。
        // 代碼在這裡會以微秒級速度直接返回，絕對不會喚醒手錶的藍牙或 Wi-Fi 晶片，耗電量為 0！
        if (logOutputStream == null) {
            return;
        }

        try {
            // 只有當手機端打開了開關、通道暢通時，才會執行無線傳輸
            String line = "[WEAR] " + level + "/" + tag + ": " + msg + "\n";
            synchronized (WearLog.class) {
                if (logOutputStream != null) {
                    logOutputStream.write(line.getBytes("UTF-8"));
                    logOutputStream.flush(); 
                }
            }
        } catch (Exception e) {
            // 一旦手錶走遠斷連、或者手機把開關關閉了，寫入失敗會自動熔斷，立刻置空，恢復零功耗狀態
            Log.e("WearLog", "無線日誌管道寫入異常，已自動熔斷關閉傳輸", e);
            logOutputStream = null;
        }
    }
}
