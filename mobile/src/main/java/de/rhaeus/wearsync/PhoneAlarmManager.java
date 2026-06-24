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
                    PhoneLog.i(TAG, "🚀 [物理模拟成功] 正在跨进程向系统时钟注入【停止/关闭】按键信号！");
                    PhoneSyncNotificationService.cachedDismissIntent.send();
                } else {
                    PhoneLog.w(TAG, "⚠️ [物理模拟失败] 手机缓存的 cachedDismissIntent 为空！可能通知已被提前销毁");
                }
            } else if ("SNOOZE".equalsIgnoreCase(commandType)) {
                PhoneLog.d(TAG, "🔍 [闹钟逆向控制] 正在验证手机端 [延后] PendingIntent 缓存状态...");
                if (PhoneSyncNotificationService.cachedSnoozeIntent != null) {
                    PhoneLog.i(TAG, "🚀 [物理模拟成功] 正在跨进程向系统时钟注入【延后/稍后】按键信号！");
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
     * 🚀 内部发送核心
     */
    private static void sendAlarmSignalToWatch(Context context, String actionStr, String label, String time) {
        PhoneLog.d(TAG, "📦 [闹钟线头发射] 启动异步发信线流 ➔ 动作: " + actionStr);
        new Thread(() -> {
            try {
                JSONObject json = new JSONObject();
                json.put("sender", "phone");
                json.put("type", "alarm");
                json.put("action", actionStr);
                json.put("timestamp", System.currentTimeMillis());

                if ("START_ALARM_UI".equalsIgnoreCase(actionStr)) {
                    json.put("alarm_label", (label == null || label.isEmpty()) ? "鬧鐘響鈴中" : label);
                    json.put("alarm_time", (time == null) ? "" : time);
                }

                byte[] data = json.toString().getBytes(StandardCharsets.UTF_8);
                PhoneLog.d(TAG, "📦 [闹钟封包完毕] 负载JSON: " + json.toString());

                // 🎯 跨类别秒读共享的活跃手表 ID
                String targetNodeId = WearSyncState.getNodeId(context);

                if (targetNodeId != null && !targetNodeId.isEmpty()) {
                    PhoneLog.d(TAG, "⚡ [闹钟发信] 命中全局车牌号缓存: " + targetNodeId + "，直接推至通道...");
                    Tasks.await(Wearable.getMessageClient(context).sendMessage(targetNodeId, "/wear-universal-sync", data));
                    PhoneLog.d(TAG, "🚀 [闹钟发信成功] 精准流控状态 [" + actionStr + "] 已送达。");
                } else {
                    PhoneLog.w(TAG, "⚠️ [闹钟发信降级] 缓存中无可用节点，触发在线扫描...");
                    List<Node> nodes = Tasks.await(Wearable.getNodeClient(context).getConnectedNodes());

                    if (nodes != null && !nodes.isEmpty()) {
                        PhoneLog.d(TAG, "🔍 [闹钟发信降级] 在线发现 " + nodes.size() + " 个手表设备");
                        for (Node node : nodes) {
                            PhoneLog.d(TAG, "  └─ 正在激活并写入节点缓存: " + node.getId());
                            WearSyncState.setNodeId(context, node.getId());
                            Tasks.await(Wearable.getMessageClient(context).sendMessage(node.getId(), "/wear-universal-sync", data));
                        }
                        PhoneLog.d(TAG, "🚀 [闹钟发信成功] 降级广播流 [" + actionStr + "] 发射完成。");
                    } else {
                        PhoneLog.w(TAG, "❌ [闹钟发信断联] 传输失败：没有发现任何可通信的手表，放弃本次发射。");
                    }
                }
            } catch (Exception e) {
                PhoneLog.e(TAG, "🔴 [闹钟线头异常] 向手表传递或封包时发生线程内部崩溃: " + e.getMessage(), e);
            }
        }).start();
    }
}
