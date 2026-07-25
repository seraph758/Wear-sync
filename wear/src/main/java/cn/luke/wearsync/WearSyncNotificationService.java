package cn.luke.wearsync;

import android.content.Context;
import android.service.notification.NotificationListenerService;
import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.util.List;

// 文件名：WearSyncNotificationService.java
public class WearSyncNotificationService extends NotificationListenerService {
    private static final String TAG = "WearSync_WearNotification";

    @Override
    public void onInterruptionFilterChanged(int interruptionFilter) {
        super.onInterruptionFilterChanged(interruptionFilter);
        // ✅ 只做一件事：转发事件给 Manager
        // 注意：这里我们调用的是 DndManager 的一个新方法，专门处理本地变化
        WearSyncDndManager.onLocalDndChanged(this, interruptionFilter);
    }
}
