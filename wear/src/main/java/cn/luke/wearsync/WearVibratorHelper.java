package cn.luke.wearsync;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;

/**
 * 手表震动统一管理工具
 * 🚀 修复：cancel 后置空静态引用，防止跨品牌设备僵尸 Vibrator 对象
 */
public final class WearVibratorHelper {

    private static final String TAG = "WearVibratorHelper";
    private static final String PREFS_NAME = "wear_vibration_prefs";

    private static volatile Vibrator sDefaultVibrator;
    private static int sOnDuration = 500;
    private static int sOffDuration = 200;
    private static int sRepeatIndex = -1;

    private WearVibratorHelper() {}

    private static Vibrator getDefaultVibrator(Context context) {
        if (sDefaultVibrator == null) {
            synchronized (WearVibratorHelper.class) {
                if (sDefaultVibrator == null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        VibratorManager vm = (VibratorManager) 
                                context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
                        if (vm != null) {
                            sDefaultVibrator = vm.getDefaultVibrator();
                        }
                    }
                    if (sDefaultVibrator == null) {
                        sDefaultVibrator = (Vibrator) 
                                context.getSystemService(Context.VIBRATOR_SERVICE);
                    }
                }
            }
        }
        return sDefaultVibrator;
    }

    /** 兼容老代码调用 */
    public static void cancel(Context context) {
        cancelVibration(context);
    }

    /** 从手机端 SharedPreferences 读取震动参数 */
    public static void initFromPhone(Context context) {
        SharedPreferences sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        sOnDuration = sp.getInt("onDuration", 500);
        sOffDuration = sp.getInt("offDuration", 200);
        sRepeatIndex = sp.getInt("repeatIndex", -1);
        WearLog.d(TAG, "📥 震动参数: on=" + sOnDuration + "ms, off=" + sOffDuration 
                + "ms, repeat=" + sRepeatIndex);
    }

    public static long[] getPattern() {
        return new long[]{0, sOnDuration, sOffDuration, sOnDuration};
    }

    public static int getRepeatIndex() {
        return sRepeatIndex;
    }

    /** 🎯 播放自定义波形震动（统一入口） */
    public static void vibratePattern(Context context) {
        initFromPhone(context);

        WearLog.d(TAG, "📳 触发波形震动 length=" + getPattern().length 
                + ", repeat=" + getRepeatIndex());

        Vibrator v = getDefaultVibrator(context);
        if (v == null || !v.hasVibrator()) {
            WearLog.e(TAG, "❌ 震动失败：Vibrator null 或不支持");
            return;
        }

        try {
            v.cancel();
            VibrationEffect effect = VibrationEffect.createWaveform(
                    getPattern(), getRepeatIndex());
            v.vibrate(effect);
            WearLog.i(TAG, "✅ 波形震动已触发");
        } catch (Exception e) {
            WearLog.e(TAG, "💥 触发震动异常: " + e.getMessage(), e);
        }
    }
    /**
     * 新增：使用传入的参数直接触发震动
     * 用于处理手表的“预览”指令
     */
    public static void vibratePattern(Context context, int onDuration, int offDuration, int repeatIndex) {
        Vibrator vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
        if (vibrator == null || !vibrator.hasVibrator()) {
            WearLog.e(TAG, "❌ 震动失败：Vibrator null 或不支持");
            return;
        }
    
        // 构建震动模式：等待0ms -> 震动onDuration -> 等待offDuration -> 震动onDuration
        long[] pattern = {0, onDuration, offDuration, onDuration};
        
        WearLog.d(TAG, "📳 触发预览波形震动 length=" + pattern.length + ", repeat=" + repeatIndex);
    
        try {
            vibrator.cancel();
            VibrationEffect effect = VibrationEffect.createWaveform(pattern, repeatIndex);
            vibrator.vibrate(effect);
            WearLog.i(TAG, "✅ 预览波形震动已触发");
        } catch (Exception e) {
            WearLog.e(TAG, "💥 触发预览震动异常: " + e.getMessage(), e);
        }
    }

    /**
     * 🛑 停止所有震动
     * 🚀 修复：cancel 后置空静态引用，确保下次获取新鲜实例
     */
    public static void cancelVibration(Context context) {
        WearLog.d(TAG, "🛑 请求停止所有震动");
        Vibrator v = getDefaultVibrator(context);
        if (v != null) {
            try {
                v.cancel();
                WearLog.i(TAG, "✅ 震动已停止");
            } catch (Exception e) {
                WearLog.e(TAG, "❌ 停止震动异常: " + e.getMessage(), e);
            }
        }

        // 🚀 关键：重置静态引用，防止僵尸对象
        synchronized (WearVibratorHelper.class) {
            sDefaultVibrator = null;
        }
    }

    /** 兼容老代码：预定义震动 */
    public static void vibratePredefined(Context context, int effectId) {
        Vibrator v = getDefaultVibrator(context);
        if (v == null || !v.hasVibrator()) return;
        try {
            v.cancel();
            v.vibrate(VibrationEffect.createPredefined(effectId));
        } catch (Exception e) {
            WearLog.e(TAG, "预定义震动失败: " + e.getMessage(), e);
        }
    }
    // 在 WearVibratorHelper.java 中添加此方法

    /**
     * 仅触发一次指定时长的震动，不循环
     * 专用于闹钟 Activity 的手动循环控制
     */
    public static void vibrateOnce(Context context, int durationMs) {
        Vibrator v = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
        if (v == null || !v.hasVibrator()) return;
    
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            v.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE));
        } else {
            v.vibrate(durationMs);
        }
    }

}
