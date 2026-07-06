package de.rhaeus.wearsync;

import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
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
 * 📡 手机端谷歌微端监听核心哨兵服务 (WearableListenerService)
 * 核心变更：全面接入 PhoneLog 动态总开关控制体系，清理冗余空行。
 */
public class PhoneSyncListenerService extends WearableListenerService {

    private static final String TAG = "WearSync_PhoneListener";
    private static final String UNIVERSAL_SYNC_PATH = "/wear-universal-sync";
    private static final Executor REMOTE_EXECUTOR = Executors.newSingleThreadExecutor();

    public static boolean isInternalUpdate = false;

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
    
            PhoneLog.d(TAG, "📥 [信令到港] type=" + type + ", action=" + action);
    
            routeMessage(json, type, action);
    
        } catch (Exception e) {
            PhoneLog.e(TAG, "🔴 [信令解析失败]", e);
        }
    }


            // ==========================================
            // 🔋 模组一：勿扰状态逆向同步 (托管至 PhoneDndManager)
            // ==========================================
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
                            PhoneLog.w(TAG, "未知type：" + type);
                    }
                }
            private void handleDnd(JSONObject json) {

                int value = json.has("dnd_profile_value")
                        ? json.optInt("dnd_profile_value", -1)
                        : json.optInt("dnd_state", -1);
            
                if (value == -1) {
                    PhoneLog.w(TAG, "⚠️ [DND] value invalid");
                    return;
                }
            
                PhoneLog.d(TAG, "🌓 [DND] sync value=" + value);
            
                isInternalUpdate = true;
                PhoneDndManager.handleIncomingAction(this, value);
            
                new Handler(getMainLooper()).postDelayed(
                        () -> isInternalUpdate = false,
                        1500
                );
            }
            // ==========================================
            // ⏰ 模组二：闹钟远端代点控制 (解耦极简版)
            // ==========================================
           private void handleAlarm(JSONObject json, String action) {

                PhoneLog.d(TAG, "⏰ [ALARM] action=" + action);
            
                PhoneAlarmManager.executeAlarmAction(this, action);
            }
           // =================================================================
            // 📸 模組三：遠端相機協定控制（全步進日誌極致除錯版）
            // =================================================================
// ============================================================
// 📸 Camera Listener + State Bridge（与 CameraService 对齐版）
// ============================================================

private enum CameraState {
    IDLE,
    STARTING,
    CAMERA_READY,
    HANDSHAKING,
    CHANNEL_OPENING,
    STREAMING,
    STOPPING
}

private volatile CameraState cameraState = CameraState.IDLE;
private final Object stateLock = new Object();

// ======================= 状态控制 =======================

private CameraState getState() {
    synchronized (stateLock) {
        return cameraState;
    }
}

private void setState(CameraState state) {
    synchronized (stateLock) {
        cameraState = state;
        PhoneLog.d(TAG, "📡 [CAM_STATE] -> " + state);
    }
}

private boolean isState(CameraState s) {
    return getState() == s;
}

// ============================================================
// 📩 Listener 注册（唯一入口）
// ============================================================

private void registerCameraListener() {

    Wearable.getMessageClient(this)
            .addListener(messageEvent -> {

                try {
                    String data = new String(messageEvent.getData(), StandardCharsets.UTF_8);
                    JSONObject json = new JSONObject(data);

                    String action = json.optString("action");

                    PhoneLog.d(TAG, "📩 [CAM_LISTENER] action=" + action);

                    handleCamera(json, action);

                } catch (Exception e) {
                    PhoneLog.e(TAG, "listener parse failed", e);
                }
            });
}

// ============================================================
// 📥 Camera 协议统一入口（状态驱动核心）
// ============================================================

private void handleCamera(JSONObject json, String action) {

    String nodeId = WearSyncState.getNodeId(this);

    // ------------------------------------------------------------
    // CAMERA READY（来自手机 CameraService）
    // ------------------------------------------------------------
    if ("CAMERA_READY".equalsIgnoreCase(action)) {

        setState(CameraState.CAMERA_READY);
        PhoneLog.d(TAG, "CAM-P002 CAMERA_READY synced");

        return;
    }

    // ------------------------------------------------------------
    // START CAMERA（双向握手）
    // ------------------------------------------------------------
    if ("START_CAMERA".equalsIgnoreCase(action)
            || "START_CAMERA_UI".equalsIgnoreCase(action)) {

        setState(CameraState.HANDSHAKING);

        if (nodeId == null || nodeId.isEmpty()) {

            new Thread(() -> {
                try {
                    List<Node> nodes =
                            Tasks.await(Wearable.getNodeClient(this).getConnectedNodes());

                    if (nodes != null && !nodes.isEmpty()) {

                        String id = nodes.get(0).getId();
                        WearSyncState.setNodeId(this, id);

                        PhoneLog.d(TAG, "📡 [AUTO_BIND] node=" + id);

                        executeRemoteActivityLaunch(id);

                        setState(CameraState.STARTING);
                    } else {
                        setState(CameraState.IDLE);
                    }

                } catch (Exception e) {
                    setState(CameraState.IDLE);
                    PhoneLog.e(TAG, "node scan failed", e);
                }
            }).start();

        } else {

            try {
                JSONObject handshake = new JSONObject();
                handshake.put("sender", "phone");
                handshake.put("type", "camera_control");
                handshake.put("action", "CAMERA_HANDSHAKE");

                Wearable.getMessageClient(this).sendMessage(
                        nodeId,
                        "/wear-universal-sync",
                        handshake.toString().getBytes(StandardCharsets.UTF_8)
                );

                PhoneLog.d(TAG, "🤝 CAMERA_HANDSHAKE sent");

            } catch (Exception e) {
                PhoneLog.e(TAG, "handshake failed", e);
            }

            executeRemoteActivityLaunch(nodeId);
            setState(CameraState.STARTING);
        }

        return;
    }

    // ------------------------------------------------------------
    // STOP / FORCE QUIT（统一熔断）
    // ------------------------------------------------------------
    if ("STOP_CAMERA".equalsIgnoreCase(action)
            || "FORCE_QUIT_CAMERA".equalsIgnoreCase(action)) {

        setState(CameraState.STOPPING);

        Intent stop = new Intent(this, PhoneSyncCameraService.class);
        stop.setAction(PhoneSyncCameraService.ACTION_STOP_CAMERA);
        startService(stop);

        return;
    }

    PhoneLog.w(TAG, "⚠️ unknown camera action: " + action);
}

// ============================================================
// 🚀 RemoteActivity 启动（与 CameraService 对齐）
// ============================================================

private void executeRemoteActivityLaunch(String nodeId) {

    try {

        PhoneLog.d(TAG, "🚀 [REMOTE_LAUNCH] node=" + nodeId);

        androidx.wear.remote.interactions.RemoteActivityHelper helper =
                new androidx.wear.remote.interactions.RemoteActivityHelper(
                        this,
                        ContextCompat.getMainExecutor(this)
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
                setState(CameraState.STARTING);
                PhoneLog.d(TAG, "✨ [REMOTE_OK] watch activity launched");
            } catch (Exception e) {
                setState(CameraState.IDLE);
                PhoneLog.e(TAG, "remote launch failed", e);
            }
        }, ContextCompat.getMainExecutor(this));

    } catch (Exception e) {
        setState(CameraState.IDLE);
        PhoneLog.e(TAG, "remote helper failed", e);
    }
}
}
