package de.rhaeus.wearsync;

import android.content.Intent;
import android.util.Log;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.WearableListenerService;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;

/**
 * 彻底洗白后的中央控制路由器
 * 完美修复：补回通知监听服务所需的 isInternalUpdate 静态标志位，彻底消灭编译错误。
 */
public class PhoneSyncListenerService extends WearableListenerService {
    private static final String TAG = "WearSync_PhoneListener";

    // 🎯 核心修复：保留这个静态标志位，供 PhoneSyncNotificationService 在通知变更时比对，防止自循环冲突
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
            String type = json.optString("type");
            Log.d(TAG, "📥 中央路由器收到手表信令，类型: " + type + ", 完整内容: " + jsonStr);

            if ("dnd".equals(type)) {
                Intent dndIntent = new Intent(this, PhoneDndService.class);
                dndIntent.putExtra("dnd_profile_value", json.optInt("dnd_profile_value"));
                startService(dndIntent);

            } else if ("alarm".equals(type)) {
                Intent alarmIntent = new Intent(this, PhoneAlarmService.class);
                alarmIntent.putExtra("action", json.optString("action"));
                startService(alarmIntent);

            } else if ("camera_action".equals(type)) {
                String action = json.optString("action");
                if ("START_CAMERA_UI".equalsIgnoreCase(action)) {
                    Log.d(TAG, "📸 收到手表开启相机指令，穿透唤醒手机前台 Activity 获取豁免权...");
                    Intent mainIntent = new Intent(this, PhoneSyncMainActivity.class);
                    mainIntent.setAction("ACTION_START_CAMERA_FLOW");
                    mainIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    startActivity(mainIntent);
                } else if ("STOP_CAMERA_UI".equalsIgnoreCase(action)) {
                    Log.d(TAG, "🛑 收到手表正常退出相机指令，通知手机端后台采集服务优雅退出...");
                    Intent stopCamera = new Intent(this, PhoneSyncCameraService.class);
                    stopCamera.setAction("STOP_CAMERA");
                    startService(stopCamera);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "中央路由器解析分发信令异常", e);
        }
    }
}
