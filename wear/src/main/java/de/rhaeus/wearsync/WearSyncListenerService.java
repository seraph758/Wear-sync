package de.rhaeus.wearsync;

import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;
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

            // ================= 🌙 1️⃣ 勿扰模式协议完美校准 =================
            if ("dnd".equalsIgnoreCase(type)) {
                // 🎯 协议拉齐：读取手机端发出的 dnd_state 字段
                int phoneDndState = json.optInt("dnd_state", -1);
                Log.d(TAG, "📥 [勿扰信令] 收到手机正向同步勿扰状态: " + phoneDndState);
                if (phoneDndState != -1) {
                    // 执行无障碍自动化模拟剧本
                    WearSyncAccessService serv = WearSyncAccessService.getSharedInstance();
                    if (serv != null) {
                        new Thread(() -> {
                            try {
                                serv.swipeDown(); Thread.sleep(600);
                                serv.clickIcon1_2(); Thread.sleep(500);
                                serv.goBack();
                            } catch (Exception ignored) {}
                        }).start();
                    }
                }
                return;
            }

            // ================= ⏰ 2️⃣ 远端闹钟核心连通协议 =================
            if ("alarm".equalsIgnoreCase(type)) {
                Log.d(TAG, "📥 [闹钟信令] 收到控制动作: " + action);
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

            // ================= 📸 3️⃣ 相机主控协议连通 =================
            if ("camera_control".equalsIgnoreCase(type)) {
                Log.d(TAG, "📥 [相机信令] 收到主控动作: " + action);
                if ("START_CAMERA".equalsIgnoreCase(action)) {
                    int rotationDegrees = json.optInt("rotation_degrees", 0);
                    Intent camIntent = new Intent(this, WearCameraActivity.class);
                    camIntent.putExtra("rotation_degrees", rotationDegrees);
                    camIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                    startActivity(camIntent);
                } else if ("FORCE_QUIT_CAMERA".equalsIgnoreCase(action)) {
                    WearCameraActivity.forceQuitInstance();
                }
                return;
            }

        } catch (Exception e) {
            Log.e(TAG, "手表骨干路由分发异常", e);
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
