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
import android.media.ExifInterface;
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
import android.view.OrientationEventListener;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.google.android.gms.wearable.ChannelClient;
import com.google.android.gms.wearable.MessageClient;
import com.google.android.gms.wearable.Wearable;

import java.io.ByteArrayInputStream;
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
import java.util.Set;
import java.util.Comparator;
import androidx.heifwriter.HeifWriter;
import java.io.FileInputStream;
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
    private OrientationEventListener mOrientationEventListener;
    private int mDeviceOrientation = OrientationEventListener.ORIENTATION_UNKNOWN;
    private int mSensorOrientation = 0;

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

    // ==================== HEIC 直出与降级转码 ====================
    private volatile boolean mHeifFallbackEnabled = true;  // 用户开关：是否启用 HeifWriter 降级转码
    private boolean mHeicDirectSupported = false;           // Camera2 直出 HEIC 是否可用
    private boolean mHeifEncoderAvailable = false;          // HeifWriter 硬件编码器是否可用
    
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
        
        if (mOrientationEventListener != null) {
        mOrientationEventListener.disable();
        mOrientationEventListener = null;
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

        // 检查 HeifWriter 硬件编码器可用性（仅首次检查）
        if (!mHeifEncoderAvailable) {
            checkHeifEncoderAvailable();
        }

        startBackgroundThread();
        startOrientationListener();
    
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
            mPhotoReader = ImageReader.newInstance(
                    photoWidth,
                    photoHeight,
                    mCaptureFormat,
                    2
            );
            
            PhoneLog.d(TAG, "📷 [PhotoReader] 创建成功: " +
                    photoWidth + "x" + photoHeight +
                    ", format=" +
                    (mCaptureFormat == ImageFormat.HEIC ? "HEIC" : "JPEG"));
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
    
    private void startOrientationListener() {
        if (mOrientationEventListener != null) {
            mOrientationEventListener.disable();
        }
    
        mOrientationEventListener = new OrientationEventListener(this) {
            @Override
            public void onOrientationChanged(int orientation) {
                if (orientation == ORIENTATION_UNKNOWN) {
                    return;
                }
    
                mDeviceOrientation = orientation;
            }
        };
    
        if (mOrientationEventListener.canDetectOrientation()) {
            mOrientationEventListener.enable();
            PhoneLog.d(TAG, "📱 [方向] OrientationEventListener 已启动");
        } else {
            PhoneLog.w(TAG, "⚠️ [方向] 当前设备不支持方向检测");
            mOrientationEventListener = null;
        }
    }
    private void logCameraHeicCapabilities(CameraCharacteristics characteristics, StreamConfigurationMap map, String cameraId) {
        PhoneLog.d(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        PhoneLog.d(TAG, "📷 [HEIC诊断] Camera ID: " + cameraId);
    
        Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
        PhoneLog.d(TAG, "📷 [HEIC诊断] Facing: " + facing);
    
        Integer hardwareLevel = characteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);
        PhoneLog.d(TAG, "📷 [HEIC诊断] Hardware Level: " + hardwareLevel);
    
        int[] capabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
        if (capabilities != null) {
            StringBuilder capabilityText = new StringBuilder();
            for (int capability : capabilities) {
                capabilityText.append(capability).append(" ");
            }
            PhoneLog.d(TAG, "📷 [HEIC诊断] Available Capabilities: " + capabilityText);
        }
    
        int[] formats = map.getOutputFormats();
        if (formats != null) {
            PhoneLog.d(TAG, "📷 [HEIC诊断] Output Formats:");
    
            for (int format : formats) {
                String name;
    
                switch (format) {
                    case ImageFormat.JPEG:
                        name = "JPEG";
                        break;
                    case ImageFormat.HEIC:
                        name = "HEIC";
                        break;
                    case ImageFormat.YUV_420_888:
                        name = "YUV_420_888";
                        break;
                    case ImageFormat.RAW_SENSOR:
                        name = "RAW_SENSOR";
                        break;
                    case ImageFormat.RAW10:
                        name = "RAW10";
                        break;
                    case ImageFormat.RAW12:
                        name = "RAW12";
                        break;
                    case ImageFormat.DEPTH16:
                        name = "DEPTH16";
                        break;
                    case ImageFormat.DEPTH_JPEG:
                        name = "DEPTH_JPEG";
                        break;
                    default:
                        name = "UNKNOWN";
                        break;
                }
    
                PhoneLog.d(TAG, "📷 [HEIC诊断]   format=" + format + " -> " + name);
    
                Size[] sizes = map.getOutputSizes(format);
                if (sizes != null) {
                    PhoneLog.d(TAG, "📷 [HEIC诊断]   sizes=" + sizes.length);
    
                    if (format == ImageFormat.HEIC) {
                        for (Size size : sizes) {
                            PhoneLog.d(TAG, "📷 [HEIC诊断]   HEIC size: " +
                                    size.getWidth() + "x" + size.getHeight());
                        }
                    }
                }
            }
        }
    
        Size[] heicSizes = map.getOutputSizes(ImageFormat.HEIC);
        if (heicSizes != null && heicSizes.length > 0) {
            PhoneLog.d(TAG, "✅ [HEIC诊断] getOutputSizes(HEIC) 成功");
            PhoneLog.d(TAG, "📷 [HEIC诊断] HEIC 普通尺寸数量: " + heicSizes.length);
    
            for (Size size : heicSizes) {
                PhoneLog.d(TAG, "📷 [HEIC诊断] HEIC: " +
                        size.getWidth() + "x" + size.getHeight());
            }
        } else {
            PhoneLog.w(TAG, "⚠️ [HEIC诊断] getOutputSizes(HEIC) 返回为空");
        }
    
        Size[] heicHighResSizes = map.getHighResolutionOutputSizes(ImageFormat.HEIC);
        if (heicHighResSizes != null && heicHighResSizes.length > 0) {
            PhoneLog.d(TAG, "✅ [HEIC诊断] 找到 HEIC 高分辨率输出");
    
            for (Size size : heicHighResSizes) {
                PhoneLog.d(TAG, "📷 [HEIC诊断] HEIC HighRes: " +
                        size.getWidth() + "x" + size.getHeight());
            }
        } else {
            PhoneLog.d(TAG, "ℹ️ [HEIC诊断] 没有 HEIC HighRes 输出");
        }
    
        PhoneLog.d(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
    }

    /**
     * 无参包装方法：从当前相机设备获取信息并调用有参版本，确保使用 PhoneLog 输出
     */
    private void logCameraHeicCapabilities() {
        try {
            CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            if (manager == null) {
                PhoneLog.w(TAG, "⚠️ [HEIC诊断] CameraManager 为 null");
                return;
            }
            if (mCameraDevice == null) {
                PhoneLog.w(TAG, "⚠️ [HEIC诊断] 相机未打开");
                return;
            }
            String cameraId = mCameraDevice.getId();
            CameraCharacteristics chars = manager.getCameraCharacteristics(cameraId);
            StreamConfigurationMap map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map != null) {
                logCameraHeicCapabilities(chars, map, cameraId);
            }
        } catch (CameraAccessException e) {
            PhoneLog.e(TAG, "❌ [HEIC诊断] CameraAccessException", e);
        }
    }

     
    /**
     * 检查 HeifWriter 硬件编码器是否可用
     * 用于 HEIC 直出不支持时的降级方案
     *
     * @return true 如果 HEIF 编码器可用
     */
    private void checkHeifEncoderAvailable() {
        try {
            MediaCodec codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_IMAGE_HEIF);
            codec.release();
            mHeifEncoderAvailable = true;
            PhoneLog.i(TAG, "✅ HeifWriter 硬件编码器可用");
        } catch (Exception e) {
            mHeifEncoderAvailable = false;
            PhoneLog.w(TAG, "⚠️ HeifWriter 硬件编码器不可用: " + e.getMessage());
        }
    }

    /**
     * 获取当前 HEIF 降级策略的日志描述
     */
    private String getHeifStrategyDescription() {
        if (mHeicDirectSupported) {
            return "HEIC 直出";
        } else if (mHeifFallbackEnabled && mHeifEncoderAvailable) {
            return "HeifWriter 降级转码";
        } else {
            return "JPEG/WebP 兜底";
        }
    }

