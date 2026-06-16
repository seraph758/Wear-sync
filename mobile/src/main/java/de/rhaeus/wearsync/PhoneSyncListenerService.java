package de.rhaeus.wearsync;

import android.util.Log;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.WearableListenerService;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;

public class PhoneSyncListenerService extends WearableListenerService {
    private static final String TAG = "WearSync_PhoneListener";
    public static boolean isInternalUpdate = false; // 原生反向锁

    @Override
    public void onMessageReceived(MessageEvent messageEvent) {
        if (!"/wear-universal-sync".equals(messageEvent.getPath())) {
            super.onMessageReceived(messageEvent);
            return;
        }

        try {
            String jsonStr = new String(messageEvent.getData(), StandardCharsets.UTF_8);
            JSONObject json = new JSONObject(jsonStr);
            String type = json.optString("type");
            String action = json.optString("action");

            Log.d(TAG, "📥 手机底层收网到信令 -> 类型: " + type + ", 动作: " + action);

            // 1. DND 模块反向同步
            if ("dnd".equals(type)) {
                int state = json.optInt("dnd_state", -1);
                if (state != -1) {
                    isInternalUpdate = true; // 激活内部更新锁，防止无限回环循环同步
                    PhoneDndManager.handleIncomingAction(this, state);
                    
                    // 缓冲 1 秒后释放锁
                    new Thread(() -> {
                        try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
                        isInternalUpdate = false;
                    }).start();
                }
            } 
            // 2. 闹钟模块：代点匹配关键字
            else if ("alarm".equals(type)) {
                if ("ACTION_WATCH_ALARM_COMMAND".equalsIgnoreCase(action) || json.has("command_type")) {
                    String commandType = json.optString("command_type", "DISMISS");
                    PhoneAlarmManager.handleWatchCommand(this, commandType);
                } else if ("FORCE_STOP_PHONE_ALARM".equalsIgnoreCase(action)) {
                    PhoneAlarmManager.handleWatchCommand(this, "DISMISS");
                }
            } 
            // 3. 相机模块
            else if ("camera".equals(type)) {
                if ("START_CAMERA_UI".equalsIgnoreCase(action)) {
                    android.content.Intent cIntent = new android.content.Intent();
                    cIntent.setClassName(getPackageName(), "de.rhaeus.wearsync.PhoneSyncCameraActivity");
                    cIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK | android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP);
                    startActivity(cIntent);
                } else if ("STOP_CAMERA_STREAM".equalsIgnoreCase(action)) {
                    android.content.Intent stopIntent = new android.content.Intent("de.rhaeus.wearsync.ACTION_STOP_CAMERA_STREAM");
                    sendBroadcast(stopIntent);
                }
            }

        } catch (Exception e) {
            Log.e(TAG, "通道解析多端协同信令失败", e);
        }
    }
}
