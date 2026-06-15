package de.rhaeus.wearsync;

import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

/**
 * 手机端通知与勿扰模式监听服务
 * 核心修复：无缝补齐静态实例持有与 getInstance() 单例，供独立的闹钟服务动态拉取通知栏列表，对齐闭环。
 */
public class PhoneSyncNotificationService extends NotificationListenerService {
    private static final String TAG = "WearSync_PhoneNotification";

    // 🎯 核心修复：建立安全的静态单例持有
    private static PhoneSyncNotificationService instance;

    public static PhoneSyncNotificationService getInstance() {
        return instance;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this; // 绑定单例
    }

    @Override
    public void onDestroy() {
        if (instance == this) {
            instance = null;
        }
        super.onDestroy();
    }

    @Override
    public void onInterruptionFilterChanged(int interruptionFilter) {
        super.onInterruptionFilterChanged(interruptionFilter);
        Log.d(TAG, "📲 手机系统自身勿扰触发变更: " + interruptionFilter);

        // 如果检测到是由手表端主动发起修改引起的系统回调，立刻拦截，防止来回双向同步死循环
        if (PhoneSyncListenerService.isInternalUpdate) {
            Log.d(TAG, "🛑 判定为手表引起的内部勿扰修改回调，阻止反向同步回传。");
            return;
        }
    }
}
