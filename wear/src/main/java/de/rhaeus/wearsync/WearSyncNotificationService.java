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

            List<Node> nodes =
                    Tasks.await(
                            Wearable.getNodeClient(context)
                                    .getConnectedNodes()
                    );

            Log.e(
                    TAG,
                    "★★★★ NODE COUNT ★★★★ "
                            + nodes.size()
            );

            for (Node node : nodes) {

                Log.e(
                        TAG,
                        "★★★★ TRY SEND TO PHONE ★★★★ "
                                + node.getId()
                );

                Tasks.await(
                        Wearable.getMessageClient(context)
                                .sendMessage(
                                        node.getId(),
                                        UNIVERSAL_SYNC_PATH,
                                        data
                                )
                );

                Log.e(
                        TAG,
                        "★★★★ SEND SUCCESS ★★★★ "
                                + interruptionFilter
                );
            }

        } catch (Exception e) {

            Log.e(
                    TAG,
                    "★★★★ SEND FAIL ★★★★",
                    e
            );

        }
    }).start();
}
@Override
public void onInterruptionFilterChanged(int interruptionFilter) {
    super.onInterruptionFilterChanged(interruptionFilter);

    Log.e(
            TAG,
            "★★★★ WATCH DND CHANGED ★★★★ "
                    + interruptionFilter
    );

    if (isInternalUpdate) {

        Log.e(
                TAG,
                "★★★★ INTERNAL UPDATE BLOCKED ★★★★"
        );

        return;
    }

    Log.e(
            TAG,
            "★★★★ SEND DND TO PHONE ★★★★ "
                    + interruptionFilter
    );

    sendDndReverseSyncToPhone(
            this,
            interruptionFilter
    );
}
}