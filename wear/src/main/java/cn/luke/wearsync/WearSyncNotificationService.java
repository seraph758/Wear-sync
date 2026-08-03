package cn.luke.wearsync;

import android.service.notification.NotificationListenerService;

/**
 * 纯粹的 DND 状态变化监听器
 * 职责：监听手表本地 DND 变化，直接转发给 CommManager 进行反向同步
 */
public class WearSyncNotificationService extends NotificationListenerService {
    private static final String TAG = "WearSync_WearNotification";

    // 用于防止内部更新导致的循环触发
    public static volatile boolean isInternalUpdate = false;
    public static long lastInternalUpdateTime = 0;

    @Override
    public void onInterruptionFilterChanged(int interruptionFilter) {
        super.onInterruptionFilterChanged(interruptionFilter);
        if (isInternalUpdate) {
            WearLog.d(TAG, "🔒 内部更新，忽略此次 DND 变化");
            return;
        }
        WearLog.d(TAG, "📡 检测到本地 DND 变化: " + interruptionFilter);
        // ✅ 直接传回调参数，不要重新读系统值
        WearSyncCommManager.sendDndReverseSync(this, interruptionFilter);
    }
}
