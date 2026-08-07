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
        startForeground(NOTIFICATION_ID, buildNotification());
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

    // ==================== Channel 管理 ====================
    private void openChannelStream() {
        if (mCachedNodeId == null || !mIsStreaming.get()) {
            PhoneLog.w(TAG, "⚠️ [2/4] 节点 ID 为空或推流已停止，取消打开 Channel 通道");
            return;
        }
        PhoneLog.d(TAG, "📡 [2/4] 正在向手表节点 [" + mCachedNodeId + "] 发起 Channel 打开请求 (Path: " + WEAR_CHANNEL_PATH + ")...");
        
        mChannelClient.openChannel(mCachedNodeId, WEAR_CHANNEL_PATH)
                .addOnSuccessListener(channel -> {
                    PhoneLog.d(TAG, "🤝 [2/4] Channel 管道建立成功！正在请求 OutputStream...");
                    mChannelClient.getOutputStream(channel)
                            .addOnSuccessListener(os -> {
                                mChannelOutputStream = os;
                                PhoneLog.d(TAG, "✅ [3/4] Channel OutputStream 获取成功！通道已完全打通");
                                // 重置重连计数
                                mReconnectAttempts = 0;
                                // 启动编码器
                                try {
                                    mEncoder.start();
                                    PhoneLog.d(TAG, "▶️ MediaCodec 编码器已成功 start()");
                                } catch (Exception e) {
                                    PhoneLog.e(TAG, "❌ 启动 MediaCodec 编码器失败", e);
                                    attemptReconnect();
                                    return;
                                }
                                // 通道打通后，开始请求硬件相机
                                startCameraDevice();
                            })
                            .addOnFailureListener(e -> {
                                PhoneLog.e(TAG, "❌ [3/4] 获取 Channel OutputStream 失败", e);
                                attemptReconnect();
                            });
                })
                .addOnFailureListener(e -> {
                    PhoneLog.e(TAG, "❌ [2/4] 打开 Channel 通道失败", e);
                    attemptReconnect();
                });
    }

    private void startCameraDevice() {
        PhoneLog.d(TAG, "📷 [4/4] 正在请求打开硬件相机...");
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            PhoneLog.e(TAG, "❌ [4/4] 缺少 CAMERA 权限");
            stopStreamingAndRelease();
            stopSelf();
            return;
        }
        try {
            CameraManager mgr = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            if (mgr == null || mgr.getCameraIdList().length == 0) {
                PhoneLog.e(TAG, "❌ [4/4] 未检测到可用的相机设备");
                stopStreamingAndRelease();
                stopSelf();
                return;
            }
            String camId = mgr.getCameraIdList()[0];
            PhoneLog.d(TAG, "📷 [4/4] 选中相机 ID: " + camId + "，准备 openCamera");
            mgr.openCamera(camId, new CameraStateCallback(), mBgHandler);
        } catch (Exception e) {
            PhoneLog.e(TAG, "❌ [4/4] 启动硬件相机异常", e);
            stopStreamingAndRelease();
            stopSelf();
        }
    }

    /**
     * 安全停止推流并按严格顺序释放所有硬件与线程资源
     * 顺序：1. Close Session/Camera -> 2. Close Stream/Codec -> 3. Quit HandlerThread
     */
    private void stopStreamingAndRelease() {
        PhoneLog.d(TAG, "🔄 开始安全释放相机与推流资源...");
        mIsStreaming.set(false);
        mIsCameraOpened.set(false);

        // 1. 优先关闭 Camera CaptureSession 与 Device
        if (mCaptureSession != null) {
            try {
                PhoneLog.d(TAG, "🧹 正在关闭 CameraCaptureSession...");
                mCaptureSession.close();
            } catch (Exception e) {
                PhoneLog.w(TAG, "⚠️ 关闭 CameraCaptureSession 异常: " + e.getMessage());
            }
            mCaptureSession = null;
        }

        if (mCameraDevice != null) {
            try {
                PhoneLog.d(TAG, "🧹 正在关闭 CameraDevice...");
                mCameraDevice.close();
            } catch (Exception e) {
                PhoneLog.w(TAG, "⚠️ 关闭 CameraDevice 异常: " + e.getMessage());
            }
            mCameraDevice = null;
        }

        // 2. 关闭网络 OutputStream 与编码器
        if (mChannelOutputStream != null) {
            try {
                PhoneLog.d(TAG, "🧹 正在关闭 Channel OutputStream...");
                mChannelOutputStream.close();
            } catch (IOException e) {
                PhoneLog.w(TAG, "⚠️ 关闭 Channel OutputStream 异常: " + e.getMessage());
            }
            mChannelOutputStream = null;
        }

        if (mEncoder != null) {
            try {
                PhoneLog.d(TAG, "🧹 正在停止并释放 MediaCodec 编码器...");
                mEncoder.stop();
                mEncoder.release();
            } catch (Exception e) {
                PhoneLog.w(TAG, "⚠️ 释放 MediaCodec 异常: " + e.getMessage());
            }
            mEncoder = null;
        }

        if (mEncoderSurface != null) {
            try {
                mEncoderSurface.release();
            } catch (Exception e) {
                PhoneLog.w(TAG, "⚠️ 释放 mEncoderSurface 异常: " + e.getMessage());
            }
            mEncoderSurface = null;
        }

        if (mPhotoReader != null) {
            try {
                mPhotoReader.close();
            } catch (Exception e) {
                PhoneLog.w(TAG, "⚠️ 释放 mPhotoReader 异常: " + e.getMessage());
            }
            mPhotoReader = null;
        }

        // 3. 最后关闭背景 Handler 和 HandlerThread（彻底根治 NPE 崩溃）
        stopBackgroundThread();
        PhoneLog.d(TAG, "✅ 所有推流与相机资源已完全安全释放");
    }

    // ==================== 自动重连逻辑 ====================
    private void attemptReconnect() {
        if (!mIsStreaming.get()) {
            PhoneLog.w(TAG, "⚠️ 当前已不在推流模式，放弃自动重连");
            return;
        }

        mReconnectAttempts++;
        if (mReconnectAttempts > MAX_RECONNECT_ATTEMPTS) {
            PhoneLog.e(TAG, "❌ Channel 重连次数到达上限 (" + MAX_RECONNECT_ATTEMPTS + " 次)，停止服务");
            stopStreamingAndRelease();
            stopSelf();
            return;
        }

        long delay = mReconnectAttempts * RECONNECT_BASE_DELAY_MS;
        PhoneLog.w(TAG, "🔁 触发自动重连机制，第 " + mReconnectAttempts + "/" + MAX_RECONNECT_ATTEMPTS + " 次尝试，延迟 " + delay + "ms");

        // 先清理当前旧连接与资源
        stopStreamingAndRelease();
        
        // 重置推流状态与背景线程
        mIsStreaming.set(true);
        startBackgroundThread();

        if (mMainHandler != null) {
            mMainHandler.postDelayed(() -> {
                if (mIsStreaming.get()) {
                    PhoneLog.d(TAG, "🚀 重连延迟结束，开始重新发起 openChannelStream()");
                    openChannelStream();
                }
            }, delay);
        }
    }

    // ==================== 编码器回调 ====================
    private class EncoderCallback extends MediaCodec.Callback {
        @Override
        public void onInputBufferAvailable(@NonNull MediaCodec codec, int index) {
            // 使用 Surface 输入模式，此回调留空
        }

        @Override
        public void onOutputBufferAvailable(@NonNull MediaCodec codec, int index, @NonNull MediaCodec.BufferInfo info) {
            try {
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
            } catch (Exception e) {
                PhoneLog.e(TAG, "❌ 编码器 onOutputBufferAvailable 处理帧数据异常", e);
            }
        }

        @Override
        public void onError(@NonNull MediaCodec c, @NonNull MediaCodec.CodecException e) {
            PhoneLog.e(TAG, "❌ MediaCodec 编码器内部发生错误: " + e.getDiagnosticInfo(), e);
        }

        @Override
        public void onOutputFormatChanged(@NonNull MediaCodec c, @NonNull MediaFormat f) {
            PhoneLog.d(TAG, "ℹ️ MediaCodec 输出格式已改变: " + f);
        }
    }

    private void writeFrameToChannel(byte[] h264, long tsUs, int flags) {
        OutputStream os = mChannelOutputStream;
        if (os == null || !mIsStreaming.get()) return;
        try {
            // 协议格式：长度 (4字节) + 时间戳 (8字节) + 标志位 (4字节) + H.264 Data
            os.write(ByteBuffer.allocate(4).putInt(h264.length).array());
            os.write(ByteBuffer.allocate(8).putLong(tsUs).array());
            os.write(ByteBuffer.allocate(4).putInt(flags).array());
            os.write(h264);
            os.flush();
        } catch (IOException e) {
            PhoneLog.w(TAG, "⚠️ 向 Channel OutputStream 写入视频帧失败 (IO 异常，连接可能已断开): " + e.getMessage());
            mChannelOutputStream = null;
            // 触发重连机制
            attemptReconnect();
        }
    }

    // ==================== 相机回调 ====================
    private class CameraStateCallback extends CameraDevice.StateCallback {
        @Override
        public void onOpened(@NonNull CameraDevice camera) {
            PhoneLog.d(TAG, "✅ 硬件相机已成功 onOpened，ID: " + camera.getId() + "，准备开启 CaptureSession 预览");
            mCameraDevice = camera;
            mIsCameraOpened.set(true);
            startPreviewSession();
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice camera) {
            PhoneLog.w(TAG, "⚠️ 硬件相机被系统或其他应用断开 (onDisconnected), Camera ID: " + camera.getId());
            try {
                camera.close();
            } catch (Exception ignored) {}
            mCameraDevice = null;
            mIsCameraOpened.set(false);
            stopStreamingAndRelease();
            stopSelf();
        }

        @Override
        public void onError(@NonNull CameraDevice camera, int error) {
            PhoneLog.e(TAG, "❌ 硬件相机打开/运行错误 (onError 错误码: " + error + ")");
            try {
                camera.close();
            } catch (Exception ignored) {}
            mCameraDevice = null;
            mIsCameraOpened.set(false);
            stopStreamingAndRelease();
            stopSelf();
        }
    }

    private void startPreviewSession() {
        if (mCameraDevice == null) {
            PhoneLog.e(TAG, "❌ startPreviewSession 失败: mCameraDevice 为 null");
            return;
        }
        try {
            PhoneLog.d(TAG, "⚙️ 正在构建 Camera TEMPLATE_RECORD CaptureRequest...");
            CaptureRequest.Builder b = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            b.addTarget(mEncoderSurface);
            b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
            
            List<OutputConfiguration> outputConfigs = new ArrayList<>();
            outputConfigs.add(new OutputConfiguration(mEncoderSurface));
            if (mPhotoReader != null) {
                outputConfigs.add(new OutputConfiguration(mPhotoReader.getSurface()));
            }

            // 安全 Executor，防止在 Handler 被 quit/置空时引发 Session 回调 NPE 崩溃
            Executor safeExecutor = runnable -> {
                Handler h = mBgHandler;
                if (h != null && mBgThread != null && mBgThread.isAlive()) {
                    h.post(runnable);
                } else {
                    // 保底机制：若背景线程已销毁，投递到主线程执行，防止 Camera2 底层 API 抛 NPE
                    mMainHandler.post(runnable);
                }
            };

            SessionConfiguration sessionConfig = new SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    outputConfigs,
                    safeExecutor,
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession s) {
                            mCaptureSession = s;
                            try {
                                PhoneLog.d(TAG, "✅ CameraCaptureSession 预览会话配置成功，开始提交 setRepeatingRequest 进行持续推流！");
                                s.setRepeatingRequest(b.build(), null, mBgHandler);
                            } catch (Exception e) {
                                PhoneLog.e(TAG, "❌ 提交 setRepeatingRequest 失败", e);
                            }
                        }

                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession s) {
                            PhoneLog.e(TAG, "❌ CameraCaptureSession 配置失败 (onConfigureFailed)");
                            stopStreamingAndRelease();
                            stopSelf();
                        }

                        @Override
                        public void onClosed(@NonNull CameraCaptureSession session) {
                            PhoneLog.d(TAG, "🧹 CameraCaptureSession 已安全 onClosed");
                            super.onClosed(session);
                        }
                    }
            );
            
            PhoneLog.d(TAG, "📷 正在向 CameraDevice 发起 createCaptureSession...");
            mCameraDevice.createCaptureSession(sessionConfig);
        } catch (Exception e) {
            PhoneLog.e(TAG, "❌ 创建 CameraCaptureSession 发生异常", e);
            stopStreamingAndRelease();
            stopSelf();
        }
    }

    // ==================== 工具方法 ====================
    private void discoverAndCacheNode() {
        mCachedNodeId = WearSyncState.getNodeId(this);
        if (mCachedNodeId != null) {
            PhoneLog.d(TAG, "✅ 成功获取到节点 ID: " + mCachedNodeId);
            openChannelStream();
        } else {
            PhoneLog.w(TAG, "⚠️ 未能获取到有效的节点 ID，手表端可能未连接或未在线");
        }
    }

    private void startBackgroundThread() {
        if (mBgThread == null) {
            mBgThread = new HandlerThread("CameraBgThread");
            mBgThread.start();
            mBgHandler = new Handler(mBgThread.getLooper());
            PhoneLog.d(TAG, "🧵 HandlerThread [CameraBgThread] 已启动");
        }
    }

    private void stopBackgroundThread() {
        if (mBgThread != null) {
            PhoneLog.d(TAG, "🧵 正在停止 HandlerThread [CameraBgThread]...");
            mBgThread.quitSafely();
            try {
                mBgThread.join();
                PhoneLog.d(TAG, "🧵 HandlerThread [CameraBgThread] 已成功 join 退出");
            } catch (InterruptedException e) {
                PhoneLog.w(TAG, "⚠️ 等待 HandlerThread 退出被打断: " + e.getMessage());
            }
            mBgThread = null;
            mBgHandler = null;
        }
    }

    private void createNotificationChannel() {
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "相机同步", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("用于相机预览与推流的前台服务通知通道");
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.createNotificationChannel(ch);
            PhoneLog.d(TAG, "🔔 前台通知 Channel 已创建/更新");
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
}