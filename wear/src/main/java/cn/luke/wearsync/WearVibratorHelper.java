package cn.luke.wearsync;

import android.content.Context;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;

/**
 * Wear OS 统一震动管理器（API 31+ 推荐写法）
 * 解决：统一创建/取消 VibrationEffect、防止内存泄漏、兼容多个调用方
 */
public final class WearVibratorHelper {

    private static final String TAG = "WearVibratorHelper";

    // 缓存默认振动器（懒加载 + 线程安全）
    private static volatile Vibrator sDefaultVibrator;

    private WearVibratorHelper() {} // 禁止实例化

    // ====================== 核心公共方法（推荐所有地方使用） ======================

    /**
     * 获取默认振动器（Wear OS 推荐写法）
     */
    public static Vibrator getDefaultVibrator(Context context) {
        if (sDefaultVibrator == null) {
            synchronized (WearVibratorHelper.class) {
                if (sDefaultVibrator == null) {
                    VibratorManager vm = (VibratorManager)
                            context.getApplicationContext()
                                   .getSystemService(Context.VIBRATOR_MANAGER_SERVICE);

                    if (vm == null) {
                        WearLog.e(TAG, "❌ VibratorManager 服务不可用");
                        return null;
                    }

                    int[] ids = vm.getVibratorIds();
                    if (ids.length > 0) {
                        sDefaultVibrator = vm.getVibrator(ids[0]);
                        WearLog.i(TAG, "✅ 默认振动器初始化成功 (ID=" + ids[0] + ")");
                    }
                }
            }
        }
        return sDefaultVibrator;
    }

    /**
     * 🎯 播放自定义波形震动（带重复控制）—— 所有地方推荐使用这个
     */
    public static void vibratePattern(Context context, long[] pattern, int repeatIndex) {
        WearLog.d(TAG, "📳 [Pattern] 请求触发波形震动 length=" + pattern.length + ", repeat=" + repeatIndex);

        Vibrator v = getDefaultVibrator(context);
        if (v == null || !v.hasVibrator()) {
            WearLog.e(TAG, "❌ 震动失败：Vibrator null 或不支持");
            return;
        }

        try {
            v.cancel();                    // 先取消所有正在播放的震动
            VibrationEffect effect = VibrationEffect.createWaveform(pattern, repeatIndex);
            v.vibrate(effect);

            WearLog.i(TAG, "✅ 波形震动已成功触发");
        } catch (Exception e) {
            WearLog.e(TAG, "💥 触发震动异常: " + e.getMessage(), e);
        }
    }

    /**
     * 🛑 停止所有震动（所有地方推荐使用）
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