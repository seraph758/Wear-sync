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



/**
 * 🌓 手錶端勿擾、掩碼解讀與無障礙自動化聯控核心管理器
 */
public class WearSyncDndManager {
    private static final String TAG = "WearSync_WearDnd";

    private static boolean isSyncAllowed = true;          
    private static boolean isVibrateSwitchOn = true;       
    private static boolean isSleepLinkageOpen = true;      
    private static boolean isPowerSaveLinkageOpen = true;  

    public static void updateConfigs(JSONObject json) {
        if (json == null) return;

        int statusMask = json.optInt("mask", -1);
        if (statusMask == -1) {
            WearLog.w(TAG, "⚠️ [Mask读取失败] 未找到mask");
            return;
        }

        isSyncAllowed = (statusMask & 0x01) != 0;

        if (!isSyncAllowed) {
            WearLog.w(TAG, "🛑 [Mask拦截] Bit0=0，总同步关闭");
            return;
        }

        isVibrateSwitchOn = (statusMask & 0x02) != 0;
        isSleepLinkageOpen = (statusMask & 0x04) != 0;
        isPowerSaveLinkageOpen = (statusMask & 0x08) != 0;

        WearLog.d(TAG,
                "📥 [Mask解析] mask=" + statusMask
                        + " 震动=" + isVibrateSwitchOn
                        + " 睡眠=" + isSleepLinkageOpen
                        + " 省电=" + isPowerSaveLinkageOpen);
    }

    /**
     * ✅ 兼容旧调用：使用默认延迟值 500ms
     */
 public static void executeDndSync(Context context, int dndStatePhone) {
    // ✅ 在兜底方法内部自动读取SP，杜绝硬编码遗漏
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

        NotificationManager mNotificationManager =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);

        if (mNotificationManager == null) {
            WearLog.e(TAG, "❌ NotificationManager 获取失败");
            return;
        }

        int currentDndState = mNotificationManager.getCurrentInterruptionFilter();

        WearLog.d(TAG, "🔍 [DND状态检查] 手机=" + dndStatePhone + " 手表=" + currentDndState);

        if (dndStatePhone == currentDndState) {
            WearLog.d(TAG, "✅ [DND一致] 继续执行子联动");
        } else {
            WearLog.d(TAG, "⚡ [DND变化] 开始同步");
        }

        // 标记内部更新，防止通知监听器重复触发
        WearSyncNotificationService.isInternalUpdate = true;
        WearSyncNotificationService.lastInternalUpdateTime = System.currentTimeMillis();

        if (isVibrateSwitchOn && dndStatePhone > 1) {
            WearLog.d(TAG, "📳 [开始震动]");
            vibrate(context);
        }

        // ✅ 睡眠联动：透传最新的下拉延迟值给 Activity
        if (isSleepLinkageOpen) {
            Intent intent = new Intent(context, WearSyncBedtimeAutomationActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            intent.putExtra("extra_pull_down_delay", pullDownDelayMs);
            context.startActivity(intent);
        }

        if (isPowerSaveLinkageOpen) {
            boolean enable = dndStatePhone > 1;
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                try {
                    Settings.Global.putInt(context.getContentResolver(), "low_power", enable ? 1 : 0);
                    context.sendBroadcast(new Intent(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED));
                    WearLog.d(TAG, "🔋 [省电异步同步] " + (enable ? "开启" : "关闭"));
                } catch (Exception e) {
                    WearLog.e(TAG, "❌ [省电同步失败] " + e.getMessage());
                }
            }, 5500);
        }

        if (mNotificationManager.isNotificationPolicyAccessGranted()) {
            mNotificationManager.setInterruptionFilter(dndStatePhone);
        }

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            WearSyncNotificationService.isInternalUpdate = false;
        }, 7000);
    }


    private static void toggleBedtimeMode(Context context) {
    // 🚀 修正：真正加上無障礙服務的狀態檢查判斷
    // (注意：請確保你的項目中存在 WearSyncAccessService 類別，如果它在同一個包下則無需額外 import)
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


    private static void vibrate(Context context) {
        WearVibratorHelper.vibratePredefined(context, VibrationEffect.EFFECT_TICK);
    }
}
