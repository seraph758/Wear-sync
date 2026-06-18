package de.rhaeus.wearsync;

import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
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

import java.io.EOFException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public class WearSyncListenerService extends WearableListenerService {
    private static final String TAG = "WearSync_WearListener";
    private static final String UNIVERSAL_SYNC_PATH = "/wear-universal-sync";
    private static final String CAMERA_PREVIEW_STREAM_PATH = "/camera-preview-stream";

    // 🔒 核心防護：全局內部更新鎖，徹底避免雙向狀態回旋死循環
    public static boolean isInternalUpdate = false;

    @Override
    public void onMessageReceived(@NonNull MessageEvent messageEvent) {
        if (!UNIVERSAL_SYNC_PATH.equalsIgnoreCase(messageEvent.getPath())) return;
        byte[] data = messageEvent.getData();
        if (data == null) return;

        try {
            String jsonStr = new String(data, StandardCharsets.UTF_8);
            JSONObject json = new JSONObject(jsonStr);
            String sender = json.optString("sender", "");
            String type = json.optString("type", "");
            String action = json.optString("action", "");

            if ("wear".equalsIgnoreCase(sender)) return;

            // =========================================================================
            // 🌗 模塊一：勿擾狀態閉環矩陣（支持動態狀態校準、省電關聯、防回旋）
            // =========================================================================
            if ("status_mask".equalsIgnoreCase(type) || json.has("status_mask") || "dnd".equalsIgnoreCase(type)) {
                int statusMask = json.optInt("status_mask", -1);
                if (statusMask == -1 && json.has("dnd_state")) {
                    statusMask = (json.optInt("dnd_state", 0) > 0) ? 0x01 : 0x00;
                }

                if (statusMask != -1) {
                    final int finalMask = statusMask;
                    Log.d(TAG, "📥 [勿擾模塊] 收到手機端權威 Mask: " + finalMask);

                    // 🔒 上鎖：告訴系統當前是手機觸發的正向同步，手錶本地監聽器收到變更時不要反向發送，避免回旋
                    isInternalUpdate = true;

                    new Thread(() -> {
                        try {
                            // ------ 🚀 獲取手錶當前物理狀態 ------
                            NotificationManager mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                            // 獲取手錶當前勿擾狀態 (通常 1=無勿擾, 2,3,4=各類勿擾)
                            int currentWatchDndFilter = (mNotificationManager != null) ? mNotificationManager.getCurrentInterruptionFilter() : 1;
                            boolean isWatchDndOn = (currentWatchDndFilter > 1);

                            // 獲取手錶當前睡眠模式數據庫狀態
                            boolean isWatchBedtimeOn = Settings.Global.getInt(getContentResolver(), "bedtime_mode", 0) == 1;

                            // ------ 🎯 🧠 狀態動態校準與執行 ------

                            // 1️⃣ 解析 Bit 1 (0x01) -> 手機送來的勿擾預期狀態
                            boolean targetDndEnabled = (finalMask & 0x01) != 0;
                            Log.d(TAG, "🔍 勿擾校準: 手機預期=" + targetDndEnabled + ", 手錶當前=" + isWatchDndOn);
                            
                            // 【規則1】不一致則修改，一致則不動
                            if (targetDndEnabled != isWatchDndOn) {
                                Log.d(TAG, " 🌗 [物理執行] 手錶勿擾狀態與手機不一致，開始對齊...");
                                if (mNotificationManager != null && mNotificationManager.isNotificationPolicyAccessGranted()) {
                                    // 3 代表 INTERRUPTION_FILTER_NONE (全面勿擾)，1 代表 INTERRUPTION_FILTER_ALL (全部允許)
                                    mNotificationManager.setInterruptionFilter(targetDndEnabled ? 3 : 1);
                                    // 更新手錶當前的動態快照，供後面省電模式精準過濾
                                    isWatchDndOn = targetDndEnabled; 
                                }
                            }

                            // 2️⃣ 解析 Bit 2 (0x02) -> 就寢/睡眠模式狀態
                            boolean targetBedtimeEnabled = (finalMask & 0x02) != 0;
                            Log.d(TAG, "🔍 睡眠校準: 手機預期=" + targetBedtimeEnabled + ", 手錶當前=" + isWatchBedtimeOn);
                            
                            // 【規則1】不一致則修改，一致則不動
                            if (targetBedtimeEnabled != isWatchBedtimeOn) {
                                Log.d(TAG, " 🛌 [物理執行] 手錶睡眠狀態與手機不一致，啟動無障礙自動點擊...");
                                Settings.Global.putInt(getContentResolver(), "bedtime_mode", targetBedtimeEnabled ? 1 : 0);
                                
                                WearSyncAccessService serv = WearSyncAccessService.getSharedInstance();
                                if (serv != null) {
                                    triggerBedtimeModeViaAccessibility(serv);
                                }
                            }

                            // 3️⃣ 解析 Bit 4 (0x08) -> 同步省電模式開關
                            boolean shouldSyncPowerSave = (finalMask & 0x08) != 0;
                            if (shouldSyncPowerSave) {
                                // 【規則2】省電模式如果為 true，則跟隨手錶的勿擾狀態（要開一起開，要關一起關）
                                Log.d(TAG, "🔋 [物理執行] 省電同步開啟，跟隨手錶勿擾狀態 ➔ " + isWatchDndOn);
                                String powerValue = isWatchDndOn ? "1" : "0";
                                Settings.Global.putString(getContentResolver(), "low_power", powerValue);
                                Settings.Secure.putString(getContentResolver(), "low_power", powerValue);
                            }

                            // 4️⃣ 解析 Bit 3 (0x04) -> 決定手錶在「收到同步且發生變更」時是否發出震動提示
                            boolean shouldVibrateOnSync = (finalMask & 0x04) != 0;
                            if (shouldVibrateOnSync) {
                                triggerWatchVibrate();
                            }

                        } catch (Exception e) {
                            Log.e(TAG, "🔴 閉環矩陣執行遭遇異常", e);
                        } finally {
                            // ⏳ 延時重置更新鎖，確保手錶系統底層狀態徹底渲染完畢，完美破除狀態回旋
                            new Thread(() -> {
                                try { Thread.sleep(1200); } catch (Exception ignored) {}
                                isInternalUpdate = false;
                                Log.d(TAG, "🔒 [解鎖] 內部更新鎖解除，恢復手錶主動控手機能力。");
                            }).start();
                        }
                    }).start();
                }
                return; 
            }

            // =========================================================================
 // =========================================================================
// ⏰ 模塊二：遠端鬧鐘控制鏈（完美支持全屏提示注入與核查再次拉起機制）
// =========================================================================
if ("alarm".equalsIgnoreCase(type)) {
    Log.d(TAG, "📥 [鬧鐘模塊] 收到控制動作: " + action);
    if ("START_ALARM_UI".equalsIgnoreCase(action)) {
        
        // 🎯 1. 提取手機端同步發過來的全屏提示文字（若手機端沒發，則給出默認缺省值）
        String alarmLabel = json.optString("alarm_label", "鬧鐘響鈴中");
        String alarmTime = json.optString("alarm_time", "");

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            PowerManager.WakeLock wakeLock = pm.newWakeLock(
                    PowerManager.FULL_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP | PowerManager.ON_AFTER_RELEASE, 
                    "wearsync:AlarmScreenWakeLock"
            );
            wakeLock.acquire(3000L);
        }

        // 🎯 2. 組裝 Intent，將全屏提示數據注入 Activity
        Intent alarmIntent = new Intent(this, WearAlarmActivity.class);
        alarmIntent.putExtra("EXTRA_ALARM_LABEL", alarmLabel);
        alarmIntent.putExtra("EXTRA_ALARM_TIME", alarmTime);
        
        // FLAG_ACTIVITY_SINGLE_TOP 核心用處：如果手錶代點失敗被手機二次拉起，
        // 且手錶介面還沒來得及退出時，不會重複開多個頁面，而是直接觸發 onNewIntent 刷新。
        alarmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK 
                            | Intent.FLAG_ACTIVITY_CLEAR_TOP 
                            | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(alarmIntent);
    } 
    else if ("FORCE_STOP_WEAR_ALARM".equalsIgnoreCase(action)) {
        Intent stopBroadcast = new Intent(WearAlarmActivity.ACTION_INTERNAL_FORCE_STOP);
        sendBroadcast(stopBroadcast);
    }
    return; 
}

            // =========================================================================
            // 📸 模塊三：相機主控協議鏈（完全獨立）
            // =========================================================================
            if ("camera_control".equalsIgnoreCase(type) || "camera".equalsIgnoreCase(type)) {
                Log.d(TAG, "📥 [相機模塊] 收到主控動作: " + action);
                if ("START_CAMERA".equalsIgnoreCase(action)) {
                    int rotationDegrees = json.optInt("rotation_degrees", 0);
                    Intent camIntent = new Intent(this, WearCameraActivity.class);
                    camIntent.putExtra("rotation_degrees", rotationDegrees);
                    camIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                    startActivity(camIntent);
                } 
                else if ("FORCE_QUIT_CAMERA".equalsIgnoreCase(action) || "STOP_CAMERA_ACTIVE".equalsIgnoreCase(action)) {
                    Intent killIntent = new Intent("de.rhaeus.wearsync.ACTION_KILL_WEAR_CAMERA");
                    sendBroadcast(killIntent);
                    WearCameraActivity.forceQuitInstance();
                }
                return; 
            }

        } catch (Exception e) {
            Log.e(TAG, "手錶骨幹路由分發異常", e);
        }
    }

    private void triggerWatchVibrate() {
        try {
            Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null) {
                v.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE));
            }
        } catch (Exception e) {
            Log.e(TAG, "手錶同步震動提示失敗", e);
        }
    }

    private void triggerBedtimeModeViaAccessibility(WearSyncAccessService serv) {
        try {
            PowerManager pm = (PowerManager) getApplicationContext().getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                PowerManager.WakeLock wakeLock = pm.newWakeLock(
                        PowerManager.FULL_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP, 
                        "wearsync:BedtimeWakeLock"
                );
                wakeLock.acquire(3000L); 

                Thread.sleep(400); 
                serv.swipeDown();  
                Thread.sleep(800); 

                serv.clickIcon1_2(); 
                Thread.sleep(600); 

                serv.goBack();     
                
                if (wakeLock.isHeld()) {
                    wakeLock.release();
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "睡眠模式無障礙執行中斷", e);
        }
    }

    @Override
    public void onChannelOpened(@NonNull ChannelClient.Channel channel) {
        super.onChannelOpened(channel);
        if (!CAMERA_PREVIEW_STREAM_PATH.equalsIgnoreCase(channel.getPath())) return;

        new Thread(() -> {
            InputStream inputStream = null;
            try {
                inputStream = Tasks.await(Wearable.getChannelClient(this).getInputStream(channel));
                byte[] headerBuffer = new byte[4];

                while (true) {
                    readFully(inputStream, headerBuffer, 4);
                    int packetLength = ((headerBuffer[0] & 0xFF) << 24) |
                                       ((headerBuffer[1] & 0xFF) << 16) |
                                       ((headerBuffer[2] & 0xFF) << 8)  |
                                       (headerBuffer[3] & 0xFF);

                    if (packetLength <= 0 || packetLength > 1024 * 1024 * 3) break;

                    byte[] jpegPayload = new byte[packetLength];
                    readFully(inputStream, jpegPayload, packetLength);

                    int trailer = inputStream.read();
                    if (trailer != 0xFF) continue;

                    WearCameraActivity.updateFrame(jpegPayload);
                }
            } catch (Exception ignored) {
            } finally {
                if (inputStream != null) {
                    try { inputStream.close(); } catch (Exception ignored) {}
                }
            }
        }).start();
    }

    private void readFully(InputStream is, byte[] buffer, int length) throws Exception {
        int totalRead = 0;
        while (totalRead < length) {
            int read = is.read(buffer, totalRead, length - totalRead);
            if (read == -1) throw new EOFException("Stream closed prematurely");
            totalRead += read;
        }
    }
}
