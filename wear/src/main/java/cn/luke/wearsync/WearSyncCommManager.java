package cn.luke.wearsync;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.wear.remote.interactions.RemoteActivityHelper;

import com.google.android.gms.tasks.Task;
import com.google.android.gms.wearable.CapabilityClient;
import com.google.android.gms.wearable.ChannelClient;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 信令发送管理器 (Command Sender) - 兼容修复版
 * 职责：仅负责向手机端发送控制指令与打开通道。
 * 注意：已移除所有消息接收逻辑，接收统一由 WearSyncListenerService 处理。
 */
@SuppressWarnings({"unused", "SpellChecking"})
public class WearSyncCommManager {

    private static final String TAG = "WearSyncCommManager";
    private static final String UNIVERSAL_SYNC_PATH = "/wear-universal-sync";
    private static volatile WearSyncCommManager instance;
    private static final String CAPABILITY_NAME = "wear_sync";

    private final Context appContext;
    private final ExecutorService executor;
    private final ChannelClient channelClient;
    private Node connectedNode;

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

    // ✅ connect() 已删除：构造函数已调用 refreshConnectedNode()，无需冗余包装

    /**
     * 保留 sendBusinessCommand 方法
     */
    public void sendBusinessCommand(@NonNull String type, @NonNull String action) {
        sendCommand(type, action, null);
    }

    /**
     * 带额外参数的业务指令发送
     */
    public void sendBusinessCommand(@NonNull String type, @NonNull String action, Object... extras) {
        try {
            JSONObject extraJson = null;
            if (extras != null && extras.length > 0) {
                extraJson = new JSONObject();
                for (int i = 0; i < extras.length; i += 2) {
                    extraJson.put((String) extras[i], extras[i + 1]);
                }
            }
            sendCommand(type, action, extraJson);
        } catch (Exception e) {
            WearLog.e(TAG, "❌ sendBusinessCommand 异常", e);
        }
    }

    /**
     * 保留静态方法 sendDndReverseSync
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
        // ✅ 完全异步，零阻塞，无需 Tasks.await
        Wearable.getCapabilityClient(appContext)
                .getCapability(CAPABILITY_NAME, CapabilityClient.FILTER_REACHABLE)
                .addOnSuccessListener(executor, capabilityInfo -> {
                    // ✅ 移除冗余 null 检查：getNodes() 永远返回非 null Set
                    if (!capabilityInfo.getNodes().isEmpty()) {
                        connectedNode = capabilityInfo.getNodes().iterator().next();
                        WearLog.d(TAG, "✅ 已缓存连接节点: " + connectedNode.getDisplayName());
                    } else {
                        connectedNode = null;
                        WearLog.w(TAG, "⚠️ 无具备 '" + CAPABILITY_NAME + "' 能力的可达节点");
                    }
                })
                .addOnFailureListener(executor, e -> {
                    connectedNode = null;
                    WearLog.e(TAG, "❌ 获取连接节点失败", e);
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
                payload.put("source", "wear_dnd_change"); // ✅ 新增：明确来源，用于手机端回环拦截
                payload.put("type", type);
                payload.put("action", action);
                payload.put("timestamp", System.currentTimeMillis());
                if (extra != null) {
                    Iterator<String> keys = extra.keys();
                    while (keys.hasNext()) {
                        String key = keys.next();
                        payload.put(key, extra.get(key));
                    }
                }

                byte[] data = payload.toString().getBytes(StandardCharsets.UTF_8);
                Task<Integer> task = Wearable.getMessageClient(appContext)
                        .sendMessage(connectedNode.getId(), UNIVERSAL_SYNC_PATH, data);
                // ✅ Statement lambda → Expression lambda
                task.addOnSuccessListener(_ ->
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

                RemoteActivityHelper helper = new RemoteActivityHelper(appContext, executor);
                // ✅ Statement lambda → Expression lambda
                helper.startRemoteActivity(intent).addListener(() ->
                                WearLog.w(TAG, "✨ [成功] 手机端远程 Activity 唤醒请求已被确认"),
                        executor);
            } catch (Exception e) {
                WearLog.e(TAG, "🔴 openPhoneCamera 初始化失败", e);
            }
        });
    }

    /**
     * 通道打开方法 - 供拍照/日志/文件传输模块使用
     */
    public void openChannel(String channelPath) {
        if (connectedNode == null) {
            WearLog.w(TAG, "⚠️ 打开通道失败 [" + channelPath + "]：节点未连接");
            return;
        }
        WearLog.d(TAG, "🔗 正在请求打开通道: " + channelPath);

        channelClient.openChannel(connectedNode.getId(), channelPath)
                // ✅ Statement lambda → Expression lambda
                .addOnSuccessListener(_ ->
                        WearLog.d(TAG, "✅ 通道已打开: " + channelPath))
                .addOnFailureListener(e ->
                        WearLog.e(TAG, "❌ 打开通道失败 [" + channelPath + "]: " + e.getMessage(), e));
    }

    /**
     * DND 状态同步 - 供 DND 维护模块使用
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

    /**
     * 生命周期清理 - 应在 Application.onDestroy 或主 Service 销毁时调用
     */
    public void shutdown() {
        executor.shutdown();
        instance = null;
    }
}