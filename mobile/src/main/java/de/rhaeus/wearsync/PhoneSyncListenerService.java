package de.rhaeus.wearsync;

import android.content.Intent;
import android.util.Log;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.WearableListenerService;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;

public class PhoneSyncListenerService extends WearableListenerService {
    private static final String TAG = "WearSync_PhoneListener";
    public static boolean isInternalUpdate = false; 

    @Override
    public void onMessageReceived(MessageEvent messageEvent) {
        if (!"/wear-universal-sync".equals(messageEvent.getPath())) {
            super.onMessageReceived(messageEvent);
            return;
        }

        try {
            String jsonStr = new String(messageEvent.getData(), StandardCharsets.UTF_8);
            JSONObject json = new JSONObject(jsonStr);
            String type = json.optString("type", "");
            String action = json.optString("action", "");

            Log.d(TAG, "📥 手机底层骨干网收到信令 -> type: " + type + ", action: " + action);

            // 1. ☯️ 勿扰反向同步协议拉齐
            if ("dnd".equalsIgnoreCase(type)) {
                // 同时兼容读取 dnd_state 和 dnd_profile_value，阻断任何因拼写不一致导致的失效
                int state = json.has("dnd_state") ? json.optInt("dnd_state", -1) : json.optInt("dnd_profile_value", -1);
                if (state != -1) {
                    isInternalUpdate = true;
                    PhoneDndManager.handleIncomingAction(this, state);
                    
                    new Thread(() -> {
                        try { Thread.sleep(1000); } catch (Exception ignored) {}
                        isInternalUpdate = false;
                    }).start();
                }
            } 
            // 2. ⏰ 闹钟反向代点协议拉齐
            else if ("alarm".equalsIgnoreCase(type) || "alarm_action".equalsIgnoreCase(type)) {
                // 完美匹配来自手表的 action（DISMISS / SNOOZE）
                if ("DISMISS".equalsIgnoreCase(action) || "SNOOZE".equalsIgnoreCase(action)) {
                    PhoneAlarmManager.handleWatchCommand(this, action);
                }
            } 
            // 3. 📸 相机唤醒与释放协议拉齐
            else if ("camera".equalsIgnoreCase(type)) {
                if ("START_CAMERA_UI".equalsIgnoreCase(action)) {
                    Log.d(TAG, "📸 收到手表开机口令，正在全速拉开相机前台画布Activity...");
                    Intent cIntent = new Intent();
                    cIntent.setClassName(getPackageName(), "de.rhaeus.wearsync.PhoneSyncCameraActivity");
                    cIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                    startActivity(cIntent);
                } else if ("STOP_CAMERA_STREAM".equalsIgnoreCase(action)) {
                    sendBroadcast(new Intent("de.rhaeus.wearsync.ACTION_STOP_CAMERA_STREAM"));
                }
            }

        } catch (Exception e) {
            Log.e(TAG, "骨干通道解析数据包灾难性失败", e);
        }
    }
}
