package de.rhaeus.wearsync;

import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.util.Log;

import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;
import com.google.android.gms.wearable.WearableListenerService;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public class PhoneSyncListenerService extends WearableListenerService {

    private static final String TAG = "WearSync_PhoneListener";
    private static final String UNIVERSAL_SYNC_PATH = "/wear-universal-sync";

    private static final Executor REMOTE_EXECUTOR =
            Executors.newSingleThreadExecutor();

    public static boolean isInternalUpdate = false;


    @Override
    public void onMessageReceived(MessageEvent messageEvent) {

        if (messageEvent != null
                && messageEvent.getSourceNodeId() != null) {

            WearSyncState.setNodeId(
                    this,
                    messageEvent.getSourceNodeId()
            );
        }


        if (!UNIVERSAL_SYNC_PATH.equals(messageEvent.getPath())) {
            super.onMessageReceived(messageEvent);
            return;
        }


        try {

            String jsonStr =
                    new String(
                            messageEvent.getData(),
                            StandardCharsets.UTF_8
                    );

            JSONObject json =
                    new JSONObject(jsonStr);


            String sender =
                    json.optString("sender", "");

            String type =
                    json.optString("type", "");

            String action =
                    json.optString("action", "");


            if ("phone".equalsIgnoreCase(sender)) {
                return;
            }


            Log.d(
                    TAG,
                    "收到信令 type="
                            + type
                            + " action="
                            + action
            );


            // ===============================
            // 勿扰
            // ===============================

            if ("dnd".equalsIgnoreCase(type)) {

                int value =
                        json.has("dnd_profile_value")
                                ?
                                json.optInt(
                                        "dnd_profile_value",
                                        -1
                                )
                                :
                                json.optInt(
                                        "dnd_state",
                                        -1
                                );


                if (value == -1) {
                    return;
                }


                isInternalUpdate = true;


                NotificationManager nm =
                        (NotificationManager)
                                getSystemService(
                                        Context.NOTIFICATION_SERVICE
                                );


                if (nm != null
                        && nm.isNotificationPolicyAccessGranted()) {

                    nm.setInterruptionFilter(value);

                }


                new Handler(getMainLooper())
                        .postDelayed(
                                () -> isInternalUpdate = false,
                                1500
                        );


                return;
            }



            // ===============================
            // 闹钟
            // ===============================

            if ("alarm".equalsIgnoreCase(type)
                    ||
                    "alarm_action".equalsIgnoreCase(type)) {


                if ("DISMISS".equalsIgnoreCase(action)
                        ||
                        "SNOOZE".equalsIgnoreCase(action)) {


                    PhoneAlarmManager.handleWatchCommand(
                            this,
                            action
                    );
                }


                return;
            }



            // ===============================
            // 相机
            // ===============================

            if ("camera".equalsIgnoreCase(type)
                    ||
                    "camera_control".equalsIgnoreCase(type)) {


                if ("START_CAMERA_UI".equalsIgnoreCase(action)
                        ||
                        "START_CAMERA".equalsIgnoreCase(action)) {


                    String nodeId =
                            WearSyncState.getNodeId(this);


                    if (nodeId == null
                            || nodeId.isEmpty()) {


                        Log.w(
                                TAG,
                                "NodeId为空，查询连接节点"
                        );


                        new Thread(() -> {

                            try {

                                List<Node> nodes =
                                        Tasks.await(
                                                Wearable.getNodeClient(this)
                                                        .getConnectedNodes()
                                        );


                                if (nodes != null
                                        && !nodes.isEmpty()) {


                                    String id =
                                            nodes.get(0)
                                                    .getId();


                                    WearSyncState.setNodeId(
                                            this,
                                            id
                                    );


                                    executeRemoteActivityLaunch(id);

                                } else {

                                    Log.e(
                                            TAG,
                                            "没有发现手表节点"
                                    );

                                }


                            } catch (Exception e) {

                                Log.e(
                                        TAG,
                                        "Node查询失败",
                                        e
                                );

                            }


                        }).start();


                    } else {


                        executeRemoteActivityLaunch(nodeId);

                    }

                }


                return;
            }


        } catch(Exception e) {


            Log.e(
                    TAG,
                    "解析失败",
                    e
            );

        }

    }




    private void executeRemoteActivityLaunch(
            String nodeId
    ) {


        try {


            androidx.wear.remote.interactions.RemoteActivityHelper helper =
                    new androidx.wear.remote.interactions.RemoteActivityHelper(
                            this,
                            REMOTE_EXECUTOR
                    );


            Intent intent =
                    new Intent(
                            Intent.ACTION_VIEW
                    );


            intent.setData(
                    android.net.Uri.parse(
                            "wearsync://camera/"
                    )
            );


            intent.setPackage(
                    getPackageName()
            );


            intent.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK
                            |
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
                            |
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            );



            helper.startRemoteActivity(
                    intent,
                    nodeId
            )
            .addListener(
                    () -> {

                        Log.d(
                                TAG,
                                "🚀 RemoteActivity发送完成"
                        );

                    },
                    REMOTE_EXECUTOR
            );


        } catch(Exception e) {


            Log.e(
                    TAG,
                    "启动RemoteActivity失败",
                    e
            );

        }

    }




    public static void sendStatusMaskToWatch(
            Context context,
            boolean dndOn,
            boolean vibrateOn,
            boolean sleepLinkOn,
            boolean powerSaveLinkOn
    ) {


        if (isInternalUpdate) {
            return;
        }


        new Thread(() -> {

            try {


                JSONObject json =
                        new JSONObject();


                json.put(
                        "sender",
                        "phone"
                );


                json.put(
                        "type",
                        "status_mask"
                );


                byte[] data =
                        json.toString()
                                .getBytes(
                                        StandardCharsets.UTF_8
                                );



                String nodeId =
                        WearSyncState.getNodeId(context);



                if (nodeId != null
                        && !nodeId.isEmpty()) {


                    Tasks.await(
                            Wearable.getMessageClient(context)
                                    .sendMessage(
                                            nodeId,
                                            UNIVERSAL_SYNC_PATH,
                                            data
                                    )
                    );

                }


            } catch(Exception e) {


                Log.e(
                        TAG,
                        "发送失败",
                        e
                );

            }


        }).start();

    }

}