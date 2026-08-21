package cn.luke.wearsync;

import android.content.Intent;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.wear.remote.interactions.RemoteActivityHelper;

import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.ChannelClient;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;
import com.google.android.gms.wearable.WearableListenerService;
import com.google.common.util.concurrent.ListenableFuture;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 📡 手机端监听核心（Camera + DND + Alarm + 🚀安全追加：无线日志大流接收舱）
 */
public class PhoneSyncListenerService extends WearableListenerService {

    private static final String TAG = "WearSync_PhoneListener";
    private static final String UNIVERSAL_SYNC_PATH = "/wear-universal-sync";
    // 🚀 追加：用于识别流传输的通道路径（保持与手表端定义一致）
    private static final String WEAR_LOG_CHANNEL_PATH = "/wear_data_channel/log";

    private static final Executor REMOTE_EXECUTOR = Executors.newSingleThreadExecutor();
    private static final ExecutorService MESSAGE_EXECUTOR = Executors.newSingleThreadExecutor();

    // ============================================================
    // 📩 主入口
    // ============================================================
    @Override
    public void onMessageReceived(@NonNull MessageEvent messageEvent) {
        // 1. 更新节点ID
        WearSyncState.setNodeId(this, messageEvent.getSourceNodeId());

        String path = messageEvent.getPath();
        
        // 处理文件传输状态回传路径
        if ("/file-transfer-status".equals(path)) {
            String status = new String(messageEvent.getData(), StandardCharsets.UTF_8);
            PhoneLog.d(TAG, "📥 [传输回执] " + status);
            PhoneSyncFileTransferManager.updateTransferStatus(status);
            return;
        }

        // 2. 路径校验
        if (!UNIVERSAL_SYNC_PATH.equals(messageEvent.getPath())) {
            super.onMessageReceived(messageEvent);
            return;
        }

        // 3. 在后台线程处理消息
        MESSAGE_EXECUTOR.execute(() -> {
            try {
                byte[] data = messageEvent.getData();
                String dataStr = new String(data, StandardCharsets.UTF_8);

                JSONObject json = new JSONObject(dataStr);

                // 4. 忽略来自手机自身的消息
                String sender = json.optString("sender", "");
                if ("phone".equalsIgnoreCase(sender)) return;

                // 5. 解析消息类型和动作
                String type = json.optString("type", "");
                String action = json.optString("action", "");
                PhoneLog.d(TAG, "📥 [信令] type=" + type + " action=" + action);

                // 🚀 新增：监听手表的“准备就绪”信号
                if ("READY_TO_RECEIVE".equalsIgnoreCase(action)) {
                    PhoneLog.d(TAG, "✅ 收到手表 ACK，触发文件传输");
                    PhoneSyncFileTransferManager.onWearReadyToReceive();
                    return; 
                }

                // 6. 其他消息按原有逻辑分发
                routeMessage(json, type, action);

            } catch (Exception e) {
                PhoneLog.e(TAG, "后台解析信令失败", e);
            }
        });
    }

    private void routeMessage(JSONObject json, String type, String action) {
        switch (type.toLowerCase()) {
            case "dnd":
                handleDnd(json);
                break;
            case "alarm":
            case "alarm_action":
                handleAlarm(action);
                break;
            case "camera":
            case "camera_control":
            case "camera_action": 
                handleCamera(json, action);
                break;
            default:
                PhoneLog.w(TAG, "unknown type: " + type);
        }
    }

    private void handleDnd(JSONObject json) {
        int targetValue = json.has("interruption_filter") ? json.optInt("interruption_filter", -1)
                : json.optInt("dnd_state", -1); 

        if (targetValue == -1) {
            PhoneLog.w(TAG, "⚠️ 未找到有效DND字段");
            return;
        }
        PhoneDndManager.handleIncomingAction(this, targetValue);
    }

    private void handleAlarm(String action) {
        PhoneLog.d(TAG, "⏰ ALARM " + action);
        PhoneAlarmManager.executeAlarmAction(this, action);
    }

