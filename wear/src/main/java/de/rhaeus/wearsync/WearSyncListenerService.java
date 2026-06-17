package de.rhaeus.wearsync;

import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;
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

            // ================= 🧠 終極重構：雙端 Bitmask 狀態矩陣解耦分流器 =================
            if ("status_mask".equalsIgnoreCase(type) || json.has("status_mask") || "dnd".equalsIgnoreCase(type)) {
                // 優先讀取綜合 mask，若無則降級兼容舊勿擾欄位
                int statusMask = json.optInt("status_mask", -1);
                if (statusMask == -1 && json.has("dnd_state")) {
                    int dndState = json.optInt("dnd_state", 0);
                    statusMask = (dndState > 0) ? 0x01 : 0x00;
                }

                if (statusMask != -1) {
                    final int finalMask = statusMask;
                    Log.d(TAG, "📥 [骨幹矩陣信令] 手錶解析到手機同步 Mask: " + finalMask + " (二進制: " + Integer.toBinaryString(finalMask) + ")");
                    
                    new Thread(() -> {
                        try {
                            // 1. 【勿擾模式校準】 檢查第 1 位 (0x01)
                            boolean dndEnabled = (finalMask & 0x01) != 0;
                            Log.d(TAG, " 🌗 矩陣分流 ➔ 勿擾模式狀態: " + dndEnabled);
                            WearSyncAccessService serv = WearSyncAccessService.getSharedInstance();
                            if (serv != null) {
                                // 觸發無障礙自動點擊同步
                                serv.swipeDown(); Thread.sleep(600);
                                serv.clickIcon1_2(); Thread.sleep(500);
                                serv.goBack(); Thread.sleep(500);
                            }

                            // 2. 【就寢/睡眠模式校準】 檢查第 2 位 (0x02)
                            boolean bedtimeEnabled = (finalMask & 0x02) != 0;
                            Log.d(TAG, " 🛌 矩陣分流 ➔ 就寢模式狀態: " + bedtimeEnabled);
                            Settings.Global.putInt(getContentResolver(), "bedtime_mode", bedtimeEnabled ? 1 : 0);

                            // 3. 【同步震動模式校準】 檢查第 3 位 (0x04)
                            boolean vibrateEnabled = (finalMask & 0x04) != 0;
                            Log.d(TAG, " 📳 矩陣分流 ➔ 震動狀態: " + vibrateEnabled);
                            AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
                            if (am != null) {
                                am.setRingerMode(vibrateEnabled ? AudioManager.RINGER_MODE_VIBRATE : AudioManager.RINGER_MODE_NORMAL);
                            }

                            // 4. 【系統省電模式校準】 檢查第 4 位 (0x08)
                            boolean powerSaveEnabled = (finalMask & 0x08) != 0;
                            Log.d(TAG, " 🔋 矩陣分流 ➔ 系統省電模式狀態: " + powerSaveEnabled);
                            String powerValue = powerSaveEnabled ? "1" : "0";
                            // 利用 ADB 授予的 WRITE_SECURE_SETTINGS 權限雙域協同寫入
                            Settings.Global.putString(getContentResolver(), "low_power", powerValue);
                            Settings.Secure.putString(getContentResolver(), "low_power", powerValue);

                        } catch (Exception e) {
                            Log.e(TAG, "🔴 手錶執行矩陣指令時遭遇中斷或權限拒絕", e);
                        }
                    }).start();
                }
                
                // 如果純粹是狀態同步，解析完 mask 即可返回；若含舊型 dnd 字段則不影響向下執行
                if ("status_mask".equalsIgnoreCase(type)) return;
            }

            // ================= ⏰ 2️⃣ 遠端鬧鐘核心連通協議 =================
            if ("alarm".equalsIgnoreCase(type)) {
                Log.d(TAG, "📥 [鬧鐘信令] 收到控制動作: " + action);
                if ("START_ALARM_UI".equalsIgnoreCase(action)) {
                    PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                    if (pm != null) {
                        PowerManager.WakeLock wakeLock = pm.newWakeLock(
                                PowerManager.FULL_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP | PowerManager.ON_AFTER_RELEASE, 
                                "wearsync:AlarmScreenWakeLock"
                        );
                        wakeLock.acquire(3000L);
                    }
                    Intent alarmIntent = new Intent(this, WearAlarmActivity.class);
                    alarmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                    startActivity(alarmIntent);
                } else if ("FORCE_STOP_WEAR_ALARM".equalsIgnoreCase(action)) {
                    Intent stopBroadcast = new Intent(WearAlarmActivity.ACTION_INTERNAL_FORCE_STOP);
                    sendBroadcast(stopBroadcast);
                }
                return;
            }

            // ================= 📸 3️⃣ 相機主控協議連通 =================
            if ("camera_control".equalsIgnoreCase(type) || "camera".equalsIgnoreCase(type)) {
                Log.d(TAG, "📥 [相機信令] 收到主控動作: " + action);
                if ("START_CAMERA".equalsIgnoreCase(action)) {
                    int rotationDegrees = json.optInt("rotation_degrees", 0);
                    Intent camIntent = new Intent(this, WearCameraActivity.class);
                    camIntent.putExtra("rotation_degrees", rotationDegrees);
                    camIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                    startActivity(camIntent);
                } 
                else if ("FORCE_QUIT_CAMERA".equalsIgnoreCase(action) || "STOP_CAMERA_ACTIVE".equalsIgnoreCase(action)) {
                    Log.d(TAG, "🛑 收到手機下發的明確關閉命令，下發手錶本地廣播清退相機...");
                    Intent killIntent = new Intent("de.rhaeus.wearsync.ACTION_KILL_WEAR_CAMERA");
                    sendBroadcast(killIntent);
                    WearCameraActivity.forceQuitInstance(); // 雙重兜底清理
                }
                return;
            }

        } catch (Exception e) {
            Log.e(TAG, "手錶骨幹路由分發異常", e);
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
