package cn.luke.wearsync;

import android.content.Intent;
import androidx.annotation.NonNull; // 🚀 完美修复 1：补齐 NonNull 的关键导包
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
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * 📡 手机端监听核心（Camera + DND + Alarm + 🚀安全追加：无线日志大流接收舱）
 */
public class PhoneSyncListenerService extends WearableListenerService {

    private static final String TAG = "WearSync_PhoneListener";
    private static final String UNIVERSAL_SYNC_PATH = "/wear-universal-sync";
    
    // 🚀 追加：用于识别流传输的通道路径（保持与手表端定义一致）
    private static final String WEAR_LOG_CHANNEL_PATH = "/wear_log_path";

    private static final Executor REMOTE_EXECUTOR = Executors.newSingleThreadExecutor();

    public static boolean isInternalUpdate = false;

    // ============================================================
    // 📩 主入口（100% 还原，未动任一字句）
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
    // 📡 路由分发（100% 还原，未动任一字句）
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
    private void handleAlarm(JSONObject json, String action) {
        PhoneLog.d(TAG, "⏰ ALARM " + action);
        PhoneAlarmManager.executeAlarmAction(this, action);
    }

    // ============================================================
    // 📩 Camera Handler（100% 还原，未动任一字句）
    // ============================================================
    private void handleCamera(JSONObject json, String action) {

        PhoneLog.d(TAG, "P-080 handleCamera action=" + action);
        String nodeId = WearSyncState.getNodeId(this);
         
        if ("CAMERA_READY".equalsIgnoreCase(action)) {

            PhoneLog.d(TAG, "P-081 CAMERA_READY branch");
         
            PhoneSyncCameraService service = PhoneSyncCameraService.getInstance();

            PhoneLog.d(
                TAG,
                "service=" + service
                        + " nodeId=" + nodeId);

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
@Override
    public void onChannelOpened(@NonNull com.google.android.gms.wearable.ChannelClient.Channel channel) {
        Log.d("WearSync_Tunnel", "🔗 收到 Channel 建立请求，Path 为: " + channel.getPath());
        String path = channel.getPath();
        Log.d("PhoneLog_Trace", "🛰️ [手机雷达] 检测到有人建立了 Channel 管道! Path 是: " + path);
        
        if ("/wear_log_path".equals(path)) {
            Log.d("PhoneLog_Trace", "🎯 [暗号吻合] 确认是手表日志管道！正在尝试强力扭开手机端水龙头获取 InputStream...");
            
            com.google.android.gms.wearable.Wearable.getChannelClient(this)
                .getInputStream(channel)
                .addOnSuccessListener(inputStream -> {
                    Log.d("PhoneLog_Trace", "🟢 [水管畅通] 成功拿到 InputStream 输入流！开启后台无线轮询接收线程...");
                    new Thread(() -> readLogStream(inputStream)).start();
                })
                .addOnFailureListener(e -> {
                    Log.e("PhoneLog_Trace", "❌ [水管炸裂] 拿到 InputStream 失败!", e);
                });
        }
    }

    // 🚀 完美修复 2：将此方法参数修正为标准的 3 参数，契合最新 Wearable SDK 签名
    @Override
    public void onInputClosed(@NonNull ChannelClient.Channel channel, int closeReason, int appSpecificErrorCode) {
        super.onInputClosed(channel, closeReason, appSpecificErrorCode);
        if (channel.getPath().equals(WEAR_LOG_CHANNEL_PATH)) {
            PhoneLog.w(TAG, "🔌 手表端主动断开或熔断了日志大流通道。原因代码: " + closeReason + ", 错误码: " + appSpecificErrorCode);
        }
    }

    /**
     * 📥 后台无线日志连续行读取器
     */
    /**
     * 📥 后台无线日志连续行读取器
     */
    private void readLogStream(java.io.InputStream inputStream) {
        Log.d("PhoneLog_Trace", "🔄 [线程激活] readLogStream 轮询读取线程已正式进入工作状态。");
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.getDefault());
        
        try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(inputStream, java.nio.charset.StandardCharsets.UTF_8))) {
            String line;
            // 🔬 如果卡死在下面这一句，说明手表没有发送换行符 \n 或者压根没有往流里写东西
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("[WEAR]") && !line.contains("] [20")) {
                    String timeStr = sdf.format(new java.util.Date());
                    line = "[WEAR] [" + timeStr + "]" + line.substring(6);
                }
                PhoneLog.appendFromRemote(line); 
            }
            Log.d("PhoneLog_Trace", "🛑 [流结束] readLine() 返回了 null，说明手表端主动关闭了输出流。");
        } catch (Exception e) {
            Log.e("PhoneLog_Trace", "❌ [流读取异常] 管道崩塌或读取被动中断", e);
        }
    }
    // 請將這段代碼直接塞進手機端的 PhoneSyncListenerService.java 中

private static final String TEST_CHANNEL_PATH = "/channel_test_path";

@Override
public void onChannelOpened(@NonNull com.google.android.gms.wearable.ChannelClient.Channel channel) {
    String path = channel.getPath();
    
    // 🎯 這是原有的日誌通道
    if ("/wear_log_path".equals(path)) {
        // ...你原有的日誌流讀取邏輯...
        return;
    }

    // 🔬 這是新增的 Channel Client 專屬純淨測試通道
    if (TEST_CHANNEL_PATH.equals(path)) {
        Log.d("Channel_Test_Trace", "🛰️ [手機測試端] 偵測到手錶發起了測試 Channel 管道握手！");
        
        com.google.android.gms.wearable.Wearable.getChannelClient(this)
            .getInputStream(channel)
            .addOnSuccessListener(inputStream -> {
                Log.d("Channel_Test_Trace", "🟢 [手機測試端] 成功拿到 InputStream 輸入流，開始監聽數據...");
                new Thread(() -> {
                    try (java.io.BufferedReader reader = new java.io.BufferedReader(
                            new java.io.InputStreamReader(inputStream, java.nio.charset.StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            Log.i("Channel_Test_Trace", "📥 [測試數據抵達] 收到手錶發來的測試包 ➔ " + line);
                            // 同步寫入 PhoneLog 方便在 Activity 和懸浮窗界面直接肉眼觀察
                            PhoneLog.appendFromRemote("[TEST] 手錶 Channel 測試包: " + line);
                        }
                        Log.d("Channel_Test_Trace", "🛑 [手機測試端] 手錶關閉了測試流。");
                    } catch (Exception e) {
                        Log.e("Channel_Test_Trace", "❌ [手機測試端] 讀取測試流過程中發生異常: " + e.getMessage());
                    }
                }).start();
            })
            .addOnFailureListener(e -> {
                Log.e("Channel_Test_Trace", "❌ [手機測試端] 獲取 InputStream 失敗", e);
            });
    }
}

}
