package de.rhaeus.wearsync;

import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.PowerManager;
import android.os.Vibrator;
import android.os.VibrationEffect;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;
import androidx.annotation.NonNull;
import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.ChannelClient;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.Wearable;
import com.google.android.gms.wearable.WearableListenerService;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class WearSyncListenerService extends WearableListenerService {
    private static final String TAG = "WearSync_WearListener";
    private static final String UNIVERSAL_SYNC_PATH = "/wear-universal-sync";

    // 手勢內部宏防併發鎖
    private static boolean isGestureMacroRunning = false;

    @Override
    public void onMessageReceived(@NonNull MessageEvent messageEvent) {
        if (!UNIVERSAL_SYNC_PATH.equalsIgnoreCase(messageEvent.getPath())) return;
        byte[] data = messageEvent.getData();
        if (data == null) return;

        try {
            String jsonStr = new String(data, StandardCharsets.UTF_8);
            JSONObject json = new JSONObject(jsonStr);
            String type = json.optString("type", "");
            String action = json.optString("action", "");

            // 1️⃣ 勿擾/就寢/省電 同步區
            if ("dnd".equalsIgnoreCase(type)) {
                int dndStatePhone = json.optInt("dnd_profile_value", -1);
                int score = json.optInt("switches_mask", 0); // 🎯 完美保留：手機發來的同步配置掩碼

                if (dndStatePhone == -1) return;

                NotificationManager mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                if (mNotificationManager == null) return;

                // 🎯 完美保留：精準拆解用戶在手機 UI 上到底啟用了哪些「同步選項」
                boolean isSleepSyncEnabled   = (score & 1) != 0; // 第一位：是否勾選了「就寢同步」
                boolean isPowerSyncEnabled   = (score & 2) != 0; // 第二位：是否勾選了「省電同步」
                boolean isVibrateEnabled     = (score & 4) != 0; // 第三位：是否勾選了「震動提示」

                // ---------------------------------------------------------------------
                // 🔋 【完美保留：省電同步選項的真實動作】
                // ---------------------------------------------------------------------
                if (isPowerSyncEnabled) {
                    try {
                        boolean phoneExpectsDndOn = (dndStatePhone == NotificationManager.INTERRUPTION_FILTER_PRIORITY || 
                                                     dndStatePhone == NotificationManager.INTERRUPTION_FILTER_NONE ||
                                                     dndStatePhone == NotificationManager.INTERRUPTION_FILTER_ALARMS);
                        
                        if (phoneExpectsDndOn) {
                            Log.d(TAG, "🔋 [省電同步激活] 手機開啟了就寢，手錶跟隨底層強制開啟省電模式");
                            Settings.Global.putInt(getContentResolver(), "low_power", 1);
                        } else {
                            Log.d(TAG, "🔌 [省電同步激活] 手機關閉了就寢，手錶跟隨底層強制關閉省電模式");
                            Settings.Global.putInt(getContentResolver(), "low_power", 0);
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "🚨 省電模式底層寫入失敗", e);
                    }
                } else {
                    Log.d(TAG, "🛡️ [省電同步關閉] 檢測到未開啟省電同步選項，手錶不對省電模式做任何操作。");
                }

                // ---------------------------------------------------------------------
                // 🛌 【完美保留：原本跑得很完美的勿擾/就寢手勢宏區域】
                // ---------------------------------------------------------------------
                int currentDndState = mNotificationManager.getCurrentInterruptionFilter();
                boolean phoneExpectsDndOn = (dndStatePhone == NotificationManager.INTERRUPTION_FILTER_PRIORITY || 
                                             dndStatePhone == NotificationManager.INTERRUPTION_FILTER_NONE ||
                                             dndStatePhone == NotificationManager.INTERRUPTION_FILTER_ALARMS);
                boolean wearLocalDndIsOn = (currentDndState == NotificationManager.INTERRUPTION_FILTER_PRIORITY || 
                                            currentDndState == NotificationManager.INTERRUPTION_FILTER_NONE ||
                                            currentDndState == NotificationManager.INTERRUPTION_FILTER_ALARMS);

                // 狀態感知防火牆
                if (phoneExpectsDndOn != wearLocalDndIsOn) {
                    Log.d(TAG, "🔄 勿擾狀態不一致！執行物理手勢校準。");

                    if (isVibrateEnabled) {
                        vibrateShort(); // 如果勾選了震動選項，觸發短震動
                    }

                    if (isSleepSyncEnabled) {
                        executePhysicalBedtimeMacro(); // 如果勾選了就寢同步選項，執行下拉手勢
                    }

                    // 保底強制同步手錶底層的勿擾 Filter
                    if (mNotificationManager.isNotificationPolicyAccessGranted()) {
                        mNotificationManager.setInterruptionFilter(dndStatePhone);
                    }
                } else {
                    Log.d(TAG, "🛡️ [防錯位攔截] 勿擾狀態已對齊，攔截物理下拉手勢，防止反向翻轉錯位。");
                }
                return; // 🎯 乾淨返回，結束 dnd 分支
            } 
            
            // 2️⃣ 相機模組控制分支
else if ("camera_control".equalsIgnoreCase(type)) {
    if ("START_CAMERA".equalsIgnoreCase(action)) {
        Log.d(TAG, "📸 [手錶監聽] 收到手機端拉起相機指令，執行強行物理亮屏保護...");

        // 🎯 核心修正二：后台收到拉起命令时，先申请 3 秒 WakeLock 点亮硬件屏幕
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                PowerManager.WakeLock wl = pm.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP, 
                    "WearSync:CameraWakeLock"
                );
                wl.acquire(3000L); // 唤醒 3 秒，足够给 Activity 完成转场和接管
            }
        } catch (Exception e) {
            Log.e(TAG, "🚨 后台物理亮屏唤醒失败", e);
        }

        // 唤醒后拉起唯一的 Activity
        Intent startCamIntent = new Intent(this, WearCameraActivity.class);
        startCamIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        try {
            startActivity(startCamIntent);
        } catch (Exception e) {
            Log.e(TAG, "🚨 手錶端拉起 WearCameraActivity 失敗", e);
        }
    }
    return; 
}


            // ===================================================================================
            // === [🔥 LOCKED_FIREWALL: ALARM_MODULE_WEAR_UI_LAUNCH_FIREWALL - START] ===
            // 🚨 完美保留：鬧鐘核心代碼嚴密保護，包名修復後依然完好如初，絕不允許被污染或改動！
            if ("alarm".equalsIgnoreCase(type)) {
                if ("START_ALARM_UI".equalsIgnoreCase(action)) {
                    Intent uiIntent = new Intent(this, WearAlarmActivity.class);
                    uiIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    startActivity(uiIntent);
                } else if ("FORCE_STOP_WEAR_ALARM".equalsIgnoreCase(action)) {
                    sendBroadcast(new Intent("de.rhaeus.wearsync.FORCE_STOP_ALARM_UI"));
                }
                return;
            }
            // === [🔥 LOCKED_FIREWALL: ALARM_MODULE_WEAR_UI_LAUNCH_FIREWALL - END] ===
            // ===================================================================================

        } catch (Exception e) { 
            Log.e(TAG, "流解析异常", e); 
        }
    }

    /**
     * 獨立非阻塞線程：嚴格依照舊代碼的時序執行「喚醒 -> 下滑 -> 點擊 -> 返回」
     */
    private void executePhysicalBedtimeMacro() {
        if (isGestureMacroRunning) {
            Log.w(TAG, "⚠️ 手勢宏正在執行中，拒絕併發干擾");
            return;
        }

        new Thread(() -> {
            WearSyncAccessService serv = WearSyncAccessService.getSharedInstance();
            if (serv == null) {
                Log.e(TAG, "❌ 無障礙服務未連通，放棄執行手勢");
                return;
            }

            PowerManager.WakeLock wakeLock = null;
            try {
                isGestureMacroRunning = true;

                // 1. 物理強制點亮螢幕
                PowerManager pm = (PowerManager) getApplicationContext().getSystemService(Context.POWER_SERVICE);
                if (pm != null) {
                    wakeLock = pm.newWakeLock(PowerManager.FULL_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP | PowerManager.ON_AFTER_RELEASE, "wearsync:WakeLock");
                    wakeLock.acquire(8000L); // 鎖定 7 秒完成整套動作
                }

                // 2. 核心時序緩衝：留出2000ms 讓螢幕硬體完全亮起，防止螢幕沒亮完導致下滑無效
                Thread.sleep(2000);

                // 3. 呼叫舊代碼純淨下滑
                serv.swipeDown();

                // 4. 時序緩衝：留出 1000ms 給下拉選單動畫展開，防止面板還沒拉下來就去盲點
                Thread.sleep(1000);

                // 5. 點擊第一排中間圖標
                serv.clickIcon1_2();

                // 6. 時序緩衝：留出 1000ms 給系統響應就寢/睡眠狀態變更
                Thread.sleep(800);

                // 7. 收起快捷面板
                serv.goBack();
                Log.d(TAG, "🏁 [手勢宏] 物理控制校準鏈條圓滿結束");

            } catch (InterruptedException e) {
                Log.e(TAG, "手勢宏線程中斷", e);
            } finally {
                if (wakeLock != null && wakeLock.isHeld()) {
                    wakeLock.release();
                }
                isGestureMacroRunning = false;
            }
        }).start();
    }

    private void vibrateShort() {
        Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        if (v != null && v.hasVibrator()) {
            v.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE));
        }
    }
}