package cn.luke.wearsync;

import android.content.Context;
import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.MessageClient;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class WearSyncCommManager implements MessageClient.OnMessageReceivedListener {
    private static final String TAG = "WearSync_CommManager";
    private static final String UNIVERSAL_SYNC_PATH = "/wear-universal-sync";
    
    private static volatile WearSyncCommManager instance;
    private final Context appContext;
    private final ExecutorService executor;
    
    // ✅ 防循环标记统一收口在通信层
    private static volatile boolean isInternalUpdate = false;

    private WearSyncCommManager(Context context) {
        this.appContext = context.getApplicationContext();
        this.executor = Executors.newSingleThreadExecutor();
        Wearable.getMessageClient(appContext).addListener(this);
        WearLog.i(TAG, "🔧 全局通信管理器已初始化");
    }

    public static synchronized WearSyncCommManager getInstance(Context context) {
        if (instance == null) {
            instance = new WearSyncCommManager(context);
        }
        return instance;
    }

    /**
     * ✅ 反向同步唯一出口：供 NotificationService 直接调用
     */
    public static void sendDndReverseSync(Context context, int interruptionFilter) {
        if (isInternalUpdate) {
            WearLog.d(TAG, "🔒 内部同步触发的 DND 变化，跳过反向通知");
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
    public void onMessageReceived(MessageEvent messageEvent) {
        if (!UNIVERSAL_SYNC_PATH.equals(messageEvent.getPath())) return;
        
        executor.execute(() -> {
            try {
                String payload = new String(messageEvent.getData(), StandardCharsets.UTF_8);
                JSONObject json = new JSONObject(payload);
                String type = json.optString("type", "");
                
                if ("dnd".equalsIgnoreCase(type)) {
                    // ✅ 标记为内部更新，防止触发反向同步死循环
                    isInternalUpdate = true;
                    // ✅ 仅将手机发来的指令交给 DndManager 处理
                    WearSyncDndManager.handleIncomingCommand(appContext, json);
                    
                    new android.os.Handler(android.os.Looper.getMainLooper())
                        .postDelayed(() -> isInternalUpdate = false, 1500);
                }
            } catch (Exception e) {
                WearLog.e(TAG, "❌ 解析消息失败", e);
            }
        });
    }
}
