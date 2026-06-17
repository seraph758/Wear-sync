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

            // 1. ☯️ 勿扰反向同步
            if ("dnd".equalsIgnoreCase(type)) {
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
            // 2. ⏰ 闹钟反向代点
            else if ("alarm".equalsIgnoreCase(type) || "alarm_action".equalsIgnoreCase(type)) {
                if ("DISMISS".equalsIgnoreCase(action) || "SNOOZE".equalsIgnoreCase(action)) {
                    PhoneAlarmManager.handleWatchCommand(this, action);
                }
            } 
            // 3. 📸 相机唤醒与释放协议（★核心重构点：手錶直接拉起手機端 Activity 跳板★）
            else if ("camera".equalsIgnoreCase(type) || "camera_control".equalsIgnoreCase(type)) {
                if ("START_CAMERA_UI".equalsIgnoreCase(action)) {
                    Log.d(TAG, "🚀 [核心重构] 收到手表拍照口令！拒绝后启动，直接将手机主控 Activity 拽到前台...");
                    
                    // 🎯 核心改变：拉起手机主界面，而非直接拉起后台预览
                    Intent clIntent = new Intent();
                    clIntent.setClassName(getPackageName(), "de.rhaeus.wearsync.PhoneSyncMainActivity");
                    clIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK 
                                    | Intent.FLAG_ACTIVITY_CLEAR_TOP 
                                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    clIntent.putExtra("INTERNAL_CMD", "LAUNCH_CAMERA_SERVICE_FROM_FOREGROUND");
                    startActivity(clIntent);

                } else if ("STOP_CAMERA".equalsIgnoreCase(action) || "STOP_CAMERA_STREAM".equalsIgnoreCase(action)) {
                    Log.d(TAG, "🛑 收到手表断开要求，下发本地广播释放手机端相机服务");
                    sendBroadcast(new Intent("de.rhaeus.wearsync.ACTION_STOP_CAMERA_STREAM"));
                }
            }

        } catch (Exception e) {
            Log.e(TAG, "骨干通道解析数据包灾难性失败", e);
        }
    }
}
