package cn.luke.wearsync;

import android.content.Context;
import androidx.annotation.NonNull;
import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.Channel;
import com.google.android.gms.wearable.ChannelClient;
import com.google.android.gms.wearable.MessageClient;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import android.content.Intent;
import android.net.Uri;
import androidx.wear.remote.interactions.RemoteActivityHelper;
import com.google.common.util.concurrent.ListenableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;


/**
 * 全局统一通信管理器
 * 职责：连接维护、通用信令收发、多路通道管理
 */
public class WearSyncCommManager implements MessageClient.OnMessageReceivedListener {
    private static final String TAG = "WearSyncCommManager";
    
    // 通用同步路径，用于手表->手机的DND反向同步
    private static final String UNIVERSAL_SYNC_PATH = "/wear-universal-sync";
    
    // 业务路径，用于手机->手表的指令分发
    private static final String PATH_DND = "/dnd_sync";
    private static final String PATH_ALARM = "/alarm_control";
    private static final String PATH_CAMERA = "/camera_control";

    private static volatile WearSyncCommManager instance;
    private final Context appContext;
    private final MessageClient messageClient;
    private final ChannelClient channelClient;
    private final ExecutorService executor;
    private Node connectedNode;
    private final Executor executor = Executors.newSingleThreadExecutor();
    

    // ✅ 防循环标记，统一由 CommManager 管理
    private static volatile boolean isInternalUpdate = false;

    public interface ConnectionListener {
        void onConnected(Node node);
        void onDisconnected();
    }

    private ConnectionListener connectionListener;

    // 👇 私有构造，全局单例
    private WearSyncCommManager(Context context) {
        this.appContext = context.getApplicationContext();
        this.messageClient = Wearable.getMessageClient(appContext);
        this.channelClient = Wearable.getChannelClient(appContext);
        this.executor = Executors.newSingleThreadExecutor();
        this.messageClient.addListener(this);
        WearLog.i(TAG, "🔧 全局通信管理器已初始化");
    }

    public static synchronized WearSyncCommManager getInstance(Context context) {
        if (instance == null) {
            instance = new WearSyncCommManager(context);
        }
        return instance;
    }

    // ==================== 1. 通用信令发送 ====================

    /**
     * ✅ 核心：完全通用的信令发送接口
     * @param type 业务类型标识，如 "camera_control", "sensor_data", "notification"
     * @param action 具体动作，如 "START_STREAM", "TAKE_PHOTO"
     * @param extra 附加参数，可为 null
     */
    public void sendCommand(String type, String action, JSONObject extra) {
  WearLog.d(TAG, "📤 准备发送命令: type=" + type + ", action=" + action 
        + ", nodeId=" + (connectedNode != null ? connectedNode.getId() : "NULL")
        + ", path=" + UNIVERSAL_SYNC_PATH);
    
        if (connectedNode == null) {
            WearLog.w(TAG, "⚠️ 发送命令失败 [type=" + type + ", action=" + action + "]：节点未连接");
            return;
        }
        executor.execute(() -> {
            try {
                JSONObject json = new JSONObject();
                json.put("sender", "wear");
                json.put("type", type);
                json.put("action", action);
                json.put("timestamp", System.currentTimeMillis());
                if (extra != null) {
                    json.put("extra", extra);
                }
                byte[] payload = json.toString().getBytes(StandardCharsets.UTF_8);
                Tasks.await(messageClient.sendMessage(
                        connectedNode.getId(), UNIVERSAL_SYNC_PATH, payload));
                WearLog.d(TAG, "📤 命令已发送: type=" + type + ", action=" + action);
            } catch (Exception e) {
                WearLog.e(TAG, "🔴 发送命令失败: " + e.getMessage(), e);
            }
        });
    }
    // 在 WearSyncCommManager.java 的末尾，添加以下两个方法
// ============================================================

/**
 * ✅ 专用方法：通知手机关闭闹钟
 * 封装了 "alarm_action" 和 "DISMISS" 这两个魔法字符串
 */
public void dismissPhoneAlarm() {
    sendCommand("alarm_action", "DISMISS", null);
}

/**
 * ✅ 专用方法：通知手机延后闹钟
 * 封装了 "alarm_action" 和 "SNOOZE" 这两个魔法字符串
 */
public void snoozePhoneAlarm() {
    sendCommand("alarm_action", "SNOOZE", null);
}
    

