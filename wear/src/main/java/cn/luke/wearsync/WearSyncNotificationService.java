package cn.luke.wearsync;

import android.service.notification.NotificationListenerService;

/**
 * 纯粹的 DND 状态变化监听器
 * 职责：监听手表本地 DND 变化，直接转发给 CommManager 进行反向同步
 */
public class WearSyncNotificationService extends NotificationListenerService {
    private static final String TAG = "WearSync_WearNotification";

    public static volatile boolean isInternalUpdate = false;
    public static long lastInternalUpdateTime = 0; // 🔑 善用這個時間戳

    @Override
    public void onInterruptionFilterChanged(int interruptionFilter) {
        super.onInterruptionFilterChanged(interruptionFilter);

        // 🛡️ 第一重防護：直接檢查布林值鎖
        if (isInternalUpdate) {
            WearLog.d(TAG, "🔒 [防回环] 内部更新标记中，忽略 DND 变化: " + interruptionFilter);
            return;
        }

        // 🛡️ 第二重防護：時間戳防抖 (如果距離上次內部更新不足 5 秒，依然視為內部觸發)
        long timeDiff = System.currentTimeMillis() - lastInternalUpdateTime;
        if (timeDiff < 5000) {
            WearLog.d(TAG, "🔒 [防回环] 距离上次内部同步仅 " + timeDiff + "ms，忽略本次 DND 变化");
            return;
        }

        WearLog.d(TAG, "📡 [手动触发] 检测到本地 DND 变化: " + interruptionFilter + "，发送逆向同步");
        
        // ✅ 安全發送逆向同步
        WearSyncCommManager.sendDndReverseSync(this, interruptionFilter);
    }
}
