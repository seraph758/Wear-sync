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
        
        // ✅ 直接获取 CommManager 实例并通知它
        // 注意：这里需要确保 CommManager 已经初始化
        WearSyncCommManager commManager = WearSyncCommManager.getInstance(this);
        if (commManager.getDndStateListener() != null) {
             commManager.getDndStateListener().onLocalDndChanged(interruptionFilter);
        }
    }
}
