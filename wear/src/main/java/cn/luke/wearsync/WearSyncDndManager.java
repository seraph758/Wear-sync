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

    public static void executeDndSync(Context context, int dndStatePhone) {
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

        // 假设项目中依然有这两个变量，如果有报错可以移除
        WearSyncNotificationService.isInternalUpdate = true;
        WearSyncNotificationService.lastInternalUpdateTime = System.currentTimeMillis();

        if (isVibrateSwitchOn && dndStatePhone > 1) {
            WearLog.d(TAG, "📳 [开始震动]");
            vibrate(context);
        }

        if (isSleepLinkageOpen) {
            toggleBedtimeMode(context);
        }

        if (isPowerSaveLinkageOpen) {
            boolean enable = dndStatePhone > 1;
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                try {
                    Settings.Global.putInt(context.getContentResolver(), "low_power", enable ? 1 : 0);
                    context.sendBroadcast(new Intent(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED));
                    WearLog.d(TAG, "🔋 [省电异步同步] " + (enable ? "开启" : "关闭"));
                } catch(Exception e){
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
        // 💡 注意：根据你的 WearSyncBedtimeAutomationActivity.java，它的包名依然是下面的导入位置
        // 如果 WearSyncAccessService 的 sharedInstance 为空，直接主线程弹窗
        new Handler(Looper.getMainLooper()).post(() -> {
            // 这里我们改用原生的 Toast 解决 showToast 缺失的问题
            Toast.makeText(context, "無障礙服務未連接", Toast.LENGTH_SHORT).show();
        });

        Intent intent = new Intent(context, WearSyncBedtimeAutomationActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        context.startActivity(intent);
        
        WearLog.d(TAG, "🛌 [就寝模式] 已启动透明自动化页面");
    }

    private static void vibrate(Context context) {
        WearVibratorHelper.vibratePredefined(context, VibrationEffect.EFFECT_TICK);
    }
}
