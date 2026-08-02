package cn.luke.wearsync;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import com.google.android.gms.wearable.CapabilityClient;
import com.google.android.gms.wearable.Wearable;

/**
 * 纯后台保活服务
 * 职责：维持前台服务状态，显示通知，并提供节点发现能力。
 * 不再包含任何相机、编码、推流逻辑。
 */
public class PhoneSyncCameraService extends Service {

    private static final String TAG = "WearSync_CameraService";
    public static final String ACTION_START_CAMERA = "cn.luke.wearsync.action.START_CAMERA";
    public static final String ACTION_STOP_CAMERA = "cn.luke.wearsync.action.STOP_CAMERA";
    public static final String EXTRA_SOURCE = "extra_source"; // 接收来自 Activity 的来源信息

    private static final String CHANNEL_ID = "camera_service_channel";
    private static final int NOTIFICATION_ID = 101;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification());
        PhoneLog.d(TAG, "✅ 后台保活服务已启动");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_STICKY;

        String action = intent.getAction();
        if (ACTION_START_CAMERA.equals(action)) {
            // 1. 启动自身为前台服务（已在 onCreate 完成）
            // 2. 发现并缓存节点，为后续的相机操作做准备
            discoverAndCacheNode();
            
            // 注意：真正的相机初始化逻辑现在由 PhoneSyncRemoteCameraActivity 触发
            // 这里的职责仅仅是确保服务存活并准备好节点信息
        } else if (ACTION_STOP_CAMERA.equals(action)) {
            stopSelf();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopForeground(STOP_FOREGROUND_REMOVE);
        PhoneLog.d(TAG, "🛑 后台保活服务已停止");
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // --- 私有方法 ---

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "相机同步服务", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("用于保持服务在后台运行");
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification() {
        Intent notificationIntent = new Intent(this, PhoneSyncMainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, notificationIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("相机服务运行中")
                .setContentText("正在等待相机连接...")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
    }

    /**
     * 发现并缓存节点 ID，供 PhoneSyncRemoteCameraActivity 使用
     */
    private void discoverAndCacheNode() {
        String nodeId = WearSyncState.getNodeId(this);
        if (nodeId != null) {
            PhoneLog.d(TAG, "✅ 从 State 命中缓存节点: " + nodeId);
            return;
        }

        PhoneLog.w(TAG, "⚠️ State 无缓存，启动异步节点发现...");
        Wearable.getCapabilityClient(this)
                .getCapability("wear_sync", CapabilityClient.FILTER_REACHABLE)
                .addOnSuccessListener(capabilityInfo -> {
                    if (!capabilityInfo.getNodes().isEmpty()) {
                        String fallbackNodeId = capabilityInfo.getNodes().iterator().next().getId();
                        WearSyncState.setNodeId(this, fallbackNodeId);
                        PhoneLog.d(TAG, "✅ 异步兜底获取节点成功: " + fallbackNodeId);
                    } else {
                        PhoneLog.e(TAG, "❌ 异步兜底也未找到可达节点");
                    }
                })
                .addOnFailureListener(e -> PhoneLog.e(TAG, "❌ 异步兜底查询失败", e));
    }
}
