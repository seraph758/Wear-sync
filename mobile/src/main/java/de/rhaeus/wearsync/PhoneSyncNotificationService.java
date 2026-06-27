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

        // 🎯 1. 彻底删掉顶部的这俩静态变数
    // public static android.app.PendingIntent cachedDismissIntent = null;
    // public static android.app.PendingIntent cachedSnoozeIntent = null;

    /**
     * 🎯 新增：供 PhoneAlarmManager 调用的实时逆向控制核心方法
     * 抛弃静态缓存，直接实时穿透通知栏获取最新的 Intent
     */
    public static boolean triggerLiveAlarmAction(Context context, boolean isDismissCommand) {
        PhoneSyncNotificationService serviceInstance = getInstance();
        if (serviceInstance == null) {
            PhoneLog.e(TAG, "❌ 实时控制失败：PhoneSyncNotificationService 实例未就绪（可能服务被彻底杀死了）");
            return false;
        }

        SharedPreferences prefs = context.getSharedPreferences("wearsync_prefs", Context.MODE_PRIVATE);
        String targetPkg = prefs.getString("selected_alarm_package", "com.google.android.deskclock");
        
        String dismissKey = prefs.getString("alarm_dismiss_key", "停止").toLowerCase();
        String snoozeKey = prefs.getString("alarm_snooze_key", "延后").toLowerCase();

        try {
            // 🚀 核心黑科技：实时捞取当前系统通知栏活着的所有通知
            StatusBarNotification[] activeNotifications = serviceInstance.getActiveNotifications();
            if (activeNotifications == null || activeNotifications.length == 0) {
                PhoneLog.w(TAG, "⚠️ 实时控制失败：当前通知栏空空如也，没有任何活跃通知");
                return false;
            }

            for (StatusBarNotification sbn : activeNotifications) {
                if (sbn == null || !targetPkg.equalsIgnoreCase(sbn.getPackageName())) {
                    continue; // 不是目标时钟应用的通知，跳过
                }

                Notification notification = sbn.getNotification();
                if (notification == null || notification.actions == null) continue;

                // 遍历当前这个活着的目标闹钟的所有按钮
                for (Notification.Action action : notification.actions) {
                    if (action.title == null || action.actionIntent == null) continue;

                    String title = action.title.toString().toLowerCase();

                    if (isDismissCommand) {
                        // 判定为停止指令
                        if (title.contains(dismissKey) || title.contains("stop") || title.contains("关闭") || title.contains("dismiss")) {
                            PhoneLog.d(TAG, "🔥 [实时击穿成功] 抓到最新有效的【停止】Intent，立刻注入执行！ActionTitle=" + action.title);
                            action.actionIntent.send();
                            return true;
                        }
                    } else {
                        // 判定为延后指令
                        if (title.contains(snoozeKey) || title.contains("snooze") || title.contains("稍后")) {
                            PhoneLog.d(TAG, "🔥 [实时击穿成功] 抓到最新有效的【延后】Intent，立刻注入执行！ActionTitle=" + action.title);
                            action.actionIntent.send();
                            return true;
                        }
                    }
                }
            }
        } catch (Exception e) {
            PhoneLog.e(TAG, "🔴 实时解析执行通知栏 Action 发生致命崩溃: " + e.getMessage(), e);
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
                                    4000);

                        }

                    }

                };



        alarmWatchdogHandler.postDelayed(
                alarmWatchdogRunnable,
                4000);

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
