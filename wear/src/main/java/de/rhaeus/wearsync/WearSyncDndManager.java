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
 * 🌓 手錶端勿擾、掩碼解讀與無障礙自動化聯控核心管理器
 */
public class WearSyncDndManager {
    private static final String TAG = "WearSync_WearDnd";

    /**
     * 📥 核心解析入口：負責解讀手機發送過來的 status_mask 數據並執行連鎖聯動
     */
    public static void handleIncomingMask(Context context, JSONObject json) {
        if (json == null) return;
        
        // 相容兩種 JSON 鍵名格式："status_mask" 欄位值或對象屬性
        int statusMask = json.optInt("status_mask", json.optInt("mask_value", -1));
        if (statusMask == -1) return;

        WearLog.d(TAG, "📥 [手錶端勿擾解析] 收到手機權威 Mask: " + statusMask);

        // 🎯 核心防護：直接把鎖下在發信人身上，絕殺雙向死循環大風暴！
        WearSyncNotificationService.isInternalUpdate = true;

        // 位運算精準拆解子功能開關狀態
        boolean targetDndEnabled = (statusMask & 0x01) != 0;       
        boolean isVibrateSwitchOn = (statusMask & 0x02) != 0;      
        boolean isSleepLinkageOpen = (statusMask & 0x04) != 0;     
        boolean isPowerSaveLinkageOpen = (statusMask & 0x08) != 0; 

        NotificationManager mNotificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        boolean hasDndChanged = false;

        // 1. 同步勿擾模式
        if (mNotificationManager != null) {
            int currentWatchDndFilter = mNotificationManager.getCurrentInterruptionFilter();
            boolean isWatchDndOn = (currentWatchDndFilter > 1);
            if (targetDndEnabled != isWatchDndOn) {
                mNotificationManager.setInterruptionFilter(targetDndEnabled ? 3 : 1); // 3: INTERRUPTION_FILTER_PRIORITY, 1: INTERRUPTION_FILTER_ALL
                hasDndChanged = true;
                WearLog.d(TAG, "🌗 [勿擾同步] 手錶本地勿擾已變更為: " + targetDndEnabled);
            }
        }

        // 2. 觸發勿擾同步震動
        if (isVibrateSwitchOn) {
            vibrate(context);
        } else {
            WearLog.w(TAG, "🔇 [勿擾震動子開關=關閉] 攔截並封鎖震動。");
        }

        // 3. 睡眠模式（就寢模式）自動化聯動
        if (isSleepLinkageOpen && hasDndChanged) {
            toggleBedtimeMode(context);
        }

        // 4. 省電模式自動化聯動
        if (isPowerSaveLinkageOpen) {
            Settings.Global.putInt(context.getContentResolver(), "low_power", targetDndEnabled ? 1 : 0);
            context.sendBroadcast(new Intent(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED));
            WearLog.d(TAG, "🔋 [省電聯動] 已同步手錶本地省電狀態為: " + (targetDndEnabled ? "開啟" : "關閉"));
        }

        // 5. 延遲解鎖，給手錶系統廣播徹底消散留出反應時間
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            WearSyncNotificationService.isInternalUpdate = false;
            WearLog.d(TAG, "🔓 [解鎖] 手錶防回傳死循環鎖已安全釋放。");
        }, 1500);
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
        wakeLock.acquire(2 * 60 * 1000L); // 2 分鐘安全期，保持手錶螢幕點亮以便無障礙模擬操作

        new Handler(Looper.getMainLooper()).post(() -> 
            Toast.makeText(context.getApplicationContext(), "正在同步睡眠模式...", Toast.LENGTH_SHORT).show()
        );

        // 開闢工作線程進行無障礙動作序列編排，避免阻塞主線程
        new Thread(() -> {
            try {
                Thread.sleep(1000);
                serv.swipeDown();      // 下滑拉出快捷面板
                Thread.sleep(1000);
                serv.clickIcon1_2();   // 點擊對應格子（就寢模式開關）
                Thread.sleep(1000);
                serv.goBack();         // 返回，關閉面板
                WearLog.d(TAG, "✨ [無障礙聯動] 就寢模式（睡眠模式）自動化模擬腳本執行完畢。");
            } catch (Exception e) {
                WearLog.e(TAG, "❌ 無障礙操作中途被系統熔斷或中斷", e);
            } finally {
                // 🎯 雙 'l' 的 finally 修正檢驗：強制釋放 Wakelock，防止手錶燒電長亮
                if (wakeLock.isHeld()) {
                    wakeLock.release();
                    WearLog.d(TAG, "🔒 Wakelock 釋放成功，手錶螢幕重回休眠機制。");
                }
            }
        }).start();
    }

    /**
     * 📳 本地輕量級震動反饋
     */
    private static void vibrate(Context context) {
        Vibrator v = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
        if (v != null && v.hasVibrator()) {
            v.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE));
        }
    }
}
