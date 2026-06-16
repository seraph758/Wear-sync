package de.rhaeus.wearsync;

import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

/**
 * 📲 手机端通知与勿扰模式监听服务（哨兵服务）
 * 核心升级：
 * 1. 完美保留勿扰防死循环拦截机制。
 * 2. 补齐闹钟监听：通过 Ongoing 与不可清除标志，完美过滤预告通知，精准锁定真实响铃事件。
 * 3. 去服务化解耦：直接直调本地业务 Manager 静态方法，全面绕过 Android 后台 Service 拦截。
 */
public class PhoneSyncNotificationService extends NotificationListenerService {
    private static final String TAG = "WearSync_PhoneNotification";

    private static PhoneSyncNotificationService instance;

    public static PhoneSyncNotificationService getInstance() {
        return instance;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this; // 绑定全局单例
    }

    @Override
    public void onDestroy() {
        if (instance == this) {
            instance = null;
        }
        super.onDestroy();
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        super.onNotificationPosted(sbn);
        if (sbn == null) return;

        // ⏰ 1. 验证闹钟同步总开关
        SharedPreferences prefs = getSharedPreferences("wear_sync_prefs", Context.MODE_PRIVATE);
        boolean isAlarmSyncOn = prefs.getBoolean("key_alarm_sync_master", true);
        if (!isAlarmSyncOn) return;

        // ⏰ 2. 匹配自定义闹钟包名
        String targetPkg = prefs.getString("key_alarm_package_name", "com.google.android.deskclock");
        if (targetPkg.equalsIgnoreCase(sbn.getPackageName())) {

            // 🎯 【精准过滤预告通知算法】：
            // 真实的闹钟响铃通知必然满足两个硬条件：一是正在运行中（isOngoing），二是绝对无法被清除（!isClearable）
            if (sbn.isOngoing() && !sbn.isClearable()) {
                Log.d(TAG, "⏰ [哨兵拦截] 检测到目标闹钟包名正在进入真实响铃状态: " + sbn.getPackageName());

                // 🌟 干净平移：直接使用新重构的管理类静态方法发送响铃信令
                PhoneAlarmManager.notifyWatchAlarmRinging(this);

            } else {
                Log.d(TAG, "⏳ [哨兵拦截] 目标闹钟产生通知，但判定为「预告通知」或「非响铃状态」，已自动安全过滤。");
            }
        }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        super.onNotificationRemoved(sbn);
        if (sbn == null) return;

        SharedPreferences prefs = getSharedPreferences("wear_sync_prefs", Context.MODE_PRIVATE);
        String targetPkg = prefs.getString("key_alarm_package_name", "com.google.android.deskclock");

        // ⏰ 3. 双端操控闭环：如果用户在手机端点击了延后/关闭，通知消失，主动去灭掉手表的 UI 弹窗
        if (targetPkg.equalsIgnoreCase(sbn.getPackageName())) {
            Log.d(TAG, "⏰ [哨兵拦截] 手机端目标闹钟通知已消失（由于手机端点灭或代点成功），通知手表彻底停震并销毁...");
            
            // 🌟 干净平移：直接通知手表清除闹钟状态
            PhoneAlarmManager.notifyWatchAlarmDismissed(this);
        }
    }

    @Override
    public void onInterruptionFilterChanged(int interruptionFilter) {
        super.onInterruptionFilterChanged(interruptionFilter);
        Log.d(TAG, "📲 手机系统自身勿扰触发变更: " + interruptionFilter);

        if (PhoneSyncListenerService.isInternalUpdate) {
            Log.d(TAG, "🛑 判定为手表引起的内部勿扰修改回调，阻止反向同步回传。");
            return;
        }

        // 🌟 干净平移修正：直接将系统回调入参的 interruptionFilter 发送给手表
        PhoneDndManager.syncDndToWear(this, interruptionFilter);
    }
}
