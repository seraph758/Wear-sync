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
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import android.util.Log;

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
    // 🟢 修复：彻底删除了错误的 @Override 注解
   private static final String TEST_CHANNEL_PATH = "/channel_test_path";

    @Override
    public void onChannelOpened(@NonNull com.google.android.gms.wearable.ChannelClient.Channel channel) {
        String path = channel.getPath();
        Log.d("PhoneLog_Trace", "🛰️ [手機雷達] 偵測到 Channel 管道握手! Path: " + path);
        
        // 1. 原有的普通日誌管道
        if ("/wear_log_path".equals(path)) {
            Log.d("PhoneLog_Trace", "🎯 [暗號吻合] 正在建立手錶日誌接收流...");
            com.google.android.gms.wearable.Wearable.getChannelClient(this)
                .getInputStream(channel)
                .addOnSuccessListener(inputStream -> {
                    Log.d("PhoneLog_Trace", "🟢 [日誌流就緒] 啟動背景讀取執行緒...");
                    new Thread(() -> readLogStream(inputStream)).start();
                })
                .addOnFailureListener(e -> Log.e("PhoneLog_Trace", "❌ [日誌流獲取失敗]", e));
            return;
        }

        // 2. 🔬 新增的大包高壓傳輸測試通道
        if (TEST_CHANNEL_PATH.equals(path)) {
            Log.d("Channel_Test_Trace", "🛰️ [手機測試端] 偵測到手錶高壓大包測試管道接入！");
            
            com.google.android.gms.wearable.Wearable.getChannelClient(this)
                .getInputStream(channel)
                .addOnSuccessListener(inputStream -> {
                    Log.d("Channel_Test_Trace", "🟢 [手機測試端] InputStream 獲取成功，開始接收大包流量...");
                    
                    new Thread(() -> {
                        long totalReceivedBytes = 0;
                        long testStartTime = System.currentTimeMillis();
                        
                        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                                new java.io.InputStreamReader(inputStream, java.nio.charset.StandardCharsets.UTF_8))) {
                            
                            String line;
                            while ((line = reader.readLine()) != null) {
                                int lineSize = line.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
                                totalReceivedBytes += lineSize;
                                
                                String packetInfo = "普通包";
                                if (line.contains("[PAYLOAD_START_PACKET_1]")) packetInfo = "第 1 個大包";
                                else if (line.contains("[PAYLOAD_START_PACKET_2]")) packetInfo = "第 2 個大包";
                                else if (line.contains("[PAYLOAD_START_PACKET_3]")) packetInfo = "第 3 個大包";
                                
                                double sizeKb = lineSize / 1024.0;
                                String summary = String.format(java.util.Locale.getDefault(),
                                        "🟢 成功接收 %s | 大小: %.2f KB (%d 字节)", 
                                        packetInfo, sizeKb, lineSize);
                                
                                Log.i("Channel_Test_Trace", "📥 [接收成功] " + summary);
                                
                                // 💡 關鍵：只將摘要送進 PhoneLog，防止大文本重繪導致介面卡死
                                PhoneLog.appendFromRemote("[TEST] " + summary);
                            }
                            
                            double totalMb = totalReceivedBytes / (1024.0 * 1024.0);
                            double totalTimeSec = (System.currentTimeMillis() - testStartTime) / 1000.0;
                            double speed = totalMb / totalTimeSec;
                            
                            String finalSummary = String.format(java.util.Locale.getDefault(),
                                    "🏆 [測試完成] 共接收 %.2f MB 數據 | 總耗時: %.2f 秒 | 平均速度: %.2f MB/s",
                                    totalMb, totalTimeSec, speed);
                            
                            Log.i("Channel_Test_Trace", finalSummary);
                            PhoneLog.appendFromRemote("[TEST] " + finalSummary);
                            
                        } catch (Exception e) {
                            Log.e("Channel_Test_Trace", "❌ [手機測試端] 讀取大流時發生異常: " + e.getMessage());
                            PhoneLog.appendFromRemote("[TEST] ❌ 讀取高壓流中斷: " + e.getMessage());
                        }
                    }).start();
                })
                .addOnFailureListener(e -> Log.e("Channel_Test_Trace", "❌ [手機接收端] 獲取 InputStream 失敗", e));
        }
    }

    /**
     * 📥 后台无线日志连续行读取器
     */
    private void readLogStream(java.io.InputStream inputStream) {
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.getDefault());
        try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(inputStream, java.nio.charset.StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("[WEAR]") && !line.contains("] [20")) {
                    String timeStr = sdf.format(new java.util.Date());
                    line = "[WEAR] [" + timeStr + "]" + line.substring(6);
                }
                PhoneLog.appendFromRemote(line); 
            }
        } catch (Exception e) {
            Log.e("PhoneLog_Trace", "❌ [流讀取異常] 管道中斷", e);
        }
    }
} // 🟢 修复：去掉了原本末尾多余的一个右大括号
