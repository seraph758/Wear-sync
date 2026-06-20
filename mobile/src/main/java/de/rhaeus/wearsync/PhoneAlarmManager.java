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
import android.content.SharedPreferences; // 🎯 补上这一行

public class PhoneAlarmManager {
    private static final String TAG = "WearSync_PhoneAlarm";

    /**
     * 🎯 升級：當手機端系統鬧鐘響起時調用（由通知攔截哨兵服務觸發）
     * @param context 上下文
     * @param label   鬧鐘標題（例如 "起床上班"）
     * @param time    鬧鐘響鈴時間（例如 "07:30"）
     */
    public static void notifyWatchAlarmRinging(Context context, String label, String time) {
        Log.d(TAG, "🔔 偵測到手機鬧鐘轟鳴，準備注入全屏提示資料 ➔ 標題: " + label + ", 時間: " + time);
        sendAlarmSignalToWatch(context, "START_ALARM_UI", label, time);
    }

    /**
     * 🤝 當用戶主動在手機端關閉/延後鬧鐘，導致手機通知消失時，由哨兵調用此處。
     * 職責：發射強退信號，遠程同步銷毀手錶端的全屏 AlarmActivity。
     */
    public static void notifyWatchAlarmDismissed(Context context) {
        Log.d(TAG, "⏰ [雙端控制閉環] 手機端鬧鐘通知已消失，正在命令手錶端強行銷毀 UI 並停震...");
        sendAlarmSignalToWatch(context, "FORCE_STOP_WEAR_ALARM", null, null);
    }

    /**
     * 🎯 協議拉齊：高精準匹配來自手錶的關鍵字 DISMISS 和 SNOOZE 代點請求
     */
    public static void handleWatchCommand(Context context, String commandType) {
    Log.d(TAG, "⚡ [闹钟核心执行] 收到手表反向口令: " + commandType); [cite: 6]
    try {
        if ("DISMISS".equalsIgnoreCase(commandType) && PhoneSyncNotificationService.cachedDismissIntent != null) {
            Log.i(TAG, "🚀 物理模拟点击【停止】按钮");
            PhoneSyncNotificationService.cachedDismissIntent.send();
        } else if ("SNOOZE".equalsIgnoreCase(commandType) && PhoneSyncNotificationService.cachedSnoozeIntent != null) {
            Log.i(TAG, "🚀 物理模拟点击【延后】按钮");
            PhoneSyncNotificationService.cachedSnoozeIntent.send();
        }
    } catch (Exception e) {
        Log.e(TAG, "🔴 执行通知栏代点失败", e);
    }
}

    /**
     * 🚀 內部發送核心：封裝全屏提示資料 JSON 並投遞給手錶
     */
    private static void sendAlarmSignalToWatch(Context context, String actionStr, String label, String time) {
        new Thread(() -> {
            try {
                JSONObject json = new JSONObject();
                json.put("sender", "phone");
                json.put("type", "alarm");
                json.put("action", actionStr);
                json.put("timestamp", System.currentTimeMillis());

                // 🎯 核心：只有在拉起 UI 時，才塞入鬧鐘資料文字
                if ("START_ALARM_UI".equalsIgnoreCase(actionStr)) {
                    json.put("alarm_label", (label == null || label.isEmpty()) ? "鬧鐘響鈴中" : label);
                    json.put("alarm_time", (time == null) ? "" : time);
                }

                byte[] data = json.toString().getBytes(StandardCharsets.UTF_8);
                List<Node> nodes = Tasks.await(Wearable.getNodeClient(context).getConnectedNodes());
                
                if (nodes != null && !nodes.isEmpty()) {
                    for (Node node : nodes) {
                        Tasks.await(Wearable.getMessageClient(context).sendMessage(node.getId(), "/wear-universal-sync", data));
                    }
                    Log.d(TAG, "🚀 鬧鐘狀態流 [" + actionStr + "] 正向推送到手錶成功。資料包: " + json.toString());
                } else {
                    Log.w(TAG, "⚠️ 傳輸失敗：當前沒有任何已連接的手錶節點！");
                }
            } catch (Exception e) {
                Log.e(TAG, "🔴 向手錶發送鬧鐘狀態或封裝資料失敗", e);
            }
        }).start();
    }
}
