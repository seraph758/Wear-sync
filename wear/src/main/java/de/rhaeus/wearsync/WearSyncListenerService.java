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
// 🌗 模块一：勿扰模式单向对齐块（手机发 MASK -> 手表对齐本地勿扰）
// =========================================================================
if ("status_mask".equalsIgnoreCase(type) || json.has("status_mask") || "dnd".equalsIgnoreCase(type)) {
    int statusMask = json.optInt("status_mask", -1);
    if (statusMask == -1 && json.has("dnd_state")) {
        statusMask = (json.optInt("dnd_state", 0) > 0) ? 0x01 : 0x00;
    }

    if (statusMask != -1) {
        final int finalMask = statusMask;
        Log.d(TAG, "📥 [勿扰模块] 收到手机端权威状态 MASK: " + finalMask);

        // 🔒 激活锁，防止手表本地勿扰改变反向回传手机
        isInternalUpdate = true;

        new Thread(() -> {
            try {
                NotificationManager mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                if (mNotificationManager != null) {
                    int currentWatchDndFilter = mNotificationManager.getCurrentInterruptionFilter();
                    boolean isWatchDndOn = (currentWatchDndFilter > 1); // >1 说明开启了某些勿扰

                    // 🎯 核心：精炼提取手机发来的 Bit 0 勿扰标志位 (0x01)
                    boolean targetDndEnabled = (finalMask & 0x01) != 0;
                    Log.d(TAG, "🔍 勿扰对齐核查 -> 手机 Mask 勿扰状态=" + targetDndEnabled + ", 手表当当前状态=" + isWatchDndOn);

                    if (targetDndEnabled != isWatchDndOn) {
                        // 3 代表 INTERRUPTION_FILTER_NONE (全面勿扰模式), 1 代表 INTERRUPTION_FILTER_ALL (允许所有)
                        mNotificationManager.setInterruptionFilter(targetDndEnabled ? 3 : 1);
                        Log.i(TAG, "🌗 [物理执行] 手表本地勿扰已完美对齐手机，状态: " + targetDndEnabled);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "🔴 物理执行同步手表勿扰异常", e);
            } finally {
                // ⏳ 延时安全释放锁
                new Handler(getMainLooper()).postDelayed(() -> {
                    isInternalUpdate = false;
                    Log.d(TAG, "🔒 [解锁] 手表内部勿扰更新锁已释放。");
                }, 1500);
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
