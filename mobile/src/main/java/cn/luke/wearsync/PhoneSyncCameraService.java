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
import android.content.pm.ServiceInfo;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import android.widget.Toast;

import com.google.android.gms.wearable.ChannelClient;
import com.google.android.gms.wearable.MessageClient;
import com.google.android.gms.wearable.Wearable;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

public class PhoneSyncCameraService extends Service {
    private static final String TAG = "WearSync_CameraSvc";

    // ==================== 常量定义 ====================
    public static final String ACTION_START_CAMERA = "cn.luke.wearsync.action.START_CAMERA";
    public static final String ACTION_STOP_CAMERA = "cn.luke.wearsync.action.STOP_CAMERA";
    public static final String WEAR_MSG_PATH_TAKE_PHOTO = "/camera/take_photo";
    public static final String WEAR_CHANNEL_PATH = "/wear_data_channel/camera";

    // 🎯 极低延迟手表预览参数 (320x320 正方形流，完美契合圆屏/方屏手表)
    private static final int PREVIEW_WIDTH = 320;
    private static final int PREVIEW_HEIGHT = 320;
    private static final int BIT_RATE = 400_000; // 400Kbps 低码率保障流畅
    private static final int FRAME_RATE = 25;
    private static final int I_FRAME_INTERVAL = 1; // 1秒一个关键帧

    // 🎯 高清拍照参数 (动态查询最高分辨率)
    private int photoWidth = 1920;
    private int photoHeight = 1080;

    // 通知相关
    private static final String CHANNEL_ID = "camera_service_channel";
    private static final int NOTIFICATION_ID = 101;

    // ==================== 核心组件 ====================
    private HandlerThread mBgThread;
    private Handler mBgHandler;
    private Handler mMainHandler;
    
    private CameraDevice mCameraDevice;
    private CameraCaptureSession mCaptureSession;
    private MediaCodec mEncoder;
    private android.view.Surface mEncoderSurface;
    private ImageReader mPhotoReader;
    
    private final AtomicBoolean mIsStreaming = new AtomicBoolean(false);
    private final AtomicBoolean mIsCameraOpened = new AtomicBoolean(false);
    
    private String mCachedNodeId;
    private ChannelClient mChannelClient;
    private OutputStream mChannelOutputStream;
    private DataOutputStream mDataOutputStream; // 🎯 新增：封装数据包头

    // ==================== 监听器 ====================
    private final MessageClient.OnMessageReceivedListener mMessageListener = event -> {
        if (WEAR_MSG_PATH_TAKE_PHOTO.equals(event.getPath())) {
            PhoneLog.d(TAG, "📸 收到手表端拍照指令，准备执行最高画质拍摄");
            captureHighResPhoto();
        }
    };

