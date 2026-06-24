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
            // 🔋 模组一：勿扰状态逆向同步
            // ==========================================
            if ("dnd".equalsIgnoreCase(type)) {
                int value = json.has("dnd_profile_value") 
                        ? json.optInt("dnd_profile_value", -1) 
                        : json.optInt("dnd_state", -1);

                if (value == -1) {
                    PhoneLog.w(TAG, "⚠️ [勿扰信令异常] 勿扰状态解析值为 -1，放弃执行");
                    return;
                }

                PhoneLog.d(TAG, "🌓 [勿扰核心流转] 手表请求同步勿扰状态 ➔ 目标系统值: " + value);
                isInternalUpdate = true;

                NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                if (nm != null && nm.isNotificationPolicyAccessGranted()) {
                    nm.setInterruptionFilter(value);
                    PhoneLog.d(TAG, "✨ [勿扰核心流转] 手机系统勿扰成功变更为: " + value);
                } else {
                    PhoneLog.w(TAG, "⚠️ [勿扰核心流转] 更改失败：手机端缺乏 NotificationPolicyAccess 权限！");
                }

                // 延迟 1.5 秒解锁，防止双向回环激荡
                new Handler(getMainLooper()).postDelayed(() -> isInternalUpdate = false, 1500);
                return;
            }

            // ==========================================
            // ⏰ 模组二：闹钟远端代点控制
            // ==========================================
            if ("alarm".equalsIgnoreCase(type) || "alarm_action".equalsIgnoreCase(type)) {
                if ("DISMISS".equalsIgnoreCase(action) || "SNOOZE".equalsIgnoreCase(action)) {
                    PhoneLog.d(TAG, "⏰ [闹钟核心流转] 收到手表下发的动作指令 ➔ " + action + "，正在移交 PhoneAlarmManager 跨进程点击...");
                    PhoneAlarmManager.handleWatchCommand(this, action);
                } else {
                    PhoneLog.w(TAG, "⚠️ [闹钟信令异常] 无法识别的闹钟动作: " + action);
                }
                return;
            }

            // ==========================================
            // 📸 模组三：远程相机协议控制
            // ==========================================
            if ("camera".equalsIgnoreCase(type) || "camera_control".equalsIgnoreCase(type)) {
                // 子动作 A：启动手机相机服务
                if ("START_CAMERA".equalsIgnoreCase(action) || "START_CAMERA_UI".equalsIgnoreCase(action)) {
                    String nodeId = WearSyncState.getNodeId(this);

                    if (nodeId == null || nodeId.isEmpty()) {
                        PhoneLog.w(TAG, "⚠️ [相机寻址降级] 当前全局节点 ID 为空，立刻触发多路轮询扫描...");
                        new Thread(() -> {
                            try {
                                List<Node> nodes = Tasks.await(Wearable.getNodeClient(this).getConnectedNodes());
                                if (nodes != null && !nodes.isEmpty()) {
                                    String id = nodes.get(0).getId();
                                    WearSyncState.setNodeId(this, id);
                                    PhoneLog.d(TAG, "🔍 [相机寻址成功] 在线探测到可用节点: " + id + "，准备远程穿透 Activity...");
                                    executeRemoteActivityLaunch(id);
                                } else {
                                    PhoneLog.w(TAG, "❌ [相机寻址断联] 未能扫描到任何处于连线状态的手表节点！");
                                }
                            } catch (Exception e) {
                                PhoneLog.e(TAG, "🔴 [相机寻址崩溃] 在线轮询节点遭遇致命异常: " + e.getMessage(), e);
                            }
                        }).start();
                    } else {
                        PhoneLog.d(TAG, "⚡ [相机寻址命中] 命中活跃手表缓存: " + nodeId + "，直接启动远程穿透...");
                        executeRemoteActivityLaunch(nodeId);
                    }
                }
                // 子动作 B：停止手机相机服务
                else if ("STOP_CAMERA".equalsIgnoreCase(action) || "FORCE_QUIT_CAMERA".equalsIgnoreCase(action)) {
                    PhoneLog.d(TAG, "🛑 [相机核心流转] 收到手表退出指令，正在向 PhoneSyncCameraService 发送安全关闭中断命令...");
                    Intent stopIntent = new Intent(this, PhoneSyncCameraService.class);
                    stopIntent.setAction("de.rhaeus.wearsync.ACTION_STOP_CAMERA");
                    startService(stopIntent);
                }
                return;
            }

        } catch (Exception e) {
            PhoneLog.e(TAG, "🔴 [信令解析失败] 处理手錶底层密文或业务流转时发生异常: " + e.getMessage(), e);
        }
    }

    /**
     * 🛰️ 通过谷歌穿透引擎 (RemoteActivityHelper) 强制唤醒手表的配对拍照 Activity
     */
    private void executeRemoteActivityLaunch(String nodeId) {
        PhoneLog.d(TAG, "🚀 [穿透发射中] 准备通过微端通道穿透启动远端手表 Activity -> 目标节点: " + nodeId);
        try {
            androidx.wear.remote.interactions.RemoteActivityHelper helper =
                    new androidx.wear.remote.interactions.RemoteActivityHelper(this, REMOTE_EXECUTOR);

            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setData(android.net.Uri.parse("wearsync://camera/"));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK 
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP 
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);

            helper.startRemoteActivity(intent, nodeId).addListener(() -> {
                PhoneLog.d(TAG, "✨ [穿透成功] 谷歌微端底层确认：wearsync://camera/ 远程跳板指令已成功送达目标手表节点");
            }, REMOTE_EXECUTOR);

        } catch (Exception e) {
            PhoneLog.e(TAG, "🔴 [穿透失败] 调用 RemoteActivityHelper 发生致命阻断: " + e.getMessage(), e);
        }
    }

    /**
     * 🛰️ 同步系统配置掩码至手表
     */
    public static void sendStatusMaskToWatch(Context context, boolean dndOn, boolean vibrateOn, boolean sleepLinkOn, boolean powerSaveLinkOn) {
        if (isInternalUpdate) {
            return;
        }

        PhoneLog.d(TAG, "🛰️ [掩码广播启动] 准备异步打包当前面板状态掩码并发送至手表...");
        new Thread(() -> {
            try {
                JSONObject json = new JSONObject();
                json.put("sender", "phone");
                json.put("type", "status_mask");
                
                byte[] data = json.toString().getBytes(StandardCharsets.UTF_8);
                String nodeId = WearSyncState.getNodeId(context);

                if (nodeId != null && !nodeId.isEmpty()) {
                    Tasks.await(Wearable.getMessageClient(context).sendMessage(nodeId, UNIVERSAL_SYNC_PATH, data));
                    PhoneLog.d(TAG, "🚀 [掩码广播成功] 状态掩码已精准推送到手表节点: " + nodeId);
                } else {
                    PhoneLog.w(TAG, "⚠️ [掩码广播失败] 活跃手表車牌號緩存為空，放弃本次狀態掩碼推送");
                }
            } catch (Exception e) {
                PhoneLog.e(TAG, "🔴 [掩码广播异常] 异步向通道推送当前面板掩码失败: " + e.getMessage(), e);
            }
        }).start();
    }
}
