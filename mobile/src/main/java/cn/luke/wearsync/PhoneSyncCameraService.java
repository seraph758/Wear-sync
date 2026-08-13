package cn.luke.wearsync;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.MeteringRectangle;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.Size;
import android.view.Surface;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.google.android.gms.wearable.ChannelClient;
import com.google.android.gms.wearable.MessageClient;
import com.google.android.gms.wearable.Wearable;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

public class PhoneSyncCameraService extends Service {
    private static final String TAG = "WearSync_CameraSvc";

    // ==================== 常量定义 ====================
    public static final String ACTION_START_CAMERA = "cn.luke.wearsync.action.START_CAMERA";
    public static final String ACTION_STOP_CAMERA = "cn.luke.wearsync.action.STOP_CAMERA";
    public static final String ACTION_TAKE_PHOTO = "cn.luke.wearsync.action.TAKE_PHOTO";
    public static final String ACTION_SWITCH_CAMERA = "cn.luke.wearsync.action.SWITCH_CAMERA";
    public static final String ACTION_FOCUS_CAMERA = "cn.luke.wearsync.action.FOCUS_CAMERA";
    public static final String WEAR_MSG_PATH_TAKE_PHOTO = "/camera/take_photo";
    public static final String WEAR_CHANNEL_PATH = "/wear_data_channel/camera";

    // 🎯 极低延迟手表预览目标参数 (实际将根据硬件支持动态调整)
    private int mPreviewWidth = 320;
    private int mPreviewHeight = 320;
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
    private Surface mEncoderSurface;
    private ImageReader mPhotoReader;
    private int mCaptureFormat = ImageFormat.JPEG; 
    
    private final AtomicBoolean mIsStreaming = new AtomicBoolean(false);
    private final AtomicBoolean mIsCameraOpened = new AtomicBoolean(false);
    private int mCameraFacing = CameraCharacteristics.LENS_FACING_BACK;
    
    private String mCachedNodeId;
    private ChannelClient mChannelClient;
    private OutputStream mChannelOutputStream;
    private DataOutputStream mDataOutputStream; // 🎯 新增：封装数据包头
    
