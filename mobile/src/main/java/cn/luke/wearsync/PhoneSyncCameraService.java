package cn.luke.wearsync;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.ImageFormat;
import android.graphics.Rect;
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
import android.location.Location;
import android.location.LocationManager;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.util.Range;
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

import java.io.DataOutputStream;
import java.io.File;
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
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 手机端相机同步服务 (Android 16/17 优先版)
 * 职责：高性能流媒体传输、多镜头切换、高保真拍照
 * 优化：采用 SessionConfiguration API，动态分辨率适配，极致流畅度优先
 */
public class PhoneSyncCameraService extends Service {
    private static final String TAG = "PhoneSync_CameraSvc";

    public static final String ACTION_START_CAMERA = "cn.luke.wearsync.action.START_CAMERA";
    public static final String ACTION_STOP_CAMERA = "cn.luke.wearsync.action.STOP_CAMERA";
    public static final String ACTION_TAKE_PHOTO = "cn.luke.wearsync.action.TAKE_PHOTO";
    public static final String ACTION_SWITCH_CAMERA = "cn.luke.wearsync.action.SWITCH_CAMERA";
    public static final String ACTION_SET_ZOOM = "cn.luke.wearsync.action.SET_ZOOM";
    public static final String ACTION_TOGGLE_VIDEO = "cn.luke.wearsync.action.TOGGLE_VIDEO";
    public static final String ACTION_FOCUS_CAMERA = "cn.luke.wearsync.action.FOCUS_CAMERA";
    public static final String ACTION_REQUEST_CAMERA_LIST = "cn.luke.wearsync.action.REQUEST_CAMERA_LIST";
    public static final String ACTION_CAMERA_FGS_READY = "cn.luke.wearsync.action.CAMERA_FGS_READY";
    
    public static final String WEAR_CHANNEL_PATH = "/wear_data_channel/camera";
    public static final String WEAR_MSG_PATH_CAMERA_LIST = "/camera/info_list";

    private final AtomicBoolean mIsStreaming = new AtomicBoolean(false);
    private final AtomicBoolean mIsRecording = new AtomicBoolean(false);
    private final AtomicBoolean mIsCameraOpened = new AtomicBoolean(false);
    
    private String mCameraId; 
    private float mCurrentZoom = 1.0f; 
    private float mMaxZoom = 1.0f; 
    private Rect mActiveArraySize; 
    private int mCameraFacing = CameraCharacteristics.LENS_FACING_BACK;
    private int mSensorOrientation = 0;
    private int mDeviceOrientation = OrientationEventListener.ORIENTATION_UNKNOWN;
    private Size mPhotoSize = new Size(1920, 1080); // 🎯 默认拍照尺寸
    private Size mPreviewSize = new Size(256, 256); // 🎯 默认低分预览尺寸，优先流畅度
    private Size mVideoSize = new Size(1920, 1080); // 🎯 默认录像尺寸
    private int mMaxFps = 30; // 🎯 默认最大帧率

    private HandlerThread mBgThread;
    private Handler mBgHandler;
    private CameraDevice mCameraDevice;
    private CameraCaptureSession mCaptureSession;
    private MediaCodec mEncoder;
    private Surface mEncoderSurface;
    private ImageReader mPhotoReader;
    private MediaRecorder mVideoRecorder;
    private File mCurrentVideoFile; // 🎯 修正录像文件变量名
    private String mCachedNodeId;
    private ChannelClient mChannelClient;
    private DataOutputStream mDataOutputStream;
    private OrientationEventListener mOrientationEventListener;
    
    // 🚀 新增：自动释放看门狗，防止在极端情况下镜头被永久占用
    private final Handler mWatchdogHandler = new Handler(Looper.getMainLooper());
    private final Runnable mWatchdogRunnable = () -> {
        if (mIsStreaming.get()) {
            PhoneLog.w(TAG, "⏰ [看门狗] 检测到相机服务运行时间过长或无交互，执行自动释放...");
            stopStreamingAndRelease();
            stopSelf();
        }
    };
    private static final long WATCHDOG_TIMEOUT = 1000 * 60 * 30; // 30 分钟强制断开

    private static final Comparator<Size> SIZE_BY_AREA = (lhs, rhs) -> 
            Long.signum((long) lhs.getWidth() * lhs.getHeight() - (long) rhs.getWidth() * rhs.getHeight());

