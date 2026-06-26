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
                        || title.contains("关闭")){


                    cachedDismissIntent =
                            action.actionIntent;


                }


                else if(title.contains(snoozeKey)
                        || title.contains("snooze")){


                    cachedSnoozeIntent =
                            action.actionIntent;

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