    private int mConfigFailRetryCount = 0;
    private static final int MAX_CONFIG_RETRY = 2;


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
        } else if (ACTION_TAKE_PHOTO.equals(action)) {
            PhoneLog.d(TAG, "📸 收到 Intent 拍照指令");
            captureHighResPhoto();
        } else if (ACTION_SWITCH_CAMERA.equals(action)) {
            PhoneLog.d(TAG, "🔄 收到切换摄像头指令");
            switchCamera();
        } else if (ACTION_FOCUS_CAMERA.equals(action)) {
            double x = intent.getDoubleExtra("x", 0.5);
            double y = intent.getDoubleExtra("y", 0.5);
            PhoneLog.d(TAG, "🎯 收到对焦指令: " + x + ", " + y);
            manualFocus(x, y);
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
            stopSelf();
            return;
        }
    
        mIsStreaming.set(true);
        startBackgroundThread();
    
        try {
            // 步骤 1: 选择硬件支持的最佳分辨率 (解决 320x320 可能导致的配置失败)
            chooseOptimalSizes();
            
            // 步骤 2: 配置视频编码器
            MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, mPreviewWidth, mPreviewHeight);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            format.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL);
            // 🔄 关键：在编码格式中加入旋转 90 度的标记
            format.setInteger(MediaFormat.KEY_ROTATION, 90);
            
            mEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            mEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            
            PhoneLog.d(TAG, "⏸️ [1/4] 编码器已配置 (" + mPreviewWidth + "x" + mPreviewHeight + ")，等待通道连接");
    
            // 步骤 3: 配置拍照模块 (动态选择 HEIC 或 JPEG)
            mPhotoReader = ImageReader.newInstance(photoWidth, photoHeight, mCaptureFormat, 2);
            mPhotoReader.setOnImageAvailableListener(reader -> {
                Image image = reader.acquireLatestImage();
                if (image != null) {
                    savePhoto(image);
                    image.close();
                }
            }, mBgHandler);
            PhoneLog.d(TAG, "⏸️ [2/4] ImageReader 配置完成 (" + photoWidth + "x" + photoHeight + ", Format: " + (mCaptureFormat == ImageFormat.HEIC ? "HEIC" : "JPEG") + ")");
    
        } catch (Exception e)  {
            PhoneLog.e(TAG, "❌ 初始化编码器/ImageReader 失败", e);
            stopStreamingAndRelease();
            stopSelf();
            return;
        }

        // 步骤 4: 连接手表通道 (成功后会触发 startCameraHardware)
        openChannelStream();
    }

    private void chooseOptimalSizes() {
        CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        // 用于保存最终选定的尺寸
        Size mPreviewSize;
        Size mPhotoSize;
        try {
            // 1. 获取后置摄像头 ID
            String cameraId = null;
            for (String id : manager.getCameraIdList()) {
                CameraCharacteristics characteristics = manager.getCameraCharacteristics(id);
                Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    cameraId = id;
                    break;
                }
            }
            if (cameraId == null) cameraId = manager.getCameraIdList()[0];

            // 2. 获取硬件能力
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
            StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map == null) throw new RuntimeException("无法获取 StreamConfigurationMap");

            // 3. 获取支持的尺寸
            Size[] allPreviewSizes = map.getOutputSizes(SurfaceTexture.class);
            Size[] allPhotoSizes = map.getOutputSizes(ImageFormat.JPEG);

            if (allPreviewSizes == null || allPhotoSizes == null) {
                 throw new RuntimeException("相机不支持所需格式");
            }

            // 4. 选择最接近 320x320 的支持预览分辨率 (解决 320x320 不支持导致的配置失败)
            mPreviewSize = allPreviewSizes[0];
            int minDiff = Integer.MAX_VALUE;
            for (Size size : allPreviewSizes) {
                // 寻找最接近 320x320 的，但优先保持 4:3 或 16:9
                int diff = Math.abs(size.getWidth() - 320) + Math.abs(size.getHeight() - 320);
                if (diff < minDiff) {
                    minDiff = diff;
                    mPreviewSize = size;
                }
            }
            mPreviewWidth = mPreviewSize.getWidth();
            mPreviewHeight = mPreviewSize.getHeight();
            
            // 5. 选择最大的拍照尺寸，并检测是否支持 HEIC
            int[] formats = map.getOutputFormats();
            boolean heicSupported = false;
            for (int f : formats) {
                if (f == ImageFormat.HEIC) {
                    heicSupported = true;
                    break;
                }
            }
            mCaptureFormat = heicSupported ? ImageFormat.HEIC : ImageFormat.JPEG;

            Size[] photoSizes = map.getOutputSizes(mCaptureFormat);
            if (photoSizes == null || photoSizes.length == 0) {
                 mCaptureFormat = ImageFormat.JPEG;
                 photoSizes = map.getOutputSizes(ImageFormat.JPEG);
            }

            mPhotoSize = Collections.max(Arrays.asList(photoSizes), new CompareSizesByArea());
            photoWidth = mPhotoSize.getWidth();
            photoHeight = mPhotoSize.getHeight();

            PhoneLog.d(TAG, "✅ 自动选择最佳分辨率 -> 预览: " + mPreviewWidth + "x" + mPreviewHeight + ", 拍照: " + photoWidth + "x" + photoHeight + " (" + (mCaptureFormat == ImageFormat.HEIC ? "HEIC" : "JPEG") + ")");

        } catch (Exception e) {
            PhoneLog.e(TAG, "❌ 选择最佳分辨率失败，降级使用 640x480", e);
            mPreviewWidth = 640;
            mPreviewHeight = 480;
            photoWidth = 1920;
            photoHeight = 1080;
            mPreviewSize = new Size(mPreviewWidth, mPreviewHeight);
            mPhotoSize = new Size(photoWidth, photoHeight);
        }
    }

    /**
     * 比较器：用于比较 Size 对象的面积大小
     */
    static class CompareSizesByArea implements Comparator<Size> {
        @Override
        public int compare(Size lhs, Size rhs) {
            // 使用 long 防止整数溢出
            return Long.signum((long) lhs.getWidth() * lhs.getHeight() - (long) rhs.getWidth() * rhs.getHeight());
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
            // 注意这里：getOutputStream 返回一个新的 Task，需要单独处理它的成功和失败
            mChannelClient.getOutputStream(channel)
                .addOnSuccessListener(outputStream -> {
                    PhoneLog.d(TAG, "🎉 [2/4] OutputStream 就绪！");
                    mChannelOutputStream = outputStream;
                    mDataOutputStream = new DataOutputStream(outputStream);

                    try {
                        mEncoderSurface = mEncoder.createInputSurface();
                        mEncoder.setCallback(new EncoderCallback(), mBgHandler);
                        mEncoder.start();
                        PhoneLog.d(TAG, "✅ 编码器已完全启动，Surface 已创建");
                        startCameraHardware();
                    } catch (Exception e) {
                        PhoneLog.e(TAG, "❌ 启动编码器失败", e);
                        stopStreamingAndRelease();
                        stopSelf();
                    }
                }) // ✅ 1. 闭合 getOutputStream 的 addOnSuccessListener 的括号
                .addOnFailureListener(e -> { // ✅ 2. 这是 getOutputStream 任务的失败监听器
                    PhoneLog.e(TAG, "❌ 获取 Channel OutputStream 失败", e);
                    stopStreamingAndRelease();
                    stopSelf();
                }); // ✅ 3. 结束 getOutputStream 的整个链式调用
        }) // ✅ 4. 闭合 openChannel 的 addOnSuccessListener 的括号
        .addOnFailureListener(e -> { // ✅ 5. 这是 openChannel 任务的失败监听器
            PhoneLog.e(TAG, "❌ 打开 Channel 通道失败", e);
            stopStreamingAndRelease();
            stopSelf();
        }); 
    }

      private void startCameraHardware() {
        CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        if (manager == null) {
            stopStreamingAndRelease();
            stopSelf();
            return;
        }

        try {
            String cameraId = null;
            for (String id : manager.getCameraIdList()) {
                CameraCharacteristics characteristics = manager.getCameraCharacteristics(id);
                Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == mCameraFacing) {
                    cameraId = id;
                    break;
                }
            }
            if (cameraId == null) cameraId = manager.getCameraIdList()[0];
            
            PhoneLog.d(TAG, "📷 [3/4] 正在强制开启相机硬件, ID: " + cameraId + " (Facing: " + mCameraFacing + ")");

            // 💡 核心保險：如果之前有殘留的相機實例，強制關閉它，避免佔用死鎖
            if (mCameraDevice != null) {
                try {
                    mCameraDevice.close();
                    mCameraDevice = null;
                    PhoneLog.w(TAG, "⚠️ 強制關閉先前的相機佔用實例");
                } catch (Exception ignored) {}
            }

            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                return;
            }

            manager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(@NonNull CameraDevice camera) {
                    mCameraDevice = camera;
                    mIsCameraOpened.set(true);
                    PhoneLog.d(TAG, "✅ [3/4] 相機硬體已成功開啟！");
                    
                    // 立即建立 CaptureSession
                    createCameraCaptureSession();
                }

                @Override
                public void onDisconnected(@NonNull CameraDevice camera) {
                    PhoneLog.w(TAG, "⚠️ 相機斷開連接");
                    stopStreamingAndRelease();
                    stopSelf();
                }

                @Override
                public void onError(@NonNull CameraDevice camera, int error) {
                    PhoneLog.e(TAG, "❌ 相機開啟錯誤, Error Code: " + error);
                    stopStreamingAndRelease();
                    stopSelf();
                }
            }, mBgHandler);

        } catch (Exception e) {
            PhoneLog.e(TAG, "❌ 打開相機異常", e);
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
            // 修复：创建 OutputConfiguration 列表，而不是 Surface 列表
            List<OutputConfiguration> outputConfigurations = new ArrayList<>(2);
            outputConfigurations.add(new OutputConfiguration(mEncoderSurface));
            outputConfigurations.add(new OutputConfiguration(mPhotoReader.getSurface()));
    
            Executor executor = command -> mBgHandler.post(command);
            SessionConfiguration sessionConfig = new SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    outputConfigurations, // 传入修正后的列表
                    executor,
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession session) {
                            if (!mIsStreaming.get() || mCameraDevice == null) {
                                PhoneLog.w(TAG, "⚠️ Session 配置完成时相机已关闭，关闭无效 Session");
                                try {
                                    session.close();
                                } catch (Exception ignored) {}
                                return;
                            }
                            mConfigFailRetryCount = 0;
                            mCaptureSession = session;
                            PhoneLog.d(TAG, "🎉 Camera CaptureSession 配置完成，启动预览推流！");
                            startPreviewRequest();
                        }
    
                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                            PhoneLog.e(TAG, "❌ Session 配置失败, retry=" + mConfigFailRetryCount);
                            if (mConfigFailRetryCount < MAX_CONFIG_RETRY) {
                                mConfigFailRetryCount++;
                                mBgHandler.postDelayed(() -> {
                                    PhoneLog.d(TAG, "🔄 重试 createCameraCaptureSession...");
                                    createCameraCaptureSession();
                                }, 300);
                            } else {
                                PhoneLog.e(TAG, "❌ 重试 " + MAX_CONFIG_RETRY + " 次仍失败，停止服务");
                                mConfigFailRetryCount = 0;
                                stopStreamingAndRelease();
                                stopSelf();
                            }
                        }
                    });
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
            
            // 【修改点】移除 mEncoder.start()，因为它已经在别处启动了
            // mEncoder.start(); 
            
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
            // 🔄 设置拍照时的图片旋转角度（顺时针 90 度）
            builder.set(CaptureRequest.JPEG_ORIENTATION, 90);

            mCaptureSession.capture(builder.build(), null, mBgHandler);
            PhoneLog.d(TAG, "📸 最高画质拍照请求已提交给 Session (" + photoWidth + "x" + photoHeight + ")");
        } catch (Exception e) {
            PhoneLog.e(TAG, "❌ 提交拍照请求失败", e);
        }
    }

    private void savePhoto(Image image) {
        try {
            ByteBuffer buffer = image.getPlanes()[0].getBuffer();
            byte[] encodedData = new byte[buffer.remaining()];
            buffer.get(encodedData);
            
            // 1. 🎯 [手机端保存]
            File dcimDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM);
            File wearSyncDir = new File(dcimDir, "WearSync");
            if (!wearSyncDir.exists()) wearSyncDir.mkdirs();
            
            String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
            boolean isHeic = (mCaptureFormat == ImageFormat.HEIC);
            
            String extension;
            String mimeType;
            byte[] finalData;

            if (isHeic) {
                extension = ".heic";
                mimeType = "image/heif";
                finalData = encodedData;
                PhoneLog.d(TAG, "✅ 高清照片已直存 (HEIC): IMG_" + timeStamp + extension);
            } else {
                // 假如不是 HEIC，转换成 WebP 格式文件保存 (用户要求)
                extension = ".webp";
                mimeType = "image/webp";
                Bitmap bmp = BitmapFactory.decodeByteArray(encodedData, 0, encodedData.length);
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                // ✅ minSdk 35，直接使用非废弃的 WEBP_LOSSY
                bmp.compress(Bitmap.CompressFormat.WEBP_LOSSY, 95, baos);
                finalData = baos.toByteArray();
                bmp.recycle();
                PhoneLog.d(TAG, "✅ 高清照片已转换 WebP 保存 (降级自 JPEG): IMG_" + timeStamp + extension);
            }
            
            File photoFile = new File(wearSyncDir, "IMG_" + timeStamp + extension);
            try (FileOutputStream fos = new FileOutputStream(photoFile)) {
                fos.write(finalData);
            }

            // 通知系统扫描
            MediaScannerConnection.scanFile(this, new String[]{photoFile.getAbsolutePath()}, new String[]{mimeType}, null);

            // 2. 🎯 [预览图优化] 为手表生成 320px WebP 缩略图
            SharedPreferences sp = getSharedPreferences("dndsync_prefs", Context.MODE_PRIVATE);
            boolean previewEnabled = sp.getBoolean("camera_watch_preview_enabled", true);

            if (previewEnabled && mCachedNodeId != null) {
                Bitmap originalBitmap = BitmapFactory.decodeByteArray(finalData, 0, finalData.length);
                
                if (originalBitmap != null) {
                    Matrix matrix = new Matrix();
                    // 前置摄像头可能需要镜像翻转，这里简化处理，如有需要可根据 mCameraFacing 调整
                    matrix.postRotate(90);
                    Bitmap rotatedBitmap = Bitmap.createBitmap(originalBitmap, 0, 0, originalBitmap.getWidth(), originalBitmap.getHeight(), matrix, true);
                    
                    int targetWidth = 320;
                    float ratio = (float) rotatedBitmap.getHeight() / (float) rotatedBitmap.getWidth();
                    int targetHeight = (int) (targetWidth * ratio);
                    Bitmap finalBitmap = Bitmap.createScaledBitmap(rotatedBitmap, targetWidth, targetHeight, true);
                    
                    // 🚀 名字和实际名字一样 (IMG_yyyyMMdd_HHmmss.webp)
                    String thumbName = "THUMB_" + timeStamp + ".webp";
                    File thumbFile = new File(getCacheDir(), thumbName);
                    try (FileOutputStream thumbFos = new FileOutputStream(thumbFile)) {
                        finalBitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, 75, thumbFos);
                    }
                    
                    PhoneLog.d(TAG, "📤 发送预览图: " + thumbName + " (" + thumbFile.length() + " bytes)");
                    Uri thumbUri = Uri.fromFile(thumbFile);
                    PhoneSyncFileTransferManager.sendFileToWear(this, mCachedNodeId, thumbUri, thumbName, null);
                    
                    finalBitmap.recycle();
                    if (rotatedBitmap != originalBitmap) rotatedBitmap.recycle();
                    originalBitmap.recycle();
                }
            }

        } catch (Exception e) {
            PhoneLog.e(TAG, "❌ 保存照片或生成预览异常", e);
        }
    }

    private void switchCamera() {
        mCameraFacing = (mCameraFacing == CameraCharacteristics.LENS_FACING_BACK) ? 
                        CameraCharacteristics.LENS_FACING_FRONT : CameraCharacteristics.LENS_FACING_BACK;
        
        PhoneLog.d(TAG, "🔄 正在切换到: " + (mCameraFacing == CameraCharacteristics.LENS_FACING_BACK ? "后置" : "前置"));
        
        // 重启推流流程
        stopStreamingAndRelease();
        mIsStreaming.set(true);
        startBackgroundThread();
        
        // 重新初始化并开启新摄像头
        try {
            chooseOptimalSizes();
            
            MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, mPreviewWidth, mPreviewHeight);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            format.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL);
            
            mEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            mEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            
            mPhotoReader = ImageReader.newInstance(photoWidth, photoHeight, mCaptureFormat, 2);
            mPhotoReader.setOnImageAvailableListener(reader -> {
                Image image = reader.acquireLatestImage();
                if (image != null) {
                    savePhoto(image);
                    image.close();
                }
            }, mBgHandler);

            openChannelStream(); // 内部会触发 startCameraHardware
        } catch (Exception e) {
            PhoneLog.e(TAG, "❌ 切换摄像头失败", e);
        }
    }

    private void manualFocus(double x, double y) {
        if (mCaptureSession == null || mCameraDevice == null) return;
        
        try {
            CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            String cameraId = mCameraDevice.getId();
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
            Rect sensorArraySize = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
            
            if (sensorArraySize != null) {
                int centerX = (int) (x * sensorArraySize.width());
                int centerY = (int) (y * sensorArraySize.height());
                int halfSide = 100; // 聚焦区域半边长
                
                MeteringRectangle focusArea = new MeteringRectangle(
                    Math.max(0, centerX - halfSide),
                    Math.max(0, centerY - halfSide),
                    Math.min(sensorArraySize.width(), 2 * halfSide),
                    Math.min(sensorArraySize.height(), 2 * halfSide),
                    MeteringRectangle.METERING_WEIGHT_MAX
                );
                
                CaptureRequest.Builder builder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
                builder.addTarget(mEncoderSurface);
                builder.set(CaptureRequest.CONTROL_AF_REGIONS, new MeteringRectangle[]{focusArea});
                builder.set(CaptureRequest.CONTROL_AE_REGIONS, new MeteringRectangle[]{focusArea});
                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO);
                builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START);
                builder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_START);
                
                mCaptureSession.setRepeatingRequest(builder.build(), null, mBgHandler);
            }
        } catch (Exception e) {
            PhoneLog.e(TAG, "❌ 手动对焦失败", e);
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
                // 【修改点】先 stop 再 release
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

}
