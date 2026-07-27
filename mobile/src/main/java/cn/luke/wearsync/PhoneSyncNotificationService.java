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

    // 🛡️ 新增：用于记录最后一次由手表触发的远程操作时间，防止状态回弹
    private static final AtomicLong sLastRemoteActionTimeMs = new AtomicLong(0);
    // 定义一个足够覆盖 send() 到 onNotificationRemoved 回调的时间窗口
    private static final long REMOTE_ACTION_WINDOW_MS = 3000; 

    /**
     * 🎯 [全步進日誌版] 供 PhoneAlarmManager 調用的即時逆向控制核心方法
     * 拋棄靜態變數，直接實時全量掃描通知欄
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

        // 🎯【新增拦截逻辑 1】如果总开关关闭，逆向控制也不予执行
        boolean isAlarmMasterEnabled = prefs.getBoolean("alarm_proxy_master_switch", true);
        if (!isAlarmMasterEnabled) {
            PhoneLog.w(TAG, "🎛️ [即時控制熔斷] 闹钟代点总开关已关闭，直接退出，不执行任何逆向点击意图！");
            return false;
        }

        String targetPkg = prefs.getString("selected_alarm_package", "com.google.android.deskclock");
        String dismissKey = prefs.getString("alarm_dismiss_key", "停止").toLowerCase();
        String snoozeKey = prefs.getString("alarm_snooze_key", "延后").toLowerCase();

        // 🔒 防止关键字被保存为空
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

            for (int i = 0; i < activeNotifications.length; i++) {
                StatusBarNotification sbn = activeNotifications[i];
                if (sbn == null) continue;

                String currentPkg = sbn.getPackageName();
                // 不是目標鬧鐘，跳過
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

                // 遍歷當前這個活著的鬧鐘的所有按鈕
                for (int j = 0; j < notification.actions.length; j++) {
                    Notification.Action action = notification.actions[j];
                    if (action == null || action.title == null) continue;

                    String title = action.title.toString().toLowerCase();
                    
                    boolean isMatch = false;
                    if (isDismissCommand) {
                        // 判定為停止指令
                        if (title.contains(dismissKey) || title.contains("stop") || title.contains("关闭") || title.contains("停止") || title.contains("dismiss") || title.contains("清除")) {
                            isMatch = true;
                        }
                    } else {
                        // 判定為延後指令
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
                            // ✅ 关键：标记这次操作是远程触发的，防止 onNotificationRemoved 误判
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
    private boolean alarmReady = false;
    private final Handler readyHandler = new Handler(Looper.getMainLooper());
    private Runnable pendingAlarmRunnable;
    private static final int READY_CHECK_INTERVAL = 100;
    private static final int READY_TIMEOUT = 1500;
    private int readyCheckElapsed = 0;

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

        // 1. 总开关检查
        SharedPreferences prefs = getSharedPreferences("wearsync_prefs", Context.MODE_PRIVATE);
        boolean isAlarmMasterEnabled = prefs.getBoolean("alarm_proxy_master_switch", true);
        if (!isAlarmMasterEnabled) {
            return;
        }

        // 2. 核心判断：FLAG_INSISTENT + 包名，两者必须同时满足
Notification notification = sbn.getNotification();
if (notification == null) return;

// 1. 第一道门槛：先判断包名（成本最低，快速排除无关通知）
String currentPkg = sbn.getPackageName();
String selectedPkg = prefs.getString("selected_alarm_package", "com.google.android.deskclock");
boolean isTargetPackage = selectedPkg.equalsIgnoreCase(currentPkg);

if (!isTargetPackage) {
    // 非目标包名，直接静默返回，不打印任何日志
    return;
}

// 2. 第二道门槛：包名匹配后，再判断 FLAG_INSISTENT
boolean isInsistent = (notification.flags & Notification.FLAG_INSISTENT) != 0;

PhoneLog.d(TAG, "🔔 闹钟包名匹配 -> isInsistent: " + isInsistent + ", pkg: " + currentPkg);

if (!isInsistent) {
    PhoneLog.d(TAG, "⚠️ 拦截：目标包名匹配但非持续响铃通知 (isInsistent=false)");
    return;
}

// ✅ 双重验证通过，确认为真正的闹钟通知
PhoneLog.d(TAG, "✅ 确认闹钟通知，准备同步到手表 -> pkg: " + currentPkg);
// ... 后续触发手表闹钟的逻辑 ...


        // 3. 防重复检查
        if (isAlarmCurrentlyRinging) {
            PhoneLog.d(TAG, "闹钟已经运行，忽略重复通知");
            return;
        }

        // 4. 查找按钮关键字
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

        // 5. 通知手表（移除了所有错误的超时和重试逻辑）
        alarmReady = true;
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
        
        // 🛡️ 新增：防回弹拦截
        // 如果通知消失是由我们刚刚的 send() 操作引起的，则忽略此次回调，不向手表发送任何消息
        if (sLastRemoteActionTimeMs.get() > 0 && 
            (SystemClock.elapsedRealtime() - sLastRemoteActionTimeMs.get()) < REMOTE_ACTION_WINDOW_MS) {
            PhoneLog.d(TAG, "🛡️ [防回彈攔截] 通知消失是由遠程操作觸發的，跳過後續處理");
            // 可选：清除标记，防止影响下一次非远程触发的消失
            // sLastRemoteActionTimeMs.set(0); 
            return;
        }

        // 🎯 同样加入开关校验，如果开关此时是关着的，说明手表之前根本没收到过叮咚声，removed 也不必发下发给手表了
        boolean isAlarmMasterEnabled = prefs.getBoolean("alarm_proxy_master_switch", true);
        if (!isAlarmMasterEnabled) {
            return;
        }

        String targetPkg = prefs.getString("selected_alarm_package", "com.google.android.deskclock");
        String currentPkg = sbn.getPackageName();
        if (!targetPkg.equalsIgnoreCase(currentPkg)) {
            return;
        }

        alarmReady = false;
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
            PhoneDndManager.syncDndToWear(this);
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

    private boolean isAlarmSystemReady() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return false;
            int filter = nm.getCurrentInterruptionFilter();
            if (filter == NotificationManager.INTERRUPTION_FILTER_UNKNOWN) {
                return false;
            }
            StatusBarNotification[] active = getActiveNotifications();
            if (active == null || active.length == 0) {
                return false;
            }
            for (StatusBarNotification sbn : active) {
                if (sbn == null) continue;
                Notification n = sbn.getNotification();
                if (n == null) continue;
                if (Notification.CATEGORY_ALARM.equals(n.category)) {
                    if (n.actions != null && n.actions.length > 0) {
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            PhoneLog.e(TAG, "READY CHECK ERROR: " + e.getMessage());
        }
        return false;
    }
}


