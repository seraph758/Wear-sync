package cn.luke.wearsync;

import android.content.Intent;
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

/**
 * 📡 手机端监听核心（Camera + DND + Alarm）
 */
public class PhoneSyncListenerService extends WearableListenerService {

    private static final String TAG = "WearSync_PhoneListener";
    private static final String UNIVERSAL_SYNC_PATH = "/wear-universal-sync";

    private static final Executor REMOTE_EXECUTOR = Executors.newSingleThreadExecutor();

    public static boolean isInternalUpdate = false;

    // ============================================================
    // 📩 主入口
    // ============================================================
    @Override
    public void onMessageReceived(MessageEvent messageEvent) {

        if (messageEvent != null && messageEvent.getSourceNodeId() != null) {
            WearSyncState.setNodeId(this, messageEvent.getSourceNodeId());
        }

        if (messageEvent == null || !UNIVERSAL_SYNC_PATH.equals(messageEvent.getPath())) {
            super.onMessageReceived(messageEvent);
            return;
        }

        try {
            String jsonStr = new String(messageEvent.getData(), StandardCharsets.UTF_8);
            JSONObject json = new JSONObject(jsonStr);

            String sender = json.optString("sender", "");
            if ("phone".equalsIgnoreCase(sender)) return;

            String type = json.optString("type", "");
            String action = json.optString("action", "");

            PhoneLog.d(TAG, "📥 [信令] type=" + type + " action=" + action);

            routeMessage(json, type, action);

        } catch (Exception e) {
            PhoneLog.e(TAG, "parse failed", e);
        }
    }

    // ============================================================
    // 📡 路由分发
    // ============================================================
    private void routeMessage(JSONObject json, String type, String action) {

        switch (type.toLowerCase()) {

            case "dnd":
                handleDnd(json);
                break;

            case "alarm":
            case "alarm_action":
                handleAlarm(json, action);
                break;

            case "camera":
            case "camera_control":
                handleCamera(json, action);
                break;

            default:
                PhoneLog.w(TAG, "unknown type: " + type);
        }
    }

    // ============================================================
    // 🌓 DND
    // ============================================================
    private void handleDnd(JSONObject json) {

        int value = json.has("dnd_profile_value")
                ? json.optInt("dnd_profile_value", -1)
                : json.optInt("dnd_state", -1);

        if (value == -1) return;

        isInternalUpdate = true;
        PhoneDndManager.handleIncomingAction(this, value);

        new android.os.Handler(getMainLooper()).postDelayed(
                () -> isInternalUpdate = false,
                1500
        );
    }

    // ============================================================
    // ⏰ Alarm
    // ============================================================
    private void handleAlarm(JSONObject json, String action) {
        PhoneLog.d(TAG, "⏰ ALARM " + action);
        PhoneAlarmManager.executeAlarmAction(this, action);
    }

    // ============================================================
    // 📸 Camera（修复同步版）
    // ============================================================
    

    // ============================================================
    // 📩 Camera Handler
    // ============================================================
   private void handleCamera(JSONObject json, String action) {

            String nodeId = WearSyncState.getNodeId(this);
        
            if ("CAMERA_READY".equalsIgnoreCase(action)) {
        
                PhoneLog.d(TAG, "CAMERA_READY synced");
        
                PhoneSyncCameraService service = PhoneSyncCameraService.getInstance();

                PhoneLog.d(
                    TAG,
                    "service=" + service
                            + " nodeId=" + nodeId);

                if (service != null) {
                    service.startStreaming(nodeId);
                }
        
                return;
            }
        
            if ("STREAM_START".equalsIgnoreCase(action)) {
        
                PhoneSyncCameraService service = PhoneSyncCameraService.getInstance();
        
                if (service != null) {
                    service.startStreaming(nodeId);
                }
        
                return;
            }
        
            if ("START_CAMERA".equalsIgnoreCase(action)
                    || "START_CAMERA_UI".equalsIgnoreCase(action)) {
        
                if (nodeId == null || nodeId.isEmpty()) {
        
                    REMOTE_EXECUTOR.execute(() -> {
        
                        try {
        
                            List<Node> nodes =
                                    Tasks.await(
                                            Wearable.getNodeClient(this)
                                                    .getConnectedNodes());
        
                            if (nodes == null || nodes.isEmpty()) {
                                return;
                            }
        
                            String id = nodes.get(0).getId();
        
                            WearSyncState.setNodeId(this, id);
        
                            executeRemoteActivityLaunch(id);
        
                        } catch (Exception e) {
        
                            PhoneLog.e(TAG,
                                    "node scan failed",
                                    e);
        
                        }
        
                    });
        
                } else {
        
                    try {
        
                        JSONObject handshake = new JSONObject();
        
                        handshake.put("sender", "phone");
                        handshake.put("type", "camera_control");
                        handshake.put("action", "CAMERA_HANDSHAKE");
        
                        Wearable.getMessageClient(this)
                                .sendMessage(
                                        nodeId,
                                        UNIVERSAL_SYNC_PATH,
                                        handshake.toString().getBytes(StandardCharsets.UTF_8));
        
                    } catch (Exception e) {
        
                        PhoneLog.e(TAG,
                                "handshake failed",
                                e);
        
                    }
        
                    executeRemoteActivityLaunch(nodeId);
                }
        
                return;
            }
        
            if ("STOP_CAMERA".equalsIgnoreCase(action)
                    || "FORCE_QUIT_CAMERA".equalsIgnoreCase(action)) {
        
                Intent stop =
                        new Intent(this,
                                PhoneSyncCameraService.class);
        
                stop.setAction(
                        PhoneSyncCameraService.ACTION_STOP_CAMERA);
        
                startService(stop);
        
                return;
            }
        
            PhoneLog.w(TAG,
                    "unknown camera action: "
                            + action);
        }

     // ============================================================
    // 🚀 Remote Activity
    // ============================================================
    private void executeRemoteActivityLaunch(String nodeId) {

        try {

            PhoneLog.d(TAG, "🚀 REMOTE -> " + nodeId);

            androidx.wear.remote.interactions.RemoteActivityHelper helper =
                    new androidx.wear.remote.interactions.RemoteActivityHelper(
                            this,
                            REMOTE_EXECUTOR
                    );

            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setData(android.net.Uri.parse("wearsync://camera"));

            intent.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK |
                    Intent.FLAG_ACTIVITY_CLEAR_TOP |
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            );

            com.google.common.util.concurrent.ListenableFuture<Void> future =
                    helper.startRemoteActivity(intent, nodeId);

            future.addListener(() -> {
                try {
                    future.get();
                    // ⚡ 这里原本的 setState(CameraState.STARTING); 已经删掉
                    PhoneLog.d(TAG, "REMOTE OK");
                } catch (Exception e) {
                    // ⚡ 这里原本的 setState(CameraState.IDLE); 已经删掉
                    PhoneLog.e(TAG, "remote failed", e);
                }
            }, REMOTE_EXECUTOR);

        } catch (Exception e) {
            // ⚡ 这里原本的 setState(CameraState.IDLE); 已经删掉
            PhoneLog.e(TAG, "remote helper failed", e);
        }
    }

}
