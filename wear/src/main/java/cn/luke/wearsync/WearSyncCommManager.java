package cn.luke.wearsync;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.wearable.ChannelClient;
import com.google.android.gms.wearable.MessageClient;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.RemoteActivityHelper;
import com.google.android.gms.wearable.Wearable;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 信令发送管理器 (Command Sender)
 * 职责：仅负责向手机端发送控制指令与打开通道，不包含任何消息接收与业务处理逻辑。
 */
public class WearSyncCommManager {

    private static final String TAG = "WearSyncCommManager";
    private static final String UNIVERSAL_SYNC_PATH = "/wear-universal-sync";
    private static volatile WearSyncCommManager instance;

    private final Context appContext;
    private final ExecutorService executor;
    private final ChannelClient channelClient;
    private Node connectedNode;

    private WearSyncCommManager(@NonNull Context context) {
        this.appContext = context.getApplicationContext();
        this.executor = Executors.newSingleThreadExecutor();
        this.channelClient = Wearable.getChannelClient(appContext);
        // 初始化时获取已连接节点
        refreshConnectedNode();
    }

    public static WearSyncCommManager getInstance(@NonNull Context context) {
        if (instance == null) {
            synchronized (WearSyncCommManager.class) {
                if (instance == null) {
                    instance = new WearSyncCommManager(context);
                }
            }
        }
        return instance;
    }

    /**
     * 刷新已连接的节点缓存
     */
    private void refreshConnectedNode() {
        executor.execute(() -> {
            try {
                List<Node> nodes = Wearable.getNodeClient(appContext).getConnectedNodes().getResult();
                if (nodes != null && !nodes.isEmpty()) {
                    connectedNode = nodes.get(0);
                    WearLog.d(TAG, "✅ 已缓存连接节点: " + connectedNode.getDisplayName());
                } else {
                    connectedNode = null;
                    WearLog.w(TAG, "⚠️ 无已连接的手机节点");
                }
            } catch (Exception e) {
                connectedNode = null;
                WearLog.e(TAG, "❌ 获取连接节点失败", e);
            }
        });
    }

    // ======================== 核心发送方法 ========================

    /**
     * 通用信令发送入口（兼容原有字符串参数格式）
     */
    public void sendCommand(@NonNull String type, @NonNull String action, @Nullable JSONObject extra) {
        executor.execute(() -> {
            try {
                if (connectedNode == null) {
                    refreshConnectedNode();
                    if (connectedNode == null) {
                        WearLog.w(TAG, "⚠️ 无已连接节点，信令未发送 [" + type + "/" + action + "]");
                        return;
                    }
                }

                JSONObject payload = new JSONObject();
                payload.put("sender", "wear");
                payload.put("type", type);
                payload.put("action", action);
                payload.put("timestamp", System.currentTimeMillis());
                if (extra != null) {
                    // 将额外参数合并到主payload中
                    java.util.Iterator<String> keys = extra.keys();
                    while (keys.hasNext()) {
                        String key = keys.next();
                        payload.put(key, extra.get(key));
                    }
                }

                byte[] data = payload.toString().getBytes(StandardCharsets.UTF_8);
                Task<Integer> task = Wearable.getMessageClient(appContext)
                        .sendMessage(connectedNode.getId(), UNIVERSAL_SYNC_PATH, data);
                task.addOnSuccessListener(result ->
                        WearLog.d(TAG, "✅ 信令已发送 [" + type + "/" + action + "]"));
                task.addOnFailureListener(e ->
                        WearLog.e(TAG, "❌ 信令发送失败 [" + type + "/" + action + "]: " + e.getMessage(), e));
            } catch (Exception e) {
                WearLog.e(TAG, "❌ sendCommand 异常 [" + type + "/" + action + "]", e);
            }
        });
    }

    /**
     * ✅ 专用方法：通知手机关闭闹钟
     */
    public void dismissPhoneAlarm() {
        sendCommand("alarm_action", "DISMISS", null);
    }

    /**
     * ✅ 专用方法：通知手机延后闹钟
     */
    public void snoozePhoneAlarm() {
        sendCommand("alarm_action", "SNOOZE", null);
    }

    /**
     * ✅ 通知手机端打开相机（通过 RemoteActivityHelper）
     */
    public void openPhoneCamera() {
        executor.execute(() -> {
            try {
                Intent intent = new Intent(Intent.ACTION_VIEW);
                intent.addCategory(Intent.CATEGORY_BROWSABLE);
                intent.setPackage("cn.luke.wearsync");
                intent.setData(Uri.parse("wearsync://camera"));

                RemoteActivityHelper helper = new RemoteActivityHelper(appContext, executor);
                helper.startRemoteActivity(intent).addListener(() -> {
                    try {
                        WearLog.w(TAG, "✨ [成功] 手机端远程 Activity 唤醒请求已被 Google 通道确认");
                    } catch (Exception e) {
                        WearLog.e(TAG, "🔴 [失败] RemoteActivityHelper 回调异常", e);
                    }
                }, executor);
            } catch (Exception e) {
                WearLog.e(TAG, "🔴 openPhoneCamera 初始化失败", e);
            }
        });
    }

    /**
     * ✅ 打开 Wearable Channel（用于日志/流媒体传输）
     */
    public void openChannel(String channelPath) {
        if (connectedNode == null) {
            WearLog.w(TAG, "⚠️ 打开通道失败 [" + channelPath + "]：节点未连接");
            return;
        }
        WearLog.d(TAG, "🔗 正在请求打开通道: " + channelPath);

        channelClient.openChannel(connectedNode.getId(), channelPath)
                .addOnSuccessListener(channel -> {
                    WearLog.d(TAG, "✅ 通道已打开: " + channelPath);
                    // TODO: 在这里处理通道打开后的逻辑，例如开始传输日志或流媒体
                    // 可以调用 channelClient.getOutputStream(channel) 获取输出流
                })
                .addOnFailureListener(e -> {
                    WearLog.e(TAG, "❌ 打开通道失败 [" + channelPath + "]: " + e.getMessage(), e);
                });
    }

    /**
     * 通知手机端同步 DND 状态
     */
    public void syncDndState(boolean enabled) {
        try {
            JSONObject extra = new JSONObject();
            extra.put("enabled", enabled);
            sendCommand("dnd", "SYNC_STATE", extra);
        } catch (Exception e) {
            WearLog.e(TAG, "❌ syncDndState 构建失败", e);
        }
    }

    // ======================== 资源释放 ========================

    public void shutdown() {
        executor.shutdown();
        instance = null;
    }
}
