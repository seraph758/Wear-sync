package cn.luke.wearsync;

import android.util.Log;

/**
 * 📲 手機端專用日誌管理器
 */
public class PhoneLog {
    // 🌟 去掉了 final，現在它是一個可以被界面按鈕動態撥動的開關了！
    public static boolean DEBUG = true; 

    public static void d(String tag, String msg) {
        if (DEBUG) {
            Log.d(tag, msg);
        }
    }

    public static void w(String tag, String msg) {
        if (DEBUG) {
            Log.w(tag, msg);
        }
    }

    public static void e(String tag, String msg) {
        Log.e(tag, msg);
    }

    public static void e(String tag, String msg, Throwable tr) {
        Log.e(tag, msg, tr);
    }
}
