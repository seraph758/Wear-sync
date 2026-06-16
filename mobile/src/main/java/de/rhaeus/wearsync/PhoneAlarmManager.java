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

    // 当手机端系统闹钟响起时调用（由 Notification 拦截器或 Service 触发）
    public static void notifyWatchAlarmRinging(Context context) {
        sendAlarmSignalToWatch(context, "START_ALARM_UI");
    }

    // 当手机端闹钟被灭掉时调用
    public static void notifyWatchAlarmDismissed(Context context) {
        sendAlarmSignalToWatch(context, "FORCE_STOP_WEAR_ALARM");
    }

    // 🎯 协议拉齐：高精准匹配来自手表的关键字 DISMISS 和 SNOOZE 代点请求
    public static void handleWatchCommand(Context context, String commandType) {
        Log.d(TAG, "⚙️ [协议命中] 正在将手表的虚拟代点请求映射到系统底层: " + commandType);
        
        if ("DISMISS".equalsIgnoreCase(commandType)) {
            // 完美击中系统时钟解散协议
            context.sendBroadcast(new Intent("com.android.deskclock.ALARM_DISMISS"));
            context.sendBroadcast(new Intent("android.intent.action.ALARM_DISMISS"));
            Log.d(TAG, "⏰ 手机系统 [DISMISS] 广播代点弹射完毕");
            
        } else if ("SNOOZE".equalsIgnoreCase(commandType)) {
            // 完美击中系统时钟小睡延后协议
            context.sendBroadcast(new Intent("com.android.deskclock.ALARM_SNOOZE"));
            Log.d(TAG, "⏰ 手机系统 [SNOOZE] 广播代点弹射完毕");
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
                Log.d(TAG, "🚀 闹钟状态流 [" + actionStr + "] 正向推送到手表成功");
            } catch (Exception e) {
                Log.e(TAG, "向手表发送闹钟状态失败", e);
            }
        }).start();
    }
}
