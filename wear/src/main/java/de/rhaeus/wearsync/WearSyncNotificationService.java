package de.rhaeus.wearsync;

import android.content.Context;
import android.service.notification.NotificationListenerService;
import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class WearSyncNotificationService extends NotificationListenerService {
    private static final String TAG = "WearSync_WearNotification";
    private static final String UNIVERSAL_SYNC_PATH = "/wear-universal-sync";
    public static boolean isInternalUpdate = false;

    public static void sendDndReverseSyncToPhone(Context context, int interruptionFilter) {
        new Thread(() -> {
            try {
                JSONObject json = new JSONObject();
                json.put("sender", "wear");
                json.put("type", "dnd");
                json.put("dnd_profile_value", interruptionFilter);
                json.put("timestamp", System.currentTimeMillis());

                byte[] data = json.toString().getBytes(StandardCharsets.UTF_8);
                List<Node> nodes = Tasks.await(Wearable.getNodeClient(context).getConnectedNodes());
                
                for (Node node : nodes) {
                    Tasks.await(Wearable.getMessageClient(context).sendMessage(node.getId(), UNIVERSAL_SYNC_PATH, data));
                }
                WearLog.d(TAG, "🚀 [逆向同步成功] 手表勿扰改变，已反向通知手机 ➔ " + interruptionFilter);
            } catch (Exception e) {
                WearLog.e(TAG, "❌ 手表投递反向勿扰信令失败", e);
            }
        }).start();
    }

    @Override
    public void onInterruptionFilterChanged(int interruptionFilter) {
        super.onInterruptionFilterChanged(interruptionFilter);
        if (isInternalUpdate) {
            WearLog.d(TAG, "🔒 判定内部流转激荡锁，跳过反向循环轰炸");
            return;
        }
        sendDndReverseSyncToPhone(this, interruptionFilter);
    }
}
