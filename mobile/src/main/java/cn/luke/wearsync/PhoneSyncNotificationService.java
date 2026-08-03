package cn.luke.wearsync;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

public class PhoneSyncNotificationService extends NotificationListenerService {
    private static final String TAG = "WearSync_PhoneNotification";
    private static PhoneSyncNotificationService instance;

    // 🛡️ 用于记录最后一次由手表触发的远程操作时间，防止状态回弹
    private static final AtomicLong sLastRemoteActionTimeMs = new AtomicLong(0);


    /**
     * 🎯 [全步進日誌版] 供 PhoneAlarmManager 調用的即時逆向控制核心方法
     */
    public static boolean triggerLiveAlarmAction(Context context, boolean isDismissCommand) {
        String cmdName = isDismissCommand ? "【停止/DISMISS】" : "【延後/SNOOZE】";
        PhoneLog.d(TAG, "🔍 [即時控制] ━━━ 進入實時通知欄解析流 ━━━ 正在準備執行: " + cmdName);
        PhoneSyncNotificationService serviceInstance = getInstance();
        if (serviceInstance == null) {
            PhoneLog.e(TAG, "❌ [即時控制攔截] 核心服務實例 (instance) 為 null！說明哨兵服務可能被系統徹底殺死或未啟動。");
            return false;
        }
        PhoneLog.d(TAG, "✅ [即時控制] 核心服務實例健康存在，開始讀取用戶配置...");
        SharedPreferences prefs = context.getSharedPreferences("wearsync_prefs", Context.MODE_PRIVATE);
        boolean isAlarmMasterEnabled = prefs.getBoolean("alarm_proxy_master_switch", true);
        if (!isAlarmMasterEnabled) {
            PhoneLog.w(TAG, "🎛️ [即時控制熔斷] 闹钟代点总开关已关闭，直接退出，不执行任何逆向点击意图！");
            return false;
        }
        String targetPkg = prefs.getString("selected_alarm_package", "com.google.android.deskclock");
        String dismissKey = prefs.getString("alarm_dismiss_key", "停止").toLowerCase();
        String snoozeKey = prefs.getString("alarm_snooze_key", "延后").toLowerCase();
        if (dismissKey.trim().isEmpty()) dismissKey = "停止";
        if (snoozeKey.trim().isEmpty()) snoozeKey = "延后";
        PhoneLog.d(TAG, "📌 [即時控制配置] 目標鬧鐘包名: [" + targetPkg + "], 停止關鍵字: [" + dismissKey + "], 延後關鍵字: [" + snoozeKey + "]");
        try {
            PhoneLog.d(TAG, "🚀 [即時控制] 正在調用系統 getActiveNotifications() 抓取當前快照...");
            StatusBarNotification[] activeNotifications = serviceInstance.getActiveNotifications();
            if (activeNotifications == null) {
                PhoneLog.w(TAG, "⚠️ [即時控制結束] 系統返回的通知陣列為 null（極端異常）");
                return false;
            }
            PhoneLog.d(TAG, "📊 [即時控制] 當前通知欄總共有 " + activeNotifications.length + " 個活躍通知，開始逐一篩選...");
            for (StatusBarNotification sbn : activeNotifications) {
                if (sbn == null) continue;
                String currentPkg = sbn.getPackageName();
                if (!targetPkg.equalsIgnoreCase(currentPkg)) continue;
                PhoneLog.d(TAG, "🎯 [命中目標包] 成功定位到目標鬧鐘通知！開始解析內部的 Notification 物件...");
                Notification notification = sbn.getNotification();
                if (notification == null) {
                    PhoneLog.w(TAG, "❌ [異常] 該鬧鐘的 Notification 數據結構為 null！");
                    continue;
                }
                if (notification.actions == null || notification.actions.length == 0) {
                    PhoneLog.w(TAG, "❌ [攔截失敗] 該鬧鐘通知目前沒有攜帶 any 操作按鈕 (actions == null)！");
                    continue;
                }
                PhoneLog.d(TAG, "📦 發現該鬧鐘通知包含 " + notification.actions.length + " 個 Action 按鈕，開始逐個匹配字串...");
                for (Notification.Action action : notification.actions) {
                    if (action == null || action.title == null) continue;
                    String title = action.title.toString().toLowerCase();
                    boolean isMatch = false;
                    if (isDismissCommand) {
                        if (title.contains(dismissKey) || title.contains("stop") || title.contains("关闭") || title.contains("停止") || title.contains("dismiss") || title.contains("清除")) {
                            isMatch = true;
                        }
                    } else {
                        if (title.contains(snoozeKey) || title.contains("snooze") || title.contains("延后") || title.contains("稍后")) {
                            isMatch = true;
                        }
                    }
                    if (isMatch) {
                        PhoneLog.d(TAG, "🔥 [🔥🔥 匹配成功 🔥🔥] 成功鎖定意圖！按鈕文字: [" + action.title + "]");
                        if (action.actionIntent == null) {
                            PhoneLog.e(TAG, "❌ [致命] 雖然找到了按鈕，但其 actionIntent 為 null，無法引爆！");
                            return false;
                        }
                        try {
                            PhoneLog.d(TAG, "🚀 [發射] 正在跨進程調用 actionIntent.send()...");
                            action.actionIntent.send();
                            sLastRemoteActionTimeMs.set(SystemClock.elapsedRealtime());
                            PhoneLog.d(TAG, "🏁 [結束] 穿透控制圓滿成功！");
                            return true;
                        } catch (Exception e) {
                            PhoneLog.e(TAG, "❌ [發送失敗] PendingIntent.send() 抛出異常: " + e.getMessage(), e);
                            return false;
                        }
                    }
                }
                PhoneLog.w(TAG, "⚠️ [匹配結束] 遍歷了該鬧鐘的所有按鈕，但沒有任何一個操作字串能與口令匹配成功。");
            }
            PhoneLog.w(TAG, "⚠️ [全面搜尋結束] 遍歷了整條通知欄，未找到任何屬於 [" + targetPkg + "] 的活躍通知。");
        } catch (Exception e) {
            PhoneLog.e(TAG, "🔴 [即時控制崩潰] 實時解析執行通知欄 Action 發生致命異常: " + e.getMessage(), e);
        }
        return false;
    }

