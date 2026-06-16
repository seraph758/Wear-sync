package de.rhaeus.wearsync;

import android.app.Notification;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.IBinder;
import android.service.notification.StatusBarNotification;
import android.util.Log;
import androidx.annotation.Nullable;

import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;

import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * ⏰ 独立解耦的远端闹钟控制服务（手机端核心逻辑）
 * 核心职责：
 * 1. 响应手机闹钟响铃，向手表投递唤醒并全屏弹窗的指令（含重复响铃的二次防护拉起）。
 * 2. 接收手表回传的 DISMISS / SNOOZE 动作，通过单例动态拉取列表实现高精准代点。
 * 3. 响应手机端闹钟关闭，同步通知手表彻底销毁界面，保持全周期双端生命周期联锁。
 */
public class PhoneAlarmService extends Service {
    private static final String TAG = "WearSync_PhoneAlarm";
    private static final String UNIVERSAL_SYNC_PATH = "/wear-universal-sync";

    // 🌟 规范定义的显式指令 Action
    public static final String ACTION_PHONE_ALARM_RINGING = "de.rhaeus.wearsync.ACTION_ALARM_RINGING";
    public static final String ACTION_PHONE_ALARM_DISMISSED = "de.rhaeus.wearsync.ACTION_ALARM_DISMISSED";
    public static final String ACTION_WATCH_ALARM_COMMAND = "de.rhaeus.wearsync.ACTION_WATCH_COMMAND";

    public static final String EXTRA_COMMAND_TYPE = "command_type"; // 取值: "DISMISS" 或 "SNOOZE"

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || intent.getAction() == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        String action = intent.getAction();
        Log.d(TAG, "⚙️ 独立闹钟服务收到指令 Action: " + action);

        new Thread(() -> {
            try {
                if (ACTION_PHONE_ALARM_RINGING.equals(action)) {
                    // 手机闹钟响铃 -> 命令手表拉起全屏 UI 并密集震动
                    sendAlarmSignalToWatch("START_ALARM_UI");
                } else if (ACTION_PHONE_ALARM_DISMISSED.equals(action)) {
                    // 手机闹钟主动关闭 -> 强行让手表也停止震动并销毁 UI
                    sendAlarmSignalToWatch("FORCE_STOP_WEAR_ALARM");
                } else if (ACTION_WATCH_ALARM_COMMAND.equals(action)) {
                    // 收到来自手表的代点请求
                    String command = intent.getStringExtra(EXTRA_COMMAND_TYPE);
                    if (command != null) {
                        executeWatchCommandOnPhone(command);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "处理闹钟核心逻辑发生致命异常", e);
            } finally {
                stopSelf(); // 任务执行完立即清空自杀，保持纯净
            }
        }).start();

        return START_NOT_STICKY;
    }

    /**
     * 🛰️ 跨端发射器：向手表推送闹钟状态交互信令
     */
    private void sendAlarmSignalToWatch(String alarmAction) {
        try {
            JSONObject json = new JSONObject();
            json.put("sender", "phone");
            json.put("type", "alarm");
            json.put("action", alarmAction);
            json.put("timestamp", System.currentTimeMillis());

            byte[] payload = json.toString().getBytes(StandardCharsets.UTF_8);
            List<Node> nodes = Tasks.await(Wearable.getNodeClient(this).getConnectedNodes());
            if (nodes != null && !nodes.isEmpty()) {
                for (Node node : nodes) {
                    Tasks.await(Wearable.getMessageClient(this).sendMessage(node.getId(), UNIVERSAL_SYNC_PATH, payload));
                    Log.d(TAG, "🚀 [闹钟同步] 成功向手表推送动作: " + alarmAction + " -> 目标节点: " + node.getDisplayName());
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "向手表发送闹钟状态信令失败", e);
        }
    }

    /**
     * 🎯 核心高精准代点：通过单例动态拉取通知栏列表，模拟点击对应的 Stop 或 Snooze 动作按钮
     */
    private void executeWatchCommandOnPhone(String command) {
        // 1. 安全提取通知栏哨兵的全局静态单例
        PhoneSyncNotificationService notificationService = PhoneSyncNotificationService.getInstance();
        if (notificationService == null) {
            Log.e(TAG, "❌ 无法执行代点：PhoneSyncNotificationService 单例为空，可能未获取通知栏访问权限！");
            return;
        }

        // 2. 动态加载 UI 层的自定义配置项
        SharedPreferences prefs = getSharedPreferences("wear_sync_prefs", Context.MODE_PRIVATE);
        String targetPkg = prefs.getString("key_alarm_package_name", "com.google.android.deskclock");
        
        // 智能匹配对应的停止/延后关键字
        String keyword = "DISMISS".equalsIgnoreCase(command) ? 
                prefs.getString("key_alarm_keyword_stop", "停止") : 
                prefs.getString("key_alarm_keyword_snooze", "延后");

        Log.d(TAG, "🔍 [精确检索] 开始在通知栏扫描包名 [" + targetPkg + "]，寻找包含关键字 [" + keyword + "] 的动作按钮...");

        try {
            // 3. 动态拉取当前系统通知栏列表
            StatusBarNotification[] activeNotifications = notificationService.getActiveNotifications();
            if (activeNotifications == null || activeNotifications.length == 0) {
                Log.w(TAG, "⚠️ 手机当前通知栏中没有活动中的通知。");
                return;
            }

            boolean isClickedSuccess = false;
            for (StatusBarNotification sbn : activeNotifications) {
                if (targetPkg.equalsIgnoreCase(sbn.getPackageName())) {
                    Notification notification = sbn.getNotification();
                    if (notification != null && notification.actions != null) {
                        // 4. 遍历通知中附带的物理动作按钮（如：Action 1 叫 "延后"、Action 2 叫 "停止"）
                        for (Notification.Action act : notification.actions) {
                            String actionTitle = act.title != null ? act.title.toString() : "";
                            
                            // 5. 智能文本模糊过滤匹配
                            if (actionTitle.toLowerCase().contains(keyword.toLowerCase())) {
                                Log.d(TAG, "🎯 [精准代点成功] 匹配到目标按钮: " + actionTitle + "，正在跨进程触发模拟点击！");
                                act.actionIntent.send(); // 👈 模拟代点核心动作，使手机本地通知消失并执行闹钟逻辑
                                isClickedSuccess = true;
                                break;
                            }
                        }
                    }
                }
                if (isClickedSuccess) break;
            }

            if (!isClickedSuccess) {
                Log.w(TAG, "❌ 未能在目标闹钟通知包名下找到包含关键字 [" + keyword + "] 的操作按钮。");
            }

        } catch (Exception e) {
            Log.e(TAG, "模拟代点通知栏按钮产生异常", e);
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }
}
