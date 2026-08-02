package cn.luke.wearsync;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.IBinder;
import android.util.Size;
import android.view.Surface;
import androidx.annotation.NonNull;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.Preview;
import androidx.camera.core.SurfaceRequest;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;
import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.CapabilityClient; // ✅ 修复1: 添加缺失的导入
import com.google.android.gms.wearable.ChannelClient;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;
import com.google.common.util.concurrent.ListenableFuture;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 远程相机服务 (面向 Android 16+ 优化版)
 * 移除了所有低版本兼容代码，专注于现代 Android 前台服务规范。
 */
public class PhoneSyncCameraService extends Service implements LifecycleOwner {
    private static final String TAG = "WearSync_CameraService";
    // --- 常量定义 ---
    private static PhoneSyncCameraService sInstance;
    public static final String ACTION_START_CAMERA = "cn.luke.wearsync.action.START_CAMERA";
    public static final String ACTION_STOP_CAMERA = "cn.luke.wearsync.action.STOP_CAMERA";
    public static final String EXTRA_NODE_ID = "node_id";
    private static final String CHANNEL_ID = "camera_service_channel";
    private static final int NOTIFICATION_ID = 101;
    private static final int PENDING_INTENT_REQUEST_CODE = 0;
    // --- 视频流传输相关 ---
    private static final String CAMERA_STREAM_PATH = "/wear-universal-sync/camera";
    private MediaCodec mEncoder;
    private Surface mEncoderInputSurface; // <--- 新增: 持有 Surface 引用以便安全管理
    private Thread mEncoderThread;
    private volatile boolean mIsStreaming = false;
    private final ExecutorService mNodeExecutor = Executors.newSingleThreadExecutor();
    // --- 生命周期管理 ---
    private final LifecycleRegistry lifecycleRegistry = new LifecycleRegistry(this);

    @Override
    public Lifecycle getLifecycle() {
        return lifecycleRegistry;
    }

