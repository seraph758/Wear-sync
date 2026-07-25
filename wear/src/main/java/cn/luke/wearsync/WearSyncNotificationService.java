package cn.luke.wearsync;

import android.service.notification.NotificationListenerService;

public class WearSyncNotificationService extends NotificationListenerService {
    private static final String TAG = "WearSync_WearNotification";

    @Override
    public void onInterruptionFilterChanged(int interruptionFilter) {
        super.onInterruptionFilterChanged(interruptionFilter);
        WearLog.d(TAG, "📡 检测到本地 DND 变化: " + interruptionFilter);
        // ✅ 直接通知通信管理器，不经过 DndManager
        WearSyncCommManager.sendDndReverseSync(this, interruptionFilter);
    }
}
