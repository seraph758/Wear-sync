package cn.luke.wearsync;

import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;

import org.json.JSONObject;

/**
 * 🌓 手錶端勿擾、掩碼解讀與無障礙自動化聯控核心管理器
 */
public class WearSyncDndManager {
    private static final String TAG = "WearSyncDndManager";

    // 状态标志位
    private static boolean isSyncAllowed = true;
    private static boolean isVibrateSwitchOn = true;
    private static boolean isSleepLinkageOpen = true;
    private static boolean isPowerSaveLinkageOpen = true;

    /**
     * 解析JSON中的配置信息
     * 核心逻辑：通过位掩码（mask）来高效传递多个布尔状态
     * mask 值的二进制位定义：
     * Bit 0 (0x01): 总同步开关 (isSyncAllowed)
     * Bit 1 (0x02): 震动开关 (isVibrateSwitchOn)
     * Bit 2 (0x04): 睡眠联动开关 (isSleepLinkageOpen)
     * Bit 3 (0x08): 省电模式联动开关 (isPowerSaveLinkageOpen)
     */
    public static void updateConfigs(JSONObject json) {
        if (json == null) return;
        int statusMask = json.optInt("mask", -1);
        if (statusMask == -1) {
            WearLog.w(TAG, "⚠️ [Mask读取失败] 未找到mask");
            return;
        }
        // 解析总同步开关
        isSyncAllowed = (statusMask & 0x01) != 0;
        if (!isSyncAllowed) {
            WearLog.w(TAG, "🛑 [Mask拦截] Bit0=0，总同步关闭");
            return;
        }
        // 解析其他联动开关
        isVibrateSwitchOn = (statusMask & 0x02) != 0;
        isSleepLinkageOpen = (statusMask & 0x04) != 0;
        isPowerSaveLinkageOpen = (statusMask & 0x08) != 0;
        WearLog.d(TAG, "📥 [Mask解析] mask=" + statusMask + " 震动=" + isVibrateSwitchOn + " 睡眠=" + isSleepLinkageOpen + " 省电=" + isPowerSaveLinkageOpen);
    }

    /**
     * 核心执行逻辑
     * @param context          上下文 Context
     * @param dndStatePhone    手机端传来的 DND Filter 原始数值 (1=ALL/关, 2=PRIORITY/开, 3=NONE/开, 4=ALARMS/开)
     * @param pullDownDelayMs  下拉面板延迟时间 (ms)
     */
    public static void executeDndSync(Context context, int dndStatePhone, int pullDownDelayMs) {
        if (!isSyncAllowed) {
            WearLog.w(TAG, "🛑 [DND拦截] 总开关关闭");
            return;
        }

        NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (nm == null) {
            WearLog.e(TAG, "❌ NotificationManager 获取失败");
            return;
        }

        // 🔑 Step 1: 标记内部更新（防死循环）
        WearSyncNotificationService.isInternalUpdate = true;
        WearSyncNotificationService.lastInternalUpdateTime = System.currentTimeMillis();

        // 🔑 Step 2: 设置系统 DND
        if (nm.isNotificationPolicyAccessGranted()) {
            nm.setInterruptionFilter(dndStatePhone);
            WearLog.d(TAG, "✨ [DND设置成功] filter=" + dndStatePhone);
        } else {
            WearLog.e(TAG, "❌ [DND设置失败] 未授予通知策略权限");
        }

        // 🔑 Step 3: 判断“目标勿扰是否为开启状态”
        boolean isDndOn = (dndStatePhone != NotificationManager.INTERRUPTION_FILTER_ALL);

        // 🔑 Step 4: 震动联动
        if (isVibrateSwitchOn && isDndOn) {
            WearLog.d(TAG, "📳 [开始震动]");
            Vibrator v = context.getSystemService(Vibrator.class);
            if (v != null && v.hasVibrator()) {
                v.cancel();
                v.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE));
                WearLog.d(TAG, "📳 [DND震动] 已触发 OneShot 50ms");
            }
        }

        // 🔑 Step 5: 睡眠模式自动化联动
        if (isSleepLinkageOpen) {
            WearLog.d(TAG, "🚀 [睡眠联动] 启动 WearSyncBedtimeAutomationActivity");
            Intent intent = new Intent(context, WearSyncBedtimeAutomationActivity.class);
            // 🎯 在非 Activity 环境启动必须使用 NEW_TASK。移除多余的 CLEAR_TOP。
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.putExtra("extra_pull_down_delay", pullDownDelayMs);
            intent.putExtra("extra_target_dnd_on", isDndOn);
            context.startActivity(intent);
        }

        // 🔑 Step 6: 省电模式联动
        if (isPowerSaveLinkageOpen) {
            int targetPowerSaveMode = isDndOn ? 1 : 0;
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                try {
                    Settings.Global.putInt(context.getContentResolver(), "low_power", targetPowerSaveMode);
                    WearLog.d(TAG, "🔋 [省电模式设置] target=" + targetPowerSaveMode);
                } catch (Exception e) {
                    WearLog.e(TAG, "❌ [省电模式失败] " + e.getMessage());
                }
            }, 1000);
        }

        // 🔑 Step 7: 5秒后重置内部更新标记
        new Handler(Looper.getMainLooper()).postDelayed(() -> WearSyncNotificationService.isInternalUpdate = false, 5000);
    }

}
