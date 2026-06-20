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
import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 📲 手机端通知与勿扰模式监听服务（哨兵服务）
 * 核心升级：
 * 1. 彻底修复勿扰变化回调中的未定义变量编译崩溃。
 * 2. 独家补齐【闹钟高频核查再次拉起看门狗】：只要手机闹钟在响，每4秒强制激活一次手表，直到真正点灭，彻底解决UI消失后不重新拉起的致命缺陷！
 */
public class PhoneSyncNotificationService extends NotificationListenerService {
    private static final String TAG = "WearSync_PhoneNotification";
    private static PhoneSyncNotificationService instance;
    
    // 🎯 修复：全局静态变量正确放置在 class 内部最上方
    public static android.app.PendingIntent cachedDismissIntent = null;
    public static android.app.PendingIntent cachedSnoozeIntent = null;

    // ⏰ 闹钟高频再次拉起看门狗执行器
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
        Log.d(TAG, "🚀 PhoneSyncNotificationService 哨兵服务已启动");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopAlarmWatchdog();
        instance = null;
        Log.d(TAG, "🛑 PhoneSyncNotificationService 哨兵服务已销毁");
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null) return;
        String packageName = sbn.getPackageName();

        // 判斷是否為常見的系統鬧鐘包名
        if ("com.google.android.deskclock".equals(packageName) 
            || "com.coloros.alarmclock".equals(packageName) 
            || "com.android.deskclock".equals(packageName)) {

            Notification notification = sbn.getNotification();
            if (notification == null) return;

            boolean isInsistent = (notification.flags & Notification.FLAG_INSISTENT) != 0;
            boolean isOngoing = (notification.flags & Notification.FLAG_ONGOING_EVENT) != 0;
            boolean isAlarmCategory = Notification.CATEGORY_ALARM.equals(notification.category);

            if (!isInsistent && !isOngoing && !isAlarmCategory) return; 

            SharedPreferences prefs = getSharedPreferences("wearsync_prefs", Context.MODE_PRIVATE);
            String dismissKey = prefs.getString("alarm_dismiss_key", "停止").toLowerCase();
            String snoozeKey = prefs.getString("alarm_snooze_key", "延后").toLowerCase();

            // 🧠 智能提取通知栏的真实物理按键 Intent
            if (notification.actions != null) {
                for (Notification.Action action : notification.actions) {
                    if (action.title == null) continue;
                    String actionTitle = action.title.toString().toLowerCase();
                    if (actionTitle.contains(dismissKey) || actionTitle.contains("stop") || actionTitle.contains("关闭")) {
                        cachedDismissIntent = action.actionIntent;
                    } else if (actionTitle.contains(snoozeKey) || actionTitle.contains("snooze")) {
                        cachedSnoozeIntent = action.actionIntent;
                    }
                }
            }
            
            Log.d(TAG, "🔥 [放行轰鸣] 检测到真正的闹钟响铃事件！");
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

            // 缓存当前的闹钟文本，供看门狗高频再次拉起使用
            cachedAlarmTitle = alarmTitle;
            cachedAlarmText = alarmText;

            // 🚀 1. 立即触发第一次正向推送拉起手表 UI
            PhoneAlarmManager.notifyWatchAlarmRinging(this, alarmTitle, alarmText);

            // 🚀 2. 启动/刷新【核查再次拉起机制看门狗】
            isAlarmCurrentlyRinging = true;
            startAlarmWatchdog();
        }
    }
    
    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        super.onNotificationRemoved(sbn);
        if (sbn == null) return;

        // 🎯 修复：统一使用 "wearsync_prefs" 键名，确保多端配置能正确读取
        SharedPreferences prefs = getSharedPreferences("wearsync_prefs", Context.MODE_PRIVATE);
        String targetPkg = prefs.getString("key_alarm_package_name", "com.google.android.deskclock");
        String currentPkg = sbn.getPackageName();

        if (targetPkg.equalsIgnoreCase(currentPkg) 
            || "com.android.deskclock".equals(currentPkg) 
            || "com.coloros.alarmclock".equals(currentPkg)) {
            
            Log.d(TAG, "⏰ [哨兵拦截] 手机端目标闹钟通知已消失（已点灭/延后成功），通知手表停震并销毁，同时关闭看门狗...");
            
            // 🛑 关闭再次拉起轮询器，释放 CPU 资源
            stopAlarmWatchdog();

            // 🌟 通知手表清除闹钟状态并强行销毁全屏 UI
            PhoneAlarmManager.notifyWatchAlarmDismissed(this);
        }
    }

    @Override
    public void onInterruptionFilterChanged(int interruptionFilter) {
        super.onInterruptionFilterChanged(interruptionFilter);

        // 🔒 联锁防死循环：如果是手表反向同步导致的手机勿扰改变，直接拦截，绝不回传
        if (PhoneSyncListenerService.isInternalUpdate) {
            Log.d(TAG, "🛑 判定为手表引起的内部修改，阻止反向回传。");
            return;
        }

        SharedPreferences prefs = getSharedPreferences("wearsync_prefs", Context.MODE_PRIVATE);
        boolean dndMaster = prefs.getBoolean("dnd_master", true);
        
        // 如果总开关没开，手机自身状态变化绝不通知手表
        if (!dndMaster) return; 

        boolean isPhoneDndOn = (interruptionFilter > 1);
        boolean vibrateSwitch = prefs.getBoolean("dnd_vibrate", false);
        boolean sleepLinkage = prefs.getBoolean("wear_sleep", false);     
        boolean powerSaveLinkage = prefs.getBoolean("wear_power_saving", false); 

        PhoneSyncListenerService.sendStatusMaskToWatch(this, isPhoneDndOn, vibrateSwitch, sleepLinkage, powerSaveLinkage);
    }

    /**
     * ⏰ 启动闹钟状态高频核查轮询器
     * 如果手表端 UI 被意外划掉、或者因为系统原因熄灭，只要手机通知还在，每 4 秒强行再次拉起，直到彻底掐灭闹钟！
     */
    private void startAlarmWatchdog() {
        if (alarmWatchdogRunnable != null) return; // 已经运行中则不再重复创建

        alarmWatchdogRunnable = new Runnable() {
            @Override
            public void run() {
                if (isAlarmCurrentlyRinging) {
                    Log.d(TAG, "🕵️ 【鬧鐘再次拉起核查】檢測到手機鬧鐘仍處於轟鳴狀態！強行再次向手錶發射 START_ALARM_UI 確保不漏接...");
                    // 持续高频轰炸拉起手表，确保万无一失
                    PhoneAlarmManager.notifyWatchAlarmRinging(PhoneSyncNotificationService.this, cachedAlarmTitle, cachedAlarmText);
                    
                    // 每隔 4000 毫秒（4秒）检查并再次拉起一次
                    alarmWatchdogHandler.postDelayed(this, 4000);
                }
            }
        };
        alarmWatchdogHandler.postDelayed(alarmWatchdogRunnable, 4000);
    }

    /**
     * 🛑 关闭闹钟轮询看门狗
     */
    private void stopAlarmWatchdog() {
        isAlarmCurrentlyRinging = false;
        if (alarmWatchdogRunnable != null) {
            alarmWatchdogHandler.removeCallbacks(alarmWatchdogRunnable);
            alarmWatchdogRunnable = null;
            Log.d(TAG, "🛑 【鬧鐘看門狗已銷毀】輪詢拉起機制已安全關閉。");
        }
    }
} // 🎯 整个类的唯一闭合大括号在最底部
