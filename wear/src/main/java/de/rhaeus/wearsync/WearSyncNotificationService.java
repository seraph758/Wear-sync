package de.rhaeus.wearsync;

import android.app.NotificationManager;
import android.content.Context;
import android.service.notification.NotificationListenerService;
import android.util.Log;
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
                    Wearable.getMessageClient(context).sendMessage(node.getId(), UNIVERSAL_SYNC_PATH, data);
                    Log.d(TAG, "📤 手表反向同步勿扰状态到手机成功: " + interruptionFilter);
                }
            } catch (Exception e) {

    Log.e(
        TAG,
        "★★★★ SEND FAIL ★★★★",
        e
    );
} {
                Log.e(TAG, "手表投递反向勿扰信令失败", e);
            }
        }).start();
    }

     @Override
    public void onInterruptionFilterChanged(int interruptionFilter) {
        super.onInterruptionFilterChanged(interruptionFilter);
        Log.e(TAG,
            "★★★★ DND CHANGE ★★★★ "
                    + interruptionFilter);
        // 🔒 如果是收到手机指令导致的改变，直接拦截，绝不回传给手机！打破死循环！
        if (isInternalUpdate) {
            Log.d(TAG, "🛑 判定为手机同步引起的内部修改，阻止反向回传。");
            return;
        }

        Log.d(TAG, "⌚ 手表系统勿扰触发变更: " + interruptionFilter);
        sendDndReverseSyncToPhone(this, interruptionFilter);
    }
}