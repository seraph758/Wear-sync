package de.rhaeus.wearsync;

import android.util.Log;

/**
 * 📲 手機端專用日誌管理器
 * 作用：一鍵控制手機端所有自定義排查日誌的開啟與關閉
 */
public class PhoneLog {
    // 🌟 核心開關：調試時保持 true；發布正式版時改為 false 全局靜默
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
        // 錯誤日誌建議無論何時都保持輸出，方便線上抓崩潰
        Log.e(tag, msg);
    }

    public static void e(String tag, String msg, Throwable tr) {
        Log.e(tag, msg, tr);
    }
}

