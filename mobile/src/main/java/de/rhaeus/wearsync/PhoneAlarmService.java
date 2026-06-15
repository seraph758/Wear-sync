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

/**
 * 独立解耦的自定义闹钟处理服务。
 * 完美解决手表端点击“关闭”和“延后”无效、对齐双端协议。
 * 动态加载用户自定义的停止/延后关键字，遍历一加/谷歌系统当前的响铃通知，精准触发挂起意图模拟点击。
 */
public class PhoneAlarmService extends Service {
    private static final String TAG = "WearSync_PhoneAlarm";

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        String action = intent.getStringExtra("action");
        Log.d(TAG, "🔔 独立闹钟服务启动，收到手表动作信令: " + action);

        if ("DISMISS_ALARM".equalsIgnoreCase(action) || "SNOOZE_ALARM".equalsIgnoreCase(action)) {
            executeAlarmAction(action);
        }

        stopSelf();
        return START_NOT_STICKY;
    }

    private void executeAlarmAction(String watchAction) {
        try {
            // 1. 读取本地偏好设置中的用户自定义内容
            SharedPreferences prefs = getSharedPreferences("dndsync_prefs", Context.MODE_PRIVATE);
            
            // 动态读取自定义闹钟包名，如果没有则采用谷歌时钟和一加时钟作为兜底默认值
            String customPackages = prefs.getString("custom_alarm_packages", "com.google.android.deskclock,com.oneplus.deskclock");
            
            // 动态读取用户自定义停止和延后的匹配关键字（默认简体中文：停止、延后）
            String dismissKeyword = prefs.getString("custom_dismiss_keyword", "停止");
            String snoozeKeyword = prefs.getString("custom_snooze_keyword", "延后");

            // 2. 获取当前 system 通知栏挂载的所有活跃通知
            PhoneSyncNotificationService notificationService = PhoneSyncNotificationService.getInstance();
            if (notificationService == null) {
                Log.w(TAG, "PhoneSyncNotificationService 尚未就绪，尝试发送系统按键广播兜底模拟中断。");
                sendBroadcast(new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS));
                return;
            }

            StatusBarNotification[] activeNotifications = notificationService.getActiveNotifications();
            if (activeNotifications == null) return;

            // 3. 寻找与当前自定义响铃包名相匹配的正在响铃的活跃通知
            for (StatusBarNotification sbn : activeNotifications) {
                String pkgName = sbn.getPackageName();
                
                // 判断此通知包名是否处于用户自定义的闹钟包名列表内
                if (customPackages.contains(pkgName)) {
                    Notification notification = sbn.getNotification();
                    if (notification.actions == null) continue;

                    // 4. 遍历该闹钟通知自带的所有 Action 动作按钮（如通知栏上的“清除”或“稍后提醒”）
                    for (Notification.Action action : notification.actions) {
                        String buttonText = action.title.toString();
                        
                        // 场景 A：手表点了关闭 -> 精准匹配用户设定的停止关键字（如包含“停止”、“关闭”、“清除”等）
                        if ("DISMISS_ALARM".equalsIgnoreCase(watchAction) && buttonText.contains(dismissKeyword)) {
                            Log.d(TAG, "🎯 成功匹配到手机通知栏停止按钮: [" + buttonText + "]，开始下发挂起意图模拟按下...");
                            action.actionIntent.send();
                            return;
                        }
                        
                        // 场景 B：手表点了延后 -> 精准匹配用户设定的延后关键字（如包含“延后”、“稍后提醒”、“贪睡”等）
                        else if ("SNOOZE_ALARM".equalsIgnoreCase(watchAction) && buttonText.contains(snoozeKeyword)) {
                            Log.d(TAG, "🎯 成功匹配到手机通知栏延后按钮: [" + buttonText + "]，开始下发挂起意图模拟延时...");
                            action.actionIntent.send();
                            return;
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "执行远程自定义关键字匹配关闹钟产生异常", e);
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }
}