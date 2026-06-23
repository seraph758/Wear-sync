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
    
    // 🎯 全局緩存：隨時記錄當前活躍連接的手錶車牌號（NodeId）
    private static String cachedWatchNodeId = null;

    @Override
    public void onMessageReceived(MessageEvent messageEvent) {
        // 📥 每一條飄過來的消息，都順手把它的發射源 ID 更新到緩存裡
        if (messageEvent != null && messageEvent.getSourceNodeId() != null) {
            cachedWatchNodeId = messageEvent.getSourceNodeId();
        }

        if (!UNIVERSAL_SYNC_PATH.equals(messageEvent.getPath())) {
            super.onMessageReceived(messageEvent);
            return;
        }

        try {
            String jsonStr = new String(messageEvent.getData(), StandardCharsets.UTF_8);
            JSONObject json = new JSONObject(jsonStr);

            String sender = json.optString("sender", "");
            String type = json.optString("type", "");
            String action = json.optString("action", "");

            if ("phone".equalsIgnoreCase(sender)) {
                return;
            }

            Log.d(TAG, "收到信令 type=" + type + " action=" + action);

            // ==========================
            // 勿扰同步
            // ==========================
            if ("dnd".equalsIgnoreCase(type)) {
                int wearDndVal = json.has("dnd_profile_value")
                                ? json.optInt("dnd_profile_value", -1)
                                : json.optInt("dnd_state", -1);

                if (wearDndVal == -1) {
                    return;
                }

                isInternalUpdate = true;

                NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

                if (nm != null && nm.isNotificationPolicyAccessGranted()) {
                    nm.setInterruptionFilter(wearDndVal);
                } else {
                    Log.w(TAG, "手机端缺少勿扰模式权限");
                }

                new Handler(getMainLooper()).postDelayed(() -> isInternalUpdate = false, 1500);
                return;
            }

            // ==========================
            // 闹钟控制
            // ==========================
            if ("alarm".equalsIgnoreCase(type) || "alarm_action".equalsIgnoreCase(type)) {
                if ("DISMISS".equalsIgnoreCase(action) || "SNOOZE".equalsIgnoreCase(action)) {
                    Log.d(TAG, "收到闹钟控制: " + action);
                    PhoneAlarmManager.handleWatchCommand(this, action.toUpperCase());
                }
                return;
            }

            // ==========================
            // 相机控制
            // ==========================
            if ("camera".equalsIgnoreCase(type) || "camera_control".equalsIgnoreCase(type)) {
                if ("START_CAMERA_UI".equalsIgnoreCase(action) || "START_CAMERA".equalsIgnoreCase(action)) {
                    Log.d(TAG, "收到手表拍照请求，準備透過全局緩存 NodeId 啟動 WearSyncRemoteCameraActivity");

                    // 🎯 1. 優先嘗試從全局變量中直接讀取手錶 ID（零延遲）
                    String targetNodeId = cachedWatchNodeId;

                    // 🎯 2. 防禦性編程：如果變量是空的（比如剛開機），立刻啟動線程主動去拿一次並補寫進緩存
                    if (targetNodeId == null || targetNodeId.isEmpty()) {
                        Log.w(TAG, "⚠️ 全局緩存為空，啟動背景線程實時追溯並刷新緩存...");
                        
                        new Thread(() -> {
                            try {
                                com.google.android.gms.tasks.Task<List<Node>> nodeTask = 
                                        Wearable.getNodeClient(this).getConnectedNodes();
                                List<Node> nodes = com.google.android.gms.tasks.Tasks.await(nodeTask);
                                
                                if (nodes != null && !nodes.isEmpty()) {
                                    cachedWatchNodeId = nodes.get(0).getId(); 
                                    Log.d(TAG, "♻️ 已成功實時追溯手錶 NodeId 並同步至全局緩存: " + cachedWatchNodeId);
                                    executeRemoteActivityLaunch(cachedWatchNodeId);
                                } else {
                                    Log.e(TAG, "❌ 嚴重警告：連線檢查未發現任何手錶，特權通道降級盲發！");
                                    executeRemoteActivityLaunch(null);
                                }
                            } catch (Exception e) {
                                Log.e(TAG, "背景線程追溯 NodeId 失敗", e);
                                executeRemoteActivityLaunch(null);
                            }
                        }).start();
                        
                    } else {
                        // 🎯 3. 緩存命中！直接秒級發射
                        Log.d(TAG, "⚡ 全局緩存命中！直接提取 NodeId 發射免死金牌: " + targetNodeId);
                        executeRemoteActivityLaunch(targetNodeId);
                    }
                }
                return;
            }

        } catch (Exception e) {
            Log.e(TAG, "解析手錶信令出錯", e);
        }
    }

    /**
     * 🛰️ 封裝：執行帶有免死金牌特權的 Activity 發射（獨立方法，嚴禁嵌套）
     */
    private void executeRemoteActivityLaunch(String nodeId) {
        try {
            androidx.wear.remote.interactions.RemoteActivityHelper remoteHelper = 
                    new androidx.wear.remote.interactions.RemoteActivityHelper(this, java.util.concurrent.Executors.newSingleThreadExecutor());

            Intent activityIntent = new Intent(Intent.ACTION_VIEW);
            activityIntent.setData(android.net.Uri.parse("wearsync://camera/")); 
            activityIntent.setPackage(getPackageName()); 

            // 注入穿透後台與鎖屏限制的強力 Flags 組合
            activityIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK 
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP 
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);

            if (nodeId != null) {
                // 帶有精確車牌號的最高優先級發射
                remoteHelper.startRemoteActivity(activityIntent, nodeId).addListener(() -> {
                    Log.d(TAG, "🚀 [特權穿透成功] 成功借道谷歌 GMS 服務拉起相機界面！");
                }, java.util.concurrent.Executors.newSingleThreadExecutor());
            } else {
                // 盲發降級方案
                remoteHelper.startRemoteActivity(activityIntent).addListener(() -> {
                    Log.d(TAG, "🚀 RemoteActivityHelper 普通發射成功（無 NodeId 備用）");
                }, java.util.concurrent.Executors.newSingleThreadExecutor());
            }
            
            Log.d(TAG, "URI启动WearSyncRemoteCameraActivity指令已递交给谷歌服务");
        } catch (Exception e) {
            Log.e(TAG, "調用 RemoteActivityHelper 失敗", e);
        }
    }

    /**
     * 手机主动发送状态到手表（獨立方法，嚴禁嵌套）
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
                if (dndOn) mask |= 0x01;
                if (vibrateOn) mask |= 0x02;
                if (sleepLinkOn) mask |= 0x04;
                if (powerSaveLinkOn) mask |= 0x08;

                JSONObject json = new JSONObject();
                json.put("sender", "phone");
                json.put("type", "status_mask");
                json.put("status_mask", mask);

                byte[] payload = json.toString().getBytes(StandardCharsets.UTF_8);

                List<Node> nodes = Tasks.await(Wearable.getNodeClient(context).getConnectedNodes());

                if (nodes != null) {
                    for (Node node : nodes) {
                        Tasks.await(
                                Wearable.getMessageClient(context).sendMessage(
                                        node.getId(),
                                        UNIVERSAL_SYNC_PATH,
                                        payload
                                        )
                        );
                    }
                }

            } catch (Exception e) {
                Log.e(TAG, "同步状态到手表失败", e);
            }
        }).start();
    }
}
