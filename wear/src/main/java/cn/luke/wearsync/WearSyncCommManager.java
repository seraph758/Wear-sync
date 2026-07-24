package cn.luke.wearsync;

import android.content.Context;
import android.net.Uri;
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

/**
 * 全局统一通信管理器
 * 职责：连接维护、通用信令收发、多路通道管理
 */
public class WearSyncCommManager implements MessageClient.OnMessageReceivedListener {

    private static final String TAG = "WearSync_Comm";
    private static final String UNIVERSAL_SYNC_PATH = "/wear-universal-sync";
    
    private static volatile WearSyncCommManager instance;
    private final Context appContext;
    private final MessageClient messageClient;
    private final ChannelClient channelClient;
    private final ExecutorService executor;
    private Node connectedNode;

    // 👇 私有构造，全局单例
    private WearSyncCommManager(Context context) {
        this.appContext = context.getApplicationContext();
        this.messageClient = Wearable.getMessageClient(appContext);
        this.channelClient = Wearable.getChannelClient(appContext);
        this.executor = Executors.newSingleThreadExecutor();
        this.messageClient.addListener(this);
        WearLog.i(TAG, "🔧 全局通信管理器已初始化");
    }

    public static WearSyncCommManager getInstance(Context context) {
        if (instance == null) {
            synchronized (WearSyncCommManager.class) {
                if (instance == null) {
                    instance = new WearSyncCommManager(context);
                }
            }
        }
        return instance;
    }

    // ==================== 1. 通用信令发送 ====================

    /**
     * ✅ 核心：完全通用的信令发送接口
     * @param type   业务类型标识，如 "camera_control", "sensor_data", "notification"
     * @param action 具体动作，如 "START_STREAM", "TAKE_PHOTO"
     * @param extra  附加参数，可为 null
     */
    public void sendCommand(String type, String action, JSONObject extra) {
        if (connectedNode == null) {
            WearLog.w(TAG, "⚠️ 发送命令失败 [type=" + type + ", action=" + action + "]：节点未连接");
            return;
        }

        executor.execute(() -> {
            try {
                JSONObject json = new JSONObject();
                json.put("sender", "wear");
                json.put("type", type);       // 👈 业务类型由调用方决定
                json.put("action", action);   // 👈 具体动作由调用方决定
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

    // ==================== 2. 通用通道管理 ====================

    /**
     * ✅ 打开通用数据通道（视频流、文件传输等都走这里）
     * @param channelPath 通道路径标识，如 "/wear-video-stream", "/wear-file-transfer"
     */
    public void openChannel(String channelPath, ChannelClient.OpenChannelResultCallback callback) {
        if (connectedNode == null) {
            WearLog.w(TAG, "⚠️ 打开通道失败 [" + channelPath + "]：节点未连接");
            return;
        }
        WearLog.d(TAG, "🔗 正在请求打开通道: " + channelPath);
        channelClient.openChannel(connectedNode.getId(), channelPath, callback);
    }

    // ==================== 3. 连接管理 & 消息接收 ====================
    
    public void connect() { /* 与之前相同，查找并缓存 Node */ }
    public void disconnect() { /* 清理资源 */ }

    @Override
    public void onMessageReceived(@NonNull MessageEvent event) {
        // 👈 收到手机消息后，同样通过 LocalBroadcast 按 type 分发
        // 不在此处做任何业务处理，只做解析和转发
        if (UNIVERSAL_SYNC_PATH.equals(event.getPath())) {
            String raw = new String(event.getData(), StandardCharsets.UTF_8);
            WearLog.d(TAG, "📥 收到手机端消息: " + raw);
            
            try {
                JSONObject json = new JSONObject(raw);
                String type = json.optString("type", "");
                String action = json.optString("action", "");
                
                Intent intent = new Intent("cn.luke.wearsync.COMM_MESSAGE_RECEIVED");
                intent.putExtra("type", type);
                intent.putExtra("action", action);
                intent.putExtra("raw", raw);
                LocalBroadcastManager.getInstance(appContext).sendBroadcast(intent);
                
            } catch (JSONException e) {
                WearLog.e(TAG, "🔴 解析手机端消息失败", e);
            }
        }
    }
}