    private final MessageClient.OnMessageReceivedListener mMessageListener = event -> {
        String path = event.getPath();
        switch (path) {
            case "/camera/take_photo": captureHighResPhoto(); break;
            case "/camera/toggle_video": toggleVideoRecording(); break;
            case "/camera/control":
                try {
                    String jsonStr = new String(event.getData(), StandardCharsets.UTF_8);
                    JSONObject json = new JSONObject(jsonStr);
                    String action = json.optString("action");
                    switch (action.toUpperCase(Locale.US)) {
                        case "REQUEST_CAMERA_LIST": sendCameraListToWear(); break;
                        case "SELECT_CAMERA": switchCamera(json.optString("camera_id")); break;
                        case "SET_ZOOM": setZoom((float) json.optDouble("zoom", 1.0)); break;
                    }
                } catch (Exception ignored) {}
                break;
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        PhoneLog.d(TAG, "🏗️ [PhoneSyncCameraService] onCreate");
        createNotificationChannel();
        
        try {
            PhoneLog.d(TAG, "🏗️ [PhoneSyncCameraService] 正在启动 Camera FGS");
            startForeground(101, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA);
            PhoneLog.d(TAG, "✅ [PhoneSyncCameraService] Camera FGS 启动成功");
            
            // 只有 startForeground 成功返回后才能通知 Activity
            sendCameraFgsReady();
        } catch (Exception e) {
            PhoneLog.e(TAG, "❌ [PhoneSyncCameraService] Camera FGS 启动失败", e);
            // 如果 startForeground 失败，在 Android 12+ 上可能会抛出异常或导致服务停止
        }
        
        mChannelClient = Wearable.getChannelClient(this);
        Wearable.getMessageClient(this).addListener(mMessageListener);
    }

    private void sendCameraFgsReady() {
        Intent intent = new Intent(ACTION_CAMERA_FGS_READY);
        intent.setPackage(getPackageName()); // 限制为本应用接收
        sendBroadcast(intent);
        PhoneLog.d(TAG, "✅ [PhoneSyncCameraService] 已发送 CAMERA_FGS_READY");
    }
    
    @Override public void onDestroy() {
        stopStreamingAndRelease();
        Wearable.getMessageClient(this).removeListener(mMessageListener);
        super.onDestroy();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        String action = intent.getAction();
        PhoneLog.d(TAG, "🟢 onStartCommand: Action=" + action);
        
        // 🚀 每次收到有效指令都重置看门狗
        resetWatchdog();

        if (ACTION_START_CAMERA.equals(action)) {
            mCachedNodeId = intent.getStringExtra("remote_node_id");
            if (mCachedNodeId == null) mCachedNodeId = WearSyncState.getNodeId(this);
            PhoneLog.d(TAG, "📸 启动相机流程. NodeID=" + mCachedNodeId);
            if (mCachedNodeId == null) { 
                PhoneLog.e(TAG, "❌ 无效的 NodeID，停止服务");
                stopSelf(); 
                return START_NOT_STICKY; 
            }
            if (!mIsStreaming.get()) {
                PhoneLog.d(TAG, "🚀 开始初始化相机与流传输...");
                initCameraAndStartStreaming(); 
            } else {
                PhoneLog.d(TAG, "ℹ️ 相机已在运行，仅刷新相机列表");
                sendCameraListToWear();
            }
        } else if (ACTION_STOP_CAMERA.equals(action)) {
            PhoneLog.d(TAG, "🛑 停止相机服务");
            stopStreamingAndRelease(); stopSelf();
        } else if (ACTION_TAKE_PHOTO.equals(action)) {
            PhoneLog.d(TAG, "📸 执行拍照...");
            captureHighResPhoto();
        } else if (ACTION_SWITCH_CAMERA.equals(action)) {
            String camId = intent.getStringExtra("camera_id");
            PhoneLog.d(TAG, "🔄 切换摄像头: " + camId);
            switchCamera(camId);
        }
        else if (ACTION_TOGGLE_VIDEO.equals(action)) toggleVideoRecording();
        else if (ACTION_SET_ZOOM.equals(action)) setZoom(intent.getFloatExtra("zoom", 1.0f));
        else if (ACTION_FOCUS_CAMERA.equals(action)) manualFocus(intent.getDoubleExtra("x", 0.5), intent.getDoubleExtra("y", 0.5));
        else if (ACTION_REQUEST_CAMERA_LIST.equals(action)) {
            PhoneLog.d(TAG, "📋 请求相机列表");
            sendCameraListToWear();
        }
        return START_NOT_STICKY;
    }

    private void initCameraAndStartStreaming() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            PhoneLog.e(TAG, "❌ initCameraAndStartStreaming: 缺少相机权限");
            return;
        }
        mIsStreaming.set(true); 
        startBackgroundThread();
        startOrientationListener();
        try {
            PhoneLog.d(TAG, "🔍 正在选择最佳相机尺寸...");
            chooseOptimalSizes();
            
            PhoneLog.d(TAG, "🎥 正在配置 H.264 编码器 (流畅优先模式)... 尺寸: " + mPreviewSize);
            MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, mPreviewSize.getWidth(), mPreviewSize.getHeight());
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            format.setInteger(MediaFormat.KEY_BIT_RATE, 450_000); 
            format.setInteger(MediaFormat.KEY_FRAME_RATE, 25); 
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
            
            // 🚀 动态计算旋转角度，处理前后置差异
            int rotation = calculateRotation();
            format.setInteger(MediaFormat.KEY_ROTATION, rotation);
            PhoneLog.d(TAG, "🔄 预览流旋转角度设定: " + rotation);
            
            mEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            mEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            mEncoderSurface = mEncoder.createInputSurface(); 
            