    private final Handler alarmWatchdogHandler = new Handler(Looper.getMainLooper());
    private Runnable alarmWatchdogRunnable = null;
    private boolean isAlarmCurrentlyRinging = false;

    public static PhoneSyncNotificationService getInstance() {
        return instance;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        PhoneLog.d(TAG, "🚀 PhoneSyncNotificationService 启动");
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                PhoneLog.d(TAG, "当前勿扰状态: " + nm.getCurrentInterruptionFilter());
            }
        } catch (Exception e) {
            PhoneLog.e(TAG, "获取勿扰状态失败: " + e.getMessage());
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopAlarmWatchdog();
        instance = null;
    }


@Override
public void onNotificationPosted(StatusBarNotification sbn) {
    if (sbn == null) return;

    // 1. 开关检查
    SharedPreferences prefs = getSharedPreferences("wearsync_prefs", Context.MODE_PRIVATE);
    if (!prefs.getBoolean("alarm_proxy_master_switch", true)) return;

    // 2. 包名检查
    String packageName = sbn.getPackageName();
    String selectedPkg = prefs.getString("selected_alarm_package", "com.google.android.deskclock");
    
    // 注意：这里只检查包名不匹配时返回
    if (!selectedPkg.equals(packageName)) {
        return; 
    }

    // 3. 【修复点】这里绝对不能再有 return 了！
    // 必须让代码继续往下执行，去检查是不是真的闹钟

    PhoneLog.d(TAG, "⏰检测到目标闹钟包: " + packageName);
    Notification notification = sbn.getNotification();
    if (notification == null) return;

    // 4. 【新增/恢复】闹钟特征检查 (这是新版的核心逻辑)
    boolean isInsistent = (notification.flags & Notification.FLAG_INSISTENT) != 0;
    boolean isOngoing = (notification.flags & Notification.FLAG_ONGOING_EVENT) != 0;
    boolean isAlarmCategory = Notification.CATEGORY_ALARM.equals(notification.category);

    // 只要满足任意一个特征，就认为是闹钟
    if (!isInsistent && !isOngoing && !isAlarmCategory) {
        PhoneLog.d(TAG, "⚠️ 拦截：包名匹配，但通知特征不符合闹钟定义 (非持续、非正在进行、非闹钟类别)");
        return; 
    }

    
    PhoneLog.d(TAG, "✅ 确认闹钟通知，准备同步到手表 -> pkg: " + packageName);



        if (isAlarmCurrentlyRinging) {
            PhoneLog.d(TAG, "闹钟已经运行，忽略重复通知");
            return;
        }

        String dismissKey = prefs.getString("alarm_dismiss_key", "停止").toLowerCase();
        String snoozeKey = prefs.getString("alarm_snooze_key", "延后").toLowerCase();
        if (dismissKey.trim().isEmpty()) dismissKey = "停止";
        if (snoozeKey.trim().isEmpty()) snoozeKey = "延后";
        if (notification.actions != null) {
            for (Notification.Action action : notification.actions) {
                if (action.title == null) continue;
                String title = action.title.toString().toLowerCase();
                if (title.contains(dismissKey) || title.contains("stop") || title.contains("关闭") || title.contains("停止") || title.contains("dismiss")) {
                    PhoneLog.d(TAG, "🎯 发现停止按钮: " + action.title);
                } else if (title.contains(snoozeKey) || title.contains("snooze") || title.contains("稍后")) {
                    PhoneLog.d(TAG, "🎯 发现延后按钮: " + action.title);
                }
            }
        }

        PhoneLog.d(TAG, "✅ 闹钟系统已就绪，开始下发手表");
        PhoneAlarmManager.notifyWatchAlarmRinging(
                PhoneSyncNotificationService.this,
                new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date())
        );
        PhoneSyncAlarmState.enterRinging();
        isAlarmCurrentlyRinging = true;
        startAlarmWatchdog();
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        super.onNotificationRemoved(sbn);
        if (sbn == null) return;

        SharedPreferences prefs = getSharedPreferences("wearsync_prefs", Context.MODE_PRIVATE);


        boolean isAlarmMasterEnabled = prefs.getBoolean("alarm_proxy_master_switch", true);
        if (!isAlarmMasterEnabled) {
            return;
        }

        String targetPkg = prefs.getString("selected_alarm_package", "com.google.android.deskclock");
        String currentPkg = sbn.getPackageName();
        if (!targetPkg.equalsIgnoreCase(currentPkg)) {
            return;
        }

        stopAlarmWatchdog();
        PhoneSyncAlarmState.enterStopping();
        PhoneAlarmManager.notifyWatchAlarmDismissed(this);
    }

    @Override
    public void onInterruptionFilterChanged(int interruptionFilter) {
        super.onInterruptionFilterChanged(interruptionFilter);
        if (PhoneSyncListenerService.isInternalUpdate) {
            return;
        }
        try {
            // 而不是重新 getCurrentInterruptionFilter()（两者在极端时序下可能不一致）
            PhoneDndManager.syncDndToWear(this, interruptionFilter);
        } catch (Exception e) {
            PhoneLog.e(TAG, e.getMessage());
        }
    }

    private void startAlarmWatchdog() {
        if (alarmWatchdogRunnable != null) return;
        alarmWatchdogRunnable = new Runnable() {
            @Override
            public void run() {
                if (PhoneSyncAlarmState.isRinging()) {
                    PhoneAlarmManager.notifyWatchAlarmRinging(
                            PhoneSyncNotificationService.this,
                            new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date()));
                    alarmWatchdogHandler.postDelayed(this, 6000);
                }
            }
        };
        alarmWatchdogHandler.postDelayed(alarmWatchdogRunnable, 6000);
    }

    private void stopAlarmWatchdog() {
        PhoneSyncAlarmState.reset();
        isAlarmCurrentlyRinging = false;
        if (alarmWatchdogRunnable != null) {
            alarmWatchdogHandler.removeCallbacks(alarmWatchdogRunnable);
            alarmWatchdogRunnable = null;
        }
    }
}