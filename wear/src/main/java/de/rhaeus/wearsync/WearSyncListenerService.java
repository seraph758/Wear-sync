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
            // 🌗 模塊一：狀態掩碼閉環矩陣（勿擾、就寢、省電同開同關子架構）
            // =========================================================================
            if ("status_mask".equalsIgnoreCase(type) || json.has("status_mask") || "dnd".equalsIgnoreCase(type)) {
                int statusMask = json.optInt("status_mask", -1);
                if (statusMask == -1 && json.has("dnd_state")) {
                    statusMask = (json.optInt("dnd_state", 0) > 0) ? 0x01 : 0x00;
                }

                if (statusMask != -1) {
                    final int finalMask = statusMask;
                    Log.d(TAG, "📥 [狀態模塊] 收到手機端權威 Mask: " + finalMask);

                    // 🔒 上鎖：防止狀態雙向碰撞回旋
                    isInternalUpdate = true;

                    new Thread(() -> {
                        try {
                            // ------ 🚀 獲取手錶當前物理狀態 ------
                            NotificationManager mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                            int currentWatchDndFilter = (mNotificationManager != null) ? mNotificationManager.getCurrentInterruptionFilter() : 1;
                            boolean isWatchDndOn = (currentWatchDndFilter > 1);
                            boolean isWatchBedtimeOn = Settings.Global.getInt(getContentResolver(), "bedtime_mode", 0) == 1;

                            // ------ 🎯 🧠 狀態動態校準與執行 ------

                            // 1️⃣ 解析 Bit 0 (0x01) -> 手機送來的勿擾預期狀態
                            boolean targetDndEnabled = (finalMask & 0x01) != 0;
                            Log.d(TAG, "🔍 勿擾校準: 手機預期=" + targetDndEnabled + ", 手錶當前=" + isWatchDndOn);

                            if (targetDndEnabled != isWatchDndOn) {
                                Log.d(TAG, " 🌗 [物理執行] 手錶勿擾狀態與手機不一致，開始對齊...");
                                if (mNotificationManager != null && mNotificationManager.isNotificationPolicyAccessGranted()) {
                                    mNotificationManager.setInterruptionFilter(targetDndEnabled ? 3 : 1);
                                    isWatchDndOn = targetDndEnabled; // 實時更新快照
                                }
                            }

                            // 2️⃣ 解析 Bit 1 (0x02) -> 就寢/睡眠模式狀態
                            boolean targetBedtimeEnabled = (finalMask & 0x02) != 0;
                            Log.d(TAG, "🔍 睡眠校準: 手機預期=" + targetBedtimeEnabled + ", 手錶當前=" + isWatchBedtimeOn);

                            if (targetBedtimeEnabled != isWatchBedtimeOn) {
                                Log.d(TAG, " 🛌 [物理執行] 手錶睡眠狀態與手機不一致，啟動無障礙自動點擊...");
                                Settings.Global.putInt(getContentResolver(), "bedtime_mode", targetBedtimeEnabled ? 1 : 0);

                                WearSyncAccessService serv = WearSyncAccessService.getSharedInstance();
                                if (serv != null) {
                                    triggerBedtimeModeViaAccessibility(serv);
                                }
                            }

                            // 3️⃣ 🔋 解析 Bit 3 (0x08) -> 省電模式子開關聯動（完全依附於勿擾模式，同開同關）
                            boolean isPowerSaveLinkageOn = (finalMask & 0x08) != 0;
                            boolean isDndTargetOn = (finalMask & 0x01) != 0;

                            // 只有【省電同步開啟】且【手機勿擾開啟】時，手錶才真正進省電；否則一律保持關閉
                            boolean shouldEnableWatchPowerSave = isPowerSaveLinkageOn && isDndTargetOn;
                            String expectedPowerValue = shouldEnableWatchPowerSave ? "1" : "0";

                            String currentPowerValue = Settings.Global.getString(getContentResolver(), "low_power");

                            if (!expectedPowerValue.equals(currentPowerValue)) {
                                Log.d(TAG, "🔋 [物理執行] 省電聯動觸發！同步開關=" + isPowerSaveLinkageOn + ", 勿擾狀態=" + isDndTargetOn + " ➔ 物理同步手錶底層為: " + expectedPowerValue);
                                Settings.Global.putString(getContentResolver(), "low_power", expectedPowerValue);
                                Settings.Secure.putString(getContentResolver(), "low_power", expectedPowerValue);
                            } else {
                                Log.d(TAG, "🔋 [物理執行] 手錶省電狀態符合預期，跳過重複寫入。");
                            }

                            // 4️⃣ 解析 Bit 2 (0x04) -> 決定手錶在「收到同步且發生變更」時是否發出震動提示
                            boolean shouldVibrateOnSync = (finalMask & 0x04) != 0;
                            if (shouldVibrateOnSync) {
                                triggerWatchVibrate();
                            }

                        } catch (Exception e) {
                            Log.e(TAG, "🔴 閉環矩陣執行遭遇異常", e);
                        } finally {
                            // ⏳ 延時重置更新鎖，破除狀態反向回旋死循環
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
            // ⏰ 模塊二：遠端鬧鐘控制鏈（完美支持全屏提示注入與核查再次拉起機制）
            // =========================================================================
            if ("alarm".equalsIgnoreCase(type)) {
                Log.d(TAG, "📥 [鬧鐘模塊] 收到控制動作: " + action);
                if ("START_ALARM_UI".equalsIgnoreCase(action)) {

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

                    Intent alarmIntent = new Intent(this, WearAlarmActivity.class);
                    alarmIntent.putExtra("EXTRA_ALARM_LABEL", alarmLabel);
                    alarmIntent.putExtra("EXTRA_ALARM_TIME", alarmTime);

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
            Log.pre(TAG, "手錶骨幹路由分發異常", e);
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
