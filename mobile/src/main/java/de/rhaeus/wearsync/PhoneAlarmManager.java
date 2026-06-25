package de.rhaeus.wearsync;

import android.content.Context;
import android.util.Log;
import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class PhoneAlarmManager {
    private static final String TAG = "WearSync_PhoneAlarm";

    /**
     * 当手机端系统闹钟响起时调用（由通知拦截哨兵服务触发）
     */
    public static void notifyWatchAlarmRinging(Context context, String label, String time) {
        PhoneLog.d(TAG, "🔔 [闹钟触发源] 接收到哨兵指令：手机闹钟正在狂轰乱炸 ➔ 标签: [" + label + "], 时间: [" + time + "]");
        sendAlarmSignalToWatch(context, "START_ALARM_UI", label, time);
    }

    /**
     * 当用户主动在手机端关闭/延后闹钟，导致手机通知消失时，远端销毁手表闹钟 UI
     */
    public static void notifyWatchAlarmDismissed(Context context) {
        PhoneLog.d(TAG, "⏰ [闹钟撤销源] 接收到哨兵指令：手机端闹钟通知已消失（用户代点或滑动销毁），正在命令手表立刻停震销毁...");
        sendAlarmSignalToWatch(context, "FORCE_STOP_WEAR_ALARM", null, null);
    }

    /**
     * 接收并执行来自手表的代点请求
     */
    public static void handleWatchCommand(Context context, String commandType) {
        PhoneLog.d(TAG, "⚡ [闹钟逆向控制] 收到来自手表的物理代点口令: [" + commandType + "]");
        try {
            if ("DISMISS".equalsIgnoreCase(commandType)) {
                PhoneLog.d(TAG, "🔍 [闹钟逆向控制] 正在验证手机端 [停止] PendingIntent 缓存状态...");
                if (PhoneSyncNotificationService.cachedDismissIntent != null) {
                    PhoneLog.d(TAG, "🚀 [物理模拟成功] 正在跨进程向系统时钟注入【停止/关闭】按键信号！");
                    PhoneSyncNotificationService.cachedDismissIntent.send();
                } else {
                    PhoneLog.w(TAG, "⚠️ [物理模拟失败] 手机缓存的 cachedDismissIntent 为空！可能通知已被提前销毁");
                }
            } else if ("SNOOZE".equalsIgnoreCase(commandType)) {
                PhoneLog.d(TAG, "🔍 [闹钟逆向控制] 正在验证手机端 [延后] PendingIntent 缓存状态...");
                if (PhoneSyncNotificationService.cachedSnoozeIntent != null) {
                    PhoneLog.d(TAG, "🚀 [物理模拟成功] 正在跨进程向系统时钟注入【延后/稍后】按键信号！");
                    PhoneSyncNotificationService.cachedSnoozeIntent.send();
                } else {
                    PhoneLog.w(TAG, "⚠️ [物理模拟失败] 手机缓存的 cachedSnoozeIntent 为空！");
                }
            } else {
                PhoneLog.w(TAG, "⚠️ [闹钟逆向控制] 收到无法识别的未知手表口令: " + commandType);
            }
        } catch (Exception e) {
            PhoneLog.e(TAG, "🔴 [逆向控制崩溃] 执行通知栏模拟代点按键时发生致命错误: " + e.getMessage(), e);
        }
    }

    /**
     * 🛰️ 正向发射：严格对齐手表端真实接收协议的闹钟发射流
     */
    private static void sendAlarmSignalToWatch(Context context, String actionStr, String label, String time) {
        new Thread(() -> {
            try {
                JSONObject json = new JSONObject();
                json.put("sender", "phone");
                json.put("type", "alarm");
                
                // 🔥 🔥 🔥 冲突 1 修复：将手機的 START_ALARM_UI 对齐转换为手表的 START_WEAR_ALARM
                String finalizedAction = actionStr;
                if ("START_ALARM_UI".equalsIgnoreCase(actionStr)) {
                    finalizedAction = "START_WEAR_ALARM";
                }
                json.put("action", finalizedAction);
                
                // 🔥 🔥 🔥 冲突 2 修复：严格对齐手表接收端的三个 key ("time", "label", "day_tips")
                json.put("label", (label == null || label.isEmpty()) ? "闹钟" : label);
                json.put("time", (time == null) ? "00:00" : time);
                json.put("day_tips", ""); // 如果手机端有周几的提示可以填入，暂时填空确保不崩溃
                json.put("timestamp", System.currentTimeMillis());

                byte[] data = json.toString().getBytes(StandardCharsets.UTF_8);

                // 🔥 🔥 🔥 融合想法：优先从 WearSyncState 缓存中拿 NodeId，速度极快
                String targetNodeId = WearSyncState.getNodeId(context);

                if (targetNodeId != null && !targetNodeId.isEmpty()) {
                    PhoneLog.d(TAG, "⚡ [闹钟发信] 命中 WearSyncState 缓存: " + targetNodeId + "，正在以对齐新协议秒发射 [" + finalizedAction + "]...");
                    Tasks.await(Wearable.getMessageClient(context).sendMessage(targetNodeId, "/wear-universal-sync", data));
                    PhoneLog.d(TAG, "🚀 [闹钟发信成功] 闹钟信号已安全投递到缓存通道。");
                } else {
                    // 降级方案：物理扫描并踢醒手表蓝牙
                    PhoneLog.w(TAG, "⚠️ [闹钟发信降级] 缓存中无可用节点，触发在线物理扫描...");
                    java.util.List<com.google.android.gms.wearable.Node> nodes = 
                            Tasks.await(Wearable.getNodeClient(context).getConnectedNodes());

                    if (nodes != null && !nodes.isEmpty()) {
                        for (com.google.android.gms.wearable.Node node : nodes) {
                            PhoneLog.d(TAG, "  └─ 🚀 发现复活节点: " + node.getId() + "，刷新持久化缓存并灌入闹钟动作...");
                            WearSyncState.setNodeId(context, node.getId());
                            Tasks.await(Wearable.getMessageClient(context).sendMessage(node.getId(), "/wear-universal-sync", data));
                        }
                        PhoneLog.d(TAG, "🚀 [闹钟发信成功] 降级广播流发射完成。");
                    } else {
                        PhoneLog.w(TAG, "❌ [闹钟发信断联] 传输失败：没有发现任何可通信的手表。");
                    }
                }
            } catch (Exception e) {
                PhoneLog.e(TAG, "🔴 [闹钟正向发信失败] 协议校准打包异常: " + e.getMessage(), e);
            }
        }).start();
    }

}
