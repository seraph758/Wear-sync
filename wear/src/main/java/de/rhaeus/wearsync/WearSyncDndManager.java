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
    public static void updateConfigs(Context context, JSONObject json) {
        if (json == null) return;
        
        int statusMask = json.optInt("status_mask", json.optInt("mask_value", -1));
        if (statusMask == -1) return;

        // 🎯 核心正解：第一位 (Bit 0) 是總聯動許可證！
        isSyncAllowed = (statusMask & 0x01) != 0;       
        if (!isSyncAllowed) {
            WearLog.w(TAG, "🛑 [Mask 拋棄] 檢測到第一位 (Bit 0) 為 0，手機已關閉總聯動，後續全盤拋棄！");
            return; 
        }

        // 位運算精準拆解後面幾位子功能開關狀態
        isVibrateSwitchOn = (statusMask & 0x02) != 0;      // Bit 1 (值為 2)
        isSleepLinkageOpen = (statusMask & 0x04) != 0;     // Bit 2 (值為 4)
        isPowerSaveLinkageOpen = (statusMask & 0x08) != 0; // Bit 3 (值為 8)

        WearLog.d(TAG, "📥 [配置就緒] 原始 Mask: " + statusMask + " ➔ 震動=" + isVibrateSwitchOn + ", 睡眠=" + isSleepLinkageOpen + ", 省電=" + isPowerSaveLinkageOpen);
    }

    /**
     * 📥 模組二：接收原生 DND 狀態並強行指揮所有子聯動（後到達）
     * 🔥 100% 復刻舊代碼比對精髓，唯有狀態不相等時才驅動變更！
     * @param dndStatePhone 手機傳過來的系統原生 filter 狀態值 (1, 2, 3, 4)
     */
    public static void executeDndSync(Context context, int dndStatePhone) {
        // 🎯 生死防線：如果 Mask 總開關為 0，直接拋棄，不作任何執行
        if (!isSyncAllowed) {
            WearLog.w(TAG, "🛑 [DND 狀態拋棄] 由於 Mask 總開關為 0，拒絕本次同步。");
            return;
        }

        NotificationManager mNotificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (mNotificationManager == null) return;
        
        // 🎯 100% 復刻舊代碼安全範圍校驗
        int filterState = mNotificationManager.getCurrentInterruptionFilter();
        if (filterState < 0 || filterState > 4) {
            WearLog.d(TAG, "DNDSync weird current dnd state: " + filterState);
        }
        int currentDndState = filterState;

        WearLog.d(TAG, "🔍 [對比核心] 手機發來 dndStatePhone: " + dndStatePhone + " | 手錶當前 currentDndState: " + currentDndState);

        // 🔥 【復刻舊代碼精髓】唯有當手機與手錶當前狀態「不相等」時，才引爆流水線
        if (dndStatePhone != currentDndState) {
            WearLog.d(TAG, "⚡ [狀態不相等] dndStatePhone != currentDndState: " + dndStatePhone + " != " + currentDndState + "，啟動聯動！");

            // 🎯 核心防護：直接把鎖下在發信人身上，絕殺雙向死循環大風暴！
            WearSyncNotificationService.isInternalUpdate = true;

            // 1. 睡眠模式（就寢模式）自動化聯動 (只要勿擾變了，開關開著，就執行一次無障礙開關點擊)
            if (isSleepLinkageOpen) {
                WearLog.d(TAG, "🛌 [睡眠聯動] 執行無障礙點擊切換。");
                toggleBedtimeMode(context);
            }

            // 2. 省電模式自動化聯動 (🔥 強制同步：完全參考手機原始 filter。>1 代表手機開了勿擾，省電同開；等於 1 代表關了，省電同關)
            if (isPowerSaveLinkageOpen) {
                boolean shouldPowerSaveOn = (dndStatePhone > 1);
                int targetPowerMode = shouldPowerSaveOn ? 1 : 0;
                Settings.Global.putInt(context.getContentResolver(), "low_power", targetPowerMode);
                context.sendBroadcast(new Intent(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED));
                WearLog.d(TAG, "🔋 [省電聯動] 已強制同步手錶本地省電狀態為: " + (shouldPowerSaveOn ? "開啟" : "關閉"));
            }

            // 3. 觸發勿擾同步震動 (只有當手機狀態是「開啟勿擾」時，且開關打開，才允許嗡一聲)
            if (isVibrateSwitchOn && (dndStatePhone > 1)) {
                WearLog.d(TAG, "📳 [勿擾震動] 條件成立，執行震動提示。");
                vibrate(context);
            } else {
                WearLog.w(TAG, "🔇 [勿擾震動攔截] 手機為關閉勿擾，或手錶未勾選震動，封鎖震動。");
            }

            // 4. 最後硬性寫入手錶本地勿擾狀態 (100% 還原舊代碼)
            if (mNotificationManager.isNotificationPolicyAccessGranted()) {
                mNotificationManager.setInterruptionFilter(dndStatePhone);
                WearLog.d(TAG, "🌗 [勿擾寫入] DND set to " + dndStatePhone);
            } else {
                WearLog.e(TAG, "attempting to set DND but access not granted");
            }

            // 5. 延遲解鎖，給手錶系統廣播徹底消散留出反應時間
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                WearSyncNotificationService.isInternalUpdate = false;
                WearLog.d(TAG, "🔓 [解鎖] 手錶防回傳死循環鎖已安全釋放。");
            }, 2000);

        } else {
            WearLog.d(TAG, "✅ [對比吻合] 手機與手錶狀態完全相同，安全攔截，不作重複動作。");
        }
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
