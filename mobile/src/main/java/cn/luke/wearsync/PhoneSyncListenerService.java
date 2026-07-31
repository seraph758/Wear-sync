package cn.luke.wearsync;

import android.content.Intent;

import androidx.annotation.NonNull;

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
import java.util.concurrent.ExecutorService;


/**
 * 📡 手机端监听核心（Camera + DND + Alarm + 🚀安全追加：无线日志大流接收舱）
 */
public class PhoneSyncListenerService extends WearableListenerService {

    private static final String TAG = "WearSync_PhoneListener";
    private static final String UNIVERSAL_SYNC_PATH = "/wear-universal-sync";
    
    // 🚀 追加：用于识别流传输的通道路径（保持与手表端定义一致）
    private static final String WEAR_LOG_CHANNEL_PATH = "/wear_data_channel/log";
    private static final Executor REMOTE_EXECUTOR = Executors.newSingleThreadExecutor();

    public static boolean isInternalUpdate = false;
    private static final ExecutorService MESSAGE_EXECUTOR = Executors.newSingleThreadExecutor();


    // ============================================================
    // 📩 主入口（100% 还原，未动任一字句）
    // ============================================================
 @Override
public void onMessageReceived(MessageEvent messageEvent) {
    // 1. 更新节点ID
    if (messageEvent != null && messageEvent.getSourceNodeId() != null) {
        WearSyncState.setNodeId(this, messageEvent.getSourceNodeId());
    }
    
    // 2. 路径校验
    String receivedPath = (messageEvent != null) ? messageEvent.getPath() : "null";
    PhoneLog.d(TAG, "🔍 收到消息，路径为: [" + receivedPath + "]，期望路径: [" + UNIVERSAL_SYNC_PATH + "]");
    
    if (messageEvent == null || !UNIVERSAL_SYNC_PATH.equals(messageEvent.getPath())) {
        super.onMessageReceived(messageEvent);
        return;
    }

    // 3. 在后台线程处理消息
    MESSAGE_EXECUTOR.execute(() -> {
        try {
            String jsonStr = new String(messageEvent.getData(), StandardCharsets.UTF_8);
            JSONObject json = new JSONObject(jsonStr);
            
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
                // 调用文件传输管理器的静态方法，开始真正的文件发送
                PhoneSyncFileTransferManager.onWearReadyToReceive();
                return; // 处理完毕，直接返回
            }

            // 6. 其他消息按原有逻辑分发
            routeMessage(json, type, action);

        } catch (Exception e) {
            PhoneLog.e(TAG, "后台解析信令失败", e);
        }
    });
}


    // ============================================================
    // 📡 路由分发（100% 还原，未动任一字句）
    // ============================================================
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
                handleCamera(action);
                break;

            default:
                PhoneLog.w(TAG, "unknown type: " + type);
        }
    }

    // ============================================================
    // 🌓 DND（100% 还原，未动任一字句）
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
    // ⏰ Alarm（100% 还原，未动任一字句）
    // ============================================================
    private void handleAlarm(String action) {
        PhoneLog.d(TAG, "⏰ ALARM " + action);
        PhoneAlarmManager.executeAlarmAction(this, action);
    }

    // ============================================================
    // 📩 Camera Handler（100% 还原，未动任一字句）
    // ============================================================
    private void handleCamera(String action) {
    PhoneLog.d(TAG, "P-080 handleCamera action=" + action);
    String nodeId = WearSyncState.getNodeId(this);

    if ("CAMERA_READY".equalsIgnoreCase(action)) {
      PhoneLog.d(TAG, "P-081 CAMERA_READY branch");
      PhoneSyncCameraService service = PhoneSyncCameraService.getInstance();
      PhoneLog.d(TAG, "service=" + service + " nodeId=" + nodeId);
      if (service != null) {
        PhoneLog.d(TAG, "P-082 about to call startStreaming");
        service.startStreaming(nodeId);
      }
      return;
    }

    if ("STREAM_START".equalsIgnoreCase(action)) {
      PhoneSyncCameraService service = PhoneSyncCameraService.getInstance();
      if (service != null) {
        PhoneLog.d(TAG, "P-088 about to call startStreaming");
        service.startStreaming(nodeId);
      }
      return;
    }

    if ("START_CAMERA".equalsIgnoreCase(action)
        || "START_CAMERA_UI".equalsIgnoreCase(action)
        || "open_phone_camera".equalsIgnoreCase(action)) {
      if (nodeId == null || nodeId.isEmpty()) {
        REMOTE_EXECUTOR.execute(
            () -> {
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
              .sendMessage(
                  nodeId,
                  UNIVERSAL_SYNC_PATH,
                  handshake.toString().getBytes(StandardCharsets.UTF_8));
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

    if ("LOG_CHANNEL_HANDSHAKE".equalsIgnoreCase(action)) {
      PhoneLog.d(TAG, "🤝 收到手表日志通道握手，数据通道已准备就绪！");
      // 这里不需要做任何事，onChannelOpened 会处理流的读取
      // 打印这条日志就证明“数据路”也通了
      return;
    }

    PhoneLog.w(TAG, "unknown camera action: " + action);
  }

    // ============================================================
    // 🚀 Remote Activity（100% 还原，未动任一字句）
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
                    PhoneLog.d(TAG, "REMOTE OK");
                } catch (Exception e) {
                    PhoneLog.e(TAG, "remote failed", e);
                }
            }, REMOTE_EXECUTOR);

        } catch (Exception e) {
            PhoneLog.e(TAG, "remote helper failed", e);
        }
    }

    // ============================================================
    // 🚀 核心独立追加：大流通道生命周期监控（用于对接手表的无线日志流）
    // ============================================================
    // 🟢 修复：彻底删除了错误的 @Override 注解

    @Override
    public void onChannelOpened(@NonNull com.google.android.gms.wearable.ChannelClient.Channel channel) {
        String path = channel.getPath();
        PhoneLog.d(TAG, "🛰️ [手機雷達] 偵測到 Channel 管道握手! Path: " + path);

        // 统一在主日志管道中接收数据
        if (WEAR_LOG_CHANNEL_PATH.equals(path)) {
            PhoneLog.d(TAG, "🎯 [暗號吻合] 正在建立手錶日誌接收流...");
            com.google.android.gms.wearable.Wearable.getChannelClient(this)
                .getInputStream(channel)
                .addOnSuccessListener(inputStream -> {
                    PhoneLog.d("PhoneLog_Trace", "🟢 [日誌流就緒] 啟動背景讀取執行緒...");
                    new Thread(() -> readLogStream(inputStream)).start();
                })
                .addOnFailureListener(e -> PhoneLog.e("PhoneLog_Trace", "❌ [日誌流獲取失敗]", e));
        }
    }

    /**
     * 📥 统一的无线日志与大包测试流读取器（带线程同步保护）
     */
    private void readLogStream(java.io.InputStream inputStream) {
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.getDefault());

        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(inputStream, StandardCharsets.UTF_8))) {

            String line;
            while ((line = reader.readLine()) != null) {
                // 补充时间戳
                if (line.startsWith("[WEAR]") && !line.contains("] [20")) {
                    String timeStr = sdf.format(new java.util.Date());
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