    // ==================== 2. 通用通道管理 ====================

    /**
     * ✅ 打开通用数据通道（视频流、文件传输等都走这里）
     * 修正：使用 Task 链式调用替代不存在的 Listener 参数
     * @param channelPath 通道路径标识，如 "/wear-video-stream", "/wear-file-transfer"
     */
        /**
     * ✅ 拉起手机端相机 Activity 的专用方法
     * 严格对齐手机端 handleCamera 中的 action 匹配条件：
     * "START_CAMERA" / "START_CAMERA_UI" / "open_phone_camera"
     */
    public void launchPhoneCameraActivity() {
        // type 必须是小写 "camera" 或 "camera_control"，与手机端 routeMessage 的 case 一致
        // action 必须是 "START_CAMERA"，与手机端 handleCamera 中的 equalsIgnoreCase 一致
        sendCommand("camera_control", "START_CAMERA", null);
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
                    // TODO: 在这里处理通道打开后的逻辑，例如开始传输日志或流媒体
                    // 可以调用 channelClient.getOutputStream(channel) 获取输出流
                })
                .addOnFailureListener(e -> {
                    WearLog.e(TAG, "❌ 打开通道失败 [" + channelPath + "]: " + e.getMessage(), e);
                });
    }

    // ==================== 3. 连接管理 & 消息接收 ====================

    public void connect() {
        executor.execute(() -> {
            try {
                List<Node> nodes = Tasks.await(Wearable.getNodeClient(appContext).getConnectedNodes());
                if (!nodes.isEmpty()) {
                    connectedNode = nodes.get(0);
                    WearLog.i(TAG, "✅ 节点已连接: " + connectedNode.getDisplayName());
                    if (connectionListener != null) {
                        connectionListener.onConnected(connectedNode);
                    }
                }
            } catch (Exception e) {
                WearLog.e(TAG, "连接检查失败", e);
            }
        });
    }

    public void disconnect() {
        /* 清理资源 */
    }

    // 新增：注册连接监听
    public void setConnectionListener(ConnectionListener listener) {
        this.connectionListener = listener;
        // 注册时如果已经连上了，直接回调一次
        if (connectedNode != null && listener != null) {
            listener.onConnected(connectedNode);
        }
    }

    // 新增：专门给业务用的发送方法（简化参数）
    public void sendBusinessCommand(String type, String action) {
        sendCommand(type, action, null);
    }

    /**
     * ✅ 反向同步唯一出口：供 NotificationService 调用
     * 将手表本地的 DND 变化同步给手机
     */
    public static void sendDndReverseSync(Context context, int interruptionFilter) {
        if (isInternalUpdate) {
            WearLog.d(TAG, "🔒 内部同步触发，跳过反向通知");
            return;
        }

        getInstance(context).executor.execute(() -> {
            try {
                JSONObject json = new JSONObject();
                json.put("sender", "wear");
                json.put("type", "dnd");
                json.put("dnd_profile_value", interruptionFilter);
                json.put("timestamp", System.currentTimeMillis());

                byte[] data = json.toString().getBytes(StandardCharsets.UTF_8);
                List<Node> nodes = Tasks.await(Wearable.getNodeClient(context).getConnectedNodes());
                for (Node node : nodes) {
                    Tasks.await(Wearable.getMessageClient(context)
                            .sendMessage(node.getId(), UNIVERSAL_SYNC_PATH, data));
                }
                WearLog.d(TAG, "🚀 [逆向同步] 手表DND=" + interruptionFilter + " 已通知手机");
            } catch (Exception e) {
                WearLog.e(TAG, "❌ 反向同步失败", e);
            }
        });
    }

    @Override
    public void onMessageReceived(@NonNull MessageEvent messageEvent) {
        // 1. 处理来自手机的通用同步消息（反向同步的回应）
        if (UNIVERSAL_SYNC_PATH.equals(messageEvent.getPath())) {
            handleUniversalSyncMessage(messageEvent);
            return;
        }

        // 2. 处理来自手机的业务指令分发
        String path = messageEvent.getPath();
        String data = new String(messageEvent.getData());
        WearLog.d(TAG, "收到业务消息: path=" + path + ", data=" + data);

        try {
            JSONObject json = new JSONObject(data);
            // 根据路径分发给不同的 Manager
            if (PATH_DND.equals(path)) {
                WearSyncDndManager.handleIncomingCommand(appContext, json);
            } else if (PATH_ALARM.equals(path)) {
                WearAlarmActivity.handleIncomingCommand(appContext, json);
            } else if (PATH_CAMERA.equals(path)) {
                WearCameraActivity.handleIncomingCommand(appContext, json);
            }
            // ... 其他路径处理
        } catch (Exception e) {
            WearLog.e(TAG, "解析消息失败", e);
        }
    }

    /**
     * 处理通用同步消息，主要是设置防循环标记
     */
    private void handleUniversalSyncMessage(MessageEvent messageEvent) {
        executor.execute(() -> {
            try {
                String payload = new String(messageEvent.getData(), StandardCharsets.UTF_8);
                JSONObject json = new JSONObject(payload);
                String type = json.optString("type", "");

                if ("dnd".equalsIgnoreCase(type)) {
                    // ✅ 收到手机的DND同步指令，标记为内部更新，防止触发反向同步
                    isInternalUpdate = true;
                    WearSyncDndManager.handleIncomingCommand(appContext, json);

                    // 延迟重置标记，等待系统回调完成
                    new android.os.Handler(android.os.Looper.getMainLooper())
                            .postDelayed(() -> isInternalUpdate = false, 1500);
                }
            } catch (Exception e) {
                WearLog.e(TAG, "❌ 解析通用同步消息失败", e);
            }
        });
    }
    /**
 * 通过 RemoteActivityHelper 拉起手机端相机
 * 替代原有的 MessageClient 发送方式，无需手动获取 nodeId
 */
