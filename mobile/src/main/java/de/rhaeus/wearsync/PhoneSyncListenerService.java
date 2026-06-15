package de.rhaeus.wearsync;

import android.content.Intent;
import android.util.Log;
import androidx.core.content.ContextCompat;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.WearableListenerService;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;

public class PhoneSyncListenerService extends WearableListenerService {
    private static final String TAG = "WearSync_PhoneListener";

    @Override
    public void onMessageReceived(MessageEvent messageEvent) {
        if (!"/wear-universal-sync".equals(messageEvent.getPath())) return;

        try {
            String jsonStr = new String(messageEvent.getData(), StandardCharsets.UTF_8);
            JSONObject json = new JSONObject(jsonStr);
            String type = json.optString("type");
            String action = json.optString("action");

            Log.d(TAG, "📥 收到手表信令 -> 类型: " + type + ", 动作: " + action);

            if ("camera_action".equals(type)) {
                if ("START_CAMERA_UI".equalsIgnoreCase(action)) {
                    Log.d(TAG, "🛡️ 绕过一加前台限制：直接后台静默启动 Camera 采集服务...");
                    
                    Intent serviceIntent = new Intent(this, PhoneSyncCameraService.class);
                    serviceIntent.setAction("START_CAMERA");
                    // 使用 ContextCompat 确保在各代 Android 系统上拥有前台服务豁免权
                    ContextCompat.startForegroundService(this, serviceIntent);
                    
                } else if ("STOP_CAMERA_UI".equalsIgnoreCase(action)) {
                    Log.d(TAG, "🛑 收到下线指令，强行关闭手机端相机服务...");
                    Intent serviceIntent = new Intent(this, PhoneSyncCameraService.class);
                    stopService(serviceIntent);
                }
            } else if ("alarm".equals(type)) {
                // 🎯 闹钟互控分发区
                Intent alarmIntent = new Intent("DE_RHAEUS_WEARSYNC_ALARM_TRIGGER");
                alarmIntent.putExtra("action", action);
                sendBroadcast(alarmIntent);
                Log.d(TAG, "🛎️ 闹钟广播已在手机端分发 -> " + action);
            }
            // ... 保持你原本的勿扰模式 (DND) 处理逻辑不变 ...

        } catch (Exception e) {
            Log.e(TAG, "解析手表信令发生灾难性异常", e);
        }
    }
}
