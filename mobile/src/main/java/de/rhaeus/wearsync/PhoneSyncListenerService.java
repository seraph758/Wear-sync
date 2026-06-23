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

public class PhoneSyncListenerService extends WearableListenerService {

    private static final String TAG = "WearSync_PhoneListener";
    private static final String UNIVERSAL_SYNC_PATH = "/wear-universal-sync";

    // 防止手机执行手表同步后再次反向触发
    public static boolean isInternalUpdate = false;


    @Override
    public void onMessageReceived(MessageEvent messageEvent) {

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

            JSONObject json = new JSONObject(jsonStr);

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


            // ==========================
            // 勿扰同步
            // ==========================

            if ("dnd".equalsIgnoreCase(type)) {

                int wearDndVal =
                        json.has("dnd_profile_value")
                                ? json.optInt(
                                        "dnd_profile_value",
                                        -1
                                )
                                : json.optInt(
                                        "dnd_state",
                                        -1
                                );


                if (wearDndVal == -1) {
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

                    nm.setInterruptionFilter(wearDndVal);


                } else {

                    Log.w(
                            TAG,
                            "手机端缺少勿扰模式权限"
                    );
                }


                new Handler(getMainLooper())
                        .postDelayed(
                                () -> isInternalUpdate = false,
                                1500
                        );


                return;
            }



            // ==========================
            // 闹钟控制
            // ==========================

            if ("alarm".equalsIgnoreCase(type)
                    || "alarm_action".equalsIgnoreCase(type)) {


                if ("DISMISS".equalsIgnoreCase(action)
                        || "SNOOZE".equalsIgnoreCase(action)) {


                    Log.d(
                            TAG,
                            "收到闹钟控制: "
                                    + action
                    );


                    PhoneAlarmManager.handleWatchCommand(
                            this,
                            action.toUpperCase()
                    );
                }


                return;
            }



        

                        // ==========================
            // 相机控制
            // ==========================
            if ("camera".equalsIgnoreCase(type)
                    || "camera_control".equalsIgnoreCase(type)) {

                if ("START_CAMERA_UI".equalsIgnoreCase(action)
                        || "START_CAMERA".equalsIgnoreCase(action)) {

                    Log.d(TAG, "收到手表拍照请求，准备通过 RemoteActivityHelper 启动 WearSyncRemoteCameraActivity");

                    try {
    Log.d(TAG, "收到手表拍照请求，准备通过 RemoteActivityHelper 启动 WearSyncRemoteCameraActivity");

    androidx.wear.remote.interactions.RemoteActivityHelper remoteHelper = 
            new androidx.wear.remote.interactions.RemoteActivityHelper(this, java.util.concurrent.Executors.newSingleThreadExecutor());

    Intent activityIntent = new Intent(Intent.ACTION_VIEW);
    
    // 🎯 核心修改點：在 camera 後面加上「/」或者「/start」，顯式聲明 camera 就是主機名 (Host)
    // 這樣既符合標準網址規範，又能 100% 被 Manifest 裡的 android:host="camera" 攔截到！
    activityIntent.setData(android.net.Uri.parse("wearsync://camera/"));
    
    activityIntent.setPackage(getPackageName()); 
    activityIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); 

    remoteHelper.startRemoteActivity(activityIntent).addListener(() -> {
        Log.d(TAG, "🚀 RemoteActivityHelper 特权发射成功！");
    }, java.util.concurrent.Executors.newSingleThreadExecutor());

    Log.d(TAG, "URI启动WearSyncRemoteCameraActivity指令已递交给谷歌服务");

} catch (Exception e) {
    Log.e(TAG, "启动RemoteCameraActivity失败", e);
}

                }

    /**
     * 手机主动发送状态到手表
     */
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

                int mask = 0;


                if (dndOn) {
                    mask |= 0x01;
                }

                if (vibrateOn) {
                    mask |= 0x02;
                }

                if (sleepLinkOn) {
                    mask |= 0x04;
                }

                if (powerSaveLinkOn) {
                    mask |= 0x08;
                }



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

                json.put(
                        "status_mask",
                        mask
                );


                byte[] payload =
                        json.toString()
                                .getBytes(
                                        StandardCharsets.UTF_8
                                );


                List<Node> nodes =
                        Tasks.await(
                                Wearable
                                        .getNodeClient(context)
                                        .getConnectedNodes()
                        );


                if (nodes != null) {

                    for (Node node : nodes) {

                        Tasks.await(
                                Wearable
                                        .getMessageClient(context)
                                        .sendMessage(
                                                node.getId(),
                                                UNIVERSAL_SYNC_PATH,
                                                payload
                                        )
                        );
                    }
                }


            } catch (Exception e) {

                Log.e(
                        TAG,
                        "同步状态到手表失败",
                        e
                );
            }

        }).start();
    }
}
