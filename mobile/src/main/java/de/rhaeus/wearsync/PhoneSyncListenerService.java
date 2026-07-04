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
            private void handleCamera(JSONObject json, String action) {

            if ("CAMERA_READY".equalsIgnoreCase(action)) {
        
                PhoneLog.d(TAG, "CAM-P002 CAMERA_READY");
        
                String nodeId = WearSyncState.getNodeId(this);
        
                Intent intent = new Intent(this, PhoneSyncCameraService.class);
                intent.setAction(PhoneSyncCameraService.ACTION_START_CAMERA);
                startService(intent);
        
                new Handler(getMainLooper()).postDelayed(() -> {

                    if (PhoneSyncCameraService.instance != null) {
                
                        String nodeId = WearSyncState.getNodeId(this);
                
                        if (nodeId != null && !nodeId.isEmpty()) {
                            PhoneSyncCameraService.instance.startStreaming(nodeId);
                        } else {
                            PhoneLog.e(TAG, "CAMERA_READY 后 nodeId 为空");
                        }
                    }
                
                }, 300);
        
                return;
            }
        
            if ("STREAM_START".equalsIgnoreCase(action)) {
        
                if (PhoneSyncCameraService.instance != null) {
                    String nodeId = WearSyncState.getNodeId(this);
        
                    if (nodeId == null || nodeId.isEmpty()) {
                        PhoneLog.e(TAG, "STREAM_START nodeId empty");
                        return;
                    }
        
                    PhoneSyncCameraService.instance.openChannelAndStream(nodeId);
                }
        
                return;
            }
        
            if ("START_CAMERA".equalsIgnoreCase(action)
                    || "START_CAMERA_UI".equalsIgnoreCase(action)) {
        
                String nodeId = WearSyncState.getNodeId(this);
        
                if (nodeId == null || nodeId.isEmpty()) {
        
                    new Thread(() -> {
                        try {
                            List<Node> nodes =
                                    Tasks.await(Wearable.getNodeClient(this).getConnectedNodes());
        
                            if (nodes != null && !nodes.isEmpty()) {
                                String id = nodes.get(0).getId();
                                WearSyncState.setNodeId(this, id);
                                executeRemoteActivityLaunch(id);
                            }
                        } catch (Exception e) {
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
                        UNIVERSAL_SYNC_PATH,
                        handshake.toString().getBytes(StandardCharsets.UTF_8)
                );
            
                            } catch (Exception e) {
                
                        PhoneLog.e(TAG, "发送 CAMERA_HANDSHAKE 失败", e);
                
                    }
                
                    executeRemoteActivityLaunch(nodeId);
                }
        
            if ("STOP_CAMERA".equalsIgnoreCase(action)
                    || "FORCE_QUIT_CAMERA".equalsIgnoreCase(action)) {
        
                Intent stop = new Intent(this, PhoneSyncCameraService.class);
                stop.setAction(PhoneSyncCameraService.ACTION_STOP_CAMERA);
                startService(stop);
        
                return;
            }
        
            PhoneLog.w(TAG, "⚠️ [CAMERA] unknown action: " + action);
        }

    /**
     * 🛰️ 通過谷歌穿透引擎 (RemoteActivityHelper) 強制喚醒手表的配對拍照 Activity
     */
    private void executeRemoteActivityLaunch(String nodeId) {
        PhoneLog.d(TAG, "🚀 [穿透發射中] ━━━ 進入穿透啟動核心流 ━━━ 準備擊穿手錶端 Activity ➔ 目標節點: [" + nodeId + "]");
        try {
            PhoneLog.d(TAG, "⚙️ [穿透發射中] 正在初始化 RemoteActivityHelper 並綁定執行緒池...");
            androidx.wear.remote.interactions.RemoteActivityHelper helper =
                    new androidx.wear.remote.interactions.RemoteActivityHelper(this, REMOTE_EXECUTOR);

            PhoneLog.d(TAG, "⚙️ [穿透發射中] 正在建構遠端跳板協議 URI Schema: [wearsync://camera]");
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setData(android.net.Uri.parse("wearsync://camera"));
            
            PhoneLog.d(TAG, "⚙️ [穿透發射中] 正在注入多重 Activity 啟動 Flags (NEW_TASK | CLEAR_TOP | SINGLE_TOP)...");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK 
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP 
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);

            PhoneLog.d(TAG, "📡 [穿透發射中] 正在調用 startRemoteActivity() 跨裝置投遞穿透包...");
            com.google.common.util.concurrent.ListenableFuture<Void> future = helper.startRemoteActivity(intent, nodeId);
            
            PhoneLog.d(TAG, "⏳ [穿透發射中] 穿透異步任務已掛起，正在註冊底層接線員偵聽器 (Callback Listener)...");
            future.addListener(() -> {
                try {
                    // 調用 future.get() 可以確認任務是成功還是拋出異常
                    future.get();
                    PhoneLog.d(TAG, "✨ [穿透成功] ━━━ 遠端解鎖大捷 ━━━ 谷歌微端底層回報：手錶端的 wearsync://camera/ 跳板 Activity 已被強制拉起並聚焦！");
                } catch (Exception e) {
                    PhoneLog.e(TAG, "🔴 [穿透監聽報錯] 穿透任務送達後，手錶端拉起 Activity 失敗: " + e.getMessage(), e);
                }
            }, REMOTE_EXECUTOR);
            
            PhoneLog.d(TAG, "✅ [穿透發射中] 偵聽器掛載完畢，等待手錶端底層激勵回音。");

        } catch (Exception e) {
            PhoneLog.e(TAG, "🔴 [穿透失敗] 調用 RemoteActivityHelper 投遞階段發生致命阻斷: " + e.getMessage(), e);
        }
    }
}
