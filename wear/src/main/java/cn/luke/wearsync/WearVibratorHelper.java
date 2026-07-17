package cn.luke.wearsync;

import android.content.Context;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.util.Log;

/**
 * Wear OS 统一震动管理器
 * 仅适用于 API 31+ (Wear OS 4+)，无需兼容旧版
 */
public final class WearVibratorHelper {

    private static final String TAG = "WearVibratorHelper";
    private static Vibrator sDefaultVibrator;

    private WearVibratorHelper() {} // 禁止实例化

    /**
     * 获取默认振动器（懒加载 + 缓存）
     */
    public static Vibrator getDefaultVibrator(Context context) {
        if (sDefaultVibrator == null) {
            synchronized (WearVibratorHelper.class) {
                if (sDefaultVibrator == null) {
                    VibratorManager vm = (VibratorManager)
                            context.getApplicationContext()
                                   .getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
                    if (vm != null) {
                        int[] ids = vm.getVibratorIds();
                        if (ids.length > 0) {
                            sDefaultVibrator = vm.getVibrator(ids[0]);
                        } else {
                            Log.w(TAG, "⚠️ 设备未检测到任何振动器");
                        }
                    } else {
                        Log.e(TAG, "❌ VibratorManager 服务不可用");
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
        Vibrator v = getDefaultVibrator(context);
        if (v != null && v.hasVibrator()) {
            v.cancel();
            v.vibrate(VibrationEffect.createWaveform(pattern, repeatIndex));
        }
    }

    /**
     * 播放预定义震动效果（如来电、通知等）
     */
    public static void vibratePredefined(Context context, int effectId) {
        Vibrator v = getDefaultVibrator(context);
        if (v != null && v.hasVibrator()) {
            v.cancel();
            v.vibrate(VibrationEffect.createPredefined(effectId));
        }
    }

    /**
     * 停止震动
     */
    public static void cancel(Context context) {
        Vibrator v = getDefaultVibrator(context);
        if (v != null) {
            v.cancel();
        }
    }
}
