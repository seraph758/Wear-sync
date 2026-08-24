package cn.luke.wearsync;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;

/**
 * 手錶震動統一管理工具
 * 🚀 專為 Wear OS 6+ 打造：全現代化 API，無任何歷史包袱與 SDK 版本判斷分支
 */
public final class WearVibratorHelper {

    private static final String TAG = "WearVibratorHelper";
    private static final String PREFS_NAME = "wear_vibration_prefs";

    private static volatile Vibrator sDefaultVibrator;
    private static int sOnDuration = 500;
    private static int sOffDuration = 200;
    private static int sRepeatIndex = -1;

    private WearVibratorHelper() {}

    /**
     * 🎯 獲取默認震動器 (Wear OS 6+ 直接使用 VibratorManager)
     */
    private static Vibrator getDefaultVibrator(Context context) {
        if (sDefaultVibrator == null) {
            synchronized (WearVibratorHelper.class) {
                if (sDefaultVibrator == null) {
                    VibratorManager vm = context.getSystemService(VibratorManager.class);
                    if (vm != null) {
                        sDefaultVibrator = vm.getDefaultVibrator();
                    }
                }
            }
        }
        return sDefaultVibrator;
    }

    /** 從手機端 SharedPreferences 讀取震動參數 */
    public static void initFromPhone(Context context) {
        SharedPreferences sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        sOnDuration = sp.getInt("onDuration", 500);
        sOffDuration = sp.getInt("offDuration", 200);
        sRepeatIndex = sp.getInt("repeatIndex", -1);
        WearLog.d(TAG, "📥 震動參數: on=" + sOnDuration + "ms, off=" + sOffDuration 
                + "ms, repeat=" + sRepeatIndex);
    }
    
    public static int getOnDuration() {
        return sOnDuration;
    }
    
    public static int getOffDuration() {
        return sOffDuration;
    }

    /**
     * 🎯 使用傳入的參數直接觸發震動（預覽指令）
     */
    public static void vibratePattern(Context context, int onDuration, int offDuration, int repeatIndex) {
        Vibrator vibrator = getDefaultVibrator(context);
        if (vibrator == null || !vibrator.hasVibrator()) {
            WearLog.e(TAG, "❌ 震動失敗：Vibrator null 或不支援");
            return;
        }
    
        long[] pattern = {0, onDuration, offDuration, onDuration};
        WearLog.d(TAG, "📳 觸發預覽波形震動 length=" + pattern.length + ", repeat=" + repeatIndex);
    
        try {
            vibrator.cancel();
            VibrationEffect effect = VibrationEffect.createWaveform(pattern, repeatIndex);
            vibrator.vibrate(effect);
            WearLog.i(TAG, "✅ 預覽波形震動已觸發");
        } catch (Exception e) {
            WearLog.e(TAG, "💥 觸發預覽震動異常: " + e.getMessage(), e);
        }
    }

    /**
     * 🛑 停止所有震動
     * 🚀 cancel 後置空靜態引用，確保下一次獲取全新實例
     */
    public static void cancelVibration(Context context) {
        WearLog.d(TAG, "🛑 請求停止所有震動");
        Vibrator v = getDefaultVibrator(context);
        if (v != null) {
            try {
                v.cancel();
                WearLog.i(TAG, "✅ 震動已停止");
            } catch (Exception e) {
                WearLog.e(TAG, "❌ 停止震動異常: " + e.getMessage(), e);
            }
        }

        synchronized (WearVibratorHelper.class) {
            sDefaultVibrator = null;
        }
    }

    /**
     * 僅觸發一次指定時長的震動，不循環
     * 專用於鬧鐘 Activity 的手動循環控制
     */
    public static void vibrateOnce(Context context, int durationMs) {
        Vibrator v = getDefaultVibrator(context);
        if (v == null || !v.hasVibrator()) return;
    
        try {
            v.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE));
        } catch (Exception e) {
            WearLog.e(TAG, "單次震動失敗: " + e.getMessage(), e);
        }
    }
}
