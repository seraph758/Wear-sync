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
    private Handler mMainHandler; // 用于处理重连延迟
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

    // ✅ 新增：重连状态追踪
    private int mReconnectAttempts = 0;

    // ==================== 监听器 ====================
    private final MessageClient.OnMessageReceivedListener mMessageListener = event -> {
        if (WEAR_MSG_PATH_TAKE_PHOTO.equals(event.getPath())) {
            PhoneLog.d(TAG, "📸 收到拍照指令，执行高清拍摄");
            captureHighResPhoto();
        }
    };

    // ✅ 新增：Channel 监听器，用于监听手表端断开连接
    private final ChannelClient.ChannelListener mChannelListener = new ChannelClient.ChannelListener() {
        @Override
        public void onChannelOpened(ChannelClient.Channel channel) {
            // 我们主要使用 openChannel 主动连接，这里通常不需要处理
        }

        @Override
        public void onChannelClosed(ChannelClient.Channel channel, int closeReason, int appSpecificErrorCode) {
            if (WEAR_CHANNEL_PATH.equals(channel.getPath())) {
                PhoneLog.d(TAG, "🔌 检测到通道关闭 (Reason: " + closeReason + ")，停止推流");
                // 如果通道关闭，说明手表端可能退出了，我们也应该停止推流释放相机
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
        startForeground(NOTIFICATION_ID, buildNotification());
        mChannelClient = Wearable.getChannelClient(this);
        
        // ✅ 注册消息和通道监听
        Wearable.getMessageClient(this).addListener(mMessageListener);
        Wearable.getChannelClient(this).addListener(mChannelListener);
        
        PhoneLog.d(TAG, "✅ 服务已创建，监听器已注册");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        String action = intent.getAction();
        if (ACTION_START_CAMERA.equals(action)) {
            String remoteNodeId = intent.getStringExtra("remote_node_id");
            if (remoteNodeId != null) {
                mCachedNodeId = remoteNodeId;
                PhoneLog.d(TAG, "📍 使用 Intent 传入的节点 ID: " + mCachedNodeId);
            }
            // 重置重连计数
            mReconnectAttempts = 0;
            
            // 如果没有传入ID，尝试从缓存获取
            if (mCachedNodeId == null) {
                discoverAndCacheNode();
            }
            
            if (!mIsStreaming.get()) {
                initCameraAndStartStreaming();
            }
        } else if (ACTION_STOP_CAMERA.equals(action)) {
            stopStreamingAndRelease();
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        PhoneLog.d(TAG, "🛑 服务即将销毁，开始清理资源...");
        // 1. 移除监听
        Wearable.getMessageClient(this).removeListener(mMessageListener);
        Wearable.getChannelClient(this).removeListener(mChannelListener);
        // 2. 取消所有待执行的重连任务
        mMainHandler.removeCallbacksAndMessages(null);
        // 3. 停止推流
        stopStreamingAndRelease();
        // 4. 停止前台服务
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
        PhoneLog.d(TAG, "✅ 服务已安全销毁");
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ==================== 相机 + 低延迟编码 + Channel 推流 ====================
    private void initCameraAndStartStreaming() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            PhoneLog.e(TAG, "❌ 缺少相机权限，服务停止");
            stopSelf();
            return;
        }

        mIsStreaming.set(true);
        startBackgroundThread();

        try {
            // ✅ 1. 低延迟 H.264 编码器配置
            MediaFormat fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, PREVIEW_WIDTH, PREVIEW_HEIGHT);
            fmt.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE);
            fmt.setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE);
            fmt.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL);
            fmt.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            // 设置低延迟模式
            fmt.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR);

            mEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            mEncoder.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            mEncoderSurface = mEncoder.createInputSurface();
            mEncoder.setCallback(new EncoderCallback());
            PhoneLog.d(TAG, "⏸️ [1/4] 编码器配置完成，等待通道建立");

            // ✅ 2. 高清拍照 ImageReader
            mPhotoReader = ImageReader.newInstance(PHOTO_WIDTH, PHOTO_HEIGHT, ImageFormat.JPEG, 2);
            mPhotoReader.setOnImageAvailableListener(reader -> {
                try (Image image = reader.acquireLatestImage()) {
                    if (image != null) savePhoto(image);
                }
            }, mBgHandler);

        } catch (Exception e) {
            PhoneLog.e(TAG, "❌ [1/4] 初始化编码器失败", e);
            stopStreamingAndRelease();
            stopSelf();
            return;
        }

        // ✅ 3. 打开通道
        PhoneLog.d(TAG, "🔍 [2/4] 准备打开通道，目标节点: " + mCachedNodeId);
        openChannelStream();
    }

    /** ✅ 高清拍照逻辑 */
    private void captureHighResPhoto() {
        if (mCameraDevice == null || mCaptureSession == null || mPhotoReader == null) {
            PhoneLog.w(TAG, "⚠️ 相机未就绪，忽略拍照指令");
            return;
        }
        try {
            CaptureRequest.Builder builder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            builder.addTarget(mPhotoReader.getSurface());
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            builder.set(CaptureRequest.JPEG_QUALITY, (byte) 95);
            mCaptureSession.capture(builder.build(), null, mBgHandler);
            PhoneLog.d(TAG, "📸 高清拍照请求已发出");
        } catch (Exception e) {
            PhoneLog.e(TAG, "❌ 拍照失败", e);
        }
    }

    private void savePhoto(Image image) {
        ByteBuffer buffer = image.getPlanes()[0].getBuffer();
        byte[] data = new byte[buffer.remaining()];
        buffer.get(data);
        // TODO: 这里可以将照片通过 MessageClient 发送给手表，或者保存到本地
        PhoneLog.d(TAG, "✅ 高清照片已捕获，大小: " + data.length + " bytes");
    }

    // ==================== Channel 管理 ====================
    private void openChannelStream() {
        if (mCachedNodeId == null || !mIsStreaming.get()) {
            PhoneLog.w(TAG, "⚠️ [2/4] 节点ID为空或推流已停止，放弃打开通道");
            return;
        }
        PhoneLog.d(TAG, "📡 [2/4] 正在向手表发起 Channel 连接请求...");
        
        mChannelClient.openChannel(mCachedNodeId, WEAR_CHANNEL_PATH)
                .addOnSuccessListener(channel -> {
                    PhoneLog.d(TAG, "🤝 [2/4] Channel 连接成功！正在获取输出流...");
                    mChannelClient.getOutputStream(channel)
                            .addOnSuccessListener(os -> {
                                mChannelOutputStream = os;
                                PhoneLog.d(TAG, "✅ [3/4] 输出流获取成功！通道已完全打通！");
                                // 重置重连计数
                                mReconnectAttempts = 0;
                                // 1. 启动编码器
                                mEncoder.start();
                                // 2. 通道打通后，才开始请求打开相机硬件
                                startCameraDevice();
                            })
                            .addOnFailureListener(e -> {
                                PhoneLog.e(TAG, "❌ [3/4] 获取输出流失败", e);
                                attemptReconnect();
                            });
                })
                .addOnFailureListener(e -> {
                    PhoneLog.e(TAG, "❌ [2/4] 打开 Channel 失败", e);
                    attemptReconnect();
                });
    }

    private void startCameraDevice() {
        PhoneLog.d(TAG, "📷 [4/4] 正在打开硬件相机...");
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            PhoneLog.e(TAG, "❌ [4/4] 缺少 CAMERA 权限");
            stopStreamingAndRelease();
            stopSelf();
            return;
        }
        try {
            CameraManager mgr = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            String camId = mgr.getCameraIdList()[0];
            mgr.openCamera(camId, new CameraStateCallback(), mBgHandler);
        } catch (Exception e) {
            PhoneLog.e(TAG, "❌ [4/4] 启动相机失败", e);
            stopStreamingAndRelease();
            stopSelf();
        }
    }

    private void stopStreamingAndRelease() {
        mIsStreaming.set(false);
        mIsCameraOpened.set(false);

        // 关闭流
        if (mChannelOutputStream != null) {
            try { mChannelOutputStream.close(); } catch (IOException ignored) {}
            mChannelOutputStream = null;
        }

        // 释放相机和编码器资源
        try {
            if (mCaptureSession != null) { mCaptureSession.close(); mCaptureSession = null; }
            if (mCameraDevice != null) { mCameraDevice.close(); mCameraDevice = null; }
            if (mEncoder != null) { mEncoder.stop(); mEncoder.release(); mEncoder = null; }
            if (mEncoderSurface != null) { mEncoderSurface.release(); mEncoderSurface = null; }
            if (mPhotoReader != null) { mPhotoReader.close(); mPhotoReader = null; }
        } catch (Exception e) {
            PhoneLog.e(TAG, "⚠️ 释放资源异常", e);
        }
        stopBackgroundThread();
    }

    // ==================== 自动重连逻辑 ====================
    private void attemptReconnect() {
        if (!mIsStreaming.get()) return;

        mReconnectAttempts++;
        if (mReconnectAttempts > MAX_RECONNECT_ATTEMPTS) {
            PhoneLog.e(TAG, "❌ 重连次数超过上限，停止服务");
            stopStreamingAndRelease();
            stopSelf();
            return;
        }

        long delay = mReconnectAttempts * RECONNECT_BASE_DELAY_MS;
        PhoneLog.d(TAG, "🔁 准备第 " + mReconnectAttempts + " 次重连，延迟 " + delay + "ms");

        // 先释放资源
        stopStreamingAndRelease();
        // 重置状态，准备下一次启动
        mIsStreaming.set(true);
        startBackgroundThread();

        mMainHandler.postDelayed(() -> {
            if (mIsStreaming.get()) {
                openChannelStream();
            }
        }, delay);
    }

    // ==================== 编码器回调 ====================
    private class EncoderCallback extends MediaCodec.Callback {
        @Override
        public void onInputBufferAvailable(@NonNull MediaCodec codec, int index) {
            // 使用 Surface 输入，此回调为空
        }

        @Override
        public void onOutputBufferAvailable(@NonNull MediaCodec codec, int index, @NonNull MediaCodec.BufferInfo info) {
            ByteBuffer buf = codec.getOutputBuffer(index);
            if (buf == null || !mIsStreaming.get()) {
                codec.releaseOutputBuffer(index, false);
                return;
            }
            byte[] data = new byte[info.size];
            buf.position(info.offset);
            buf.get(data);
            codec.releaseOutputBuffer(index, false);
            writeFrameToChannel(data, info.presentationTimeUs, info.flags);
        }

        @Override
        public void onError(@NonNull MediaCodec c, @NonNull MediaCodec.CodecException e) {
            PhoneLog.e(TAG, "❌ 编码器错误", e);
        }

        @Override
        public void onOutputFormatChanged(@NonNull MediaCodec c, @NonNull MediaFormat f) {}
    }

    private void writeFrameToChannel(byte[] h264, long tsUs, int flags) {
        OutputStream os = mChannelOutputStream;
        if (os == null) return;
        try {
            // 协议：长度(4) + 时间戳(8) + 标志位(4) + 数据
            os.write(ByteBuffer.allocate(4).putInt(h264.length).array());
            os.write(ByteBuffer.allocate(8).putLong(tsUs).array());
            os.write(ByteBuffer.allocate(4).putInt(flags).array());
            os.write(h264);
            os.flush();
        } catch (IOException e) {
            PhoneLog.w(TAG, "⚠️ Channel写入失败，可能连接已断开", e);
            mChannelOutputStream = null;
            // 触发重连
            attemptReconnect();
        }
    }

    // ==================== 相机回调 ====================
    private class CameraStateCallback extends CameraDevice.StateCallback {
        @Override
        public void onOpened(@NonNull CameraDevice camera) {
            PhoneLog.d(TAG, "✅ 相机硬件已打开，开启预览会话");
            mCameraDevice = camera;
            mIsCameraOpened.set(true);
            startPreviewSession();
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice camera) {
            PhoneLog.w(TAG, "⚠️ 相机被断开");
            camera.close();
            mCameraDevice = null;
            mIsCameraOpened.set(false);
        }

        @Override
        public void onError(@NonNull CameraDevice camera, int error) {
            PhoneLog.e(TAG, "❌ 相机错误: " + error);
            stopStreamingAndRelease();
            stopSelf();
        }
    }

    private void startPreviewSession() {
        if (mCameraDevice == null) return;
        try {
            CaptureRequest.Builder b = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            b.addTarget(mEncoderSurface);
            b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
            
            List<OutputConfiguration> outputConfigs = new ArrayList<>();
            outputConfigs.add(new OutputConfiguration(mEncoderSurface));
            // 只有当 mPhotoReader 不为空时才添加，防止空指针
            if (mPhotoReader != null) {
                outputConfigs.add(new OutputConfiguration(mPhotoReader.getSurface()));
            }

            SessionConfiguration sessionConfig = new SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    outputConfigs,
                    runnable -> mBgHandler.post(runnable),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession s) {
                            mCaptureSession = s;
                            try {
                                PhoneLog.d(TAG, "✅ 预览会话配置成功，开始推流！");
                                s.setRepeatingRequest(b.build(), null, mBgHandler);
                            } catch (Exception e) {
                                PhoneLog.e(TAG, "❌ 开始预览请求失败", e);
                            }
                        }

                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession s) {
                            PhoneLog.e(TAG, "❌ 预览会话配置失败");
                        }
                    }
            );
            mCameraDevice.createCaptureSession(sessionConfig);
        } catch (Exception e) {
            PhoneLog.e(TAG, "❌ 创建预览会话失败", e);
        }
    }

    // ==================== 工具方法 ====================
    private void discoverAndCacheNode() {
        // 这里假设你有一个 WearSyncState 类来管理节点ID
        // 如果没有，你可能需要通过 CapabilityClient 来发现节点
        mCachedNodeId = WearSyncState.getNodeId(this);
        if (mCachedNodeId != null) {
            openChannelStream();
        } else {
            PhoneLog.w(TAG, "未获取到节点ID，手表可能未连接");
        }
    }

    private void startBackgroundThread() {
        if (mBgThread == null) {
            mBgThread = new HandlerThread("CameraBg");
            mBgThread.start();
            mBgHandler = new Handler(mBgThread.getLooper());
        }
    }

    private void stopBackgroundThread() {
        if (mBgThread != null) {
            mBgThread.quitSafely();
            try {
                mBgThread.join();
            } catch (InterruptedException ignored) {}
            mBgThread = null;
            mBgHandler = null;
        }
    }

    private void createNotificationChannel() {
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "相机同步", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("Channel Description");
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
        manager.createNotificationChannel(ch);
        }
    }
}  