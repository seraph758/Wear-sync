package de.rhaeus.wearsync;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle; // 🎯 修正：補上漏掉的 Bundle 導包
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

/**
 * 📲 手机端通知与勿扰模式监听服务（哨兵服务）
 * 核心升级：
 * 1. 完美保留勿扰防死循环拦截机制。
 * 2. 补齐闹钟监听：通过 Ongoing 与不可清除标志，完美过滤预告通知，精准锁定真实响铃事件并抓取闹钟标题、时间。
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

            // ======= 🎯 這裡就是抓取鬧鐘資料並調用新 Manager 的地方 =======
            Bundle extras = notification.extras;
            String alarmTitle = "";
            String alarmText = "";

            if (extras != null) {
                // 抓取通知標題（通常是鬧鐘標籤，如 "起床"）
                CharSequence titleCharSequence = extras.getCharSequence(Notification.EXTRA_TITLE);
                if (titleCharSequence != null) {
                    alarmTitle = titleCharSequence.toString();
                }
                // 抓取通知內容（有時系統會把時間 "07:30" 放在這裡）
                CharSequence textCharSequence = extras.getCharSequence(Notification.EXTRA_TEXT);
                if (textCharSequence != null) {
                    alarmText = textCharSequence.toString();
                }
            }

            // 如果內容是空的，我們可以用目前系統時間兜底當作顯示時間
            if (alarmText.isEmpty()) {
                java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault());
                alarmText = sdf.format(new java.util.Date());
            }

            // 🚀 調用升級後的 Manager 方法，完美把資料發射給手錶！
            PhoneAlarmManager.notifyWatchAlarmRinging(this, alarmTitle, alarmText);
            // =========================================================
        }
    } // 🎯 修正：將原本錯位的花括号收攏，保證方法邊界正確

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        super.onNotificationRemoved(sbn);
        if (sbn == null) return;

        SharedPreferences prefs = getSharedPreferences("wear_sync_prefs", Context.MODE_PRIVATE);
        // 優化：這裡動態兼容用戶選中的包名，如果找不到則用谷歌鬧鐘兜底
        String targetPkg = prefs.getString("key_alarm_package_name", "com.google.android.deskclock");
        String currentPkg = sbn.getPackageName();

        // ⏰ 3. 双端操控闭环：如果用户在手机端点击了延后/关闭，通知消失，主动去灭掉手表的 UI 弹窗
        // 這裡做一個全包名相容判定：只要是目標包名，或者是主流三大時鐘之一，消失了都觸發停震
        if (targetPkg.equalsIgnoreCase(currentPkg) 
            || "com.android.deskclock".equals(currentPkg) 
            || "com.coloros.alarmclock".equals(currentPkg)) {
            
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
