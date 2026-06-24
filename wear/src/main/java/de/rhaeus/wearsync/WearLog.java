package de.rhaeus.wearsync; 

import android.util.Log;

/**
 * ⌚ 手錶端專用日誌管理器
 * 作用：一鍵控制手錶端所有自定義排查日誌的開啟與關閉
 */
public class WearLog {
    // 🌟 核心開關：手錶端獨立控制，調試時保持 true；發布時改為 false
    public static final boolean DEBUG = true; 

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

