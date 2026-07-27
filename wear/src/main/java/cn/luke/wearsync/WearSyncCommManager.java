package cn.luke.wearsync;

import android.content.Context;
import android.net.Uri;
import androidx.annotation.NonNull;
import com.google.android.gms.tasks.Task;
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
 * 职责：仅负责向手机端发送控制指令，不包含任何消息接收与业务处理逻辑。
 */
public class WearSyncCommManager {

    private static final String TAG = "WearSyncCommManager";
    private static final String UNIVERSAL_SYNC_PATH = "/wear-universal-sync";
    private static volatile WearSyncCommManager instance;

    private final Context appContext;
    private final ExecutorService executor;

    private WearSyncCommManager(@NonNull Context context) {
        this.appContext = context.getApplicationContext();
        this.executor = Executors.newSingleThreadExecutor();
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

    // ======================== 核心发送方法 ========================

    /**
     * 通用信令发送入口
     */
    public void sendCommand(@NonNull JSONObject payload) {
        executor.execute(() -> {
            try {
                List<Node> nodes = Wearable.getNodeClient(appContext).getConnectedNodes().getResult();
                if (nodes == null || nodes.isEmpty()) {
                    WearLog.w(TAG, "⚠️ 无已连接的手机节点，信令未发送");
                    return;
                }
                byte[] data = payload.toString().getBytes(StandardCharsets.UTF_8);
                for (Node node : nodes) {
                    Task<Integer> task = Wearable.getMessageClient(appContext)
                            .sendMessage(node.getId(), UNIVERSAL_SYNC_PATH, data);
                    task.addOnSuccessListener(result ->
                            WearLog.d(TAG, "✅ 信令已发送至 " + node.getDisplayName()));
                    task.addOnFailureListener(e ->
                            WearLog.e(TAG, "❌ 信令发送失败: " + e.getMessage(), e));
                }
            } catch (Exception e) {
                WearLog.e(TAG, "❌ sendCommand 异常", e);
            }
        });
    }

    /**
     * 通知手机端打开相机（通过 RemoteActivityHelper）
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
     * 通知手机端关闭闹钟
     */
    public void dismissPhoneAlarm(String alarmId) {
        try {
            JSONObject json = new JSONObject();
            json.put("sender", "wear");
            json.put("type", "alarm");
            json.put("action", "DISMISS");
            json.put("alarm_id", alarmId);
            json.put("timestamp", System.currentTimeMillis());
            sendCommand(json);
        } catch (Exception e) {
            WearLog.e(TAG, "❌ dismissPhoneAlarm 构建失败", e);
        }
    }

    /**
     * 通知手机端同步 DND 状态
     */
    public void syncDndState(boolean enabled) {
        try {
            JSONObject json = new JSONObject();
            json.put("sender", "wear");
            json.put("type", "dnd");
            json.put("action", "SYNC_STATE");
            json.put("enabled", enabled);
            json.put("timestamp", System.currentTimeMillis());
            sendCommand(json);
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