private static final Comparator<Size> SIZE_BY_AREA = (lhs, rhs) ->
            Long.signum(
                    (long) lhs.getWidth() * lhs.getHeight()
                            - (long) rhs.getWidth() * rhs.getHeight()
            );
    
    // 如果你类里已经有这几个字段，就不要重复声明
    private Size mPreviewSize;
    private Size mCaptureSize;
    
    /**
     * 选择最佳预览尺寸和拍照尺寸。
     *
     * 如果 HEIC 可用，则拍照格式优先 HEIC；否则回退 JPEG。
     *
     * @param characteristics    CameraCharacteristics，保留参数，方便以后扩展
     * @param map                StreamConfigurationMap
     * @param viewWidth          TextureView / 预览区域宽度
     * @param viewHeight         TextureView / 预览区域高度
     * @param maxPreviewWidth    允许的最大预览宽度，传 0 表示不限制
     * @param maxPreviewHeight   允许的最大预览高度，传 0 表示不限制
     * @param maxCaptureWidth    允许的最大拍照宽度，传 0 表示不限制
     * @param maxCaptureHeight   允许的最大拍照高度，传 0 表示不限制
     */
    private void chooseOptimalSizes() {
        try {
            CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            if (manager == null) {
                PhoneLog.e(TAG, "❌ CameraManager 为 null，无法选择最优尺寸");
                return;
            }
            String cameraId = null;
            for (String id : manager.getCameraIdList()) {
                CameraCharacteristics chars = manager.getCameraCharacteristics(id);
                Integer facing = chars.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == mCameraFacing) {
                    cameraId = id;
                    break;
                }
            }
            if (cameraId == null) cameraId = manager.getCameraIdList()[0];

            CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
            StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map == null) {
                PhoneLog.e(TAG, "❌ StreamConfigurationMap 为 null，无法选择最优尺寸");
                return;
            }

            int viewWidth = 1080;
            int viewHeight = 1920;
            int maxPreviewWidth = 0;
            int maxPreviewHeight = 0;
            int maxCaptureWidth = 0;
            int maxCaptureHeight = 0;

    
        if (viewWidth <= 0) {
            viewWidth = 1080;
        }
        if (viewHeight <= 0) {
            viewHeight = 1920;
        }
    
        // ------------------------------------------------------------------
        // 1. 检查 HEIC 支持情况
        // ------------------------------------------------------------------
    
        Size[] heicSizes = map.getOutputSizes(ImageFormat.HEIC);
        boolean mapSupport = heicSizes != null && heicSizes.length > 0;
    
        Set<Integer> readerFormats = ImageReader.getSupportedImageFormats();
        boolean readerSupport = readerFormats != null
                && readerFormats.contains(ImageFormat.HEIC);
    
        boolean canCreate = false;
        try {
            Size testSize;
            if (mapSupport) {
                // 如果相机声明支持 HEIC，就用相机支持的最小 HEIC 尺寸测试，降低内存压力
                testSize = Collections.min(Arrays.asList(heicSizes), SIZE_BY_AREA);
            } else {
                // 如果相机没有声明 HEIC 尺寸，这里只是测试 ImageReader 是否接受 HEIC 格式
                testSize = new Size(1920, 1080);
            }
    
            ImageReader testReader = ImageReader.newInstance(
                    testSize.getWidth(),
                    testSize.getHeight(),
                    ImageFormat.HEIC,
                    1
            );
            testReader.close();
            canCreate = true;
        } catch (Exception | OutOfMemoryError e) {
            canCreate = false;
        }
    
        // Camera2 直出 HEIC 时，最可靠的前提仍然是 StreamConfigurationMap 支持 HEIC。
        // 所以这里建议：mapSupport 必须为 true。
        //
        // 如果你想用“任意一个检查通过就算支持”的宽松策略，可以改成：
        // boolean heicUsable = mapSupport || readerSupport || canCreate;
        //
        // 但不建议，因为如果 map 不支持 HEIC，后续 createCaptureSession 可能失败。
        boolean heicUsable = mapSupport && (readerSupport || canCreate);
        mHeicDirectSupported = heicUsable;
    
        PhoneLog.d(TAG, "📷 [chooseOptimalSizes] HEIC检查 map=" + mapSupport
                + ", ImageReader=" + readerSupport
                + ", create=" + canCreate
                + ", heicUsable=" + heicUsable);
    
        // ------------------------------------------------------------------
        // 2. 选择拍照格式和拍照尺寸
        // ------------------------------------------------------------------
    
        Size[] captureChoices;
        if (heicUsable) {
            mCaptureFormat = ImageFormat.HEIC;
            captureChoices = heicSizes;
        } else {
            mCaptureFormat = ImageFormat.JPEG;
            captureChoices = map.getOutputSizes(ImageFormat.JPEG);
        }
    
        mCaptureSize = chooseBestCaptureSize(
                captureChoices,
                maxCaptureWidth,
                maxCaptureHeight
        );
    
        // 如果 HEIC 被选中但最终没选到尺寸，则回退 JPEG
        if (mCaptureSize == null && mCaptureFormat == ImageFormat.HEIC) {
            PhoneLog.d(TAG, "📷 [chooseOptimalSizes] HEIC 没有可用尺寸，回退 JPEG");
    
            mCaptureFormat = ImageFormat.JPEG;
            captureChoices = map.getOutputSizes(ImageFormat.JPEG);
            mCaptureSize = chooseBestCaptureSize(
                    captureChoices,
                    maxCaptureWidth,
                    maxCaptureHeight
            );
        }
    
        // 最终兜底
        if (mCaptureSize == null) {
            Size[] jpegSizes = map.getOutputSizes(ImageFormat.JPEG);
            if (jpegSizes != null && jpegSizes.length > 0) {
                mCaptureSize = jpegSizes[0];
                mCaptureFormat = ImageFormat.JPEG;
            }
        }
    
        // ------------------------------------------------------------------
        // 3. 根据拍照尺寸比例选择预览尺寸
        // ------------------------------------------------------------------
    
        Size aspectRatio;
        if (mCaptureSize != null) {
            aspectRatio = new Size(mCaptureSize.getWidth(), mCaptureSize.getHeight());
        } else {
            aspectRatio = new Size(16, 9);
        }
    
        Size[] previewChoices = map.getOutputSizes(SurfaceTexture.class);
        mPreviewSize = chooseBestPreviewSize(
                previewChoices,
                viewWidth,
                viewHeight,
                maxPreviewWidth,
                maxPreviewHeight,
                aspectRatio
        );
    
        if (mPreviewSize == null && previewChoices != null && previewChoices.length > 0) {
            mPreviewSize = previewChoices[0];
        }
    
        // ===== Fix 4: 将 chooseOptimalSizes 计算结果同步到实际使用字段 =====
        if (mPreviewSize != null) {
            mPreviewWidth = mPreviewSize.getWidth();
            mPreviewHeight = mPreviewSize.getHeight();
            PhoneLog.i(TAG, "✅ 预览尺寸已更新: " + mPreviewWidth + "x" + mPreviewHeight);
        }
        if (mCaptureSize != null) {
            photoWidth = mCaptureSize.getWidth();
            photoHeight = mCaptureSize.getHeight();
            PhoneLog.i(TAG, "✅ 拍照尺寸已更新: " + photoWidth + "x" + photoHeight);
        }

        PhoneLog.d(TAG, "📷 [chooseOptimalSizes] captureFormat="
            + (mCaptureFormat == ImageFormat.HEIC ? "HEIC" : "JPEG")
            + ", captureSize=" + mCaptureSize
            + ", previewSize=" + mPreviewSize);
    } catch (CameraAccessException e) {
        PhoneLog.e(TAG, "❌ 选择最优尺寸失败", e);
    }
    }

    private Size chooseBestCaptureSize(Size[] choices,
                                       int maxWidth,
                                       int maxHeight) {
        if (choices == null || choices.length == 0) {
            return null;
        }
    
        int maxLong = Math.max(maxWidth, maxHeight);
        int maxShort = Math.min(maxWidth, maxHeight);
        boolean hasMax = maxLong > 0 && maxShort > 0;
    
        List<Size> validSizes = new ArrayList<>();
    
        for (Size size : choices) {
            int longSide = Math.max(size.getWidth(), size.getHeight());
            int shortSide = Math.min(size.getWidth(), size.getHeight());
    
            if (!hasMax || (longSide <= maxLong && shortSide <= maxShort)) {
                validSizes.add(size);
            }
        }
    
        if (!validSizes.isEmpty()) {
            // 在限制范围内选最大的
            return Collections.max(validSizes, SIZE_BY_AREA);
        }
    
        // 如果限制范围内没有可用尺寸，就直接选设备支持的最大尺寸
        return Collections.max(Arrays.asList(choices), SIZE_BY_AREA);
    }

