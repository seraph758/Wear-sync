package cn.luke.wearsync;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo; // ✅ 新增：前台服务类型定义
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.view.Surface;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.google.android.gms.wearable.ChannelClient;
import com.google.android.gms.wearable.MessageClient;
import com.google.android.gms.wearable.Wearable;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

public class PhoneSyncCameraService extends Service {
    private static final String TAG = "WearSync_CameraSvc";

    // ==================== 对外契约常量 ====================
    public static final String ACTION_START_CAMERA = "cn.luke.wearsync.action.START_CAMERA";
    public static final String ACTION_STOP_CAMERA = "cn.luke.wearsync.action.STOP_CAMERA";
    /** ⚠️ 手表端发送拍照指令的 MessageClient Path */
    public static final String WEAR_MSG_PATH_TAKE_PHOTO = "/camera/take_photo";
    public static final String WEAR_CHANNEL_PATH = "/wear_data_channel/camera";
    public static final String WEAR_CAPABILITY = "wear_sync";

    // ==================== 低延迟预览参数 ====================
    private static final int PREVIEW_WIDTH = 320;
    private static final int PREVIEW_HEIGHT = 240;
    private static final int BIT_RATE = 300_000; // 300Kbps
    private static final int FRAME_RATE = 25;
    private static final int I_FRAME_INTERVAL = 1; // 1秒一个关键帧

    // ==================== 高清拍照参数 ====================
    private static final int PHOTO_WIDTH = 1920;
    private static final int PHOTO_HEIGHT = 1080;

    // ==================== 通知 ====================
    private static final String CHANNEL_ID = "camera_service_channel";
    private static final int NOTIFICATION_ID = 101;

    // ==================== 重连策略常量 ====================
    private static final int MAX_RECONNECT_ATTEMPTS = 5;
    private static final long RECONNECT_BASE_DELAY_MS = 1000;

    // ==================== 核心组件 ====================
    private HandlerThread mBgThread;
    private Handler mBgHandler;
    private Handler mMainHandler; // 用于处理重连延迟与主线程调度
    private CameraDevice mCameraDevice;
    private CameraCaptureSession mCaptureSession;
    private MediaCodec mEncoder;
    private Surface mEncoderSurface;
    private ImageReader mPhotoReader;
    private final AtomicBoolean mIsStreaming = new AtomicBoolean(false);
    private final AtomicBoolean mIsCameraOpened = new AtomicBoolean(false);
    private String mCachedNodeId;
    private ChannelClient mChannelClient;
    private OutputStream mChannelOutputStream;

    // ✅ 重连状态追踪
    private int mReconnectAttempts = 0;

    // ==================== 监听器 ====================
    private final MessageClient.OnMessageReceivedListener mMessageListener = event -> {
        if (WEAR_MSG_PATH_TAKE_PHOTO.equals(event.getPath())) {
            PhoneLog.d(TAG, "📸 收到手表端拍照指令，准备执行高清拍摄");
            captureHighResPhoto();
        }
    };

