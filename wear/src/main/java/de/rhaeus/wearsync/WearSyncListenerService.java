package de.rhaeus.wearsync;

import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.ChannelClient;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.Wearable;
import com.google.android.gms.wearable.WearableListenerService;

import org.json.JSONObject;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public class WearSyncListenerService extends WearableListenerService {

    private static final String TAG =
            "WearSync_WearListener";

    private static final String UNIVERSAL_SYNC_PATH =
            "/wear-universal-sync";

    private static final String CAMERA_PREVIEW_STREAM_PATH =
            "/camera-preview-stream";


    @Override
    public void onMessageReceived(
            @NonNull MessageEvent messageEvent
    ) {

        if (!UNIVERSAL_SYNC_PATH.equalsIgnoreCase(
                messageEvent.getPath()
        )) {
            return;
        }


        byte[] data =
                messageEvent.getData();


        if (data == null) {
            return;
        }


        try {


            JSONObject json =
                    new JSONObject(
                            new String(
                                    data,
                                    StandardCharsets.UTF_8
                            )
                    );


            String sender =
                    json.optString(
                            "sender",
                            ""
                    );


            String type =
                    json.optString(
                            "type",
                            ""
                    );


            String action =
                    json.optString(
                            "action",
                            ""
                    );


            if ("wear".equalsIgnoreCase(sender)) {
                return;
            }



            // =========================
            // 勿扰同步
            // =========================

            if ("status_mask".equalsIgnoreCase(type)
                    ||
                    json.has("status_mask")) {


                int mask =
                        json.optInt(
                                "status_mask",
                                -1
                        );


                if (mask != -1) {


                    WearSyncNotificationService
                            .isInternalUpdate = true;


                    boolean dnd =
                            (mask & 0x01) != 0;


                    NotificationManager nm =
                            (NotificationManager)
                                    getSystemService(
                                            Context.NOTIFICATION_SERVICE
                                    );


                    if (nm != null) {

                        boolean current =
                                nm.getCurrentInterruptionFilter()
                                        > 1;


                        if (current != dnd) {

                            nm.setInterruptionFilter(
                                    dnd ? 3 : 1
                            );

                        }

                    }



                    new Handler(
                            getMainLooper()
                    )
                    .postDelayed(
                            () -> {

                                WearSyncNotificationService
                                        .isInternalUpdate = false;

                            },
                            1500
                    );

                }


                return;

            }




            // =========================
            // 闹钟
            // =========================

            if ("alarm".equalsIgnoreCase(type)) {


                if ("START_ALARM_UI"
                        .equalsIgnoreCase(action)) {


                    Intent intent =
                            new Intent(
                                    this,
                                    WearAlarmActivity.class
                            );


                    intent.putExtra(
                            "EXTRA_ALARM_LABEL",
                            json.optString(
                                    "alarm_label",
                                    "闹钟响铃中"
                            )
                    );


                    intent.putExtra(
                            "EXTRA_ALARM_TIME",
                            json.optString(
                                    "alarm_time",
                                    ""
                            )
                    );


                    intent.addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK
                                    |
                            Intent.FLAG_ACTIVITY_CLEAR_TOP
                    );


                    startActivity(intent);

                }


                else if (
                        "FORCE_STOP_WEAR_ALARM"
                                .equalsIgnoreCase(action)
                ) {


                    sendBroadcast(
                            new Intent(
                                    WearAlarmActivity
                                            .ACTION_INTERNAL_FORCE_STOP
                            )
                    );

                }


                return;

            }




            // =========================
            // 相机
            // =========================

            if ("camera_control".equalsIgnoreCase(type)
                    ||
                    "camera".equalsIgnoreCase(type)) {


                if ("START_CAMERA".equalsIgnoreCase(action)
                        ||
                        "START_CAMERA_UI".equalsIgnoreCase(action)) {


                    Intent intent =
                            new Intent(
                                    this,
                                    WearCameraActivity.class
                            );


                    intent.putExtra(
                            "rotation_degrees",
                            json.optInt(
                                    "rotation_degrees",
                                    0
                            )
                    );


                    intent.addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK
                                    |
                            Intent.FLAG_ACTIVITY_CLEAR_TOP
                                    |
                            Intent.FLAG_ACTIVITY_SINGLE_TOP
                    );


                    startActivity(intent);


                    Log.d(
                            TAG,
                            "启动手表相机UI"
                    );


                }


                else if (
                        "FORCE_QUIT_CAMERA"
                                .equalsIgnoreCase(action)
                                ||
                        "STOP_CAMERA_ACTIVE"
                                .equalsIgnoreCase(action)
                ) {


                    sendBroadcast(
                            new Intent(
                                    "de.rhaeus.wearsync.ACTION_KILL_WEAR_CAMERA"
                            )
                    );


                    WearCameraActivity.forceQuitInstance();

                }


                return;

            }



        } catch(Exception e) {


            Log.e(
                    TAG,
                    "解析异常",
                    e
            );

        }

    }





    @Override
    public void onChannelOpened(
            @NonNull ChannelClient.Channel channel
    ) {


        if (!CAMERA_PREVIEW_STREAM_PATH.equalsIgnoreCase(
                channel.getPath()
        )) {

            return;

        }



        new Thread(() -> {


            try (
                    InputStream inputStream =
                            Tasks.await(
                                    Wearable
                                            .getChannelClient(this)
                                            .getInputStream(channel)
                            )
            ) {



                byte[] buffer =
                        new byte[8192];


                int length;



                while (
                        (length = inputStream.read(buffer))
                                != -1
                ) {



                    WearCameraActivity activity =
                            WearCameraActivity.sActivityRef.get();



                    if (activity != null) {


                        byte[] frame =
                                new byte[length];


                        System.arraycopy(
                                buffer,
                                0,
                                frame,
                                0,
                                length
                        );


                        activity.feedH264Data(
                                frame,
                                length
                        );


                    }

                }



            } catch(Exception e) {


                Log.e(
                        TAG,
                        "H264读取失败",
                        e
                );

            }



        }).start();

    }




    private void vibrate() {


        Vibrator vibrator =
                (Vibrator)
                        getSystemService(
                                Context.VIBRATOR_SERVICE
                        );


        if (vibrator != null
                &&
                vibrator.hasVibrator()) {


            vibrator.vibrate(
                    VibrationEffect.createOneShot(
                            50,
                            VibrationEffect.DEFAULT_AMPLITUDE
                    )
            );

        }

    }

}