            PhoneLog.d(TAG, "🖼️ 正在配置 ImageReader (用于拍照)... 尺寸: " + mPhotoSize);
            mPhotoReader = ImageReader.newInstance(mPhotoSize.getWidth(), mPhotoSize.getHeight(), ImageFormat.JPEG, 2);
            mPhotoReader.setOnImageAvailableListener(reader -> { 
                Image img = reader.acquireLatestImage(); 
                if (img != null) { savePhoto(img); img.close(); } 
            }, mBgHandler);
            
            PhoneLog.d(TAG, "🔌 正在开启相机硬件...");
            startCameraHardware();
        } catch (Exception e) { PhoneLog.e(TAG, "❌ Streaming Init Failed", e); stopStreamingAndRelease(); stopSelf(); }
    }

    private int calculateRotation() {
        // 根据传感器方向和设备当前姿态计算最终旋转
        // 参考 Android 官方推荐算法
        int devRot = (mDeviceOrientation != OrientationEventListener.ORIENTATION_UNKNOWN) ? (mDeviceOrientation + 45) / 90 * 90 : 0;
        int rotation;
        if (mCameraFacing == CameraCharacteristics.LENS_FACING_FRONT) {
            rotation = (mSensorOrientation + devRot) % 360;
            // 前置通常需要额外补偿
        } else {
            rotation = (mSensorOrientation - devRot + 360) % 360;
        }
        return rotation;
    }

    private void chooseOptimalSizes() throws CameraAccessException {
        CameraManager mgr = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        String[] idList = mgr.getCameraIdList();
        if (idList.length == 0) {
            PhoneLog.e(TAG, "❌ 未发现任何可用摄像头");
            throw new CameraAccessException(CameraAccessException.CAMERA_DISCONNECTED, "No cameras found");
        }
        
        if (mCameraId == null) { 
            PhoneLog.d(TAG, "🔎 未指定摄像头ID，查找默认后置摄像头...");
            for (String id : idList) { 
                Integer f = mgr.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
                if (f != null && f == CameraMetadata.LENS_FACING_BACK) { 
                    mCameraId = id; 
                    PhoneLog.d(TAG, "✅ 选定默认后置摄像头: " + id);
                    break; 
                } 
            } 
        }
        if (mCameraId == null) {
            mCameraId = idList[0];
            PhoneLog.d(TAG, "⚠️ 未找到后置摄像头，使用第一个可用摄像头: " + mCameraId);
        }
        
        CameraCharacteristics chars = mgr.getCameraCharacteristics(mCameraId);
        mCameraFacing = Objects.requireNonNullElse(chars.get(CameraCharacteristics.LENS_FACING), CameraMetadata.LENS_FACING_BACK);
        mMaxZoom = Objects.requireNonNullElse(chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM), 1.0f);
        mActiveArraySize = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
        mSensorOrientation = Objects.requireNonNullElse(chars.get(CameraCharacteristics.SENSOR_ORIENTATION), 0);
        
        StreamConfigurationMap map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (map != null) {
            // 1. 动态选择该镜头支持的最大 JPEG 尺寸
            Size[] photoSizes = map.getOutputSizes(ImageFormat.JPEG);
            if (photoSizes != null && photoSizes.length > 0) {
                mPhotoSize = Collections.max(Arrays.asList(photoSizes), SIZE_BY_AREA);
            }
            
            // 2. 动态选择一个适合流传输的预览尺寸 (接近 480p)
            Size[] previewSizes = map.getOutputSizes(MediaCodec.class);
            if (previewSizes != null && previewSizes.length > 0) {
                mPreviewSize = chooseBestPreviewSize(previewSizes);
            }

            // 3. 动态选择最高录像分辨率和 FPS
            Size[] videoSizes = map.getOutputSizes(MediaRecorder.class);
            if (videoSizes != null && videoSizes.length > 0) {
                // 限制在 4K 范围内
                mVideoSize = new Size(1920, 1080);
                for (Size s : videoSizes) {
                    if (s.getWidth() <= 3840 && s.getHeight() <= 2160) {
                        if (s.getWidth() * s.getHeight() > mVideoSize.getWidth() * mVideoSize.getHeight()) {
                            mVideoSize = s;
                        }
                    }
                }
            }
            
            // 尝试获取最高可用 FPS
            mMaxFps = 30;
            Range<Integer>[] fpsRanges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
            if (fpsRanges != null) {
                for (Range<Integer> range : fpsRanges) {
                    if (range.getUpper() > mMaxFps) mMaxFps = range.getUpper();
                }
            }
        }
        
        PhoneLog.d(TAG, "📊 参数确定: Facing=" + mCameraFacing + ", SensorOrient=" + mSensorOrientation + 
                ", Photo=" + mPhotoSize + ", Preview=" + mPreviewSize + ", Video=" + mVideoSize + "@" + mMaxFps + "fps");
    }

    private Size chooseBestPreviewSize(Size[] sizes) {
        // 🎯 核心逻辑：对齐长宽比以修复 CaptureSession 配置失败
        double targetRatio = (double) mPhotoSize.getWidth() / mPhotoSize.getHeight();
        Size bestMatch = null;
        double minDiff = Double.MAX_VALUE;

        // 1. 筛选出宽度在 240px - 450px 之间的“流畅级”候选分辨率
        List<Size> candidates = new ArrayList<>();
        for (Size s : sizes) {
            if (s.getWidth() >= 240 && s.getWidth() <= 450) {
                candidates.add(s);
            }
        }

        // 2. 如果没有这个范围的，直接找全局最小的以保底
        if (candidates.isEmpty()) {
            Size smallest = sizes[0];
            for (Size s : sizes) {
                if (s.getWidth() * s.getHeight() < smallest.getWidth() * smallest.getHeight()) smallest = s;
            }
            return smallest;
        }

        // 3. 在候选者中寻找长宽比与照片（硬件原生比例）最接近的一个
        for (Size s : candidates) {
            double ratio = (double) s.getWidth() / s.getHeight();
            double diff = Math.abs(ratio - targetRatio);
            if (diff < minDiff) {
                minDiff = diff;
                bestMatch = s;
            }
        }
        return bestMatch != null ? bestMatch : candidates.get(0);
    }

    @SuppressLint("MissingPermission")
    private void startCameraHardware() {
        CameraManager mgr = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            PhoneLog.d(TAG, "🎬 调用 CameraManager.openCamera: " + mCameraId);
            mgr.openCamera(mCameraId, new CameraDevice.StateCallback() {
                @Override public void onOpened(@NonNull CameraDevice camera) { 
                    PhoneLog.d(TAG, "✅ 相机已成功打开 [ID: " + camera.getId() + "]");
                    if (!mIsStreaming.get()) { 
                        PhoneLog.w(TAG, "⚠️ 相机已打开但 Streaming 状态已关闭，正在释放...");
                        camera.close(); 
                        return; 
                    }
                    mCameraDevice = camera; 
                    mIsCameraOpened.set(true); 
                    createCameraCaptureSession(); 
                }
                @Override public void onDisconnected(@NonNull CameraDevice camera) { 
                    PhoneLog.w(TAG, "🔌 相机已断开连接");
                    stopStreamingAndRelease(); 
                }
                @Override public void onError(@NonNull CameraDevice camera, int error) { 
                    PhoneLog.e(TAG, "❌ 相机开启错误: " + error);
                    stopStreamingAndRelease(); 
                }
            }, mBgHandler);
        } catch (Exception e) { PhoneLog.e(TAG, "❌ Hardware Open Failed", e); }
    }

    private void createCameraCaptureSession() {
        if (mCameraDevice == null || mEncoderSurface == null || mPhotoReader == null || !mIsStreaming.get()) {
            PhoneLog.e(TAG, "❌ createCameraCaptureSession: 必要组件未就绪");
            return;
        }
        try {
            PhoneLog.d(TAG, "🏗️ 正在创建相机捕获会话 (SessionConfiguration)...");
            List<OutputConfiguration> outputs = new ArrayList<>();
            if (mEncoderSurface.isValid()) outputs.add(new OutputConfiguration(mEncoderSurface));
            if (mPhotoReader.getSurface().isValid()) outputs.add(new OutputConfiguration(mPhotoReader.getSurface()));
            
            if (mIsRecording.get() && mVideoRecorder != null) {
                Surface recSurface = mVideoRecorder.getSurface();
                if (recSurface != null && recSurface.isValid()) outputs.add(new OutputConfiguration(recSurface));
            }
            
            // 🚀 采用 Android 15/16+ 推荐的 SessionConfiguration API
            SessionConfiguration sessionConfig = new SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                outputs,
                command -> mBgHandler.post(command),
                new CameraCaptureSession.StateCallback() {
                    @Override public void onConfigured(@NonNull CameraCaptureSession session) { 
                        PhoneLog.d(TAG, "✅ 相机捕获会话配置成功");
                        if (!mIsStreaming.get()) { 
                            session.close(); 
                            return; 
                        }
                        mCaptureSession = session; 
                        startPreviewRequest(); 
                        openChannelStream();

                        // 🎯 修复：仅在会话配置成功且正在录像时才启动 MediaRecorder
                        if (mIsRecording.get() && mVideoRecorder != null) {
                            try {
                                mVideoRecorder.start();
                                PhoneLog.d(TAG, "🔴 MediaRecorder 已正式启动录制");
                            } catch (Exception e) {
                                PhoneLog.e(TAG, "❌ MediaRecorder 启动失败", e);
                                mIsRecording.set(false);
                            }
                        }
                    }
                    @Override public void onConfigureFailed(@NonNull CameraCaptureSession session) { 
                        PhoneLog.e(TAG, "❌ 相机捕获会话配置失败. Targets: " + outputs.size());
                        tryRecoverFromConfigFailure();
                    }
                }
            );
            
            mCameraDevice.createCaptureSession(sessionConfig);
        } catch (Exception e) { PhoneLog.e(TAG, "❌ Session creation abandoned", e); }
    }

    private void openChannelStream() {
        if (mDataOutputStream != null) {
            PhoneLog.d(TAG, "ℹ️ 数据通道已存在");
            return;
        }
        if (mCachedNodeId == null) {
            PhoneLog.e(TAG, "❌ openChannelStream: NodeID 为空");
            return;
        }
        
        PhoneLog.d(TAG, "🔗 正在建立 Wearable Channel 数据通道: " + WEAR_CHANNEL_PATH);
        mChannelClient.openChannel(mCachedNodeId, WEAR_CHANNEL_PATH)
            .addOnSuccessListener(c -> {
                PhoneLog.d(TAG, "✅ Channel 通道已开启，正在获取输出流...");
                mChannelClient.getOutputStream(c).addOnSuccessListener(os -> {
                    PhoneLog.d(TAG, "✅ 数据输出流已就绪");
                    mDataOutputStream = new DataOutputStream(os); 
                    
                    try { 
                        mEncoder.setCallback(new EncoderCallback(), mBgHandler); 
                        mEncoder.start(); 
                        PhoneLog.d(TAG, "🔥 H.264 编码器已启动，流数据开始传输");
                        
                        // 🚀 启动顺序优化：先确保预览流发出，再在后台慢慢扫描镜头列表
                        sendCameraListToWear();
                        notifyStreamReadyToWear();
                    } catch (Exception e) { 
                        PhoneLog.e(TAG, "❌ 编码器启动失败", e);
                    }
                }).addOnFailureListener(e -> PhoneLog.e(TAG, "❌ 获取 Channel 输出流失败", e));
            })
            .addOnFailureListener(e -> PhoneLog.e(TAG, "❌ 开启 Channel 通道失败", e));
    }

    private void notifyStreamReadyToWear() {
        if (mCachedNodeId == null) return;
        try {
            JSONObject j = new JSONObject();
            j.put("type", "camera_status");
            j.put("action", "STREAM_READY");
            Wearable.getMessageClient(this).sendMessage(mCachedNodeId, "/camera/status", j.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {}
    }

    private void startPreviewRequest() {
        if (mCaptureSession == null || !mIsStreaming.get()) {
            PhoneLog.e(TAG, "❌ startPreviewRequest: 会话不可用");
            return;
        }
        try {
            PhoneLog.d(TAG, "📡 正在下达重复捕获请求 (RepeatingRequest)...");
            // 🎯 如果在录像，使用 TEMPLATE_RECORD；否则使用 TEMPLATE_PREVIEW 以获得更好性能
            int template = mIsRecording.get() ? CameraDevice.TEMPLATE_RECORD : CameraDevice.TEMPLATE_PREVIEW;
            CaptureRequest.Builder b = mCameraDevice.createCaptureRequest(template);
            
            b.addTarget(mEncoderSurface); 
            if (mIsRecording.get() && mVideoRecorder != null) {
                Surface recSurface = mVideoRecorder.getSurface();
                if (recSurface != null && recSurface.isValid()) {
                    b.addTarget(recSurface);
                }
            }
            applyZoom(b); 
            mCaptureSession.setRepeatingRequest(b.build(), null, mBgHandler);
        } catch (Exception e) { 
            PhoneLog.e(TAG, "❌ RepeatingRequest 下达失败", e);
        }
    }

    private void captureHighResPhoto() {
        if (mCaptureSession == null || !mIsStreaming.get()) {
            PhoneLog.e(TAG, "❌ captureHighResPhoto: 会话未就绪，尝试重新激活流...");
            initCameraAndStartStreaming();
            return;
        }
        try {
            CaptureRequest.Builder b = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            b.addTarget(mPhotoReader.getSurface()); applyZoom(b);
            
            // 🎯 使用一致的旋转算法
            int rotation = calculateRotation();
            b.set(CaptureRequest.JPEG_ORIENTATION, rotation);
            b.set(CaptureRequest.JPEG_QUALITY, (byte)100);
            
            mCaptureSession.capture(b.build(), null, mBgHandler);
            PhoneLog.d(TAG, "📸 Photo Capture Triggered with rotation: " + rotation);
        } catch (Exception e) {
            PhoneLog.e(TAG, "❌ 拍照捕获请求失败", e);
        }
    }

    private void toggleVideoRecording() { if (mIsRecording.get()) stopVideoRecording(); else startVideoRecording(); }

    private void startVideoRecording() {
        Uri uri = null;
        try {
            String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            String fileName = "VID_" + timeStamp + ".mp4";
            ContentValues values = new ContentValues();
            values.put(MediaStore.Video.Media.DISPLAY_NAME, fileName);
            values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
            values.put(MediaStore.Video.Media.RELATIVE_PATH, "DCIM/WearSync");
            uri = getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) return;

            try (ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(uri, "rw")) {
                if (pfd == null) return;
                mVideoRecorder = new MediaRecorder(this);
                mVideoRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
                mVideoRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
                mVideoRecorder.setOutputFile(pfd.getFileDescriptor());
                
                // 🚀 优先尝试 H.265/HEVC，否则回退 H.264
                boolean hasHevc = false;
                try {
                    MediaCodecList list = new MediaCodecList(MediaCodecList.ALL_CODECS);
                    for (MediaCodecInfo info : list.getCodecInfos()) {
                        if (info.isEncoder()) {
                            for (String type : info.getSupportedTypes()) {
                                if (type.equalsIgnoreCase(MediaFormat.MIMETYPE_VIDEO_HEVC)) {
                                    hasHevc = true; break;
                                }
                            }
                        }
                        if (hasHevc) break;
                    }
                } catch (Exception ignored) {}

                if (hasHevc) {
                    mVideoRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.HEVC);
                    PhoneLog.d(TAG, "🎥 录像使用编码器: HEVC/H.265");
                } else {
                    mVideoRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
                    PhoneLog.d(TAG, "🎥 录像使用编码器: H.264 (HEVC 不可用)");
                }

                mVideoRecorder.setVideoSize(mVideoSize.getWidth(), mVideoSize.getHeight());
                mVideoRecorder.setVideoFrameRate(mMaxFps);
                // 动态计算码率 (1080p 约 8Mbps, 4K 约 30Mbps)
                int bitRate = (int) (mVideoSize.getWidth() * mVideoSize.getHeight() * 4L); 
                mVideoRecorder.setVideoEncodingBitRate(Math.min(bitRate, 50_000_000));
                
                mVideoRecorder.prepare(); 
                mIsRecording.set(true); 
                
                // 🚀 重写：仅触发重建会话。真正的 start() 会在 onConfigured 回调中执行
                createCameraCaptureSession(); 
                notifyWearVideoStatus(true);
            }
        } catch (Exception e) { 
            PhoneLog.e(TAG, "❌ 录像启动失败", e); 
            mIsRecording.set(false); 
            cleanupEmptyVideo(uri);
        }
    }

    private void cleanupEmptyVideo(Uri uri) {
        if (uri != null) {
            try {
                getContentResolver().delete(uri, null, null);
                PhoneLog.d(TAG, "🧹 已清理启动失败的空录像文件");
            } catch (Exception ignored) {}
        }
    }

    private void stopVideoRecording() {
        if (!mIsRecording.get()) return;
        PhoneLog.d(TAG, "🎥 正在停止录像...");
        try {
            if (mVideoRecorder != null) {
                mVideoRecorder.stop();
                mVideoRecorder.release();
                mVideoRecorder = null;
            }
            mIsRecording.set(false);
            
            // 🎯 修复：仅在流传输仍在继续时才重建 Session。
            // 如果是由于全局停止触发的，则不要在这里重建，避免资源竞争。
            if (mIsStreaming.get()) {
                createCameraCaptureSession();
            }
            notifyWearVideoStatus(false);
            PhoneLog.d(TAG, "✅ 录像已停止并释放资源");
        } catch (Exception e) {
            PhoneLog.e(TAG, "❌ 停止录像时出错", e);
            mIsRecording.set(false);
            mVideoRecorder = null;
        }
    }

    private void notifyWearVideoStatus(boolean rec) {
        try { JSONObject j = new JSONObject(); j.put("type", "video_status"); j.put("isRecording", rec); Wearable.getMessageClient(this).sendMessage(mCachedNodeId, "/camera/video_status", j.toString().getBytes(StandardCharsets.UTF_8)); } catch (Exception ignored) {}
    }

    private void applyZoom(CaptureRequest.Builder b) {
        if (mActiveArraySize == null) return;
        int cx = mActiveArraySize.centerX(), cy = mActiveArraySize.centerY();
        int dx = (int)(0.5f * mActiveArraySize.width() / mCurrentZoom), dy = (int)(0.5f * mActiveArraySize.height() / mCurrentZoom);
        b.set(CaptureRequest.SCALER_CROP_REGION, new Rect(cx - dx, cy - dy, cx + dx, cy + dy));
    }

    private void sendCameraListToWear() {
        if (mCachedNodeId == null) {
            PhoneLog.w(TAG, "⚠️ sendCameraListToWear: NodeID 为空，放弃发送");
            return;
        }
        PhoneLog.d(TAG, "📋 正在获取镜头能力列表并发送至手表...");
        new Thread(() -> {
            try {
                CameraManager mgr = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
                JSONArray arr = new JSONArray();
                String[] idList = mgr.getCameraIdList();
                
                int teleCount = 0, wideCount = 0, mainCount = 0;
                
                for (String id : idList) {
                    CameraCharacteristics chars = mgr.getCameraCharacteristics(id);
                    StreamConfigurationMap map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                    if (map == null) continue;
                    
                    // 过滤掉不支持 JPEG 的虚拟摄像头
                    Size[] sz = map.getOutputSizes(ImageFormat.JPEG);
                    if (sz == null || sz.length == 0) continue;

                    Integer f = chars.get(CameraCharacteristics.LENS_FACING);
                    float[] fl = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
                    float maxZoom = Objects.requireNonNullElse(chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM), 1.0f);
                    
                    JSONObject o = new JSONObject();
                    o.put("id", id); 
                    o.put("maxZoom", (double) maxZoom);
                    
                    String name;
                    if (f != null && f == CameraMetadata.LENS_FACING_FRONT) {
                        name = "前置";
                    } else {
                        // 根据焦距粗略判定镜头类型
                        if (fl != null && fl.length > 0) {
                            float focal = fl[0];
                            if (focal < 3.0f) {
                                wideCount++;
                                name = wideCount > 1 ? "超广角" + wideCount : "超广角";
                            } else if (focal > 6.0f) {
                                teleCount++;
                                name = teleCount > 1 ? "长焦" + teleCount : "长焦";
                            } else {
                                mainCount++;
                                name = mainCount > 1 ? "主摄" + mainCount : "主摄";
                            }
                        } else {
                            mainCount++;
                            name = "镜头" + id;
                        }
                    }
                    
                    o.put("name", name);
                    arr.put(o);
                }
                
                PhoneLog.d(TAG, "✅ 镜头列表构建完成，共 " + arr.length() + " 个，正在发送...");
                Wearable.getMessageClient(this).sendMessage(mCachedNodeId, WEAR_MSG_PATH_CAMERA_LIST, arr.toString().getBytes(StandardCharsets.UTF_8))
                    .addOnSuccessListener(taskResult -> PhoneLog.d(TAG, "🚀 镜头列表已成功发送"))
                    .addOnFailureListener(e -> PhoneLog.e(TAG, "❌ 镜头列表发送失败", e));
            } catch (Exception e) {
                PhoneLog.e(TAG, "❌ 镜头列表处理异常", e);
            }
        }).start();
    }

    private void savePhoto(Image image) {
        try {
            ByteBuffer buf = image.getPlanes()[0].getBuffer(); byte[] data = new byte[buf.remaining()]; buf.get(data);
            String fileName = "IMG_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".jpg";
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, fileName);
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            values.put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/WearSync");
            Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (uri != null) {
                try (OutputStream os = getContentResolver().openOutputStream(uri)) { if (os != null) os.write(data); }
                writeLocationExifFromUri(uri);
                PhoneLog.d(TAG, "✅ Photo saved to MediaStore: " + fileName);
            }
        } catch (Exception e) { PhoneLog.e(TAG, "Save Photo Error", e); }
    }

    @SuppressLint("MissingPermission")
    private void writeLocationExifFromUri(Uri uri) {
        if (!getSharedPreferences("dndsync_prefs", Context.MODE_PRIVATE).getBoolean("save_location_enabled", false)) return;
        try (ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(uri, "rw")) {
            if (pfd == null) return;
            Location best = null;
            LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                for (String p : lm.getProviders(true)) {
                    Location l = lm.getLastKnownLocation(p);
                    if (l != null && (best == null || l.getAccuracy() < best.getAccuracy())) best = l;
                }
            }
            if (best != null) {
                ExifInterface exif = new ExifInterface(pfd.getFileDescriptor());
                exif.setGpsInfo(best); exif.saveAttributes();
            }
        } catch (Exception ignored) {}
    }

    private void setZoom(float z) { mCurrentZoom = Math.max(1.0f, Math.min(z, mMaxZoom)); startPreviewRequest(); }
    private void switchCamera(String id) { if (id != null) { mCameraId = id; stopStreamingAndRelease(); initCameraAndStartStreaming(); } }
    private void manualFocus(double x, double y) {
        if (mCameraDevice == null || mCaptureSession == null) return;
        try {
            PhoneLog.d(TAG, "🎯 正在执行手动对焦: x=" + x + ", y=" + y);
            CameraCharacteristics chars = ((CameraManager) getSystemService(Context.CAMERA_SERVICE)).getCameraCharacteristics(mCameraDevice.getId());
            Rect sensor = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
            if (sensor != null) {
                int cx = (int) (x * sensor.width()), cy = (int) (y * sensor.height());
                MeteringRectangle area = new MeteringRectangle(
                    Math.max(0, cx - 100), Math.max(0, cy - 100),
                    Math.min(sensor.width(), 200), Math.min(sensor.height(), 200),
                    MeteringRectangle.METERING_WEIGHT_MAX);
                
                // 🎯 修复：根据当前是否录像选择模板
                int template = mIsRecording.get() ? CameraDevice.TEMPLATE_RECORD : CameraDevice.TEMPLATE_PREVIEW;
                CaptureRequest.Builder b = mCameraDevice.createCaptureRequest(template);
                
                b.addTarget(mEncoderSurface);
                if (mIsRecording.get() && mVideoRecorder != null) {
                    Surface recSurface = mVideoRecorder.getSurface();
                    if (recSurface != null && recSurface.isValid()) b.addTarget(recSurface);
                }
                
                // 🎯 修复：对焦时必须应用当前的缩放倍率，否则对焦位置会偏移或缩放会被重置
                applyZoom(b);
                
                b.set(CaptureRequest.CONTROL_AF_REGIONS, new MeteringRectangle[]{area});
                b.set(CaptureRequest.CONTROL_AE_REGIONS, new MeteringRectangle[]{area});
                b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO);
                b.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START);
                
                // 🎯 修复：更新RepeatingRequest以保持持续对焦
                mCaptureSession.setRepeatingRequest(b.build(), null, mBgHandler);
                PhoneLog.d(TAG, "✅ 对焦请求已下达");
            }
        } catch (Exception e) {
            PhoneLog.e(TAG, "❌ 手动对焦失败", e);
        }
    }
    private synchronized void stopStreamingAndRelease() {
        PhoneLog.d(TAG, "🏗️ 正在释放相机资源...");
        mIsStreaming.set(false); 
        mIsCameraOpened.set(false); 
        
        // 1. 停止录像逻辑 (不带重启Session)
        if (mIsRecording.get()) {
            try {
                if (mVideoRecorder != null) {
                    mVideoRecorder.stop();
                    mVideoRecorder.release();
                    mVideoRecorder = null;
                }
                mIsRecording.set(false);
            } catch (Exception ignored) {}
        }

        // 2. 关闭 CaptureSession
        if (mCaptureSession != null) {
            try {
                mCaptureSession.stopRepeating();
                mCaptureSession.close();
            } catch (Exception ignored) {}
            mCaptureSession = null;
        }

        // 3. 关闭 CameraDevice (最关键步骤)
        if (mCameraDevice != null) {
            try {
                mCameraDevice.close();
                PhoneLog.d(TAG, "📸 CameraDevice 已成功关闭");
            } catch (Exception ignored) {}
            mCameraDevice = null;
        }

        // 4. 清理编码器
        if (mEncoder != null) {
            try {
                mEncoder.stop();
                mEncoder.release();
            } catch (Exception ignored) {}
            mEncoder = null;
        }

        // 5. 释放 Surface 资源
        if (mEncoderSurface != null) {
            mEncoderSurface.release();
            mEncoderSurface = null;
        }
        if (mPhotoReader != null) {
            mPhotoReader.close();
            mPhotoReader = null;
        }

        // 6. 清理其他后台组件
        mDataOutputStream = null;
        if (mBgThread != null) {
            mBgThread.quitSafely();
            mBgThread = null;
        }
        if (mOrientationEventListener != null) {
            mOrientationEventListener.disable();
            mOrientationEventListener = null;
        }
        PhoneLog.d(TAG, "✅ 所有相机资源已彻底释放");
    }
    private void tryRecoverFromConfigFailure() {
        if (mCameraDevice == null || !mIsStreaming.get()) return;
        PhoneLog.w(TAG, "🔄 触发降级恢复：尝试仅开启预览流...");
        try {
            List<OutputConfiguration> outputs = new ArrayList<>();
            outputs.add(new OutputConfiguration(mEncoderSurface));
            
            SessionConfiguration config = new SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                outputs,
                command -> mBgHandler.post(command),
                new CameraCaptureSession.StateCallback() {
                    @Override public void onConfigured(@NonNull CameraCaptureSession session) {
                        PhoneLog.d(TAG, "✅ 降级 Session 配置成功（仅预览）");
                        mCaptureSession = session;
                        startPreviewRequest();
                        openChannelStream();
                    }
                    @Override public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                        PhoneLog.e(TAG, "❌ 降级 Session 依然配置失败，彻底释放硬件");
                        stopStreamingAndRelease();
                    }
                }
            );
            mCameraDevice.createCaptureSession(config);
        } catch (Exception e) {
            PhoneLog.e(TAG, "❌ 降级恢复过程异常", e);
            stopStreamingAndRelease();
        }
    }
    private void startBackgroundThread() { if (mBgThread == null) { mBgThread = new HandlerThread("CamBg"); mBgThread.start(); mBgHandler = new Handler(mBgThread.getLooper()); } }
    private void startOrientationListener() { mOrientationEventListener = new OrientationEventListener(this) { @Override public void onOrientationChanged(int o) { if (o != ORIENTATION_UNKNOWN) mDeviceOrientation = o; } }; mOrientationEventListener.enable(); }

    private void resetWatchdog() {
        mWatchdogHandler.removeCallbacks(mWatchdogRunnable);
        mWatchdogHandler.postDelayed(mWatchdogRunnable, WATCHDOG_TIMEOUT);
    }
    private void createNotificationChannel() { ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(new NotificationChannel("camera_service_channel", "Camera", NotificationManager.IMPORTANCE_LOW)); }
    private Notification buildNotification() { return new NotificationCompat.Builder(this, "camera_service_channel").setContentTitle("WearSync Camera").setSmallIcon(R.drawable.ic_notification).setOngoing(true).build(); }
    @Nullable @Override public IBinder onBind(Intent intent) { return null; }
    private class EncoderCallback extends MediaCodec.Callback {
        @Override public void onInputBufferAvailable(@NonNull MediaCodec c, int i) {}
        @Override public void onOutputBufferAvailable(@NonNull MediaCodec c, int i, @NonNull MediaCodec.BufferInfo info) {
            try { ByteBuffer b = c.getOutputBuffer(i); if (b != null && info.size > 0) { byte[] d = new byte[info.size]; b.get(d); synchronized (this) { if (mDataOutputStream != null) { mDataOutputStream.writeInt(info.size); mDataOutputStream.writeLong(info.presentationTimeUs); mDataOutputStream.writeInt(info.flags); mDataOutputStream.write(d); mDataOutputStream.flush(); } } } c.releaseOutputBuffer(i, false); } catch (Exception ignored) {}
        }
        @Override public void onError(@NonNull MediaCodec c, @NonNull MediaCodec.CodecException e) {}
        @Override public void onOutputFormatChanged(@NonNull MediaCodec c, @NonNull MediaFormat f) {}
    }
}
