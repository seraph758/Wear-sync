package de.rhaeus.wearsync;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class PhoneSyncNotificationService extends NotificationListenerService {

    private static final String TAG = "WearSync_PhoneNotification";

    private static PhoneSyncNotificationService instance;

        

    /**
     * 🎯 新增：供 PhoneAlarmManager 调用的实时逆向控制核心方法
     * 抛弃静态缓存，直接实时穿透通知栏获取最新的 Intent
     */
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
        String targetPkg = prefs.getString("selected_alarm_package", "com.google.android.deskclock");
        String dismissKey = prefs.getString("alarm_dismiss_key", "停止").toLowerCase();
        String snoozeKey = prefs.getString("alarm_snooze_key", "延后").toLowerCase();
        // 🔒 防止关键字被保存为空
if (dismissKey.trim().isEmpty()) {
    dismissKey = "停止";
}

if (snoozeKey.trim().isEmpty()) {
    snoozeKey = "延后";
}

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
                if (sbn == null) {
                    PhoneLog.d(TAG, "  └─ 🛑 第 " + i + " 個通知物件為 null，跳過");
                    continue;
                }

                String currentPkg = sbn.getPackageName();
                PhoneLog.d(TAG, "  └─ 🔍 檢查第 " + i + " 個通知，包名: [" + currentPkg + "], ID: " + sbn.getId());

                if (!targetPkg.equalsIgnoreCase(currentPkg)) {
                    // 不是目標鬧鐘，不打印太多干擾日誌
                    continue; 
                }

                PhoneLog.d(TAG, "     🎯 [命中目標包] 成功定位到目標鬧鐘通知！開始解析內部的 Notification 物件...");
                Notification notification = sbn.getNotification();
                if (notification == null) {
                    PhoneLog.w(TAG, "     ❌ [異常] 該鬧鐘的 Notification 數據結構為 null！");
                    continue;
                }

                if (notification.actions == null || notification.actions.length == 0) {
                    PhoneLog.w(TAG, "     ❌ [攔截失敗] 該鬧鐘通知目前沒有攜帶任何操作按鈕 (actions == null)！");
                    continue;
                }

                PhoneLog.d(TAG, "     📦 發現該鬧鐘通知包含 " + notification.actions.length + " 個 Action 按鈕，開始逐個匹配字串...");

                // 遍歷當前這個活著的鬧鐘的所有按鈕
                for (int j = 0; j < notification.actions.length; j++) {
                    Notification.Action action = notification.actions[j];
                    if (action == null) {
                        PhoneLog.d(TAG, "        └─ 🛑 Action [" + j + "] 為 null");
                        continue;
                    }

                    if (action.title == null) {
                        PhoneLog.d(TAG, "        └─ ⚠️ Action [" + j + "] 的 title 為 null，無法進行文字匹配");
                        continue;
                    }

                    String title = action.title.toString().toLowerCase();
                    PhoneLog.d(TAG, "        └─ 🏷️ Action [" + j + "] 原始標籤: [" + action.title + "], 轉小寫後: [" + title + "]");

                    if (isDismissCommand) {
                        // 判定為停止指令
                        if (title.contains(dismissKey) || title.contains("stop") || title.contains("关闭") || title.contains("停止")|| title.contains("dismiss") || title.contains("清除")) {
                            PhoneLog.d(TAG, "        🔥 [🔥🔥 匹配成功 🔥🔥] 成功鎖定【停止】意圖！按鈕文字: [" + action.title + "]");
                            if (action.actionIntent == null) {
                                PhoneLog.e(TAG, "        ❌ [致命] 雖然找到了停止按鈕，但其 actionIntent 為 null，無法引爆！");
                                return false;
                            }
                            PhoneLog.d(TAG, "        🚀 [發射] 正在跨進程調用 actionIntent.send() 強行按掉手機鬧鐘...");
                            action.actionIntent.send();
                            PhoneLog.d(TAG, "        🏁 [結束] 穿透控制圓滿成功！");
                            return true;
                        }
                    } else {
                        // 判定為延後指令
                        if (title.contains(snoozeKey) || title.contains("snooze") || title.contains("延后") || title.contains("稍后")) {
                            PhoneLog.d(TAG, "        🔥 [🔥🔥 匹配成功 🔥🔥] 成功鎖定【延後】意圖！按鈕文字: [" + action.title + "]");
                            if (action.actionIntent == null) {
                                PhoneLog.e(TAG, "        ❌ [致命] 雖然找到了延後按鈕，但其 actionIntent 為 null，無法引爆！");
                                return false;
                            }
                            PhoneLog.d(TAG, "        🚀 [發射] 正在跨進程調用 actionIntent.send() 延後手機鬧鐘...");
                            action.actionIntent.send();
                            PhoneLog.d(TAG, "        🏁 [結束] 穿透控制圓滿成功！");
                            return true;
                        }
                    }
                }
                PhoneLog.w(TAG, "     ⚠️ [匹配結束] 遍歷了該鬧鐘的所有按鈕，但沒有任何一個操作字串能與口令匹配成功。");
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

    private String cachedAlarmTitle = "";
    private String cachedAlarmText = "";


    public static PhoneSyncNotificationService getInstance() {
        return instance;
    }


    @Override
    public void onCreate() {
        super.onCreate();

        instance = this;

        PhoneLog.d(TAG,
                "🚀 PhoneSyncNotificationService 启动");


        try {

            NotificationManager nm =
                    (NotificationManager)getSystemService(Context.NOTIFICATION_SERVICE);

            if(nm != null){

                PhoneLog.d(TAG,
                        "当前勿扰状态: "
                                + nm.getCurrentInterruptionFilter());

            }

        }catch(Exception e){

            PhoneLog.e(TAG,
                    "获取勿扰状态失败: "
                            + e.getMessage());
        }

    }


    @Override
    public void onDestroy(){

        super.onDestroy();

        stopAlarmWatchdog();

        instance = null;

    }



    @Override
    public void onNotificationPosted(StatusBarNotification sbn){


        if(sbn == null){
            return;
        }


        String packageName = sbn.getPackageName();


        SharedPreferences prefs =
                getSharedPreferences(
                        "wearsync_prefs",
                        Context.MODE_PRIVATE);


        String selectedPkg =
                prefs.getString(
                        "selected_alarm_package",
                        "com.google.android.deskclock");



        if(!selectedPkg.equals(packageName)){

            return;

        }



        PhoneLog.d(TAG,
                "⏰检测到目标闹钟包: "
                        + packageName);



        Notification notification =
                sbn.getNotification();


        if(notification == null){

            return;

        }



        boolean isInsistent =
                (notification.flags &
                        Notification.FLAG_INSISTENT) != 0;


        boolean isOngoing =
                (notification.flags &
                        Notification.FLAG_ONGOING_EVENT) != 0;


        boolean isAlarmCategory =
                Notification.CATEGORY_ALARM
                        .equals(notification.category);



        if(!isInsistent &&
                !isOngoing &&
                !isAlarmCategory){

            PhoneLog.d(TAG,
                    "不是闹钟通知");

            return;

        }



        String dismissKey =
                prefs.getString(
                        "alarm_dismiss_key",
                        "停止")
                        .toLowerCase();



        String snoozeKey =
                prefs.getString(
                        "alarm_snooze_key",
                        "延后")
                        .toLowerCase();

        if (dismissKey.trim().isEmpty()) {
    dismissKey = "停止";
}

if (snoozeKey.trim().isEmpty()) {
    snoozeKey = "延后";
}



        if(notification.actions != null){


            for(Notification.Action action:
                    notification.actions){


                if(action.title == null)
                    continue;



                String title =
                        action.title.toString()
                                .toLowerCase();



                if(title.contains(dismissKey)
        || title.contains("stop")
        || title.contains("关闭")
        || title.contains("停止")
        || title.contains("dismiss")){


    PhoneLog.d(
        TAG,
        "🎯 发现停止按钮: "
        + action.title
    );


}


else if(title.contains(snoozeKey)
        || title.contains("snooze")
        || title.contains("稍后")){


    PhoneLog.d(
        TAG,
        "🎯 发现延后按钮: "
        + action.title
    );

}


            }


        }




        Bundle extras =
                notification.extras;


        String alarmTitle="";
        String alarmText="";



        if(extras != null){


            CharSequence t =
                    extras.getCharSequence(
                            Notification.EXTRA_TITLE);


            if(t != null)
                alarmTitle=t.toString();



            CharSequence c =
                    extras.getCharSequence(
                            Notification.EXTRA_TEXT);


            if(c != null)
                alarmText=c.toString();

        }




        if(alarmText.isEmpty()){


            SimpleDateFormat sdf =
                    new SimpleDateFormat(
                            "HH:mm",
                            Locale.getDefault());


            alarmText =
                    sdf.format(new Date());

        }




        cachedAlarmTitle = alarmTitle;

        cachedAlarmText = alarmText;



        PhoneAlarmManager.notifyWatchAlarmRinging(
                this,
                alarmTitle,
                alarmText);



        isAlarmCurrentlyRinging=true;


        startAlarmWatchdog();


    }




    @Override
    public void onNotificationRemoved(StatusBarNotification sbn){


        super.onNotificationRemoved(sbn);



        if(sbn==null)
            return;



        SharedPreferences prefs =
                getSharedPreferences(
                        "wearsync_prefs",
                        Context.MODE_PRIVATE);



        String targetPkg =
                prefs.getString(
                        "selected_alarm_package",
                        "com.google.android.deskclock");



        String currentPkg =
                sbn.getPackageName();



        if(!targetPkg.equalsIgnoreCase(currentPkg)){

            return;

        }



        stopAlarmWatchdog();


        PhoneAlarmManager.notifyWatchAlarmDismissed(this);


    }




    @Override
    public void onInterruptionFilterChanged(int interruptionFilter){


        super.onInterruptionFilterChanged(interruptionFilter);



        if(PhoneSyncListenerService.isInternalUpdate){

            return;

        }



        try{


            PhoneDndManager.syncDndToWear(
                    this,
                    interruptionFilter);



        }catch(Exception e){


            PhoneLog.e(TAG,
                    e.getMessage());

        }


    }





    private void startAlarmWatchdog(){


        if(alarmWatchdogRunnable!=null)
            return;



        alarmWatchdogRunnable =
                new Runnable(){


                    @Override
                    public void run(){


                        if(isAlarmCurrentlyRinging){


                            PhoneAlarmManager.notifyWatchAlarmRinging(
                                    PhoneSyncNotificationService.this,
                                    cachedAlarmTitle,
                                    cachedAlarmText);



                            alarmWatchdogHandler.postDelayed(
                                    this,
                                    8000);

                        }

                    }

                };



        alarmWatchdogHandler.postDelayed(
                alarmWatchdogRunnable,
                8000);

    }




    private void stopAlarmWatchdog(){


        isAlarmCurrentlyRinging=false;



        if(alarmWatchdogRunnable!=null){


            alarmWatchdogHandler.removeCallbacks(
                    alarmWatchdogRunnable);



            alarmWatchdogRunnable=null;

        }

    }

}