    // --- 服务生命周期 ---
    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE);
        // Android 16+ 必须立即创建渠道并启动前台服务
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (ACTION_START_CAMERA.equals(intent.getAction())) {
            // ✅ 优先从 State 读取（与日志模块保持一致）
            String nodeId = WearSyncState.getNodeId(this);
            if (nodeId != null) {
                PhoneLog.d(TAG, "✅ 从 State 命中缓存节点: " + nodeId);
                startStreaming(nodeId); // ✅ 修复2: 调用已有的 startStreaming 方法
            } else {
                // ✅ State 为空时，采用与手表端相同的异步兜底策略
                PhoneLog.w(TAG, "⚠️ State 无缓存，启动异步节点发现...");
                Wearable.getCapabilityClient(this)
                    .getCapability("wear_sync", CapabilityClient.FILTER_REACHABLE)
                    .addOnSuccessListener(capabilityInfo -> {
                        if (!capabilityInfo.getNodes().isEmpty()) {
                            String fallbackNodeId = capabilityInfo.getNodes().iterator().next().getId();
                            // 顺便补写 State，避免下次再查
                            WearSyncState.setNodeId(this, fallbackNodeId);
                            PhoneLog.d(TAG, "✅ 异步兜底获取节点成功: " + fallbackNodeId);
                            startStreaming(fallbackNodeId); // ✅ 修复2: 调用已有的 startStreaming 方法
                        } else {
                            PhoneLog.e(TAG, "❌ 异步兜底也未找到可达节点");
                            stopSelf();
                        }
                    })
                    .addOnFailureListener(e -> {
                        PhoneLog.e(TAG, "❌ 异步兜底查询失败", e);
                        stopSelf();
                    });
            }
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        sInstance = null;
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY);
        stopStreaming();
        stopForeground(STOP_FOREGROUND_REMOVE);
        mNodeExecutor.shutdown();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // --- 公共方法 ---
    public static PhoneSyncCameraService getInstance() {
        return sInstance;
    }

    public void startStreaming(String nodeId) {
        PhoneLog.d(TAG, "开始推流到节点: " + nodeId);
        initCameraAndEncoder(nodeId);
    }

    public void stopStreaming() {
        PhoneLog.d(TAG, "停止推流");
        mIsStreaming = false;
        if (mEncoderThread != null) {
            mEncoderThread.interrupt();
            try {
                mEncoderThread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            mEncoderThread = null;
        }
        // <--- 修复3: 统一在此处安全释放编码器和 Surface
        if (mEncoder != null) {
            try {
                mEncoder.stop();
            } catch (Exception e) {
                PhoneLog.e(TAG, "停止编码器异常", e);
            }
            mEncoder.release();
            mEncoder = null;
        }
        if (mEncoderInputSurface != null) {
            mEncoderInputSurface.release();
            mEncoderInputSurface = null;
        }
    }

    // --- 私有方法 ---
    /**
     * 创建通知渠道 (Android 16+ 强制要求)
     */
    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
            CHANNEL_ID,
            "相机同步服务",
            NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription("用于保持相机推流服务在后台运行");
        channel.setShowBadge(false);
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.createNotificationChannel(channel);
        }
    }

    /**
     * 构建前台服务通知
     */
    private Notification buildNotification() {
        Intent notificationIntent = new Intent(this, PhoneSyncMainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
            this,
            PENDING_INTENT_REQUEST_CODE,
            notificationIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("相机服务运行中")
            .setContentText("正在同步相机画面...")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build();
    }

        /**
         * 初始化相机和编码器
         */
    private void initCameraAndEncoder(String nodeId) {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();
                Preview preview = new Preview.Builder().build();
                CameraSelector cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;
    
                preview.setSurfaceProvider(surfaceRequest -> {
                    Size resolution = surfaceRequest.getResolution();
                    Size evenResolution = new Size(resolution.getWidth() & ~1, resolution.getHeight() & ~1);
                    mEncoderInputSurface = createEncoderInputSurface(evenResolution);
    
                    if (mEncoderInputSurface != null) {
                        surfaceRequest.provideSurface(mEncoderInputSurface, ContextCompat.getMainExecutor(this), result -> {
                            if (result.getResultCode() == SurfaceRequest.Result.RESULT_SURFACE_USED_SUCCESSFULLY) {
                                PhoneLog.d(TAG, "✅ 相机 Surface 绑定成功，隐私灯应已亮起");
                                // ✅ 【关键】只在相机真正就绪后才启动推流线程
                                mIsStreaming = true;
                                startEncoderThread(nodeId);
                            } else {
                                PhoneLog.e(TAG, "❌ Surface 绑定失败: " + result.getResultCode());
                                if (mEncoderInputSurface != null) {
                                    mEncoderInputSurface.release();
                                    mEncoderInputSurface = null;
                                }
                                stopSelf();
                            }
                        });
                    } else {
                        surfaceRequest.willNotProvideSurface();
                        stopSelf();
                    }
                });
    
                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(this, cameraSelector, preview);
                // ❌ 删除这里的 startEncoderThread 调用！它太早了！
    
            } catch (ExecutionException | InterruptedException e) {
                PhoneLog.e(TAG, "相机初始化失败", e);
                Thread.currentThread().interrupt();
                stopSelf();
            }
        }, ContextCompat.getMainExecutor(this));
    }


    /**
     * 创建编码器输入 Surface
     */
    private Surface createEncoderInputSurface(Size size) {
        try {
            mEncoder = MediaCodec.createEncoderByType("video/avc");
            MediaFormat format = MediaFormat.createVideoFormat("video/avc", size.getWidth(), size.getHeight());
            format.setInteger(MediaFormat.KEY_BIT_RATE, 2000000);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
            mEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            Surface surface = mEncoder.createInputSurface();
            mEncoder.start(); // ✅ 必须 start
            PhoneLog.d(TAG, "✅ 编码器已启动");
            return surface;
        } catch (IOException e) {
            PhoneLog.e(TAG, "创建编码器失败", e);
            return null;
        }
    }

    /**
     * 启动编码器读取和发送线程
     */
    private void startEncoderThread(String nodeId) {
        mEncoderThread = new Thread(() -> {
            Node watchNode = null;
            // 1. 获取手表节点
            try {
                List<Node> nodes = Tasks.await(Wearable.getNodeClient(this).getConnectedNodes());
                for (Node node : nodes) {
                    if (node.getId().equals(nodeId) && node.isNearby()) {
                        watchNode = node;
                        break;
                    }
                }
                if (watchNode == null) {
                    PhoneLog.e(TAG, "未找到目标手表节点");
                    return;
                }
            } catch (Exception e) {
                PhoneLog.e(TAG, "获取节点失败", e);
                return;
            }
            // 2. 打开数据通道
            ChannelClient.Channel streamChannel = null;
            try {
                streamChannel = Tasks.await(Wearable.getChannelClient(this).openChannel(watchNode.getId(), CAMERA_STREAM_PATH));
                PhoneLog.d(TAG, "视频流通道已打开: " + streamChannel.getPath());
            } catch (Exception e) {
                PhoneLog.e(TAG, "打开视频流通道失败", e);
                return;
            }
            // 3. 获取输出流并开始循环读取编码器数据
            try (OutputStream outputStream = Tasks.await(Wearable.getChannelClient(this).getOutputStream(streamChannel))) {
                MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
                while (mIsStreaming && !Thread.currentThread().isInterrupted()) {
                    int outputBufferId = mEncoder.dequeueOutputBuffer(bufferInfo, 10000);
                    if (outputBufferId >= 0) {
                        ByteBuffer outputBuffer = mEncoder.getOutputBuffer(outputBufferId);
                        if (outputBuffer != null && bufferInfo.size > 0) {
                            // 跳过 SPS/PPS 配置帧（如需在手表端解码，建议保留或通过独立消息发送）
                            if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                                bufferInfo.size = 0;
                            }
                            if (bufferInfo.size != 0) {
                                outputBuffer.position(bufferInfo.offset);
                                outputBuffer.limit(bufferInfo.offset + bufferInfo.size);
                                byte[] data = new byte[bufferInfo.size];
                                outputBuffer.get(data);
                                outputStream.write(data);
                                outputStream.flush();
                            }
                        }
                        mEncoder.releaseOutputBuffer(outputBufferId, false);
                    }
                }
            } catch (Exception e) {
                if (mIsStreaming) {
                    PhoneLog.e(TAG, "视频流发送异常", e);
                }
            } finally {
                // 关闭通道
                if (streamChannel != null) {
                    try {
                        Tasks.await(Wearable.getChannelClient(this).close(streamChannel));
                    } catch (Exception ignored) {}
                }
            }
        });
        mEncoderThread.start();
    }
}
