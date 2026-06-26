package de.rhaeus.wearsync;

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
 * 🌓 手錶端勿擾、掩碼解讀與無障礙自動化聯控核心管理器 (致敬舊代碼・終極完美閉跨版)
 */
public class WearSyncDndManager {
    private static final String TAG = "WearSync_WearDnd";

    // 🔒 在記憶體中靜態暫存用戶從手機端傳過來的聯動開關意願（預設全開，完美對齊舊代碼行為）
    private static boolean isSyncAllowed = true;          // Bit 0: 總同步開關
    private static boolean isVibrateSwitchOn = true;       // Bit 1: 勿擾震動子開關
    private static boolean isSleepLinkageOpen = true;      // Bit 2: 睡眠模式聯動開關
    private static boolean isPowerSaveLinkageOpen = true;  // Bit 3: 省電模式聯動開關

    /**
     * 📥 模組一：更新聯動配置掩碼（先到達）
     * 🧩 替代了原本混亂的 handleIncomingMask，只純淨更新和儲存開關配置
     */
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
     * 📥 模組二：接收原生 DND 狀態並強行指揮所有子聯動（後到達）
     * 🔥 100% 復刻舊代碼比對精髓，唯有狀態不相等時才驅動變更！
     * @param dndStatePhone 手機傳過來的系統原生 filter 狀態值 (1, 2, 3, 4)
     */
     public static void executeDndSync(Context context, int dndStatePhone) {
    
        if (!isSyncAllowed) {
            WearLog.w(TAG, "🛑 [DND拦截] 总开关关闭");
            return;
        }
    
    
        NotificationManager mNotificationManager =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
    
    
        if (mNotificationManager == null) return;
    
    
        int currentDndState =
                mNotificationManager.getCurrentInterruptionFilter();
    
    
        WearLog.d(TAG,
                "🔍 [DND对比] 手机="
                        + dndStatePhone
                        + " 手表="
                        + currentDndState);
    
    
    
        if (dndStatePhone == currentDndState) {
    
            WearLog.d(TAG,"✅ [DND一致] 不执行同步");
            return;
    
        }
    
    
    
        WearLog.d(TAG,
                "⚡ [DND变化] 开始同步");
    
    
        WearSyncNotificationService.isInternalUpdate = true;
    
    
    
        if (isSleepLinkageOpen) {
    
            WearLog.d(TAG,"🛌 [睡眠联动]执行");
    
            toggleBedtimeMode(context);
    
        }
    
    
    
        if (isPowerSaveLinkageOpen) {
    
            boolean enable =
                    dndStatePhone > 1;
    
    
            Settings.Global.putInt(
                    context.getContentResolver(),
                    "low_power",
                    enable ? 1 : 0
            );
    
    
            context.sendBroadcast(
                    new Intent(
                            PowerManager.ACTION_POWER_SAVE_MODE_CHANGED
                    )
            );
    
    
            WearLog.d(TAG,
                    "🔋 [省电同步] "
                            +(enable?"开启":"关闭"));
    
        }
    
    
    
        if (isVibrateSwitchOn && dndStatePhone > 1) {
    
            WearLog.d(TAG,"📳 [震动提示]");
    
            vibrate(context);
    
        }
    
    
    
        if (mNotificationManager.isNotificationPolicyAccessGranted()) {
    
    
            mNotificationManager.setInterruptionFilter(
                    dndStatePhone
            );
    
    
            WearLog.d(TAG,
                    "🌗 [写入DND] "
                            +dndStatePhone);
    
    
        } else {
    
            WearLog.e(TAG,
                    "❌ 无DND权限");
    
        }
    
    
    
        new Handler(Looper.getMainLooper())
                .postDelayed(() -> {
    
                    WearSyncNotificationService.isInternalUpdate=false;
    
                    WearLog.d(TAG,
                            "🔓 [锁释放]");
    
                },2000);
    
    }

    /**
     * 🛌 透過無障礙模擬點擊切換手錶系統的就寢模式（睡眠模式）
     */
    private static void toggleBedtimeMode(Context context) {
        WearSyncAccessService serv = WearSyncAccessService.getSharedInstance();
        if (serv == null) {
            new Handler(Looper.getMainLooper()).post(() -> 
                Toast.makeText(context.getApplicationContext(), "無障礙服務未連接，無法同步睡眠模式", Toast.LENGTH_LONG).show()
            );
            return;
        }

        PowerManager pm = (PowerManager) context.getApplicationContext().getSystemService(Context.POWER_SERVICE);
        if (pm == null) return;

        PowerManager.WakeLock wakeLock = pm.newWakeLock(PowerManager.FULL_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP, "dndsync:MyWakeLock");
        wakeLock.acquire(2 * 60 * 1000L); // 2 分鐘安全期

        new Handler(Looper.getMainLooper()).post(() -> 
            Toast.makeText(context.getApplicationContext(), "正在同步睡眠模式...", Toast.LENGTH_SHORT).show()
        );

        new Thread(() -> {
            try {
                Thread.sleep(1000);
                serv.swipeDown();      // 下滑拉出快捷面板
                Thread.sleep(1000);
                serv.clickIcon1_1();   // 精準呼叫你現有的 clickIcon1_1() 點擊首排中心
                Thread.sleep(1000);
                serv.goBack();         // 返回，關閉面板
                WearLog.d(TAG, "✨ [無障礙聯動] 就寢模式（睡眠模式）自動化模擬腳本執行完畢。");
            } catch (Exception e) {
                WearLog.e(TAG, "❌ 無障礙操作中途被系統熔断或中斷", e);
            } finally {
                if (wakeLock.isHeld()) {
                    wakeLock.release();
                    WearLog.d(TAG, "🔒 Wakelock 釋放成功，手錶螢幕重回休眠機制。");
                }
            }
        }).start();
    }

    private static void vibrate(Context context) {
        Vibrator v = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
        if (v != null && v.hasVibrator()) {
            v.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE));
        }
    }
}
