package de.rhaeus.wearsync;

import android.content.Context;
import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class PhoneAlarmManager {
    private static final String TAG = "WearSync_PhoneAlarm";

    /**
     * 當手機端系統鬧鐘響起時調用（由通知攔截哨兵服務觸發）
     */
    public static void notifyWatchAlarmRinging(Context context, String time) {
        PhoneLog.d(TAG, "🔔 [鬧鐘觸發源] 接收到哨兵指令：手機鬧鐘正在狂轟亂炸 ➔ 標籤: [" + "], 時間: [" + time + "]");
        sendAlarmSignalToWatch(context, "START_WEAR_ALARM", time);
    }

    /**
     * 當用戶主動在手機端關閉/延後鬧鐘，導致手機通知消失時，遠端銷毀手錶鬧鐘 UI
     */
    public static void notifyWatchAlarmDismissed(Context context) {
        PhoneLog.d(TAG, "⏰ [鬧鐘撤銷源] 接收到哨兵指令：手機端鬧鐘通知已消失，正在命令手錶立刻停震銷毀...");
        sendAlarmSignalToWatch(context, "FORCE_STOP_WEAR_ALARM", null);
    }

    /**
     * 接收並執行來自手錶的代點請求（超強日誌覆蓋版）
     */
    public static void handleWatchCommand(Context context, String commandType) {
        PhoneLog.d(TAG, "⚡ handleWatchCommand -> executeAlarmAction");
    executeAlarmAction(context, commandType);
    }

    /**
     * 🛰️ 正向發射：嚴格對齊手錶端真實接收協議的鬧鐘發射流
     */
    private static void sendAlarmSignalToWatch(Context context, String actionStr, String time) {
        new Thread(() -> {
            try {
                JSONObject json = new JSONObject();
                json.put("sender", "phone");
                json.put("type", "alarm");
                json.put("action", actionStr);
                json.put("time", (time == null) ? "00:00" : time);
                Date now = new Date();

                SimpleDateFormat monthDayFormat =
                        new SimpleDateFormat("M月d日", Locale.getDefault());
                
                SimpleDateFormat weekFormat =
                        new SimpleDateFormat("EEE", Locale.CHINA);
                
                String week = weekFormat.format(now).replace("星期", "周");
                
                json.put("month_day", monthDayFormat.format(now));
                json.put("day_tips", week);
                json.put("timestamp", System.currentTimeMillis());

                byte[] data = json.toString().getBytes(StandardCharsets.UTF_8);
                String targetNodeId = WearSyncState.getNodeId(context);

                if (targetNodeId != null && !targetNodeId.isEmpty()) {
                    PhoneLog.d(TAG, "⚡ [鬧鐘正向發信] 命中緩存節點: " + targetNodeId + "，正在秒發射 [" + actionStr + "]...");
                    Tasks.await(Wearable.getMessageClient(context).sendMessage(targetNodeId, "/wear-universal-sync", data));
                    PhoneLog.d(TAG, "🚀 [鬧鐘正向發信成功] 指令 [" + actionStr + "] 已成功安全投遞。");
                } else {
                    PhoneLog.w(TAG, "⚠️ [鬧鐘正向發信降級] 緩存中無可用節點，觸發在線物理掃描...");
                    List<Node> nodes = Tasks.await(Wearable.getNodeClient(context).getConnectedNodes());

                    if (nodes != null && !nodes.isEmpty()) {
                        for (Node node : nodes) {
                            PhoneLog.d(TAG, "  └─ 🚀 發現復活節點: " + node.getId() + "，刷新緩存並灌入鬧鐘動作...");
                            WearSyncState.setNodeId(context, node.getId());
                            Tasks.await(Wearable.getMessageClient(context).sendMessage(node.getId(), "/wear-universal-sync", data));
                        }
                        PhoneLog.d(TAG, "🚀 [鬧鐘正向發信成功] 降級廣播流發射完成。");
                    } else {
                        PhoneLog.w(TAG, "❌ [鬧鐘正向發信斷聯] 傳輸失敗：沒有發現任何可通信的手錶。");
                    }
                }
            } catch (Exception e) {
                PhoneLog.e(TAG, "🔴 [鬧鐘正向發信失敗] 協議校準打包異常: " + e.getMessage(), e);
            }
        }).start();
    }
        public static void executeAlarmAction(Context context, String action) {
            PhoneLog.d(TAG, "🧭 [统一执行入口] action=" + action);
           if ("DISMISS".equalsIgnoreCase(action)) {
            
                boolean success =
                        PhoneSyncNotificationService.triggerLiveAlarmAction(
                                context,
                                true
                        );
            
                PhoneLog.d(TAG,
                        "DISMISS执行结果=" + success);
            
            }
            else if ("SNOOZE".equalsIgnoreCase(action)) {
            
                boolean success =
                        PhoneSyncNotificationService.triggerLiveAlarmAction(
                                context,
                                false
                        );
            
                PhoneLog.d(TAG,
                        "SNOOZE执行结果=" + success);
            
            }
            else {
            
                PhoneLog.w(TAG,
                        "未知闹钟动作：" + action);
            
            }
                
    }
}
