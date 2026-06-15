package de.rhaeus.wearsync;

import android.content.Intent;
import android.util.Log;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.WearableListenerService;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;

/**
 * 核心升级：彻底洗白本服务。它不再包含任何具体的勿扰、闹钟控制或模拟点击逻辑，
 * 专门扮演“中央控制中心（Router）”的角色。收到手表字节流信号后，解析类型，
 * 立刻通过有状态的 Intent 干净地分发起飞，分发给专门的独立业务 Java 服务。
 */
public class PhoneSyncListenerService extends WearableListenerService {
    private static final String TAG = "WearSync_PhoneListener";

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
                // 1. 勿扰模式模块：直接分发给独立的专用服务 PhoneDndService
                Intent dndIntent = new Intent(this, PhoneDndService.class);
                dndIntent.putExtra("dnd_profile_value", json.optInt("dnd_profile_value"));
                startService(dndIntent);

            } else if ("alarm".equals(type)) {
                // 2. 自定义闹钟模块：直接分发给独立的专用服务 PhoneAlarmService
                Intent alarmIntent = new Intent(this, PhoneAlarmService.class);
                alarmIntent.putExtra("action", json.optString("action"));
                startService(alarmIntent);

            } else if ("camera_action".equals(type)) {
                // 3. 相机流模块：智能判断手表的启动或正常退出指令
                String action = json.optString("action");
                if ("START_CAMERA_UI".equalsIgnoreCase(action)) {
                    Log.d(TAG, "📸 收到手表开启相机指令，穿透唤醒手机前台 Activity 获取豁免权...");
                    Intent mainIntent = new Intent(this, PhoneSyncMainActivity.class);
                    mainIntent.setAction("ACTION_START_CAMERA_FLOW"); // 附带私有穿透暗号
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