public void openPhoneCamera() {
    WearLog.w(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
    WearLog.w(TAG, "🚀 [远端相机启动] CommManager 开始准备向手机发送 Camera 拉起请求");
    WearLog.w(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");

    try {
        // ✅ 修正后的代码
RemoteActivityHelper helper = new RemoteActivityHelper(appContext, executor);


        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.addCategory(Intent.CATEGORY_BROWSABLE);
        intent.setPackage("cn.luke.wearsync");
        intent.setData(Uri.parse("wearsync://camera"));

        WearLog.d(TAG, "🛰️ [发射] 正在调用 RemoteActivityHelper.startRemoteActivity()");
        
        ListenableFuture<Void> future = helper.startRemoteActivity(intent);

        future.addListener(() -> {
            try {
                future.get();
                WearLog.w(TAG, "✨ [成功] 手机端远程 Activity 唤醒请求已被 Google 通道确认");
            } catch (Exception e) {
                Throwable cause = e.getCause();
                String errMsg = cause != null 
                    ? cause.getClass().getName() + " : " + cause.getMessage()
                    : e.getClass().getName() + " : " + e.getMessage();
                WearLog.e(TAG, "🔴 [失败] RemoteActivityHelper 回调异常: " + errMsg, e);
            }
        }, executor);

    } catch (Exception e) {
        WearLog.e(TAG, "🔴 [致命异常] RemoteActivityHelper 初始化/调用失败: " + e.getMessage(), e);
    }
}
    
}
