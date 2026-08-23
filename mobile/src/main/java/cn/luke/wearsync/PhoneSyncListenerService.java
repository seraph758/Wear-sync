package cn.luke.wearsync;

import android.content.Intent;

import androidx.annotation.NonNull;

import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.ChannelClient;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;
import com.google.android.gms.wearable.WearableListenerService;

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
 * 📡 手机端监听核心 (Android 16/17 优先版)
 * 职责：指令分发、跨端 Activity 调度、全双工日志接收
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
        PhoneLog.d(TAG, "P-080 收到相机指令，Action=" + action);
        String nodeId = WearSyncState.getNodeId(this);

        if ("START_CAMERA".equalsIgnoreCase(action) || "START_CAMERA_UI".equalsIgnoreCase(action) || "open_phone_camera".equalsIgnoreCase(action)) {
            PhoneLog.d(TAG, "📸 准备启动手机端相机流程...");
            if (nodeId == null || nodeId.isEmpty()) {
                PhoneLog.w(TAG, "⚠️ 内存无 NodeID，尝试扫描连接的节点...");
                REMOTE_EXECUTOR.execute(() -> {
                    try {
                        List<Node> nodes = Tasks.await(Wearable.getNodeClient(this).getConnectedNodes());
                        if (nodes == null || nodes.isEmpty()) {
                            PhoneLog.e(TAG, "❌ 未找到任何连接的 Wear 节点");
                            return;
                        }
                        String id = nodes.get(0).getId();
                        WearSyncState.setNodeId(this, id);
                        launchLocalCameraSpringboard(id);
                    } catch (Exception e) {
                        PhoneLog.e(TAG, "❌ 节点扫描失败", e);
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
                launchLocalCameraSpringboard(nodeId);
            }
            return;
        }

        if ("STOP_CAMERA".equalsIgnoreCase(action) || "FORCE_QUIT_CAMERA".equalsIgnoreCase(action)) {
            PhoneLog.d(TAG, "🛑 收到停止相机指令");
            Intent stop = new Intent(this, PhoneSyncCameraService.class);
            stop.setAction(PhoneSyncCameraService.ACTION_STOP_CAMERA);
            startService(stop);
            return;
        }

        if ("TAKE_PHOTO".equalsIgnoreCase(action)) {
            PhoneLog.d(TAG, "📸 收到拍照指令");
            Intent photo = new Intent(this, PhoneSyncCameraService.class);
            photo.setAction(PhoneSyncCameraService.ACTION_TAKE_PHOTO);
            startService(photo);
            return;
        }

        if ("SWITCH_CAMERA".equalsIgnoreCase(action) || "SELECT_CAMERA".equalsIgnoreCase(action)) {
            String camId = json.optString("camera_id", json.optString("cameraId"));
            PhoneLog.d(TAG, "🔄 收到切换摄像头指令: " + camId);
            Intent intent = new Intent(this, PhoneSyncCameraService.class);
            intent.setAction(PhoneSyncCameraService.ACTION_SWITCH_CAMERA);
            intent.putExtra("camera_id", camId);
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

        if ("TOGGLE_VIDEO".equalsIgnoreCase(action)) {
            PhoneLog.d(TAG, "🎥 收到切换录像指令");
            Intent intent = new Intent(this, PhoneSyncCameraService.class);
            intent.setAction(PhoneSyncCameraService.ACTION_TOGGLE_VIDEO);
            startService(intent);
            return;
        }

        PhoneLog.w(TAG, "unknown camera action: " + action);
    }

    private void launchLocalCameraSpringboard(String nodeId) {
        try {
            PhoneLog.d(TAG, "🚀 Local Launch -> PhoneSyncRemoteCameraActivity for node: " + nodeId);
            Intent intent = new Intent(this, PhoneSyncRemoteCameraActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            intent.putExtra(PhoneSyncRemoteCameraActivity.EXTRA_SOURCE, PhoneSyncRemoteCameraActivity.SOURCE_REMOTE);
            intent.putExtra("remote_node_id", nodeId);
            startActivity(intent);
        } catch (Exception e) {
            PhoneLog.e(TAG, "local launch failed", e);
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

    @Override
    public void onChannelClosed(@NonNull ChannelClient.Channel channel, int closeReason, int appSpecificErrorCode) {
        String path = channel.getPath();
        PhoneLog.d(TAG, "🔌 [管道断开] Path: " + path + ", Reason: " + closeReason);
        
        // 🚀 核心修复：如果相机数据通道关闭（手表崩溃/退出/断连），强制停止手机端相机服务
        if ("/wear_data_channel/camera".equals(path)) {
            PhoneLog.w(TAG, "⚠️ 检测到相机通道意外关闭，正在释放手机端摄像头资源...");
            Intent stop = new Intent(this, PhoneSyncCameraService.class);
            stop.setAction(PhoneSyncCameraService.ACTION_STOP_CAMERA);
            startService(stop);
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
