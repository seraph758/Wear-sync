package de.rhaeus.wearsync;

import android.content.Context;
import android.content.Intent;
import android.util.Log;
import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class PhoneAlarmManager {
    private static final String TAG = "WearSync_PhoneAlarm";

    // 手机本地响铃，通知手表拉起全屏 UI
    public static void notifyWatchAlarmRinging(Context context) {
        sendAlarmSignalToWatch(context, "START_ALARM_UI");
    }

    // 手机本地闹钟消失，通知手表彻底停震
    public static void notifyWatchAlarmDismissed(Context context) {
        sendAlarmSignalToWatch(context, "FORCE_STOP_WEAR_ALARM");
    }

    // 核心接收逻辑：收到来自手表的代点动作（匹配关键字 DISMISS 或 SNOOZE）
    public static void handleWatchCommand(Context context, String commandType) {
        Log.d(TAG, "⚙️ 收到来自手表的代点请求，匹配关键字: " + commandType);
        
        if ("DISMISS".equalsIgnoreCase(commandType)) {
            // 发送系统解散广播
            Intent dismissIntent = new Intent("com.android.deskclock.ALARM_DISMISS");
            context.sendBroadcast(dismissIntent);
            
            // 兼容部分特定厂商的清除广播
            Intent alternativeIntent = new Intent("android.intent.action.ALARM_DISMISS");
            context.sendBroadcast(alternativeIntent);
            Log.d(TAG, "⏰ [代点成功] 已向手机系统发送 [DISMISS] 广播");
            
        } else if ("SNOOZE".equalsIgnoreCase(commandType)) {
            // 发送系统延后广播
            Intent snoozeIntent = new Intent("com.android.deskclock.ALARM_SNOOZE");
            context.sendBroadcast(snoozeIntent);
            Log.d(TAG, "⏰ [代点成功] 已向手机系统发送 [SNOOZE] 广播");
        }
    }

    private static void sendAlarmSignalToWatch(Context context, String actionStr) {
        new Thread(() -> {
            try {
                JSONObject json = new JSONObject();
                json.put("sender", "phone");
                json.put("type", "alarm");
                json.put("action", actionStr);
                json.put("timestamp", System.currentTimeMillis());

                byte[] data = json.toString().getBytes(StandardCharsets.UTF_8);
                List<Node> nodes = Tasks.await(Wearable.getNodeClient(context).getConnectedNodes());
                for (Node node : nodes) {
                    Wearable.getMessageClient(context).sendMessage(node.getId(), "/wear-universal-sync", data);
                }
                Log.d(TAG, "🚀 闹钟状态 [" + actionStr + "] 已送往手表");
            } catch (Exception e) {
                Log.e(TAG, "发送闹钟信令到手表失败", e);
            }
        }).start();
    }
}