    private void handleCamera(JSONObject json, String action) {
        PhoneLog.d(TAG, "P-080 handleCamera action=" + action);
        String nodeId = WearSyncState.getNodeId(this);

        if ("START_CAMERA".equalsIgnoreCase(action) || "START_CAMERA_UI".equalsIgnoreCase(action) || "open_phone_camera".equalsIgnoreCase(action)) {
            if (nodeId == null || nodeId.isEmpty()) {
                REMOTE_EXECUTOR.execute(() -> {
                    try {
                        List<Node> nodes = Tasks.await(Wearable.getNodeClient(this).getConnectedNodes());
                        if (nodes == null || nodes.isEmpty()) {
                            return;
                        }
                        String id = nodes.get(0).getId();
                        WearSyncState.setNodeId(this, id);
                        executeRemoteActivityLaunch(id);
                    } catch (Exception e) {
                        PhoneLog.e(TAG, "node scan failed", e);
                    }
                });
            } else {
                try {
                    JSONObject handshake = new JSONObject();
                    handshake.put("sender", "phone");
                    handshake.put("type", "camera_control");
                    handshake.put("action", "CAMERA_HANDSHAKE");
                    Wearable.getMessageClient(this)
                            .sendMessage(nodeId, UNIVERSAL_SYNC_PATH, handshake.toString().getBytes(StandardCharsets.UTF_8));
                } catch (Exception e) {
                    PhoneLog.e(TAG, "handshake failed", e);
                }
                executeRemoteActivityLaunch(nodeId);
            }
            return;
        }

        if ("STOP_CAMERA".equalsIgnoreCase(action) || "FORCE_QUIT_CAMERA".equalsIgnoreCase(action)) {
            Intent stop = new Intent(this, PhoneSyncCameraService.class);
            stop.setAction(PhoneSyncCameraService.ACTION_STOP_CAMERA);
            startService(stop);
            return;
        }

        if ("TAKE_PHOTO".equalsIgnoreCase(action)) {
            Intent photo = new Intent(this, PhoneSyncCameraService.class);
            photo.setAction(PhoneSyncCameraService.ACTION_TAKE_PHOTO);
            startService(photo);
            return;
        }

        if ("SWITCH_CAMERA".equalsIgnoreCase(action) || "SELECT_CAMERA".equalsIgnoreCase(action)) {
            Intent intent = new Intent(this, PhoneSyncCameraService.class);
            intent.setAction(PhoneSyncCameraService.ACTION_SWITCH_CAMERA);
            intent.putExtra("camera_id", json.optString("camera_id", json.optString("cameraId")));
            startService(intent);
            return;
        }

        if ("SET_ZOOM".equalsIgnoreCase(action)) {
            Intent intent = new Intent(this, PhoneSyncCameraService.class);
            intent.setAction(PhoneSyncCameraService.ACTION_SET_ZOOM);
            intent.putExtra("zoom", (float) json.optDouble("zoom", 1.0));
            startService(intent);
            return;
        }

        if ("FOCUS_CAMERA".equalsIgnoreCase(action)) {
            Intent intent = new Intent(this, PhoneSyncCameraService.class);
            intent.setAction(PhoneSyncCameraService.ACTION_FOCUS_CAMERA);
            intent.putExtra("x", json.optDouble("x", 0.5));
            intent.putExtra("y", json.optDouble("y", 0.5));
            startService(intent);
            return;
        }

        if ("LOG_CHANNEL_HANDSHAKE".equalsIgnoreCase(action)) {
            PhoneLog.d(TAG, "🤝 收到手表日志通道握手，数据通道已准备就绪！");
            return;
        }

        if ("REQUEST_CAMERA_LIST".equalsIgnoreCase(action)) {
            Intent intent = new Intent(this, PhoneSyncCameraService.class);
            intent.setAction("cn.luke.wearsync.action.REQUEST_CAMERA_LIST");
            startService(intent);
            return;
        }

        PhoneLog.w(TAG, "unknown camera action: " + action);
    }

  private void executeRemoteActivityLaunch(String nodeId) {
    try {
        PhoneLog.d(TAG, "🚀 REMOTE -> " + nodeId);
        RemoteActivityHelper helper = new RemoteActivityHelper(
                this, REMOTE_EXECUTOR);
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setData(Uri.parse("wearsync://camera"));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        // 👇 修复：添加 BROWSABLE category，匹配手表端 Activity 的 intent-filter 要求
        intent.addCategory(Intent.CATEGORY_BROWSABLE);
        ListenableFuture<Void> future = helper.startRemoteActivity(intent, nodeId);
        future.addListener(() -> {
            try {
                future.get();
                PhoneLog.d(TAG, "REMOTE OK");
            } catch (Exception e) {
                PhoneLog.w(TAG, "remote failed", e);
            }
        }, REMOTE_EXECUTOR);
    } catch (Exception e) {
        PhoneLog.e(TAG, "remote helper failed", e);
    }
}


    @Override
    public void onChannelOpened(@NonNull ChannelClient.Channel channel) {
        String path = channel.getPath();
        PhoneLog.d(TAG, "🛰️ [手機雷達] 偵測到 Channel 管道握手! Path: " + path);

        if (WEAR_LOG_CHANNEL_PATH.equals(path)) {
            PhoneLog.d(TAG, "🎯 [暗號吻合] 正在建立手錶日誌接收流...");
            Wearable.getChannelClient(this)
                    .getInputStream(channel)
                    .addOnSuccessListener(inputStream -> {
                        PhoneLog.d("PhoneLog_Trace", "🟢 [日誌流就緒] 啟動背景讀取執行緒...");
                        REMOTE_EXECUTOR.execute(() -> readLogStream(inputStream));
                    })
                    .addOnFailureListener(e -> PhoneLog.e("PhoneLog_Trace", "❌ [日誌流獲取失敗]", e));
        }
    }

    private void readLogStream(InputStream inputStream) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault());
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("[WEAR]") && !line.contains("] [20")) {
                    String timeStr = sdf.format(new Date());
                    line = "[WEAR] [" + timeStr + "]" + line.substring(6);
                }
                synchronized (PhoneLog.class) {
                    PhoneLog.appendFromRemote(line);
                }
            }
        } catch (Exception e) {
            PhoneLog.e(TAG, "❌ [流讀取異常] 管道中斷", e);
        }
    }
}
