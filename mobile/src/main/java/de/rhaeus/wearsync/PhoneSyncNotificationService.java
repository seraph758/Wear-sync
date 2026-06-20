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

/**
 * 📲 手机端通知与勿扰模式监听服务（哨兵服务）
 * 核心升级：
 * 1. 彻底修复勿扰变化回调中的未定义变量编译崩溃。
 * 2. 独家补齐【闹钟高频核查再次拉起看门狗】：只要手机闹钟在响，每4秒强制激活一次手表，直到真正点灭，彻底解决UI消失后不重新拉起的致命缺陷！
 */
public class PhoneSyncNotificationService extends NotificationListenerService {
    private static final String TAG = "WearSync_PhoneNotification";

    private static PhoneSyncNotificationService instance;
    
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
        instance = this; // 绑定全局单例
    }

    @Override
    public void onDestroy() {
        stopAlarmWatchdog();
        if (instance == this) {
            instance = null;
        }
        super.onDestroy();
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

            // 🎯 核心看門狗：利用真正的鬧鐘響鈴核心特徵進行嚴格過濾
            boolean isInsistent = (notification.flags & Notification.FLAG_INSISTENT) != 0;
            boolean isOngoing = (notification.flags & Notification.FLAG_ONGOING_EVENT) != 0;
            boolean isAlarmCategory = Notification.CATEGORY_ALARM.equals(notification.category);

            // 如果不是正在轟鳴的正式鬧鐘事件，直接攔截，絕不傳遞給手錶
            if (!isInsistent && !isOngoing && !isAlarmCategory) {
                Log.d("PhoneSync_Alarm", "🚫 [精準攔截] 檢測到該通知為鬧鐘預告/非響鈴事件，直接過濾。");
                return; 
            }

            Log.d("PhoneSync_Alarm", "🔥 [放行轟鳴] 檢測到真正的鬧鐘響鈴事件！");

            Bundle extras = notification.extras;
            String alarmTitle = "";
            String alarmText = "";

            if (extras != null) {
                CharSequence titleCharSequence = extras.getCharSequence(Notification.EXTRA_TITLE);
                if (titleCharSequence != null) {
                    alarmTitle = titleCharSequence.toString();
                }
                CharSequence textCharSequence = extras.getCharSequence(Notification.EXTRA_TEXT);
                if (textCharSequence != null) {
                    alarmText = textCharSequence.toString();
                }
            }

            if (alarmText.isEmpty()) {
                java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault());
                alarmText = sdf.format(new java.util.Date());
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

        SharedPreferences prefs = getSharedPreferences("wear_sync_prefs", Context.MODE_PRIVATE);
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
    Log.d(TAG, "📲 手機系統自身勿擾觸發變更回調，Filter值: " + interruptionFilter);

    // 1. 阻斷手錶反向同步引發的回旋死循環
    if (PhoneSyncListenerService.isInternalUpdate) {
        Log.d(TAG, "🛑 判定為手錶引起的內部修改，阻止反向回傳。");
        return;
    }

    boolean isPhoneDndOn = (interruptionFilter > 1);

    // 2. 動態讀取用戶在手機 UI 上勾選的真實開關狀態（嚴禁寫死 true）
    SharedPreferences prefs = getSharedPreferences("wear_sync_prefs", Context.MODE_PRIVATE);
    boolean vibrateSwitch = prefs.getBoolean("key_vibrate_switch", true);      
    boolean sleepLinkage = prefs.getBoolean("key_sleep_linkage", true);     
    boolean powerSaveLinkage = prefs.getBoolean("key_powersave_linkage", true); 

    Log.d(TAG, "🛰️ [哨兵主動發信] 正向打包最新勿擾狀態投遞給手錶...");
    
    // 3. 統一由這個核心方法輸出，不要再調用 PhoneDndManager.syncDndToWear 造成多包衝突！
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
}
