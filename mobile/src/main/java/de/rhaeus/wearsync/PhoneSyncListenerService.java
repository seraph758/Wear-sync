package de.rhaeus.wearsync;

import android.content.Intent;
import android.util.Log;
import androidx.annotation.NonNull;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.WearableListenerService;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;

/**
 * 🚀 中央信令路由器
 * 职责：作为交通枢纽，完美分发勿扰、闹钟和相机信令。
 */
public class PhoneSyncListenerService extends WearableListenerService {
    private static final String TAG = "WearSync_PhoneListener";
    private static final String UNIVERSAL_SYNC_PATH = "/wear-universal-sync";
    
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

            if ("phone".equalsIgnoreCase(sender)) return; 

            // ================= 🌙 1️⃣ 勿扰同步模块分发 =================
            if ("dnd".equalsIgnoreCase(type)) {
                int dndVal = json.optInt("dnd_profile_value", -1);
                Log.d(TAG, "📥 [信令分发] 收到手表反向勿扰信令，目标系统状态: " + dndVal);
                if (dndVal != -1) {
                    isInternalUpdate = true;
                    Intent dndIntent = new Intent(this, PhoneDndService.class);
                    dndIntent.putExtra("dnd_profile_value", dndVal); 
                    startService(dndIntent);
                }
                return;
            }

            // ================= ⏰ 2️⃣ 远端闹钟控制模块分发（完美复活） =================
            if ("alarm_action".equalsIgnoreCase(type)) {
                Log.d(TAG, "⏰ [信令分发] 收到手表闹钟反馈动作: " + action + " -> 立即转接至独立闹钟服务进行精准代点");
                
                // action 字段携带了来自手表的最高控制口令: "DISMISS" 或 "SNOOZE"
                Intent alarmIntent = new Intent(this, PhoneAlarmService.class);
                alarmIntent.setAction(PhoneAlarmService.ACTION_WATCH_ALARM_COMMAND);
                alarmIntent.putExtra(PhoneAlarmService.EXTRA_COMMAND_TYPE, action); 
                startService(alarmIntent);
                return;
            }

            // ================= 📸 3️⃣ 相机控制模块分发（留存框架） =================
            if ("camera_control".equalsIgnoreCase(type)) {
                Log.d(TAG, "📸 [信令分发] 收到手表相机控制指令: " + action);
                // 后续在此扩展相机的双端互相强制退出或心跳等逻辑
                return;
            }

        } catch (Exception e) {
            Log.e(TAG, "中央路由器解析手表消息失败", e);
        }
    }
}
