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
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.Size;
import android.view.OrientationEventListener;
import android.view.Surface;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.exifinterface.media.ExifInterface;

import com.google.android.gms.wearable.ChannelClient;
import com.google.android.gms.wearable.MessageClient;
import com.google.android.gms.wearable.Wearable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 手机端推流服务：负责开启相机、视频编码、通过 ChannelClient 向手表发送 H.264 流
 * 以及执行高清拍照和文件同步。
 */
public class PhoneSyncCameraService extends Service {
    private static final String TAG = "WearSync_CameraSvc";

    // ==================== 常量定义 ====================
    public static final String ACTION_START_CAMERA = "cn.luke.wearsync.action.START_CAMERA";
    public static final String ACTION_STOP_CAMERA = "cn.luke.wearsync.action.STOP_CAMERA";
    public static final String ACTION_TAKE_PHOTO = "cn.luke.wearsync.action.TAKE_PHOTO";
    public static final String ACTION_SWITCH_CAMERA = "cn.luke.wearsync.action.SWITCH_CAMERA";
    public static final String ACTION_SET_ZOOM = "cn.luke.wearsync.action.SET_ZOOM";
    public static final String ACTION_FOCUS_CAMERA = "cn.luke.wearsync.action.FOCUS_CAMERA";
    public static final String WEAR_MSG_PATH_TAKE_PHOTO = "/camera/take_photo";
    public static final String WEAR_CHANNEL_PATH = "/wear_data_channel/camera";
    public static final String WEAR_MSG_PATH_CAMERA_LIST = "/camera/info_list";

    private static final int BIT_RATE = 400_000; // 400Kbps
    private static final int FRAME_RATE = 25;
    private static final int I_FRAME_INTERVAL = 1;

    private static final String CHANNEL_ID = "camera_service_channel";
    private static final int NOTIFICATION_ID = 101;

    // ==================== 状态与配置 ====================
    // 🎯 极低延迟手表预览目标参数
    private int mPreviewWidth = 256;
    private int mPreviewHeight = 256;
    private int photoWidth = 1920;
    private int photoHeight = 1080;
    
    private int mDeviceOrientation = OrientationEventListener.ORIENTATION_UNKNOWN;
    private int mSensorOrientation = 0;
    private String mCameraId; // 🎯 当前选中的 Camera ID
    private float mCurrentZoom = 1.0f; // 🎯 当前缩放倍数
    private float mMaxZoom = 1.0f; // 🎯 当前镜头的最大缩放
    private Rect mActiveArraySize; // 🎯 当前镜头的活跃像素区域
    
    private final AtomicBoolean mIsStreaming = new AtomicBoolean(false);
    private final AtomicBoolean mIsCameraOpened = new AtomicBoolean(false);
    private final boolean mHeifFallbackEnabled = true;  
    private boolean mHeifEncoderAvailable = false;          
    private int mCaptureFormat = ImageFormat.JPEG; 

    // ==================== 核心组件 ====================
    private HandlerThread mBgThread;
    private Handler mBgHandler;
    private Handler mMainHandler;
    
    private CameraDevice mCameraDevice;
    private CameraCaptureSession mCaptureSession;
    private MediaCodec mEncoder;
    private Surface mEncoderSurface;
    private ImageReader mPhotoReader;
    
    private String mCachedNodeId;
    private ChannelClient mChannelClient;
    private OutputStream mChannelOutputStream;
    private DataOutputStream mDataOutputStream; 
    
    private int mConfigFailRetryCount = 0;
    private static final int MAX_CONFIG_RETRY = 2;

    private OrientationEventListener mOrientationEventListener;

    private static final Comparator<Size> SIZE_BY_AREA = (lhs, rhs) ->
            Long.signum((long) lhs.getWidth() * lhs.getHeight() - (long) rhs.getWidth() * rhs.getHeight());

