package cn.luke.wearsync;

import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.widget.Toast;
import org.json.JSONObject;
import cn.luke.wearsync.WearSyncBedtimeAutomationActivity;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import org.json.JSONException;

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
    
    // 用于防止内部更新导致的循环触发
    private static final boolean isInternalUpdate = false;

    /**
     * 这个方法是由 WearSyncCommManager 调用的
     * 用于处理从手机端发来的所有DND相关指令
     */
    public static void handleIncomingCommand(Context context, JSONObject json) {
        WearLog.d(TAG, "处理勿扰模式指令");
        
        // 1. 解析配置 (mask, 震动开关等)
        updateConfigs(json);
        
        // 2. 执行同步逻辑 (DND状态、联动等)
        int dndState = json.optInt("dnd_state", 0);
        int pullDownDelay = json.optInt("pull_down_delay", 500);
        executeDndSync(context, dndState, pullDownDelay);
    }

    /**
     * 解析JSON中的配置信息
     * 核心逻辑：通过位掩码（mask）来高效传递多个布尔状态
     * 
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

        WearLog.d(TAG, "📥 [Mask解析] mask=" + statusMask + 
                " 震动=" + isVibrateSwitchOn + 
                " 睡眠=" + isSleepLinkageOpen + 
                " 省电=" + isPowerSaveLinkageOpen);
    }

    /**
     * 供 NotificationListener 调用的兜底方法
     * 用于处理用户手动切换DND的情况，会自动从SP读取延迟配置
     */
    public static void executeDndSync(Context context, int dndStatePhone) {
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(context);
        int pullDownDelay = sp.getInt("screen_pull_down_interval", 500);
        executeDndSync(context, dndStatePhone, pullDownDelay);
    }

    /**
     * ✅ 新增：带延迟参数的重载方法
     * 用于接收手机端下发的最新 pullDownDelayMs 并透传给 BedtimeAutomationActivity
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

    // 🔑 Step 1: 布尔归一化比对（修复值域错位）
    int rawWatchFilter = nm.getCurrentInterruptionFilter();
    boolean targetDndOn = (dndStatePhone != 0);
    boolean currentDndOn = (rawWatchFilter != NotificationManager.INTERRUPTION_FILTER_ALL);

    WearLog.d(TAG, "🔍 [DND状态检查] 手机=" + dndStatePhone 
            + " 手表=" + (currentDndOn ? 1 : 0) + "(raw=" + rawWatchFilter + ")");

    boolean dndChanged = (targetDndOn != currentDndOn);
    if (!dndChanged) {
        WearLog.d(TAG, "✅ [DND一致] 跳过系统变更，继续执行子联动");
    } else {
        WearLog.d(TAG, "⚡ [DND变化] 开始同步: " + (currentDndOn ? 1 : 0) + " → " + dndStatePhone);
    }

    // 标记内部更新，防止通知监听器重复触发
    WearSyncNotificationService.isInternalUpdate = true;
    WearSyncNotificationService.lastInternalUpdateTime = System.currentTimeMillis();

    // 🔑 Step 2: 震动反馈改用布尔判断（原 dndStatePhone > 1 在 0/1 协议下永远为 false）
    if (isVibrateSwitchOn && targetDndOn) {
        WearLog.d(TAG, "📳 [开始震动]");
        vibrate(context);
    }

    // 3. 执行睡眠联动（与 DND 值无关，保持不变）
    if (isSleepLinkageOpen) {
        Intent intent = new Intent(context, WearSyncBedtimeAutomationActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        intent.putExtra("extra_pull_down_delay", pullDownDelayMs);
        context.startActivity(intent);
    }

    // 4. 执行省电模式联动（改用布尔判断）
    if (isPowerSaveLinkageOpen) {
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                Settings.Global.putInt(context.getContentResolver(), "low_power", targetDndOn ? 1 : 0);
                context.sendBroadcast(new Intent(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED));
                WearLog.d(TAG, "🔋 [省电异步同步] " + (targetDndOn ? "开启" : "关闭"));
            } catch (Exception e) {
                WearLog.e(TAG, "❌ [省电同步失败] " + e.getMessage());
            }
        }, 3000); // 3秒缓冲：等DND切换完成后再改省电模式，避免系统UI并发冲突
    }

    // 🔑 Step 3: 仅在状态真正变化时才设置系统DND（修复直接传0/1的问题）
    if (dndChanged && nm.isNotificationPolicyAccessGranted()) {
        int targetFilter = targetDndOn
                ? NotificationManager.INTERRUPTION_FILTER_NONE
                : NotificationManager.INTERRUPTION_FILTER_ALL;
        nm.setInterruptionFilter(targetFilter);
        WearLog.d(TAG, "✨ [DND设置成功] filter=" + targetFilter);
    }

    // 5. 延迟重置内部更新标记
    new Handler(Looper.getMainLooper()).postDelayed(() -> {
        WearSyncNotificationService.isInternalUpdate = false;
    }, 7000); // 7秒覆盖：DND设置 + 省电延迟(3s) + 系统回调异步窗口的完整周期
}

    /**
     * 启动就寝模式自动化
     * 修正：增加了无障碍服务的状态检查判断
     */
    private static void toggleBedtimeMode(Context context) {
        WearSyncAccessService serv = WearSyncAccessService.getSharedInstance();
        if (serv == null) {
            new Handler(Looper.getMainLooper()).post(() -> {
                Toast.makeText(context, "無障礙服務未連接", Toast.LENGTH_SHORT).show();
            });
            return; // 未連接時直接攔截，不再啟動透明 Activity
        }
        
        // 只有在無障礙連接成功時，才啟動透明自動化頁面
        Intent intent = new Intent(context, WearSyncBedtimeAutomationActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        context.startActivity(intent);
        WearLog.d(TAG, "🛌 [就寢模式] 已成功啟動透明自動化頁面");
    }

    /**
     * 执行预定义的震动效果
     */
    private static void vibrate(Context context) {
        WearVibratorHelper.vibratePredefined(context, VibrationEffect.EFFECT_TICK);
    }
}
