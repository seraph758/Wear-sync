package cn.luke.wearsync;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.wear.remote.interactions.RemoteActivityHelper; // ✅ 修复1: 正确的导入路径
import com.google.android.gms.tasks.Task;
import com.google.android.gms.wearable.ChannelClient;
import com.google.android.gms.wearable.MessageClient;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 信令发送管理器 (Command Sender) - 兼容修复版
 * 职责：仅负责向手机端发送控制指令与打开通道。
 * 注意：已移除所有消息接收逻辑，接收统一由 WearSyncListenerService 处理。
 */
public class WearSyncCommManager {

    private static final String TAG = "WearSyncCommManager";
    private static final String UNIVERSAL_SYNC_PATH = "/wear-universal-sync";
    private static volatile WearSyncCommManager instance;

    private final Context appContext;
    private final ExecutorService executor;
    private final ChannelClient channelClient;
    private Node connectedNode;

    // ✅ 修复2: 保留 ConnectionListener 接口，避免 WearAlarmActivity 报错
    public interface ConnectionListener {
        void onConnected();
        void onDisconnected();
    }
    private ConnectionListener connectionListener;

    private WearSyncCommManager(@NonNull Context context) {
        this.appContext = context.getApplicationContext();
        this.executor = Executors.newSingleThreadExecutor();
        this.channelClient = Wearable.getChannelClient(appContext);
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

    // ======================== 保留的旧接口方法 (避免外部调用报错) ========================

    /**
     * ✅ 修复3: 保留 setConnectionListener
     */
    public void setConnectionListener(ConnectionListener listener) {
        this.connectionListener = listener;
    }

    /**
     * ✅ 修复4: 保留 connect() 方法
     */
    public void connect() {
        refreshConnectedNode();
    }

    /**
     * ✅ 修复5: 保留 sendBusinessCommand 方法
     */
    public void sendBusinessCommand(@NonNull String type, @NonNull String action) {
        sendCommand(type, action, null);
    }

    /**
     * ✅ 修复6: 保留静态方法 sendDndReverseSync
     */
    public static void sendDndReverseSync(@NonNull Context context, int interruptionFilter) {
        try {
            JSONObject extra = new JSONObject();
            extra.put("interruption_filter", interruptionFilter);
            getInstance(context).sendCommand("dnd", "REVERSE_SYNC", extra);
        } catch (Exception e) {
            WearLog.e(TAG, "❌ sendDndReverseSync 构建失败", e);
        }
    }

    // ======================== 核心发送方法 ========================

    private void refreshConnectedNode() {
        executor.execute(() -> {
            try {
                List<Node> nodes = Wearable.getNodeClient(appContext).getConnectedNodes().getResult();
                if (nodes != null && !nodes.isEmpty()) {
                    connectedNode = nodes.get(0);
                    WearLog.d(TAG, "✅ 已缓存连接节点: " + connectedNode.getDisplayName());
                    if (connectionListener != null) connectionListener.onConnected();
                } else {
                    connectedNode = null;
                    WearLog.w(TAG, "⚠️ 无已连接的手机节点");
                    if (connectionListener != null) connectionListener.onDisconnected();
                }
            } catch (Exception e) {
                connectedNode = null;
                WearLog.e(TAG, "❌ 获取连接节点失败", e);
                if (connectionListener != null) connectionListener.onDisconnected();
            }
        });
    }

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

    public void dismissPhoneAlarm() {
        sendCommand("alarm_action", "DISMISS", null);
    }

    public void snoozePhoneAlarm() {
        sendCommand("alarm_action", "SNOOZE", null);
    }

    public void openPhoneCamera() {
        executor.execute(() -> {
            try {
                Intent intent = new Intent(Intent.ACTION_VIEW);
                intent.addCategory(Intent.CATEGORY_BROWSABLE);
                intent.setPackage("cn.luke.wearsync");
                intent.setData(Uri.parse("wearsync://camera"));

                // ✅ 使用正确导入的 RemoteActivityHelper
                RemoteActivityHelper helper = new RemoteActivityHelper(appContext, executor);
                helper.startRemoteActivity(intent).addListener(() -> {
                    try {
                        WearLog.w(TAG, "✨ [成功] 手机端远程 Activity 唤醒请求已被确认");
                    } catch (Exception e) {
                        WearLog.e(TAG, "🔴 [失败] RemoteActivityHelper 回调异常", e);
                    }
                }, executor);
            } catch (Exception e) {
                WearLog.e(TAG, "🔴 openPhoneCamera 初始化失败", e);
            }
        });
    }

    public void openChannel(String channelPath) {
        if (connectedNode == null) {
            WearLog.w(TAG, "⚠️ 打开通道失败 [" + channelPath + "]：节点未连接");
            return;
        }
        WearLog.d(TAG, "🔗 正在请求打开通道: " + channelPath);

        channelClient.openChannel(connectedNode.getId(), channelPath)
                .addOnSuccessListener(channel -> {
                    WearLog.d(TAG, "✅ 通道已打开: " + channelPath);
                })
                .addOnFailureListener(e -> {
                    WearLog.e(TAG, "❌ 打开通道失败 [" + channelPath + "]: " + e.getMessage(), e);
                });
    }

    public void syncDndState(boolean enabled) {
        try {
            JSONObject extra = new JSONObject();
            extra.put("enabled", enabled);
            sendCommand("dnd", "SYNC_STATE", extra);
        } catch (Exception e) {
            WearLog.e(TAG, "❌ syncDndState 构建失败", e);
        }
    }

    public void shutdown() {
        executor.shutdown();
        instance = null;
    }
}