    // ==================== 监听器 ====================
    private final MessageClient.OnMessageReceivedListener mMessageListener = event -> {
        if (WEAR_MSG_PATH_TAKE_PHOTO.equals(event.getPath())) {
            PhoneLog.d(TAG, "📸 收到手表端拍照指令");
            captureHighResPhoto();
        }
    };

    private final ChannelClient.ChannelCallback mChannelListener = new ChannelClient.ChannelCallback() {
        @Override
        public void onChannelOpened(@NonNull ChannelClient.Channel channel) {
            PhoneLog.d(TAG, "🔗 收到 Channel 打开回调, Path: " + channel.getPath());
        }

        @Override
        public void onChannelClosed(@NonNull ChannelClient.Channel channel, int closeReason, int appSpecificErrorCode) {
            if (WEAR_CHANNEL_PATH.equals(channel.getPath())) {
                PhoneLog.d(TAG, "🔌 检测到手表端通道关闭，停止服务");
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
        mChannelClient.registerChannelCallback(mChannelListener);
        
        PhoneLog.d(TAG, "✅ 相机同步服务已就绪");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;

        final String action = intent.getAction();
        PhoneLog.d(TAG, "📩 收到 Intent 动作: " + action);

        if (ACTION_START_CAMERA.equals(action)) {
            final String nodeIdFromIntent = intent.getStringExtra("remote_node_id");
            final String nodeId = (nodeIdFromIntent != null && !nodeIdFromIntent.isEmpty()) 
                ? nodeIdFromIntent : WearSyncState.getNodeId(this);

            if (nodeId != null && !nodeId.isEmpty()) {
                mCachedNodeId = nodeId;
                PhoneLog.d(TAG, "📍 锁定手表 Node ID: " + mCachedNodeId);
            } else {
                PhoneLog.e(TAG, "❌ 无法获取手表 Node ID");
                stopSelf();
                return START_NOT_STICKY;
            }

            if (!mIsStreaming.get()) {
                initCameraAndStartStreaming();
            }

        } else if (ACTION_STOP_CAMERA.equals(action)) {
            stopStreamingAndRelease();
            stopSelf();
        } else if (ACTION_TAKE_PHOTO.equals(action)) {
            captureHighResPhoto();
        } else if (ACTION_SWITCH_CAMERA.equals(action)) {
            String targetId = intent.getStringExtra("camera_id");
            if (targetId != null) {
                switchCamera(targetId);
            }
        } else if (ACTION_FOCUS_CAMERA.equals(action)) {
            double x = intent.getDoubleExtra("x", 0.5);
            double y = intent.getDoubleExtra("y", 0.5);
            manualFocus(x, y);
        } else if (ACTION_SET_ZOOM.equals(action)) {
            float zoom = intent.getFloatExtra("zoom", 1.0f);
            setZoom(zoom);
        }

        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        PhoneLog.d(TAG, "🛑 onDestroy 触发");
        Wearable.getMessageClient(this).removeListener(mMessageListener);
        mChannelClient.unregisterChannelCallback(mChannelListener);

        if (mMainHandler != null) mMainHandler.removeCallbacksAndMessages(null);
        if (mOrientationEventListener != null) mOrientationEventListener.disable();

        stopStreamingAndRelease();
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void initCameraAndStartStreaming() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            PhoneLog.e(TAG, "❌ 缺少相机权限");
            stopSelf();
            return;
        }
    
        mIsStreaming.set(true);
        if (!mHeifEncoderAvailable) checkHeifEncoderAvailable();

        startBackgroundThread();
        startOrientationListener();
    
        try {
            chooseOptimalSizes();
            
            final MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, mPreviewWidth, mPreviewHeight);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            format.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL);
            format.setInteger(MediaFormat.KEY_ROTATION, 90);
            
            mEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            mEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            
            mPhotoReader = ImageReader.newInstance(photoWidth, photoHeight, mCaptureFormat, 2);
            mPhotoReader.setOnImageAvailableListener(reader -> {
                final Image image = reader.acquireLatestImage();
                if (image != null) {
                    savePhoto(image);
                    image.close();
                }
            }, mBgHandler);
            
            openChannelStream();
        } catch (Exception e)  {
            PhoneLog.e(TAG, "❌ 初始化推流失败", e);
            stopStreamingAndRelease();
            stopSelf();
        }
    }
    
    private void startOrientationListener() {
        if (mOrientationEventListener != null) mOrientationEventListener.disable();
        mOrientationEventListener = new OrientationEventListener(this) {
            @Override
            public void onOrientationChanged(int orientation) {
                if (orientation != ORIENTATION_UNKNOWN) mDeviceOrientation = orientation;
            }
        };
        if (mOrientationEventListener.canDetectOrientation()) mOrientationEventListener.enable();
    }

    private void logCameraHeicCapabilities(StreamConfigurationMap map, String cameraId) {
        PhoneLog.d(TAG, "📷 [HEIC诊断] Camera ID: " + cameraId);
        final int[] formats = map.getOutputFormats();
        if (formats != null) {
            for (final int format : formats) {
                final String name = switch (format) {
                    case ImageFormat.JPEG -> "JPEG";
                    case ImageFormat.HEIC -> "HEIC";
                    case ImageFormat.YUV_420_888 -> "YUV_420_888";
                    default -> "UNKNOWN(" + format + ")";
                };
                final Size[] sizes = map.getOutputSizes(format);
                if (sizes != null) PhoneLog.d(TAG, "📷   format=" + name + ", sizes=" + sizes.length);
            }
        }
    }

    private void checkHeifEncoderAvailable() {
        try {
            final MediaCodec codec = MediaCodec.createEncoderByType("image/heif");
            codec.release();
            mHeifEncoderAvailable = true;
            PhoneLog.i(TAG, "✅ HeifWriter 硬件编码器可用");
        } catch (Exception e) {
            mHeifEncoderAvailable = false;
        }
    }

    private void chooseOptimalSizes() {
        try {
            final CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            if (manager == null) return;
            
            // 🎯 如果没有指定 ID，则自动选择主摄
            if (mCameraId == null) {
                mCameraId = getBestBackCameraId(manager);
            }
            
            if (mCameraId == null) return;

            final CameraCharacteristics chars = manager.getCameraCharacteristics(mCameraId);
            final StreamConfigurationMap map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map == null) return;

            // 🎯 读取变焦与像素区域信息
            Float maxZoom = chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM);
            mMaxZoom = (maxZoom != null) ? maxZoom : 1.0f;
            mActiveArraySize = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
            mCurrentZoom = 1.0f; // 切换镜头重置缩放

            // 1. HEIC 支持检测
            final Size[] heicSizes = map.getOutputSizes(ImageFormat.HEIC);
            final boolean heicUsable = heicSizes != null && heicSizes.length > 0 && testImageReaderHeic();
            PhoneLog.d(TAG, "📷 [HEIC检查] ID=" + mCameraId + " 直出支持: " + heicUsable);
            
            // 2. 选择拍照参数
            final Size captureSize;
            if (heicUsable) {
                mCaptureFormat = ImageFormat.HEIC;
                captureSize = chooseBestSize(heicSizes);
            } else {
                mCaptureFormat = ImageFormat.JPEG;
                captureSize = chooseBestSize(map.getOutputSizes(ImageFormat.JPEG));
            }

            // 3. 选择预览参数
            final Size aspectRatio = (captureSize != null) ? captureSize : new Size(16, 9);
            final Size previewSize = chooseBestPreviewSize(map.getOutputSizes(SurfaceTexture.class), aspectRatio);

            // 4. 同步结果
            applySelectedSizes(previewSize, captureSize);
            logCameraHeicCapabilities(map, mCameraId);
        } catch (CameraAccessException e) {
            PhoneLog.e(TAG, "❌ 选择最优尺寸失败", e);
        }
    }

    private String getBestBackCameraId(CameraManager manager) throws CameraAccessException {
        for (final String id : manager.getCameraIdList()) {
            final CameraCharacteristics chars = manager.getCameraCharacteristics(id);
            final Integer facing = chars.get(CameraCharacteristics.LENS_FACING);
            if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) return id;
        }
        final String[] ids = manager.getCameraIdList();
        return ids.length > 0 ? ids[0] : null;
    }

    private boolean testImageReaderHeic() {
        try {
            final ImageReader testReader = ImageReader.newInstance(1920, 1080, ImageFormat.HEIC, 1);
            testReader.close();
            return true;
        } catch (Exception | OutOfMemoryError e) {
            return false;
        }
    }

    private void applySelectedSizes(Size preview, Size capture) {
        if (preview != null) {
            mPreviewWidth = preview.getWidth();
            mPreviewHeight = preview.getHeight();
        }
        if (capture != null) {
            photoWidth = capture.getWidth();
            photoHeight = capture.getHeight();
        }
    }

    private Size chooseBestSize(Size[] choices) {
        if (choices == null || choices.length == 0) return null;
        final List<Size> valid = new ArrayList<>();
        for (final Size s : choices) {
            // 🎯 支持 4096×3072 等高分辨率
            if (s.getWidth() <= 4096 && s.getHeight() <= 3072) valid.add(s);
        }
        return Collections.max(valid.isEmpty() ? Arrays.asList(choices) : valid, SIZE_BY_AREA);
    }

    private Size chooseBestPreviewSize(Size[] choices, Size aspectRatio) {
        if (choices == null || choices.length == 0) return null;
        
        // 🎯 目标：宽度 256，高度等比缩放
        final int targetWidth = 256;
        final double targetRatio = sideRatio(aspectRatio);
        
        final double TOLERANCE = 0.1;
        List<Size> matched = new ArrayList<>();
        for (final Size s : choices) {
            if (Math.abs(sideRatio(s) - targetRatio) < TOLERANCE) {
                matched.add(s);
            }
        }
        
        if (!matched.isEmpty()) {
            // 在比例匹配的尺寸中，寻找最接近 256 宽度的尺寸
            Size result = matched.get(0);
            int minDiff = Math.abs(result.getWidth() - targetWidth);
            for (final Size s : matched) {
                int diff = Math.abs(s.getWidth() - targetWidth);
                if (diff < minDiff) {
                    minDiff = diff;
                    result = s;
                }
            }
            return result;
        }
        
        return choices[0];
    }

    private static double sideRatio(Size size) {
        final int w = size.getWidth(), h = size.getHeight();
        return (double) Math.max(w, h) / Math.min(w, h);
    }

    private void openChannelStream() {
        if (mCachedNodeId == null) mCachedNodeId = WearSyncState.getNodeId(this);
        if (mCachedNodeId == null) {
            stopStreamingAndRelease();
            stopSelf();
            return;
        }

        mChannelClient.openChannel(mCachedNodeId, WEAR_CHANNEL_PATH)
        .addOnSuccessListener(channel -> mChannelClient.getOutputStream(channel)
            .addOnSuccessListener(outputStream -> {
                mChannelOutputStream = outputStream;
                mDataOutputStream = new DataOutputStream(outputStream);
                
                // 🎯 开启通道后，立即发送镜头列表
                sendCameraListToWear();
                
                try {
                    if (mEncoder == null || !mIsStreaming.get()) return;
                    mEncoderSurface = mEncoder.createInputSurface();
                    mEncoder.setCallback(new EncoderCallback(), mBgHandler);
                    mEncoder.start();
                    startCameraHardware();
                } catch (Exception e) {
                    PhoneLog.e(TAG, "❌ 启动编码器失败", e);
                    stopStreamingAndRelease();
                    stopSelf();
                }
            })
            .addOnFailureListener(e -> { stopStreamingAndRelease(); stopSelf(); }))
        .addOnFailureListener(e -> { stopStreamingAndRelease(); stopSelf(); }); 
    }

    private void sendCameraListToWear() {
        if (mCachedNodeId == null) return;
        new Thread(() -> {
            try {
                CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
                String[] ids = manager.getCameraIdList();
                JSONArray array = new JSONArray();
                
                for (String id : ids) {
                    CameraCharacteristics chars = manager.getCameraCharacteristics(id);
                    StreamConfigurationMap map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                    if (map == null) continue;
                    
                    // 🎯 筛选适合拍照的摄像头
                    Size[] sizes = map.getOutputSizes(ImageFormat.JPEG);
                    if (sizes == null || sizes.length == 0) continue;
                    
                    Size maxRes = Collections.max(Arrays.asList(sizes), SIZE_BY_AREA);
                    if (maxRes.getWidth() < 1280) continue; // 过滤掉分辨率过低的
                    
                    // 过滤特殊用途镜头
                    int[] capabilities = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
                    boolean isLogical = false;
                    if (capabilities != null) {
                        for (int cap : capabilities) {
                            if (cap == CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA) {
                                isLogical = true;
                                break;
                            }
                        }
                    }

                    Integer facing = chars.get(CameraCharacteristics.LENS_FACING);
                    float[] focalLengths = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
                    Float maxZoom = chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM);
                    
                    JSONObject obj = new JSONObject();
                    obj.put("id", id);
                    obj.put("facing", facing);
                    obj.put("maxZoom", maxZoom != null ? maxZoom : 1.0f);
                    obj.put("width", maxRes.getWidth());
                    obj.put("height", maxRes.getHeight());
                    
                    // 动态命名
                    String name;
                    if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                        name = "前置镜头";
                    } else if (isLogical) {
                        name = "多摄主镜";
                    } else {
                        // 根据焦距简单判断（如果支持的话）
                        if (focalLengths != null && focalLengths.length > 0) {
                            float f = focalLengths[0];
                            if (f < 3.0f) name = "超广角";
                            else if (f > 6.0f) name = "长焦镜头";
                            else name = "后置主摄";
                        } else {
                            name = "后置镜头 " + id;
                        }
                    }
                    obj.put("name", name);
                    array.put(obj);
                }
                
                PhoneLog.d(TAG, "📤 发送镜头列表给手表: " + array.length() + " 个镜头");
                Wearable.getMessageClient(this).sendMessage(mCachedNodeId, WEAR_MSG_PATH_CAMERA_LIST, array.toString().getBytes(StandardCharsets.UTF_8));
            } catch (Exception e) {
                PhoneLog.e(TAG, "❌ 获取/发送镜头列表失败", e);
            }
        }).start();
    }

    private void startCameraHardware() {
        final CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        if (manager == null) return;

        try {
            if (mCameraId == null) mCameraId = getBestBackCameraId(manager);
            if (mCameraId == null) return;
            
            PhoneLog.d(TAG, "📷 [3/4] 开启相机: " + mCameraId);
            
            if (mCameraDevice != null) {
                mCameraDevice.close();
                mCameraDevice = null;
            }

            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return;

            manager.openCamera(mCameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(@NonNull CameraDevice camera) {
                    mCameraDevice = camera;
                    mIsCameraOpened.set(true);
                    try {
                        final CameraCharacteristics chars = manager.getCameraCharacteristics(camera.getId());
                        final Integer sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION);
                        if (sensorOrientation != null) mSensorOrientation = sensorOrientation;
                    } catch (CameraAccessException ignored) {}
                    createCameraCaptureSession();
                }

                @Override
                public void onDisconnected(@NonNull CameraDevice camera) { stopStreamingAndRelease(); stopSelf(); }

                @Override
                public void onError(@NonNull CameraDevice camera, int error) { stopStreamingAndRelease(); stopSelf(); }
            }, mBgHandler);

        } catch (Exception e) {
            PhoneLog.e(TAG, "❌ 打开相机硬件异常", e);
            stopStreamingAndRelease();
            stopSelf();
        }
    }

    private void createCameraCaptureSession() {
        if (mCameraDevice == null || mEncoderSurface == null || mPhotoReader == null) return;
        try {
            final List<OutputConfiguration> outputs = Arrays.asList(
                new OutputConfiguration(mEncoderSurface),
                new OutputConfiguration(mPhotoReader.getSurface())
            );
    
            final SessionConfiguration sessionConfig = new SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    outputs,
                    command -> mBgHandler.post(command),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession session) {
                            if (!mIsStreaming.get() || mCameraDevice == null) {
                                session.close();
                                return;
                            }
                            mConfigFailRetryCount = 0;
                            mCaptureSession = session;
                            startPreviewRequest();
                        }
    
                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                            if (mConfigFailRetryCount < MAX_CONFIG_RETRY) {
                                mConfigFailRetryCount++;
                                mBgHandler.postDelayed(PhoneSyncCameraService.this::createCameraCaptureSession, 300);
                            } else {
                                stopStreamingAndRelease();
                                stopSelf();
                            }
                        }
                    });
            mCameraDevice.createCaptureSession(sessionConfig);
        } catch (CameraAccessException e) {
            PhoneLog.e(TAG, "❌ 创建会话异常", e);
            stopStreamingAndRelease();
            stopSelf();
        }
    }

    private void startPreviewRequest() {
        if (!mIsStreaming.get() || mCameraDevice == null || mCaptureSession == null || mEncoderSurface == null) return;
        try {
            final CaptureRequest.Builder builder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            builder.addTarget(mEncoderSurface);
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
            
            // 🎯 应用变焦
            Rect zoomRect = calculateZoomRect(mCurrentZoom);
            if (zoomRect != null) builder.set(CaptureRequest.SCALER_CROP_REGION, zoomRect);
            
            mCaptureSession.setRepeatingRequest(builder.build(), null, mBgHandler);
        } catch (Exception e) {
            PhoneLog.e(TAG, "❌ 发起预览失败", e);
            stopStreamingAndRelease();
            stopSelf();
        }
    }

    private void captureHighResPhoto() {
        if (mCameraDevice == null || mCaptureSession == null || mPhotoReader == null) return;
        try {
            final CaptureRequest.Builder builder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            builder.addTarget(mPhotoReader.getSurface());
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
    
            final int deviceRot = (mDeviceOrientation != OrientationEventListener.ORIENTATION_UNKNOWN) ? (mDeviceOrientation + 45) / 90 * 90 : 0;
             final int jpegOrientation = (mSensorOrientation + deviceRot) % 360;
            builder.set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation);
            builder.set(CaptureRequest.JPEG_QUALITY, (byte) 100);
            
            // 🎯 构图一致：应用当前的变焦
            Rect zoomRect = calculateZoomRect(mCurrentZoom);
            if (zoomRect != null) builder.set(CaptureRequest.SCALER_CROP_REGION, zoomRect);
    
            mCaptureSession.capture(builder.build(), null, mBgHandler);
        } catch (Exception e) {
            PhoneLog.e(TAG, "❌ 拍照请求失败", e);
        }
    }

    /**
     * 保存拍照结果：直接保存高质量 JPEG 或 HEIC，保留 EXIF 信息。
     * 手机端不再为手表生成缩略图，也不通过 Channel 发送文件。
     */
    private void savePhoto(Image image) {
        try {
            final ByteBuffer buffer = image.getPlanes()[0].getBuffer();
            final byte[] encodedData = new byte[buffer.remaining()];
            buffer.get(encodedData);
            
            final int rotationDegrees;
            try {
                final ExifInterface exif = new ExifInterface(new ByteArrayInputStream(encodedData));
                final int orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
                rotationDegrees = switch (orientation) {
                    case ExifInterface.ORIENTATION_ROTATE_90 -> 90;
                    case ExifInterface.ORIENTATION_ROTATE_180 -> 180;
                    case ExifInterface.ORIENTATION_ROTATE_270 -> 270;
                    default -> 0;
                };
            } catch (IOException e) {
                PhoneLog.w(TAG, "⚠️ 无法读取图片 EXIF 信息");
                return;
            }

            final File dcimDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM);
            final File wearSyncDir = new File(dcimDir, "WearSync");
            if (!wearSyncDir.exists() && !wearSyncDir.mkdirs()) {
                PhoneLog.e(TAG, "❌ 无法创建 WearSync 目录");
            }
            
            final String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
            final String extension;
            final String mimeType;
            final byte[] finalData;

            if (mCaptureFormat == ImageFormat.HEIC) {
                extension = ".heic";
                mimeType = "image/heif";
                // 如果需要写入额外的 EXIF（如旋转），在此处理
                finalData = (rotationDegrees != 0) ? writeHeicExif(encodedData, rotationDegrees) : encodedData;
            } else {
                // 🎯 手机端直接保存高质量原图 JPEG
                extension = ".jpg";
                mimeType = "image/jpeg";
                finalData = encodedData;
            }
            
            if (finalData != null) {
                final File photoFile = new File(wearSyncDir, "IMG_" + timeStamp + extension);
                try (FileOutputStream fos = new FileOutputStream(photoFile)) {
                    fos.write(finalData);
                }
                PhoneLog.d(TAG, "✅ 照片已直接保存(高质量): " + photoFile.getName());
                MediaScannerConnection.scanFile(getApplicationContext(), new String[]{photoFile.getAbsolutePath()}, new String[]{mimeType}, null);
            }

            // 🎯 手表端预览逻辑已改为实时预览冻结，不再发送图片文件。
            
        } catch (Exception e) {
            PhoneLog.e(TAG, "❌ 手机端保存照片异常", e);
        }
    }


    private byte[] writeHeicExif(byte[] data, int degrees) {
        try {
            final File tempFile = File.createTempFile("heic_exif_", ".heic", getCacheDir());
            try (FileOutputStream fos = new FileOutputStream(tempFile)) {
                fos.write(data);
            }
            final ExifInterface exif = new ExifInterface(tempFile.getAbsolutePath());
            final int orientation = switch (degrees) {
                case 90 -> ExifInterface.ORIENTATION_ROTATE_90;
                case 180 -> ExifInterface.ORIENTATION_ROTATE_180;
                case 270 -> ExifInterface.ORIENTATION_ROTATE_270;
                default -> ExifInterface.ORIENTATION_NORMAL;
            };
            exif.setAttribute(ExifInterface.TAG_ORIENTATION, String.valueOf(orientation));
            exif.saveAttributes();
            
            byte[] result;
            try (FileInputStream fis = new FileInputStream(tempFile)) {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                fis.transferTo(baos);
                result = baos.toByteArray();
            }
            if (!tempFile.delete()) PhoneLog.w(TAG, "⚠️ 无法删除临时文件");
            return result;
        } catch (IOException e) {
            PhoneLog.e(TAG, "❌ 写入 HEIC EXIF 失败", e);
            return data;
        }
    }

    private void switchCamera(String cameraId) {
        if (cameraId == null || cameraId.isEmpty()) return;
        PhoneLog.d(TAG, "🔄 切换摄像头为: " + cameraId);
        mCameraId = cameraId;
        stopStreamingAndRelease();
        initCameraAndStartStreaming();
    }

    private void setZoom(float zoomValue) {
        float zoom = Math.max(1.0f, Math.min(zoomValue, mMaxZoom));
        mCurrentZoom = zoom;
        PhoneLog.d(TAG, "🔍 设置变焦: " + zoom + "x");
        startPreviewRequest(); // 重新提交 RepeatingRequest 以应用变焦
    }

    private Rect calculateZoomRect(float zoom) {
        if (mActiveArraySize == null) return null;
        int centerX = mActiveArraySize.centerX();
        int centerY = mActiveArraySize.centerY();
        int deltaX = (int) (0.5f * mActiveArraySize.width() / zoom);
        int deltaY = (int) (0.5f * mActiveArraySize.height() / zoom);
        return new Rect(centerX - deltaX, centerY - deltaY, centerX + deltaX, centerY + deltaY);
    }

    private void manualFocus(double x, double y) {
        if (mEncoderSurface == null || mCaptureSession == null || mCameraDevice == null) return;
        try {
            final CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            final CameraCharacteristics chars = manager.getCameraCharacteristics(mCameraDevice.getId());
            final Rect sensorArraySize = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
            
            if (sensorArraySize != null) {
                final int centerX = (int) (x * sensorArraySize.width());
                final int centerY = (int) (y * sensorArraySize.height());
                final int halfSide = 100;
                
                final MeteringRectangle focusArea = new MeteringRectangle(
                    Math.max(0, centerX - halfSide),
                    Math.max(0, centerY - halfSide),
                    Math.min(sensorArraySize.width(), 2 * halfSide),
                    Math.min(sensorArraySize.height(), 2 * halfSide),
                    MeteringRectangle.METERING_WEIGHT_MAX
                );
                
                final CaptureRequest.Builder builder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
                builder.addTarget(mEncoderSurface);
                builder.set(CaptureRequest.CONTROL_AF_REGIONS, new MeteringRectangle[]{focusArea});
                builder.set(CaptureRequest.CONTROL_AE_REGIONS, new MeteringRectangle[]{focusArea});
                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO);
                builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START);
                builder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_START);
                
                mCaptureSession.setRepeatingRequest(builder.build(), null, mBgHandler);
            }
        } catch (Exception ignored) {}
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
        if (mCaptureSession != null) { try { mCaptureSession.close(); } catch (Exception ignored) {} mCaptureSession = null; }
        if (mCameraDevice != null) { try { mCameraDevice.close(); } catch (Exception ignored) {} mCameraDevice = null; }
        if (mEncoder != null) { try { mEncoder.stop(); mEncoder.release(); } catch (Exception ignored) {} mEncoder = null; }
        if (mEncoderSurface != null) { mEncoderSurface.release(); mEncoderSurface = null; }
        if (mPhotoReader != null) { mPhotoReader.close(); mPhotoReader = null; }

        mDataOutputStream = null;
        if (mChannelOutputStream != null) { try { mChannelOutputStream.close(); } catch (Exception ignored) {} mChannelOutputStream = null; }
    
        if (mBgThread != null) {
            mBgThread.quitSafely();
            try { mBgThread.join(); } catch (InterruptedException ignored) {}
            mBgThread = null;
            mBgHandler = null;
        }
    }

    private void createNotificationChannel() {
        final NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "相机同步", NotificationManager.IMPORTANCE_LOW);
        final NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) manager.createNotificationChannel(ch);
    }

    private Notification buildNotification() {
        final Intent intent = new Intent(this, PhoneSyncMainActivity.class);
        final PendingIntent pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("相机同步运行中")
                .setSmallIcon(R.drawable.ic_notification)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    private class EncoderCallback extends MediaCodec.Callback {
        @Override public void onInputBufferAvailable(@NonNull MediaCodec codec, int index) {}
        @Override
        public void onOutputBufferAvailable(@NonNull MediaCodec codec, int index, @NonNull MediaCodec.BufferInfo info) {
            if (!mIsStreaming.get() || mDataOutputStream == null) return;
            try {
                final ByteBuffer buffer = codec.getOutputBuffer(index);
                if (buffer != null && info.size > 0) {
                    final byte[] data = new byte[info.size];
                    buffer.get(data);
                    synchronized (this) {
                        if (mDataOutputStream != null) {
                            mDataOutputStream.writeInt(info.size);                 
                            mDataOutputStream.writeLong(info.presentationTimeUs); 
                            mDataOutputStream.writeInt(info.flags);                
                            mDataOutputStream.write(data);                        
                            mDataOutputStream.flush();
                        }
                    }
                }
                codec.releaseOutputBuffer(index, false);
            } catch (Exception ignored) {}
        }
        @Override public void onError(@NonNull MediaCodec codec, @NonNull MediaCodec.CodecException e) {}
        @Override public void onOutputFormatChanged(@NonNull MediaCodec codec, @NonNull MediaFormat format) {}
    }

}