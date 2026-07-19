package cn.luke.wearsync;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;

/**
 * Wear OS 统一震动管理器（API 31+ 推荐写法）
 * 统一处理：读取手机端设置 + 播放震动 + 停止震动
 */
public final class WearVibratorHelper {

    private static final String TAG = "WearVibratorHelper";
    private static final String PREFS_NAME = "wear_vibration_prefs";

    private static volatile Vibrator sDefaultVibrator;
    private static int sOnDuration = 500;
    private static int sOffDuration = 200;
    private static int sRepeatIndex = -1;

    private WearVibratorHelper() {} // 禁止实例化

    // ====================== 核心公共方法（所有模块都用这个） ======================

    /**
     * 从手机端 SharedPreferences 读取用户设置（手表端统一调用）
     */
    public static void initFromPhone(Context context) {
        SharedPreferences sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        sOnDuration = sp.getInt("on_duration", 500);
        sOffDuration = sp.getInt("off_duration", 200);
        sRepeatIndex = sp.getInt("repeat_index", -1);

        WearLog.d(TAG, "📥 [WearVibrationConfig] 从手机读取震动参数: on=" + sOnDuration + "ms, off=" + sOffDuration + "ms, repeat=" + sRepeatIndex);
    }

    /**
     * 获取当前配置的波形数组（直接用于 createWaveform）
     */
    public static long[] getPattern() {
        return new long[]{
                0,                    // 必须以 0 开头
                sOnDuration,
                sOffDuration,
                sOnDuration
        };
    }

    /**
     * 获取重复索引（-1 = 不循环）
     */
    public static int getRepeatIndex() {
        return sRepeatIndex;
    }

    /**
     * 🎯 播放自定义波形震动（所有模块推荐使用这个统一方法）
     */
    public static void vibratePattern(Context context) {
        initFromPhone(context);   // 关键：每次震动前都先读取最新参数（支持动态更新）

        WearLog.d(TAG, "📳 [Pattern] 请求触发波形震动 length=" + getPattern().length + ", repeat=" + getRepeatIndex());

        Vibrator v = getDefaultVibrator(context);
        if (v == null || !v.hasVibrator()) {
            WearLog.e(TAG, "❌ 震动失败：Vibrator null 或不支持");
            return;
        }

        try {
            v.cancel();
            VibrationEffect effect = VibrationEffect.createWaveform(getPattern(), getRepeatIndex());
            v.vibrate(effect);
            WearLog.i(TAG, "✅ 波形震动已成功触发");
        } catch (Exception e) {
            WearLog.e(TAG, "💥 触发震动异常: " + e.getMessage(), e);
        }
    }

    /**
     * 🛑 停止所有震动（所有模块都用这个）
     */
    public static void cancelVibration(Context context) {
        WearLog.d(TAG, "🛑 [Cancel] 请求停止所有震动");
        Vibrator v = getDefaultVibrator(context);
        if (v != null) {
            try {
                v.cancel();
                WearLog.i(TAG, "✅ 震动已停止");
            } catch (Exception e) {
                WearLog.e(TAG, "❌ 停止震动异常: " + e.getMessage());
            }
        }
    }

    // ====================== 老方法保留（兼容老代码） ======================

    public static void vibratePredefined(Context context, int effectId) {
        Vibrator v = getDefaultVibrator(context);
        if (v == null || !v.hasVibrator()) return;
        try {
            v.cancel();
            v.vibrate(VibrationEffect.createPredefined(effectId));
        } catch (Exception e) {
            WearLog.e(TAG, "预定义震动失败: " + e.getMessage());
        }
    }
}