    private final ChannelClient.ChannelCallback mChannelListener = new ChannelClient.ChannelCallback() {
        @Override
        public void onChannelOpened(ChannelClient.Channel channel) {
            PhoneLog.d(TAG, "🔗 收到 Channel 打开回调, Path: " + channel.getPath());
        }

        @Override
        public void onChannelClosed(ChannelClient.Channel channel, int closeReason, int appSpecificErrorCode) {
            if (WEAR_CHANNEL_PATH.equals(channel.getPath())) {
                PhoneLog.d(TAG, "🔌 检测到手表端通道关闭 (Reason: " + closeReason + ", Code: " + appSpecificErrorCode + ")，准备停止服务");
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

        startForeground(
                NOTIFICATION_ID, 
                buildNotification(), 
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        );

        mChannelClient = Wearable.getChannelClient(this);
        
        Wearable.getMessageClient(this).addListener(mMessageListener);
        Wearable.getChannelClient(this).registerChannelCallback(mChannelListener);
        
        PhoneLog.d(TAG, "✅ 相机同步服务 onCreate 成功，前台通知已启动");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            PhoneLog.w(TAG, "⚠️ onStartCommand 收到 null Intent");
            return START_NOT_STICKY;
        }

        String action = intent.getAction();
        PhoneLog.d(TAG, "📩 收到 Intent 动作: " + action);

        if (ACTION_START_CAMERA.equals(action)) {
            String remoteNodeId = intent.getStringExtra("remote_node_id");
            if (remoteNodeId == null || remoteNodeId.isEmpty()) {
                remoteNodeId = WearSyncState.getNodeId(this);
            }

            if (remoteNodeId != null && !remoteNodeId.isEmpty()) {
                mCachedNodeId = remoteNodeId;
                PhoneLog.d(TAG, "📍 成功锁定手表 Node ID: " + mCachedNodeId);
            } else {
                PhoneLog.e(TAG, "❌ 无法获取手表 Node ID (WearSyncState 亦为空)，终止启动流程");
                stopSelf();
                return START_NOT_STICKY;
            }

            if (!mIsStreaming.get()) {
                PhoneLog.d(TAG, "🚀 开始初始化相机并启动推流流程");
                initCameraAndStartStreaming();
            } else {
                PhoneLog.w(TAG, "⚠️ 当前已在推流状态中，忽略重复请求");
            }

        } else if (ACTION_STOP_CAMERA.equals(action)) {
            PhoneLog.d(TAG, "🛑 收到停止相机指令，清理资源并停止服务");
            stopStreamingAndRelease();
            stopSelf();
        }

        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        PhoneLog.d(TAG, "🛑 onDestroy 触发，开始安全销毁资源...");
        
        try {
            Wearable.getMessageClient(this).removeListener(mMessageListener);
            Wearable.getChannelClient(this).unregisterChannelCallback(mChannelListener);
            PhoneLog.d(TAG, "🧹 Wearable 监听器注销成功");
        } catch (Exception e) {
            PhoneLog.w(TAG, "⚠️ 注销 Wearable 监听器时发生异常: " + e.getMessage());
        }

        if (mMainHandler != null) {
            mMainHandler.removeCallbacksAndMessages(null);
        }

        stopStreamingAndRelease();
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
        PhoneLog.d(TAG, "✅ 相机同步服务已完全安全销毁");
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ==================== 初始化与推流流程 ====================
    private void initCameraAndStartStreaming() {
        // 步骤 0: 权限检查
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            PhoneLog.e(TAG, "❌ 缺少 CAMERA 权限，无法启动相机");
            showToast("❌ 缺少相机权限！");
            stopSelf();
            return;
        }
    
        mIsStreaming.set(true);
        startBackgroundThread();
    
        try {
            // 步骤 1: 配置 H.264 编码器
            showToast("1/4 配置编码器...");
            // ... (保持原有编码器配置代码不变) ...
            mEncoder.setCallback(new EncoderCallback(), mBgHandler);
            PhoneLog.d(TAG, "⏸️ [1/4] 编码器配置完成");
    
            // 步骤 2: 配置最高画质 ImageReader
            showToast("2/4 配置拍照模块...");
            // ... (保持原有 ImageReader 配置代码不变) ...
    
          } catch (Exception e) {
        PhoneLog.e(TAG, "❌ 初始化编码器/ImageReader 失败", e);
        
        // 👇 修改这里：把异常类名和消息都显示出来
        String errMsg = e.getClass().getSimpleName() + ": " + e.getMessage();
        showToast("❌ 初始化失败: " + errMsg);
        
        stopStreamingAndRelease();
        stopSelf();
        return;
    }

    
        // --- 逻辑修改点 ---
        
        // 步骤 3: 立即启动相机硬件
        showToast("3/4 启动相机硬件...");
        startCameraHardware();
    
        // 步骤 4: 在后台异步发起 Channel 连接
        showToast("4/4 连接手表通道...");
        openChannelStream();
    }

    private void calculateMaxPhotoResolution() {
        try {
            CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            if (manager != null) {
                String cameraId = manager.getCameraIdList()[0];
                CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
                StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                if (map != null) {
                    android.util.Size[] sizes = map.getOutputSizes(ImageFormat.JPEG);
                    if (sizes != null && sizes.length > 0) {
                        // 寻找面积最大的分辨率 (即最高像素 4K/FULL)
                        android.util.Size maxSize = sizes[0];
                        for (android.util.Size size : sizes) {
                            if (size.getWidth() * size.getHeight() > maxSize.getWidth() * maxSize.getHeight()) {
                                maxSize = size;
                            }
                        }
                        photoWidth = maxSize.getWidth();
                        photoHeight = maxSize.getHeight();
                        PhoneLog.d(TAG, "🔥 成功计算出相机最高硬件拍照分辨率: " + photoWidth + "x" + photoHeight);
                    }
                }
            }
        } catch (Exception e) {
            PhoneLog.w(TAG, "⚠️ 查询最大分辨率失败，降级使用默认 1080P: " + e.getMessage());
        }
    }

    private void openChannelStream() {
        if (mCachedNodeId == null || mCachedNodeId.isEmpty()) {
            mCachedNodeId = WearSyncState.getNodeId(this);
        }

        if (mCachedNodeId == null || mCachedNodeId.isEmpty()) {
            PhoneLog.e(TAG, "❌ [2/4] 节点 ID 为空，取消 Channel 连接");
            stopStreamingAndRelease();
            stopSelf();
            return;
        }

        PhoneLog.d(TAG, "📡 [2/4] 正在发起 Channel 连接: Target=" + mCachedNodeId + ", Path=" + WEAR_CHANNEL_PATH);

        mChannelClient.openChannel(mCachedNodeId, WEAR_CHANNEL_PATH)
                .addOnSuccessListener(channel -> {
                    PhoneLog.d(TAG, "✅ [2/4] Channel 连接建立成功，获取 OutputStream...");
                    mChannelClient.getOutputStream(channel)
                            .addOnSuccessListener(outputStream -> {
                                PhoneLog.d(TAG, "🎉 [2/4] OutputStream 就绪！开始启动相机硬件与推流");
                                mChannelOutputStream = outputStream;
                                mDataOutputStream = new DataOutputStream(outputStream); // 🎯 封装包头输出流
                                startCameraHardware();
                            })
                            .addOnFailureListener(e -> {
                                PhoneLog.e(TAG, "❌ 获取 Channel OutputStream 失败", e);
                                stopStreamingAndRelease();
                                stopSelf();
                            });
                })
                .addOnFailureListener(e -> {
                    PhoneLog.e(TAG, "❌ 打开 Channel 通道失败", e);
                    stopStreamingAndRelease();
                    stopSelf();
                });
    }

    private void startCameraHardware() {
        CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        if (manager == null) {
            PhoneLog.e(TAG, "❌ 无法获取 CameraManager");
            stopStreamingAndRelease();
            stopSelf();
            return;
        }

        try {
            String cameraId = manager.getCameraIdList()[0];
            PhoneLog.d(TAG, "📷 [3/4] 正在开启相机硬件, ID: " + cameraId);

            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                return;
            }

            manager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(@NonNull CameraDevice camera) {
                    mCameraDevice = camera;
                    mIsCameraOpened.set(true);
                    PhoneLog.d(TAG, "✅ [3/4] 相机硬件已成功开启，建立 Session...");
                    createCameraCaptureSession();
                }

                @Override
                public void onDisconnected(@NonNull CameraDevice camera) {
                    PhoneLog.w(TAG, "⚠️ 相机断开连接");
                    stopStreamingAndRelease();
                    stopSelf();
                }

                @Override
                public void onError(@NonNull CameraDevice camera, int error) {
                    PhoneLog.e(TAG, "❌ 相机开启错误, Error Code: " + error);
                    stopStreamingAndRelease();
                    stopSelf();
                }
            }, mBgHandler);

        } catch (Exception e) {
            PhoneLog.e(TAG, "❌ 打开相机失败", e);
            stopStreamingAndRelease();
            stopSelf();
        }
    }

    private void createCameraCaptureSession() {
        if (mCameraDevice == null || mEncoderSurface == null || mPhotoReader == null) {
            PhoneLog.e(TAG, "❌ 建立 Session 失败：硬件或 Surface 未就绪");
            return;
        }

        try {
            List<OutputConfiguration> outputs = new ArrayList<>();
            outputs.add(new OutputConfiguration(mEncoderSurface));
            outputs.add(new OutputConfiguration(mPhotoReader.getSurface()));

            Executor executor = command -> mBgHandler.post(command);

            SessionConfiguration sessionConfig = new SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    outputs,
                    executor,
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession session) {
                            if (!mIsStreaming.get() || mCameraDevice == null) {
                                PhoneLog.w(TAG, "⚠️ Session 配置完成时相机已关闭，关闭无效 Session");
                                try { session.close(); } catch (Exception ignored) {}
                                return;
                            }
                    
                            mCaptureSession = session;
                            PhoneLog.d(TAG, "🎉 Camera CaptureSession 配置完成，启动预览推流！");
                            startPreviewRequest();
                        }
                    
                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                            PhoneLog.e(TAG, "❌ Session 配置失败");
                            stopStreamingAndRelease();
                            stopSelf();
                        }
                    }
            );

            mCameraDevice.createCaptureSession(sessionConfig);

        } catch (CameraAccessException e) {
            PhoneLog.e(TAG, "❌ 创建 CaptureSession 异常", e);
            stopStreamingAndRelease();
            stopSelf();
        }
    }

    private void startPreviewRequest() {
        if (!mIsStreaming.get() || mCameraDevice == null || mCaptureSession == null || mEncoderSurface == null) {
            PhoneLog.w(TAG, "⚠️ 相机已关闭或推流已停止，放弃发起预览 Request");
            return;
        }
    
        try {
            CaptureRequest.Builder builder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            builder.addTarget(mEncoderSurface);
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
    
            mEncoder.start();
            mCaptureSession.setRepeatingRequest(builder.build(), null, mBgHandler);
            PhoneLog.d(TAG, "🚀 预览 CaptureRequest 已成功提交！");
    
        } catch (IllegalStateException e) {
            PhoneLog.e(TAG, "⚠️ CameraDevice 已关闭，无法建立 CaptureRequest: " + e.getMessage());
        } catch (Exception e) {
            PhoneLog.e(TAG, "❌ 启动预览 Request 失败", e);
            stopStreamingAndRelease();
            stopSelf();
        }
    }

    private void captureHighResPhoto() {
        if (mCameraDevice == null || mCaptureSession == null || mPhotoReader == null) {
            PhoneLog.w(TAG, "⚠️ 相机未就绪，无法执行拍照");
            return;
        }
        try {
            CaptureRequest.Builder builder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            builder.addTarget(mPhotoReader.getSurface());
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            builder.set(CaptureRequest.JPEG_QUALITY, (byte) 95);

            mCaptureSession.capture(builder.build(), null, mBgHandler);
            PhoneLog.d(TAG, "📸 最高画质拍照请求已提交给 Session (" + photoWidth + "x" + photoHeight + ")");
        } catch (Exception e) {
            PhoneLog.e(TAG, "❌ 提交拍照请求失败", e);
        }
    }

    private void savePhoto(Image image) {
        try {
            ByteBuffer buffer = image.getPlanes()[0].getBuffer();
            byte[] data = new byte[buffer.remaining()];
            buffer.get(data);
            PhoneLog.d(TAG, "✅ 高清照片捕获成功，数据大小: " + data.length + " bytes (" + photoWidth + "x" + photoHeight + ")");
        } catch (Exception e) {
            PhoneLog.e(TAG, "❌ 读取照片数据失败", e);
        }
    }

      // ==================== 资源清理与辅助函数 ====================
    private void startBackgroundThread() {
        if (mBgThread == null) {
            mBgThread = new HandlerThread("CameraBgThread");
            mBgThread.start();
            mBgHandler = new Handler(mBgThread.getLooper());
        }
    }

    private void stopStreamingAndRelease() {
        PhoneLog.d(TAG, "🧹 开始安全释放相机与推流资源...");
        mIsStreaming.set(false);
        mIsCameraOpened.set(false);
    
        if (mCaptureSession != null) {
            try {
                mCaptureSession.close();
                PhoneLog.d(TAG, "✅ CameraCaptureSession 已关闭");
            } catch (Exception e) {
                PhoneLog.w(TAG, "⚠️ 关闭 Session 异常: " + e.getMessage());
            }
            mCaptureSession = null;
        }
    
        if (mCameraDevice != null) {
            try {
                mCameraDevice.close();
                PhoneLog.d(TAG, "✅ CameraDevice 已关闭");
            } catch (Exception e) {
                PhoneLog.w(TAG, "⚠️ 关闭 CameraDevice 异常: " + e.getMessage());
            }
            mCameraDevice = null;
        }
    
        if (mEncoder != null) {
            try {
                mEncoder.stop();
                mEncoder.release();
                PhoneLog.d(TAG, "✅ MediaCodec 编码器已释放");
            } catch (Exception e) {
                PhoneLog.w(TAG, "⚠️ 释放 MediaCodec 异常: " + e.getMessage());
            }
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

        mDataOutputStream = null;
        if (mChannelOutputStream != null) {
            try {
                mChannelOutputStream.close();
            } catch (Exception ignored) {}
            mChannelOutputStream = null;
        }
    
        if (mBgThread != null) {
            mBgThread.quitSafely();
            try {
                mBgThread.join();
                PhoneLog.d(TAG, "🧵 HandlerThread 已退出");
            } catch (InterruptedException e) {
                PhoneLog.w(TAG, "⚠️ 等待 HandlerThread 退出被打断: " + e.getMessage());
            }
            mBgThread = null;
            mBgHandler = null;
        }
    
        PhoneLog.d(TAG, "🎉 所有资源已安全清理完毕");
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
            if (!mIsStreaming.get() || mDataOutputStream == null) {
                return;
            }
        
            try {
                ByteBuffer buffer = codec.getOutputBuffer(index);
                if (buffer != null && info.size > 0) {
                    byte[] data = new byte[info.size];
                    buffer.get(data);

                    // 🎯 核心修复：按手表端协议格式写入【数据包头】+【H.264 负载】
                    synchronized (this) {
                        if (mDataOutputStream != null) {
                            mDataOutputStream.writeInt(info.size);                 // 1. 数据帧长度
                            mDataOutputStream.writeLong(info.presentationTimeUs); // 2. 时间戳
                            mDataOutputStream.writeInt(info.flags);                // 3. 关键帧标志位
                            mDataOutputStream.write(data);                        // 4. H.264 NALU 数据
                            mDataOutputStream.flush();
                        }
                    }
                }
                codec.releaseOutputBuffer(index, false);
            } catch (IllegalStateException e) {
                PhoneLog.w(TAG, "⚠️ 释放 OutputBuffer 时 Codec 已关闭，安全忽略: " + e.getMessage());
            } catch (IOException e) {
                PhoneLog.e(TAG, "❌ 发送编码数据失败: " + e.getMessage());
            }
        }

        @Override
        public void onError(@NonNull MediaCodec codec, @NonNull MediaCodec.CodecException e) {
            PhoneLog.e(TAG, "❌ MediaCodec 异常", e);
        }

        @Override
        public void onOutputFormatChanged(@NonNull MediaCodec codec, @NonNull MediaFormat format) {
            PhoneLog.d(TAG, "ℹ️ MediaCodec 输出格式改变: " + format);
        }
    }
    // ... 其他代码 ...

    /**
     * 在 Service 中显示 Toast 弹窗的辅助方法
     */
    private void showToast(final String message) {
        // 确保在主线程（UI线程）执行弹窗操作
            if (mMainHandler != null) {
                mMainHandler.post(() -> Toast.makeText(PhoneSyncCameraService.this, message, Toast.LENGTH_SHORT).show());
            }
        
    } // <-- 这是类的结束大括号

}