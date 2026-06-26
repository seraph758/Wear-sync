package de.rhaeus.wearsync;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 📲 手机端通知与勿扰模式监听服务（哨兵服务）
 * 优雅日誌控制版 —— 使用自定義 PhoneLog 進行排查
 */
public class PhoneSyncNotificationService extends NotificationListenerService {
    private static final String TAG = "WearSync_PhoneNotification";
    private static PhoneSyncNotificationService instance;

    public static android.app.PendingIntent cachedDismissIntent = null;
    public static android.app.PendingIntent cachedSnoozeIntent = null;

    private final Handler alarmWatchdogHandler = new Handler(Looper.getMainLooper());
    private Runnable alarmWatchdogRunnable = null;
    private boolean isAlarmCurrentlyRinging = false;
    private String cachedAlarmTitle = "";
    private String cachedAlarmText = "";

    public static PhoneSyncNotificationService getInstance() {
        return instance;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        PhoneLog.d(TAG, "🚀 [生命周期] PhoneSyncNotificationService 哨兵服务 -> onCreate() 已成功启动！");
        
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                PhoneLog.d(TAG, "🔍 [启动探针] 当前手机系统底层的 InterruptionFilter 状态值为: " + nm.getCurrentInterruptionFilter());
            }
        } catch (Exception e) {
            PhoneLog.e(TAG, "❌ [启动探针] 尝试获取系统初始勿扰状态失败: " + e.getMessage());
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopAlarmWatchdog();
        instance = null;
        PhoneLog.d(TAG, "🛑 [生命周期] PhoneSyncNotificationService 哨兵服务 -> onDestroy() 已销毁");
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null) {
            PhoneLog.w(TAG, "⚠️ [闹钟监听] 收到空通知事件 (StatusBarNotification is null)");
            return;
        }
        String packageName = sbn.getPackageName();

        SharedPreferences prefs =
        getSharedPreferences("dndsync_prefs",Context.MODE_PRIVATE);

String selectedPkg =
        prefs.getString(
                "selected_alarm_package",
                "com.google.android.deskclock"
        );


if(selectedPkg.equals(packageName)){

            PhoneLog.d(TAG, "⏰ [闹钟事件] 检测到目标包名闹钟弹出: " + packageName);

            Notification notification = sbn.getNotification();
            if (notification == null) {
                PhoneLog.w(TAG, "⚠️ [闹钟事件] Notification 实体为空，拦截处理");
                return;
            }

            boolean isInsistent = (notification.flags & Notification.FLAG_INSISTENT) != 0;
            boolean isOngoing = (notification.flags & Notification.FLAG_ONGOING_EVENT) != 0;
            boolean isAlarmCategory = Notification.CATEGORY_ALARM.equals(notification.category);

            PhoneLog.d(TAG, "📊 [闹钟属性] isInsistent=" + isInsistent + ", isOngoing=" + isOngoing + ", isAlarmCategory=" + isAlarmCategory);

            if (!isInsistent && !isOngoing && !isAlarmCategory) {
                PhoneLog.w(TAG, "⚠️ [闹钟拦截] 该通知不符合响铃特征(非持续/非进行中/非闹钟分类)，已放弃后续拉起");
                return; 
            }

            SharedPreferences prefs = getSharedPreferences("wearsync_prefs", Context.MODE_PRIVATE);
            String dismissKey = prefs.getString("alarm_dismiss_key", "停止").toLowerCase();
            String snoozeKey = prefs.getString("alarm_snooze_key", "延后").toLowerCase();

            if (notification.actions != null) {
                PhoneLog.d(TAG, "🧠 [意图提取] 开始解析通知栏物理按键，总Action数: " + notification.actions.length);
                for (Notification.Action action : notification.actions) {
                    if (action.title == null) continue;
                    String actionTitle = action.title.toString().toLowerCase();
                    PhoneLog.d(TAG, "  └─ 发现按键文本: [" + action.title.toString() + "]");
                    if (actionTitle.contains(dismissKey) || actionTitle.contains("stop") || actionTitle.contains("关闭")) {
                        cachedDismissIntent = action.actionIntent;
                        PhoneLog.d(TAG, "     🎯 成功锁定并缓存 [停止/关闭] Intent");
                    } else if (actionTitle.contains(snoozeKey) || actionTitle.contains("snooze")) {
                        cachedSnoozeIntent = action.actionIntent;
                        PhoneLog.d(TAG, "     🎯 成功锁定并缓存 [延后/稍后] Intent");
                    }
                }
            } else {
                PhoneLog.w(TAG, "⚠️ [意图提取] 警告：该闹钟通知栏没有任何物理 Action 按钮！");
            }

            Bundle extras = notification.extras;
            String alarmTitle = "";
            String alarmText = "";

            if (extras != null) {
                CharSequence titleCharSequence = extras.getCharSequence(Notification.EXTRA_TITLE);
                if (titleCharSequence != null) alarmTitle = titleCharSequence.toString();

                CharSequence textCharSequence = extras.getCharSequence(Notification.EXTRA_TEXT);
                if (textCharSequence != null) alarmText = textCharSequence.toString();
            }

            if (alarmText.isEmpty()) {
                SimpleDateFormat sdf = new SimpleDateFormat("HH:mm", Locale.getDefault());
                alarmText = sdf.format(new Date());
            }

            PhoneLog.d(TAG, "🔥 [放行轰鸣] 确认真实闹钟，准备发射！标题: [" + alarmTitle + "], 内容: [" + alarmText + "]");
            cachedAlarmTitle = alarmTitle;
            cachedAlarmText = alarmText;

            PhoneAlarmManager.notifyWatchAlarmRinging(this, alarmTitle, alarmText);

            isAlarmCurrentlyRinging = true;
            startAlarmWatchdog();
        }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        super.onNotificationRemoved(sbn);
        if (sbn == null) return;

        SharedPreferences prefs = getSharedPreferences("wearsync_prefs", Context.MODE_PRIVATE);
        String targetPkg = prefs.getString("key_alarm_package_name", "com.google.android.deskclock");
        String currentPkg = sbn.getPackageName();

        if (targetPkg.equalsIgnoreCase(currentPkg) 
            || "com.android.deskclock".equals(currentPkg) 
            || "com.coloros.alarmclock".equals(currentPkg)) {

            PhoneLog.d(TAG, "⏰ [哨兵拦截] 手机端闹钟通知消失，触发清除机制。消失包名: " + currentPkg);

            stopAlarmWatchdog();
            PhoneAlarmManager.notifyWatchAlarmDismissed(this);
        }
    }

    /**
     * 🎯 勿扰模式核心监听拦截站
     */