private Size chooseBestPreviewSize(Size[] choices,
                                   int viewWidth,
                                   int viewHeight,
                                   int maxWidth,
                                   int maxHeight,
                                   Size aspectRatio) {
    if (choices == null || choices.length == 0) {
        return null;
    }

    int maxLong = Math.max(maxWidth, maxHeight);
    int maxShort = Math.min(maxWidth, maxHeight);
    boolean hasMax = maxLong > 0 && maxShort > 0;

    int targetLong = Math.max(viewWidth, viewHeight);
    int targetShort = Math.min(viewWidth, viewHeight);

    double targetRatio = sideRatio(aspectRatio);

    // 宽高比容差，可根据需要调整
    final double ASPECT_TOLERANCE = 0.08;

    List<Size> aspectMatched = new ArrayList<>();
    List<Size> bigEnough = new ArrayList<>();
    List<Size> notBigEnough = new ArrayList<>();

    for (Size option : choices) {
        int optionLong = Math.max(option.getWidth(), option.getHeight());
        int optionShort = Math.min(option.getWidth(), option.getHeight());

        // 如果设置了最大限制，则过滤掉超过限制的预览尺寸
        if (hasMax && (optionLong > maxLong || optionShort > maxShort)) {
            continue;
        }

        double optionRatio = sideRatio(option);

        if (Math.abs(optionRatio - targetRatio) <= ASPECT_TOLERANCE) {
            aspectMatched.add(option);

            if (optionLong >= targetLong && optionShort >= targetShort) {
                bigEnough.add(option);
            } else {
                notBigEnough.add(option);
            }
        }
    }

    // 优先选择：宽高比匹配，并且大于等于预览区域的最小尺寸
    if (!bigEnough.isEmpty()) {
        return Collections.min(bigEnough, SIZE_BY_AREA);
    }

    // 其次选择：宽高比匹配，但小于预览区域的最大尺寸
    if (!notBigEnough.isEmpty()) {
        return Collections.max(notBigEnough, SIZE_BY_AREA);
    }

    // 再其次：只要宽高比匹配即可，选最大的
    if (!aspectMatched.isEmpty()) {
        return Collections.max(aspectMatched, SIZE_BY_AREA);
    }

    // 最后兜底：找宽高比最接近的尺寸
    Size best = null;
    double bestDiff = Double.MAX_VALUE;
    long bestArea = -1L;

    for (Size option : choices) {
        int optionLong = Math.max(option.getWidth(), option.getHeight());
        int optionShort = Math.min(option.getWidth(), option.getHeight());

        if (hasMax && (optionLong > maxLong || optionShort > maxShort)) {
            continue;
        }

        double diff = Math.abs(sideRatio(option) - targetRatio);
        long area = (long) option.getWidth() * option.getHeight();

        if (diff < bestDiff - 1e-6
                || (Math.abs(diff - bestDiff) <= 1e-6 && area > bestArea)) {
            best = option;
            bestDiff = diff;
            bestArea = area;
        }
    }

    return best != null ? best : choices[0];
}
    private static double sideRatio(Size size) {
    int longSide = Math.max(size.getWidth(), size.getHeight());
    int shortSide = Math.min(size.getWidth(), size.getHeight());

    if (shortSide == 0) {
        return 0d;
    }

    return (double) longSide / shortSide;
}


    /**
     * 比较器：用于比较 Size 对象的面积大小
     */



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

                    // ===== Fix 5: 读取传感器方向 =====
                    try {
                        CameraCharacteristics chars = manager.getCameraCharacteristics(camera.getId());
                        Integer sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION);
                        if (sensorOrientation != null) {
                            mSensorOrientation = sensorOrientation;
                            PhoneLog.i(TAG, "📐 传感器方向: " + mSensorOrientation + "°");
                        }
                    } catch (CameraAccessException e) {
                        PhoneLog.w(TAG, "⚠️ 读取传感器方向失败，使用默认值 0", e);
                    }

                    PhoneLog.d(TAG, "✅ [3/4] 相機硬體已成功開啟！");
                    

                    // ===== Fix 8: 调用 HEIC 诊断日志 (PhoneLog 输出) =====
                    logCameraHeicCapabilities();

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
    private int getJpegOrientation() {
        if (mDeviceOrientation == OrientationEventListener.ORIENTATION_UNKNOWN) {
            PhoneLog.w(TAG, "⚠️ [方向] 当前设备方向未知，使用 0°");
            return 0;
        }
    
        int deviceOrientation =
                (mDeviceOrientation + 45) / 90 * 90;
    
        deviceOrientation %= 360;
    
        int jpegOrientation =
                (mSensorOrientation + deviceOrientation + 360) % 360;
    
        PhoneLog.d(TAG,
                "📐 [方向计算] device=" + deviceOrientation +
                "°, sensor=" + mSensorOrientation +
                "°, jpeg=" + jpegOrientation + "°");
    
        return jpegOrientation;
    }
    private void captureHighResPhoto() {
        if (mCameraDevice == null || mCaptureSession == null || mPhotoReader == null) {
            PhoneLog.w(TAG, "⚠️ 相机未就绪，无法执行拍照");
            return;
        }
    
        try {
            CaptureRequest.Builder builder =
                    mCameraDevice.createCaptureRequest(
                            CameraDevice.TEMPLATE_STILL_CAPTURE
                    );
    
            builder.addTarget(mPhotoReader.getSurface());
    
            builder.set(
                    CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            );
    
            int jpegOrientation = getJpegOrientation();
    
            builder.set(
                    CaptureRequest.JPEG_ORIENTATION,
                    jpegOrientation
            );
    
            // HEIF 降级路径需要最高质量 JPEG 作为源数据
            int jpegQuality = (mHeifFallbackEnabled && mHeifEncoderAvailable) ? 100 : 95;
            builder.set(
                    CaptureRequest.JPEG_QUALITY,
                    (byte) jpegQuality
            );
    
            PhoneLog.d(TAG,
                    "📸 [拍照] " +
                    (mCaptureFormat == ImageFormat.HEIC ? "HEIC" : "JPEG") +
                    ", orientation=" + jpegOrientation + "°");
    
            mCaptureSession.capture(
                    builder.build(),
                    null,
                    mBgHandler
            );
    
            PhoneLog.d(TAG,
                    "📸 拍照请求已提交: " +
                    photoWidth + "x" + photoHeight +
                    ", format=" +
                    (mCaptureFormat == ImageFormat.HEIC ? "HEIC" : "JPEG"));
    
        } catch (Exception e) {
            PhoneLog.e(TAG, "❌ 提交拍照请求失败", e);
        }
    }

    private void savePhoto(Image image) {
        PhoneLog.d(TAG, "📸 [Photo] Image received: format=" +
        image.getFormat() +
        ", width=" + image.getWidth() +
        ", height=" + image.getHeight());
        try {
            ByteBuffer buffer = image.getPlanes()[0].getBuffer();
            byte[] encodedData = new byte[buffer.remaining()];
            buffer.get(encodedData);
            
            // 🎯 [核心修复] 使用 ExifInterface 获取原始旋转角度
            int rotationDegrees = 0;
            try {
                ExifInterface exif = new ExifInterface(new ByteArrayInputStream(encodedData));
                int orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
                switch (orientation) {
                    case ExifInterface.ORIENTATION_ROTATE_90: rotationDegrees = 90; break;
                    case ExifInterface.ORIENTATION_ROTATE_180: rotationDegrees = 180; break;
                    case ExifInterface.ORIENTATION_ROTATE_270: rotationDegrees = 270; break;
                }
            } catch (IOException e) {
                PhoneLog.w(TAG, "⚠️ 无法读取图片 EXIF 信息: " + e.getMessage());
            }
            
            
            
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
                // ===== Fix 6: HEIC 照片写入 Exif 旋转信息 =====
                if (rotationDegrees != 0) {
                    try {
                        File tempFile = File.createTempFile("heic_exif_", ".heic", getCacheDir());
                        try (FileOutputStream fos = new FileOutputStream(tempFile)) {
                            fos.write(encodedData);
                        }
                        ExifInterface exif = new ExifInterface(tempFile.getAbsolutePath());
                        int exifOrientation;
                        switch (rotationDegrees) {
                            case 90:  exifOrientation = ExifInterface.ORIENTATION_ROTATE_90; break;
                            case 180: exifOrientation = ExifInterface.ORIENTATION_ROTATE_180; break;
                            case 270: exifOrientation = ExifInterface.ORIENTATION_ROTATE_270; break;
                            default:  exifOrientation = ExifInterface.ORIENTATION_NORMAL; break;
                        }
                        exif.setAttribute(ExifInterface.TAG_ORIENTATION, String.valueOf(exifOrientation));
                        exif.saveAttributes();
                        try (FileInputStream fis = new FileInputStream(tempFile)) {
                            ByteArrayOutputStream baos = new ByteArrayOutputStream();
                            byte[] buffer = new byte[8192];
                            int len;
                            while ((len = fis.read(buffer)) != -1) {
                                baos.write(buffer, 0, len);
                            }
                            finalData = baos.toByteArray();
                        }
                        tempFile.delete();
                        PhoneLog.i(TAG, "✅ HEIC 已写入 EXIF 方向: " + rotationDegrees + "°");
                    } catch (IOException e) {
                        PhoneLog.w(TAG, "⚠️ HEIC 写入 EXIF 失败，保存原始数据", e);
                        finalData = encodedData;
                    }
                } else {
                    finalData = encodedData;
                }
                PhoneLog.d(TAG, "✅ 高清照片已直存 (HEIC): IMG_" + timeStamp + extension);
            } else {
                // JPEG 路径：根据降级策略决定输出格式
                if (mHeifFallbackEnabled && mHeifEncoderAvailable) {
                    // ===== 策略2：HeifWriter 高质量转码 → .heif =====
                    extension = ".heif";
                    mimeType = "image/heif";
                    
                    Bitmap rawBmp = BitmapFactory.decodeByteArray(encodedData, 0, encodedData.length);
                    if (rawBmp != null) {
                        try {
                            File photoFile = new File(wearSyncDir, "IMG_" + timeStamp + extension);
                            HeifWriter heifWriter = new HeifWriter.Builder(photoFile.getAbsolutePath())
                                    .setWidth(rawBmp.getWidth())
                                    .setHeight(rawBmp.getHeight())
                                    .setQuality(95)
                                    .setRotation(rotationDegrees)
                                    .build();
                            
                            heifWriter.addBitmap(rawBmp);
                            heifWriter.stop(5000);
                            heifWriter.close();
                            rawBmp.recycle();
                            
                            finalData = null; // HeifWriter 已直接写入文件
                            PhoneLog.i(TAG, "策略2: HeifWriter 转码成功，体积: " + photoFile.length() / 1024 + "KB");
                        } catch (Exception e) {
                            PhoneLog.e(TAG, "策略2 HeifWriter 转码失败，降级到策略3", e);
                            // 降级到策略3：保存 WebP
                            extension = ".webp";
                            mimeType = "image/webp";
                            finalData = encodedData;
                        }
                    } else {
                        // Bitmap 解码失败，降级到策略3
                        extension = ".webp";
                        mimeType = "image/webp";
                        finalData = encodedData;
                    }
                } else {
                    // ===== 策略3：直接保存 WebP（兜底） =====
                    extension = ".webp";
                    mimeType = "image/webp";
                    
                    Bitmap rawBmp = BitmapFactory.decodeByteArray(encodedData, 0, encodedData.length);
                    if (rawBmp != null) {
                        // 物理旋转补正
                        Matrix matrix = new Matrix();
                        matrix.postRotate(rotationDegrees);
                        Bitmap bmp = Bitmap.createBitmap(rawBmp, 0, 0, rawBmp.getWidth(), rawBmp.getHeight(), matrix, true);
                        
                        ByteArrayOutputStream baos = new ByteArrayOutputStream();
                        // minSdk 35，直接使用 WEBP_LOSSY
                        bmp.compress(Bitmap.CompressFormat.WEBP_LOSSY, 95, baos);
                        finalData = baos.toByteArray();
                        
                        bmp.recycle();
                        if (bmp != rawBmp) rawBmp.recycle();
                        PhoneLog.d(TAG, "策略3: 保存 WebP 兜底: IMG_" + timeStamp + extension);
                    } else {
                        finalData = encodedData;
                        extension = ".jpg";
                        mimeType = "image/jpeg";
                    }
                }
            }
            
            // 写入文件（HeifWriter 路径已直接写入，跳过 FileOutputStream）
            if (finalData != null) {
                File photoFile = new File(wearSyncDir, "IMG_" + timeStamp + extension);
                try (FileOutputStream fos = new FileOutputStream(photoFile)) {
                    fos.write(finalData);
                }
            }

            // 通知系统扫描
            MediaScannerConnection.scanFile(getApplicationContext(), new String[]{photoFile.getAbsolutePath()}, new String[]{mimeType}, null);

            // 2. 🎯 [预览图优化] 为手表生成 320px WebP 缩略图
            SharedPreferences sp = getSharedPreferences("dndsync_prefs", Context.MODE_PRIVATE);
            boolean previewEnabled = sp.getBoolean("camera_watch_preview_enabled", true);

            // 🛡️ [防崩溃] 检查 nodeId 和 streaming 状态
            String nodeId = mCachedNodeId;
            if (previewEnabled && nodeId != null && mIsStreaming.get()) {
                Bitmap originalBitmap = BitmapFactory.decodeByteArray(finalData, 0, finalData.length);
                
                if (originalBitmap != null) {
                    // 🚀 注意：finalData 已经是旋转补正过的了，不需要再次旋转 90 度
                    int targetWidth = 320;
                    float ratio = (float) originalBitmap.getHeight() / (float) originalBitmap.getWidth();
                    int targetHeight = (int) (targetWidth * ratio);
                    Bitmap finalBitmap = Bitmap.createScaledBitmap(originalBitmap, targetWidth, targetHeight, true);
                    
                    String thumbName = "THUMB_" + timeStamp + ".webp";
                    File thumbFile = new File(getCacheDir(), thumbName);
                    try (FileOutputStream thumbFos = new FileOutputStream(thumbFile)) {
                        finalBitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, 75, thumbFos);
                    }
                    
                    // 🛡️ [防崩溃] 再次确认状态，防止在压缩期间通道被关闭
                    if (mIsStreaming.get()) {
                        PhoneLog.d(TAG, "📤 发送预览图: " + thumbName + " (" + thumbFile.length() + " bytes)");
                        Uri thumbUri = Uri.fromFile(thumbFile);
                        PhoneSyncFileTransferManager.sendFileToWear(getApplicationContext(), nodeId, thumbUri, thumbName, null);
                    }
                    
                    finalBitmap.recycle();
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
            PhoneLog.d(TAG, "📷 [PhotoReader] format=" +
            mCaptureFormat +
            ", width=" + photoWidth +
            ", height=" + photoHeight +
            ", imageFormat=" +
            (mCaptureFormat == ImageFormat.HEIC ? "HEIC" : "JPEG"));
                mPhotoReader.setOnImageAvailableListener(reader -> {
                    Image image = reader.acquireLatestImage();
                    if (image != null) {
                        savePhoto(image);
                        image.close();
                    }
                }, mBgHandler);
    
                    // ===== Fix 7: 重启方向监听器 =====
                    startOrientationListener();
                    // ===== Fix 11: 重置配置失败计数器 =====
                    mConfigFailRetryCount = 0;
                    // ===== HEIF 降级策略：chooseOptimalSizes 会重新探测 HEIC 支持 =====

                openChannelStream(); // 内部会触发 startCameraHardware
            } catch (Exception e) {
                PhoneLog.e(TAG, "❌ 切换摄像头失败", e);
            }
        }

    private void manualFocus(double x, double y) {
        // ===== Fix 10: 编码器 Surface 未就绪时跳过 =====
        if (mEncoderSurface == null) {
            PhoneLog.w(TAG, "⚠️ 编码器 Surface 未就绪，忽略对焦请求");
            return;
        }

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