    // ✅ Channel 监听器，用于监听手表端断开连接
    private final ChannelClient.ChannelCallback mChannelListener = new ChannelClient.ChannelCallback() {
        @Override
        public void onChannelOpened(ChannelClient.Channel channel) {
            PhoneLog.d(TAG, "🔗 收到 Channel 打开回调, Path: " + channel.getPath());
        }

        @Override
        public void onChannelClosed(ChannelClient.Channel channel, int closeReason, int appSpecificErrorCode) {
            if (WEAR_CHANNEL_PATH.equals(channel.getPath())) {
                PhoneLog.d(TAG, "🔌 检测到手表端通道关闭 (Reason: " + closeReason + ", Code: " + appSpecificErrorCode + ")，准备停止推流并销毁服务");
                stopStreamingAndRelease();
                stopSelf();
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        mMainHandler = new Handler(getMainLooper());
        createNotificationChannel();

        // 🎯 API 35 核心修復：必須指定 FOREGROUND_SERVICE_TYPE_CAMERA
        startForeground(
                NOTIFICATION_ID, 
                buildNotification(), 
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        );

        mChannelClient = Wearable.getChannelClient(this);
        
        // 注册消息和通道监听
        Wearable.getMessageClient(this).addListener(mMessageListener);
        Wearable.getChannelClient(this).registerChannelCallback(mChannelListener);
        
        PhoneLog.d(TAG, "✅ 相机同步服务 onCreate 成功完成，前台通知已启动，监听器已注册");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            PhoneLog.w(TAG, "⚠️ onStartCommand 收到 null Intent，忽略处理");
            return START_NOT_STICKY;
        }
        String action = intent.getAction();
        PhoneLog.d(TAG, "📩 收到 Intent 动作: " + action);

        if (ACTION_START_CAMERA.equals(action)) {
            String remoteNodeId = intent.getStringExtra("remote_node_id");
            if (remoteNodeId != null) {
                mCachedNodeId = remoteNodeId;
                PhoneLog.d(TAG, "📍 更新 Intent 传入的远程节点 ID: " + mCachedNodeId);
            }
            // 重置重连计数
            mReconnectAttempts = 0;
            
            // 如果没有传入ID，尝试从缓存获取
            if (mCachedNodeId == null) {
                PhoneLog.d(TAG, "🔍 当前无节点 ID，尝试进行节点发现...");
                discoverAndCacheNode();
            }
            
            if (!mIsStreaming.get()) {
                PhoneLog.d(TAG, "🚀 当前未在推流中，开始初始化相机并启动推流流程");
                initCameraAndStartStreaming();
            } else {
                PhoneLog.w(TAG, "⚠️ 当前已经在推流状态中，忽略重复启动请求");
            }
        } else if (ACTION_STOP_CAMERA.equals(action)) {
            PhoneLog.d(TAG, "🛑 收到停止相机指令，开始清理并停止服务");
            stopStreamingAndRelease();
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        PhoneLog.d(TAG, "🛑 相机同步服务 onDestroy 触发，开始安全销毁资源...");
        // 1. 移除 API 监听
        try {
            Wearable.getMessageClient(this).removeListener(mMessageListener);
            Wearable.getChannelClient(this).unregisterChannelCallback(mChannelListener);
            PhoneLog.d(TAG, "🧹 Wearable 监听器注销成功");
        } catch (Exception e) {
            PhoneLog.w(TAG, "⚠️ 注销 Wearable 监听器时发生异常: " + e.getMessage());
        }

        // 2. 取消所有主线程待执行的重连/延迟任务
        if (mMainHandler != null) {
            mMainHandler.removeCallbacksAndMessages(null);
        }

        // 3. 停止推流并释放硬件资源
        stopStreamingAndRelease();

        // 4. 停止前台服务
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
        PhoneLog.d(TAG, "✅ 相机同步服务已完全安全销毁");
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ==================== 相机 + 低延迟编码 + Channel 推流 ====================
    private void initCameraAndStartStreaming() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            PhoneLog.e(TAG, "❌ 缺少 CAMERA 权限，无法启动相机，服务即将停止");
            stopSelf();
            return;
        }

        mIsStreaming.set(true);
        startBackgroundThread();

        try {
            // 1. 低延迟 H.264 编码器配置
            PhoneLog.d(TAG, "⚙️ [1/4] 开始配置 H.264 编码器 (分辨率: " + PREVIEW_WIDTH + "x" + PREVIEW_HEIGHT + ", 码率: " + BIT_RATE + "bps)");
            MediaFormat fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, PREVIEW_WIDTH, PREVIEW_HEIGHT);
            fmt.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE);
            fmt.setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE);
            fmt.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL);
            fmt.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            // 设置 CBR 低延迟模式
            fmt.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR);

            mEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            mEncoder.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            mEncoderSurface = mEncoder.createInputSurface();
            mEncoder.setCallback(new EncoderCallback(), mBgHandler);
            PhoneLog.d(TAG, "⏸️ [1/4] 编码器及 InputSurface 配置完成，等待 Channel 通道建立");

            // 2. 高清拍照 ImageReader
            PhoneLog.d(TAG, "📷 配置高清拍照 ImageReader (分辨率: " + PHOTO_WIDTH + "x" + PHOTO_HEIGHT + ")");
            mPhotoReader = ImageReader.newInstance(PHOTO_WIDTH, PHOTO_HEIGHT, ImageFormat.JPEG, 2);
            mPhotoReader.setOnImageAvailableListener(reader -> {
                try (Image image = reader.acquireLatestImage()) {
                    if (image != null) {
                        savePhoto(image);
                    }
                } catch (Exception e) {
                    PhoneLog.e(TAG, "❌ 读取拍照 Image 帧异常", e);
                }
            }, mBgHandler);

        } catch (Exception e) {
            PhoneLog.e(TAG, "❌ [1/4] 初始化 MediaCodec 编码器失败", e);
            stopStreamingAndRelease();
            stopSelf();
            return;
        }

        // 3. 打开 Channel 通道
        PhoneLog.d(TAG, "🔍 [2/4] 准备打开 Channel 数据通道，目标节点: " + mCachedNodeId);
        openChannelStream();
    }

    /** 高清拍照逻辑 */
    private void captureHighResPhoto() {
        if (mCameraDevice == null || mCaptureSession == null || mPhotoReader == null) {
            PhoneLog.w(TAG, "⚠️ 相机或 Session 未就绪，忽略拍照指令");
            return;
        }
        try {
            CaptureRequest.Builder builder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            builder.addTarget(mPhotoReader.getSurface());
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            builder.set(CaptureRequest.JPEG_QUALITY, (byte) 95);
            mCaptureSession.capture(builder.build(), null, mBgHandler);
            PhoneLog.d(TAG, "📸 高清拍照 CaptureRequest 请求已提交给 Session");
        } catch (Exception e) {
            PhoneLog.e(TAG, "❌ 提交拍照请求失败", e);
        }
    }

    private void savePhoto(Image image) {
        try {
            ByteBuffer buffer = image.getPlanes()[0].getBuffer();
            byte[] data = new byte[buffer.remaining()];
            buffer.get(data);
            PhoneLog.d(TAG, "✅ 高清照片捕获成功，数据大小: " + data.length + " bytes");
        } catch (Exception e) {
            PhoneLog.e(TAG, "❌ 处理/保存照片数据失败", e);
        }
    }

    // ==================== Channel 管理与相机开启辅助函数省略/保全原有 ====================
    private void openChannelStream() {
        if (mCachedNodeId == null || !mIsStreaming.get()) {
            PhoneLog.w(TAG, "⚠️ [2/4] 节点 ID 为空或推流已停止");
            return;
        }
        // ... 原有的 openChannelStream 保持不变 ...
    }

    private void startBackgroundThread() {
        if (mBgThread == null) {
            mBgThread = new HandlerThread("CameraBgThread");
            mBgThread.start();
            mBgHandler = new Handler(mBgThread.getLooper());
        }
    }

    private void stopStreamingAndRelease() {
        mIsStreaming.set(false);
        mIsCameraOpened.set(false);

        if (mCaptureSession != null) {
            try { mCaptureSession.close(); } catch (Exception ignored) {}
            mCaptureSession = null;
        }
        if (mCameraDevice != null) {
            try { mCameraDevice.close(); } catch (Exception ignored) {}
            mCameraDevice = null;
        }
        if (mEncoder != null) {
            try {
                mEncoder.stop();
                mEncoder.release();
            } catch (Exception ignored) {}
            mEncoder = null;
        }
        if (mEncoderSurface != null) {
            mEncoderSurface.release();
            mEncoderSurface = null;
        }
        if (mPhotoReader != null) {
            mPhotoReader.close();
            mPhotoReader = null;
        }
        if (mChannelOutputStream != null) {
            try { mChannelOutputStream.close(); } catch (Exception ignored) {}
            mChannelOutputStream = null;
        }

        if (mBgThread != null) {
            mBgThread.quitSafely();
            try { mBgThread.join(); } catch (InterruptedException ignored) {}
            mBgThread = null;
            mBgHandler = null;
        }
    }

    private void discoverAndCacheNode() {
        Wearable.getCapabilityClient(this)
                .getCapability(WEAR_CAPABILITY, com.google.android.gms.wearable.CapabilityClient.FILTER_REACHABLE)
                .addOnSuccessListener(info -> {
                    for (com.google.android.gms.wearable.Node node : info.getNodes()) {
                        if (node.isNearby()) {
                            mCachedNodeId = node.getId();
                            PhoneLog.d(TAG, "✅ 发现附近节点: " + mCachedNodeId);
                            break;
                        }
                    }
                });
    }

    private void createNotificationChannel() {
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "相机同步", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("用于相机预览与推流的前台服务通知通道");
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.createNotificationChannel(ch);
        }
    }

    private Notification buildNotification() {
        Intent notificationIntent = new Intent(this, PhoneSyncMainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("相机同步服务运行中")
                .setContentText("正在与手表保持实时连接与推流...")
                .setSmallIcon(R.drawable.ic_notification)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
    }

    private class EncoderCallback extends MediaCodec.Callback {
        @Override
        public void onInputBufferAvailable(@NonNull MediaCodec codec, int index) {}

        @Override
        public void onOutputBufferAvailable(@NonNull MediaCodec codec, int index, @NonNull MediaCodec.BufferInfo info) {
            ByteBuffer buffer = codec.getOutputBuffer(index);
            if (buffer != null && mChannelOutputStream != null && mIsStreaming.get()) {
                try {
                    byte[] data = new byte[info.size];
                    buffer.get(data);
                    mChannelOutputStream.write(data);
                    mChannelOutputStream.flush();
                } catch (IOException e) {
                    PhoneLog.e(TAG, "❌ 发送编码数据失败", e);
                }
            }
            codec.releaseOutputBuffer(index, false);
        }

        @Override
        public void onError(@NonNull MediaCodec codec, @NonNull MediaCodec.CodecException e) {
            PhoneLog.e(TAG, "❌ MediaCodec 异常", e);
        }

        @Override
        public void onOutputFormatChanged(@NonNull MediaCodec codec, @NonNull MediaFormat format) {}
    }
}
