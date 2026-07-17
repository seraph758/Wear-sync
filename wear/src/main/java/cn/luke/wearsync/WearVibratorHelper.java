package cn.luke.wearsync;

import android.content.Context;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;

/**
 * Wear OS 统一震动管理器
 * 仅适用于 API 31+ (Wear OS 4+)，无需兼容旧版
 */
public final class WearVibratorHelper {

    private static final String TAG = "WearVibratorHelper";
    private static volatile Vibrator sDefaultVibrator;

    private WearVibratorHelper() {} // 禁止实例化

    /**
     * 获取默认振动器（懒加载 + 缓存）
     */
    public static Vibrator getDefaultVibrator(Context context) {
        if (sDefaultVibrator == null) {
            synchronized (WearVibratorHelper.class) {
                if (sDefaultVibrator == null) {
                    WearLog.d(TAG, "🔍 首次初始化，正在获取 VibratorManager...");
                    VibratorManager vm = (VibratorManager)
                            context.getApplicationContext()
                                   .getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
                    
                    if (vm == null) {
                        WearLog.e(TAG, "❌ VibratorManager 服务不可用，系统可能未提供该服务");
                        return null;
                    }

                    int[] ids = vm.getVibratorIds();
                    WearLog.d(TAG, "📋 检测到振动器数量: " + ids.length);
                    
                    if (ids.length > 0) {
                        sDefaultVibrator = vm.getVibrator(ids[0]);
                        WearLog.i(TAG, "✅ 默认振动器初始化成功 (ID=" + ids[0] + ")");
                    } else {
                        WearLog.w(TAG, "⚠️ 设备未检测到任何振动器硬件");
                    }
                }
            }
        }
        return sDefaultVibrator;
    }

    /**
     * 播放自定义波形震动
     */
    public static void vibratePattern(Context context, long[] pattern, int repeatIndex) {
        WearLog.d(TAG, "📳 [Pattern] 请求触发波形震动, 时长数组长度=" + pattern.length + ", 重复索引=" + repeatIndex);
        Vibrator v = getDefaultVibrator(context);
        
        if (v == null) {
            WearLog.e(TAG, "❌ [Pattern] 震动失败: Vibrator 实例为 null");
            return;
        }
        if (!v.hasVibrator()) {
            WearLog.e(TAG, "❌ [Pattern] 震动失败: 硬件不支持或权限缺失");
            return;
        }

        try {
            v.cancel();
            v.vibrate(VibrationEffect.createWaveform(pattern, repeatIndex));
            WearLog.i(TAG, "✅ [Pattern] 波形震动已成功触发");
        } catch (Exception e) {
            WearLog.e(TAG, "💥 [Pattern] 触发震动时发生异常: " + e.getMessage());
        }
    }

    /**
     * 播放预定义震动效果（如来电、通知等）
     */
    public static void vibratePredefined(Context context, int effectId) {
        WearLog.d(TAG, "📳 [Predefined] 请求触发预定义震动, EffectID=" + effectId);
        Vibrator v = getDefaultVibrator(context);
        
        if (v == null) {
            WearLog.e(TAG, "❌ [Predefined] 震动失败: Vibrator 实例为 null");
            return;
        }
        if (!v.hasVibrator()) {
            WearLog.e(TAG, "❌ [Predefined] 震动失败: 硬件不支持或权限缺失");
            return;
        }

        try {
            v.cancel();
            v.vibrate(VibrationEffect.createPredefined(effectId));
            WearLog.i(TAG, "✅ [Predefined] 预定义震动已成功触发");
        } catch (IllegalArgumentException e) {
            WearLog.e(TAG, "💥 [Predefined] 无效的 EffectID (" + effectId + "): " + e.getMessage());
        } catch (Exception e) {
            WearLog.e(TAG, "💥 [Predefined] 触发震动时发生异常: " + e.getMessage());
        }
    }

    /**
     * 停止震动
     */
    public static void cancel(Context context) {
        WearLog.d(TAG, "🛑 [Cancel] 请求停止所有震动");
        Vibrator v = getDefaultVibrator(context);
        if (v != null) {
            try {
                v.cancel();
                WearLog.i(TAG, "✅ [Cancel] 震动已停止");
            } catch (Exception e) {
                WearLog.e(TAG, "💥 [Cancel] 停止震动时发生异常: " + e.getMessage());
            }
        } else {
            WearLog.w(TAG, "⚠️ [Cancel] 跳过停止操作: Vibrator 实例为 null");
        }
    }
}
