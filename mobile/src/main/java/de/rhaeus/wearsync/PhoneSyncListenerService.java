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
            // 自动捕获当前处于活跃状态的手表 ID 并刷新全局变量
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
            String type = json.optString("type", "");
            String action = json.optString("action", "");

            // 过滤掉手机自身发出的回环广播数据，防止死循环
            if ("phone".equalsIgnoreCase(sender)) {
                return;
            }

            PhoneLog.d(TAG, "📥 [信令到港] 收到来自手表的底层信令 ➔ type=[" + type + "], action=[" + action + "]");

            // ==========================================
            // 🔋 模组一：勿扰状态逆向同步 (托管至 PhoneDndManager)
            // ==========================================
            if ("dnd".equalsIgnoreCase(type)) {
                int value = json.has("dnd_profile_value") 
                        ? json.optInt("dnd_profile_value", -1) 
                        : json.optInt("dnd_state", -1);

                if (value == -1) {
                    PhoneLog.w(TAG, "⚠️ [勿扰信令异常] 勿扰状态解析值为 -1，放弃执行");
                    return;
                }

                PhoneLog.d(TAG, "🌓 [勿扰核心流转] 收到手表逆向同步请求 ➔ 目标系统值: " + value + "，正在移交 PhoneDndManager...");
                
                // 1. 开启防循环锁
                isInternalUpdate = true;

                // 2. 🔥 移交专属管理器处理手机勿扰的修改
                PhoneDndManager.handleIncomingAction(this, value);

                // 3. 延迟 1.5 秒解锁，防止双向回环激荡
                new Handler(getMainLooper()).postDelayed(() -> isInternalUpdate = false, 1500);
                return;
            }
            // ==========================================
            // ⏰ 模组二：闹钟远端代点控制 (解耦极简版)
            // ==========================================
            if ("alarm".equalsIgnoreCase(type) || "alarm_action".equalsIgnoreCase(type)) {
                PhoneLog.d(TAG, "⏰ [闹钟核心流转] 捕获到手表闹钟信令 ➔ 动作: [" + action + "]，全权移交 PhoneAlarmManager 调度！");
                
                // 🔥 闭着眼睛直接转发，让 AlarmManager 内部去判定 DISMISS 或 SNOOZE
                PhoneAlarmManager.executeAlarmAction(this, action);                
                return;
            }
           // =================================================================
            // 📸 模組三：遠端相機協定控制（全步進日誌極致除錯版）
            // =================================================================
            if ("camera".equalsIgnoreCase(type) || "camera_control".equalsIgnoreCase(type)) {

                if ("CAMERA_READY".equalsIgnoreCase(action)) {

                    PhoneLog.d(TAG, "CAM-P002 收到 CAMERA_READY");
                String nodeId = WearSyncState.getNodeId(this);

                Intent serviceIntent = new Intent(this, PhoneSyncCameraService.class);
                serviceIntent.setAction(PhoneSyncCameraService.ACTION_START_CAMERA);
                startService(serviceIntent);
                
                new Handler(getMainLooper()).postDelayed(() -> {
                
                    if (PhoneSyncCameraService.instance != null) {
                        PhoneSyncCameraService.instance.startStreaming(nodeId);
                    }
                
                },300);
                
                    return;
                }
                PhoneLog.d(TAG, "📸 [相機控制流] ━━━ 接收到相機模組信令 ━━━ 動作類型: [" + action + "]");
                if ("STREAM_START".equalsIgnoreCase(action)) {

                    PhoneLog.d(TAG, "P-020 收到 STREAM_START");
                
                    if (PhoneSyncCameraService.instance != null) {
                
                        String nodeId = WearSyncState.getNodeId(this);
                
                        if (nodeId == null || nodeId.isEmpty()) {
                            PhoneLog.e(TAG, "STREAM_START 时 nodeId 为空");
                            return;
                        }
                        
                        PhoneSyncCameraService.instance.openChannelAndStream(nodeId);
                
                    } else {
                
                        PhoneLog.e(TAG, "CameraService 未启动");
                
                    }
                
                    return;
                }
                // 子動作 A：啟動手機相機服務
                if ("START_CAMERA".equalsIgnoreCase(action) || "START_CAMERA_UI".equalsIgnoreCase(action)) {
                    PhoneLog.d(TAG, "🔍 [相機尋址] 準備獲取手錶節點 ID...");
                    String nodeId = WearSyncState.getNodeId(this);

                    if (nodeId == null || nodeId.isEmpty()) {
                        PhoneLog.w(TAG, "⚠️ [相機尋址降級] 當前全域節點 ID 快存為空！立刻啟動非同步背景多路輪詢掃描...");
                        
                        new Thread(() -> {
                            PhoneLog.d(TAG, "🧵 [背景執行緒] 輪詢執行緒已啟動，正在調用 Wearable.getNodeClient().getConnectedNodes()...");
                            try {
                                List<Node> nodes = Tasks.await(Wearable.getNodeClient(this).getConnectedNodes());
                                
                                if (nodes == null) {
                                    PhoneLog.e(TAG, "❌ [背景尋址異常] 系統返回的連接節點列表 (List<Node>) 為 null！");
                                    return;
                                }
                                
                                PhoneLog.d(TAG, "📊 [背景尋址掃描] 探測結束，當前在線配對的手錶節點數量: " + nodes.size());
                                
                                if (!nodes.isEmpty()) {
                                    String id = nodes.get(0).getId();
                                    String name = nodes.get(0).getDisplayName();
                                    PhoneLog.d(TAG, "🎯 [背景尋址成功] 成功撈到首個活體節點! 名稱: [" + name + "], ID: [" + id + "]");
                                    
                                    PhoneLog.d(TAG, "💾 [背景尋址同步] 正在將新節點 ID 寫入 WearSyncState 快存...");
                                    WearSyncState.setNodeId(this, id);
                                    
                                    PhoneLog.d(TAG, "🚀 [背景尋址轉交] 準備向該節點注入遠端 Activity 穿透指令...");
                                    executeRemoteActivityLaunch(id);
                                } else {
                                    PhoneLog.w(TAG, "❌ [背景尋址斷聯] 掃描完畢，但未發現任何處於藍牙/Wi-Fi 連線狀態的手錶節點！");
                                }
                            } catch (Exception e) {
                                PhoneLog.e(TAG, "🔴 [背景尋址崩潰] 在線異步輪詢手錶節點遭遇致命異常: " + e.getMessage(), e);
                            }
                        }).start();
                    } else {
                        PhoneLog.d(TAG, "⚡ [相機尋址命中] 成功命中活躍手錶快存 ID: [" + nodeId + "]，直接跳過掃描啟動穿透...");
                        JSONObject json = new JSONObject();
                        json.put("sender","phone");
                        json.put("type","camera_control");
                        json.put("action","CAMERA_HANDSHAKE");
                        
                        Wearable.getMessageClient(this).sendMessage(
                                nodeId,
                                UNIVERSAL_SYNC_PATH,
                                json.toString().getBytes(StandardCharsets.UTF_8)
                        );
                        
                        PhoneLog.d(TAG,"CAM-P001 CAMERA_HANDSHAKE 已发送");
                        executeRemoteActivityLaunch(nodeId);
                    }
                } 
                // 子動作 B：停止手機相機服務
                else if ("STOP_CAMERA".equalsIgnoreCase(action) || "FORCE_QUIT_CAMERA".equalsIgnoreCase(action)) {
                    PhoneLog.d(TAG, "🛑 [相機核心流轉] 收到手錶端主動退出指令！準備中斷手機端相機服務...");
                    
                    PhoneLog.d(TAG, "📦 [相機核心流转] 正在建構封裝意圖 ➔ 目的類別: PhoneSyncCameraService.class");
                    Intent stopIntent = new Intent(this, PhoneSyncCameraService.class);
                    
                    PhoneLog.d(TAG, "📦 [相機核心流转] 正在注入 Action 行動暗號: [de.rhaeus.wearsync.ACTION_STOP_CAMERA]");
                    stopIntent.setAction("de.rhaeus.wearsync.ACTION_STOP_CAMERA");
                    
                    PhoneLog.d(TAG, "🚀 [相機核心流转] 正在調用 startService() 向相機服務發送安全關閉中斷命令...");
                    startService(stopIntent);
                    PhoneLog.d(TAG, "✅ [相機核心流转] 中斷指令已成功發射出去。");
                } else {
                    PhoneLog.w(TAG, "⚠️ [相機控制流] 收到未知的相機子動作 action: [" + action + "]，不做任何處理。");
                }
                return;
            }

        } catch (Exception e) {
            PhoneLog.e(TAG, "🔴 [信令解析失敗] 處理相機模組底層密文或業務流轉時發生異常: " + e.getMessage(), e);
        }
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
