package cn.luke.wearsync;

import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.provider.Settings;
import android.widget.Toast;

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
     * 这个方法是由 WearSyncCommManager 调用的
     * 用于处理从手机端发来的所有DND相关指令
     */
    public static void handleIncomingCommand(Context context, JSONObject json) {
        WearLog.d(TAG, "处理勿扰模式指令");

        // 1. 解析配置 (mask, 震动开关等)
        updateConfigs(json);

        // 2. 执行同步逻辑 (DND状态、联动等)
        // ✅ 关键修改1: 直接获取原始值，不再做 0/1 归一化
        int dndStateRaw = json.optInt("dnd_state", -1);
        if (dndStateRaw == -1) {
            WearLog.w(TAG, "⚠️ [指令解析失败] 未找到 dnd_state");
            return;
        }
        int pullDownDelay = json.optInt("pull_down_delay", 500);

        // ✅ 关键修改2: 将原始值透传给执行方法
        executeDndSync(context, dndStateRaw, pullDownDelay);
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
        WearLog.d(TAG, "📥 [Mask解析] mask=" + statusMask + " 震动=" + isVibrateSwitchOn + " 睡眠=" + isSleepLinkageOpen + " 省电=" + isPowerSaveLinkageOpen);
    }

    /**
     * 重载方法 1：方便外部只传 Context 和 dndStatePhone
     */
    public static void executeDndSync(Context context, int dndStatePhone) {
    // 1. 严格使用手机端发送端同款的檔名 "dndsync_prefs"
        SharedPreferences sp = context.getSharedPreferences("dndsync_prefs", Context.MODE_PRIVATE);
        
        // 2. 双保险读取：同时尝试几种常见的 Key 写法，哪个能拿到就用哪个！
        int pullDownDelay = sp.getInt("KEY_PULL_DOWN_DELAY", -1); // 尝试用常量的字面量
        if (pullDownDelay == -1) {
            pullDownDelay = sp.getInt("pull_down_delay", -1);
        }
        if (pullDownDelay == -1) {
            pullDownDelay = sp.getInt("screen_pull_down_interval", 500); // 最终保底默认 500
        }
    
        WearLog.d(TAG, "📥 [中轉站] 成功對齊延遲參數: " + pullDownDelay + "ms");
    
        // 3. 完美轉發給下面三個參數的執行核心
        executeDndSync(context, dndStatePhone, pullDownDelay);
    }

       

    /**
     * 重载方法 2：核心执行逻辑
     * 🎯 所有的参数名称与 handleDndCommand 完全对齐，不再使用 targetFilterFromPhone 等别名！
     * * @param context          上下文 Context
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

        // 🔑 Step 2: 盲设置系统 DND (能走到这里的，都是 handleDndCommand 已经判断为不一致的)
        if (nm.isNotificationPolicyAccessGranted()) {
            nm.setInterruptionFilter(dndStatePhone);
            WearLog.d(TAG, "✨ [DND设置成功] filter=" + dndStatePhone);
        } else {
            WearLog.e(TAG, "❌ [DND设置失败] 未授予通知策略权限");
        }

        // 🔑 Step 3: 根据 dndStatePhone 判断“目标勿扰是否为开启状态”
        // 只要 dndStatePhone 不是 1 (INTERRUPTION_FILTER_ALL)，就代表处于某种勿扰开启模式
        boolean isDndOn = (dndStatePhone != NotificationManager.INTERRUPTION_FILTER_ALL);

        // 🔑 Step 4: 震动联动 (Mask 控制：isVibrateSwitchOn 且仅在 DND 开启时震动)
        if (isVibrateSwitchOn && isDndOn) {
            WearLog.d(TAG, "📳 [开始震动]");
            Vibrator v = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
                if (v != null && v.hasVibrator()) {
                    v.cancel();
                    v.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE));
                    WearLog.d(TAG, "📳 [DND震动] 已触发 OneShot 50ms");
                } else {
                    WearLog.w(TAG, "⚠️ [DND震动] 无可用振动器");
                }
        }

        // 🔑 Step 5: 睡眠模式自动化联动 (Mask 控制：isSleepLinkageOpen)
        if (isSleepLinkageOpen) {
            WearLog.d(TAG, "🚀 [睡眠联动] 启动 WearSyncBedtimeAutomationActivity");
            Intent intent = new Intent(context, WearSyncBedtimeAutomationActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            intent.putExtra("extra_pull_down_delay", pullDownDelayMs);
            intent.putExtra("extra_target_dnd_on", isDndOn);
            context.startActivity(intent);
        }

        // 🔑 Step 6: 省电模式联动 (Mask 控制：isPowerSaveLinkageOpen)
        if (isPowerSaveLinkageOpen) {
            // 根据 dndStatePhone 推导出的 isDndOn 决定省电模式：DND开启 -> 1(开省电)；DND关闭 -> 0(关省电)
            int targetPowerSaveMode = isDndOn ? 1 : 0;

            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                try {
                    Settings.Global.putInt(context.getContentResolver(), "low_power", targetPowerSaveMode);
                    WearLog.d(TAG, "🔋 [省电模式设置] dndStatePhone=" + dndStatePhone 
                            + " (DND开启=" + isDndOn + ") ➔ 设置 low_power=" + targetPowerSaveMode);
                } catch (Exception e) {
                    WearLog.e(TAG, "❌ [省电模式设置失败] " + e.getMessage());
                }
            }, 1000);
        }

        // 🔑 Step 7: 5秒后重置内部更新标记
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            WearSyncNotificationService.isInternalUpdate = false;
        }, 5000);
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

}
