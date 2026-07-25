package cn.luke.wearsync;

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
    public static long lastInternalUpdateTime = 0;

    
    @Override
    public void onInterruptionFilterChanged(int interruptionFilter) {
        super.onInterruptionFilterChanged(interruptionFilter);
        // ✅ 只做一件事：转发事件给 Manager
        WearDndManager.onLocalDndChanged(this, interruptionFilter);
    }
}