/**
     * 🎯 勿扰模式核心监听拦截站
     */
    /**
     * 🎯 勿擾模式核心監聽攔截站
     */
    @Override
    public void onInterruptionFilterChanged(int interruptionFilter) {
        super.onInterruptionFilterChanged(interruptionFilter);

        PhoneLog.d(TAG, "🚨🚨🚨 [勿擾重磅回調] 觸發 onInterruptionFilterChanged! filter 碼 = " + interruptionFilter);

        // 🚨 安全連鎖保護：防止死循環
        if (PhoneSyncListenerService.isInternalUpdate) {
            PhoneLog.w(TAG, "🛑 [勿擾攔截] 判定該變化由手錶引起，觸發『安全連鎖保護』，拒絕回傳！");
            return;
        }

        PhoneLog.d(TAG, "🚀 [勿擾準備發射] 所有基礎校驗通過！正在移交 PhoneDndManager 調度...");
        try {
            // 🔥 🔥 🔥 升級核心：拿掉布林值轉換，直接原封不動把系統原生 interruptionFilter (1,2,3,4) 傳過去
            PhoneDndManager.syncDndToWear(this, interruptionFilter);
            PhoneLog.d(TAG, "✨ [勿擾準備發射] PhoneDndManager.syncDndToWear() 託管方法執行完畢。");
        } catch (Exception e) {
            PhoneLog.e(TAG, "❌ [致命崩潰] 在調用 PhoneDndManager 方法時發生異常！" + e.getMessage());
        }
    }
    
    private void startAlarmWatchdog() {
        if (alarmWatchdogRunnable != null) return;

        PhoneLog.d(TAG, "🕵️ [看门狗] 闹钟高频拉起机制开启...");
        alarmWatchdogRunnable = new Runnable() {
            @Override
            public void run() {
                if (isAlarmCurrentlyRinging) {
                    PhoneLog.d(TAG, "🕵️ 【鬧鐘再次拉起核查】手機鬧鐘仍在轟鳴，強行發射通知...");
                    PhoneAlarmManager.notifyWatchAlarmRinging(PhoneSyncNotificationService.this, cachedAlarmTitle, cachedAlarmText);
                    alarmWatchdogHandler.postDelayed(this, 4000);
                }
            }
        };
        alarmWatchdogHandler.postDelayed(alarmWatchdogRunnable, 4000);
    }

    private void stopAlarmWatchdog() {
        isAlarmCurrentlyRinging = false;
        if (alarmWatchdogRunnable != null) {
            alarmWatchdogHandler.removeCallbacks(alarmWatchdogRunnable);
            alarmWatchdogRunnable = null;
            PhoneLog.d(TAG, "🛑 [看门狗] 轮询拉起机制已关闭。");
        }
    }
}
