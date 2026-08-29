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
import android.util.Log;
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
 * PhoneSyncCameraService - 手机端远程相机服务
 * 
 * 修改要点：
 * 1. 录像流程完整重构：正确管理 CaptureSession 生命周期
 * 2. MediaRecorder 状态管理：防止0秒视频
 * 3. 录像分辨率/FPS 成对选择：基于Camera实际能力
 * 4. H.265 编码器能力验证
 * 5. Camera 筛选和动态列表生成
 * 6. Camera 切换完整重建流程
 * 7. 前置摄像头方向正确处理
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

    // ==================== 状态管理 ====================
    private final AtomicBoolean mIsStreaming = new AtomicBoolean(false);
    private final AtomicBoolean mIsRecording = new AtomicBoolean(false);
    private final AtomicBoolean mIsCameraOpened = new AtomicBoolean(false);
    private final AtomicBoolean mRecorderPrepared = new AtomicBoolean(false);
    private final AtomicBoolean mRecorderStarted = new AtomicBoolean(false);
    private final AtomicBoolean mSessionConfigured = new AtomicBoolean(false);

    // ==================== Camera 相关 ====================
    private String mCameraId;
    private float mCurrentZoom = 1.0f;
    private float mMaxZoom = 1.0f;
    private Rect mActiveArraySize;
    private int mCameraFacing = CameraCharacteristics.LENS_FACING_BACK;
    private int mSensorOrientation = 0;
    private int mDeviceOrientation = OrientationEventListener.ORIENTATION_UNKNOWN;

    // ==================== 尺寸配置 ====================
    private Size mPhotoSize = new Size(1920, 1080);
    private Size mPreviewSize = new Size(320, 240);
    private Size mVideoSize = new Size(1920, 1080);
    private int mVideoFps = 30;
    private boolean mUseHevcForRecording = false;

    // ==================== 硬件对象 ====================
    private HandlerThread mBgThread;
    private Handler mBgHandler;
    private CameraDevice mCameraDevice;
    private CameraCaptureSession mCaptureSession;
    private MediaCodec mEncoder;
    private Surface mEncoderSurface;
    private ImageReader mPhotoReader;
    private MediaRecorder mVideoRecorder;
    private Uri mCurrentVideoUri;

    // ==================== 通信相关 ====================
    private String mCachedNodeId;
    private ChannelClient mChannelClient;
    private DataOutputStream mDataOutputStream;
    private OrientationEventListener mOrientationEventListener;

    // ==================== 看门狗 ====================
    private final Handler mWatchdogHandler = new Handler(Looper.getMainLooper());
    private final Runnable mWatchdogRunnable = () -> {
        if (mIsStreaming.get()) {
            PhoneLog.w(TAG, "⚠️ [看门狗] 30分钟无活动，自动释放资源...");
            stopStreamingAndRelease();
            stopSelf();
        }
    };
    private static final long WATCHDOG_TIMEOUT = 1000L * 60 * 30;

    private static final Comparator<Size> SIZE_BY_AREA = (lhs, rhs) ->
            Long.signum((long) lhs.getWidth() * lhs.getHeight() - (long) rhs.getWidth() * rhs.getHeight());

    // ==================== 消息监听 ====================
    private final MessageClient.OnMessageReceivedListener mMessageListener = event -> {
        String path = event.getPath();
        switch (path) {
            case "/camera/take_photo":
                captureHighResPhoto();
                break;
            case "/camera/toggle_video":
                toggleVideoRecording();
                break;
            case "/camera/control":
                try {
                    String jsonStr = new String(event.getData(), StandardCharsets.UTF_8);
                    JSONObject json = new JSONObject(jsonStr);
                    String action = json.optString("action");
                    switch (action.toUpperCase(Locale.US)) {
                        case "REQUEST_CAMERA_LIST":
                            sendCameraListToWear();
                            break;
                        case "SELECT_CAMERA":
                            switchCamera(json.optString("camera_id"));
                            break;
                        case "SET_ZOOM":
                            setZoom((float) json.optDouble("zoom", 1.0));
                            break;
                    }
                } catch (Exception ignored) {
                }
                break;
        }
    };

    // ==================== Service 生命周期 ====================

    @Override
    public void onCreate() {
        super.onCreate();
        PhoneLog.d(TAG, "🟢 [PhoneSyncCameraService] onCreate");
        createNotificationChannel();

        try {
            PhoneLog.d(TAG, "🟢 [PhoneSyncCameraService] 启动 Camera FGS");
            startForeground(101, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA);
            PhoneLog.d(TAG, "✅ [PhoneSyncCameraService] Camera FGS 启动成功");
            sendCameraFgsReady();
        } catch (Exception e) {
            PhoneLog.e(TAG, "❌ [PhoneSyncCameraService] Camera FGS 启动失败", e);
        }

        mChannelClient = Wearable.getChannelClient(this);
        Wearable.getMessageClient(this).addListener(mMessageListener);
    }

    private void sendCameraFgsReady() {
        Intent intent = new Intent(ACTION_CAMERA_FGS_READY);
        intent.setPackage(getPackageName());
        sendBroadcast(intent);
        PhoneLog.d(TAG, "📡 [PhoneSyncCameraService] 已发送 CAMERA_FGS_READY");
    }

    @Override
    public void onDestroy() {
        stopStreamingAndRelease();
        Wearable.getMessageClient(this).removeListener(mMessageListener);
        super.onDestroy();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        String action = intent.getAction();
        PhoneLog.d(TAG, "▶ onStartCommand: Action=" + action);

        resetWatchdog();

        if (ACTION_START_CAMERA.equals(action)) {
            mCachedNodeId = intent.getStringExtra("remote_node_id");
            if (mCachedNodeId == null) mCachedNodeId = WearSyncState.getNodeId(this);
            PhoneLog.d(TAG, "📱 手表请求启动相机. NodeID=" + mCachedNodeId);
            if (mCachedNodeId == null) {
                PhoneLog.e(TAG, "❌ 无法获取 NodeID，无法启动");
                stopSelf();
                return START_NOT_STICKY;
            }
            if (!mIsStreaming.get()) {
                PhoneLog.d(TAG, "📷 相机未运行，开始初始化...");
                initCameraAndStartStreaming();
            } else {
                PhoneLog.d(TAG, "ℹ️ 相机已在运行，发送相机列表");
                sendCameraListToWear();
            }
        } else if (ACTION_STOP_CAMERA.equals(action)) {
            PhoneLog.d(TAG, "🛑 收到停止相机请求");
            stopStreamingAndRelease();
            stopSelf();
        } else if (ACTION_TAKE_PHOTO.equals(action)) {
            PhoneLog.d(TAG, "📸 收到拍照请求...");
            captureHighResPhoto();
        } else if (ACTION_SWITCH_CAMERA.equals(action)) {
            String camId = intent.getStringExtra("camera_id");
            PhoneLog.d(TAG, "🔄 收到切换相机请求: " + camId);
            switchCamera(camId);
        } else if (ACTION_TOGGLE_VIDEO.equals(action)) {
            toggleVideoRecording();
        } else if (ACTION_SET_ZOOM.equals(action)) {
            setZoom(intent.getFloatExtra("zoom", 1.0f));
        } else if (ACTION_FOCUS_CAMERA.equals(action)) {
            manualFocus(intent.getDoubleExtra("x", 0.5), intent.getDoubleExtra("y", 0.5));
        } else if (ACTION_REQUEST_CAMERA_LIST.equals(action)) {
            PhoneLog.d(TAG, "📋 收到相机列表请求");
            sendCameraListToWear();
        }
        return START_NOT_STICKY;
    }

    // ==================== 初始化与启动 ====================

    private void initCameraAndStartStreaming() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            PhoneLog.e(TAG, "❌ initCameraAndStartStreaming: 缺少相机权限");
            return;
        }
        mIsStreaming.set(true);
        startBackgroundThread();
        startOrientationListener();
        try {
            PhoneLog.d(TAG, "📐 正在计算最优尺寸...");
            chooseOptimalSizes();

            PhoneLog.d(TAG, "🎬 创建 H.264 编码器 (实时预览用)... 尺寸: " + mPreviewSize);
            MediaFormat format = MediaFormat.createVideoFormat(
                    MediaFormat.MIMETYPE_VIDEO_AVC, mPreviewSize.getWidth(), mPreviewSize.getHeight());
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            format.setInteger(MediaFormat.KEY_BIT_RATE, 450_000);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, 25);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);

            int rotation = calculatePreviewRotation();
            format.setInteger(MediaFormat.KEY_ROTATION, rotation);
            PhoneLog.d(TAG, "🔄 预览编码器旋转角度: " + rotation);

            mEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            mEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            mEncoderSurface = mEncoder.createInputSurface();

            PhoneLog.d(TAG, "📷 创建 ImageReader (拍照用)... 尺寸: " + mPhotoSize);
            mPhotoReader = ImageReader.newInstance(mPhotoSize.getWidth(), mPhotoSize.getHeight(), ImageFormat.JPEG, 2);
            mPhotoReader.setOnImageAvailableListener(reader -> {
                Image img = reader.acquireLatestImage();
                if (img != null) {
                    savePhoto(img);
                    img.close();
                }
            }, mBgHandler);

            PhoneLog.d(TAG, "📱 正在打开相机硬件...");
            startCameraHardware();
        } catch (Exception e) {
            PhoneLog.e(TAG, "❌ Streaming Init Failed", e);
            stopStreamingAndRelease();
            stopSelf();
        }
    }

    /**
     * 计算预览流旋转角度（H.264 发送到手表）
     * 前置和后置分别处理
     */
    private int calculatePreviewRotation() {
        int devRot = (mDeviceOrientation != OrientationEventListener.ORIENTATION_UNKNOWN)
                ? (mDeviceOrientation + 45) / 90 * 90 : 0;
        int rotation;
        if (mCameraFacing == CameraCharacteristics.LENS_FACING_FRONT) {
            // 前置：(360 - (sensorOrientation + devRot) % 360) % 360
            rotation = (360 - (mSensorOrientation + devRot) % 360) % 360;
        } else {
            // 后置：(sensorOrientation - devRot + 360) % 360
            rotation = (mSensorOrientation - devRot + 360) % 360;
        }
        return rotation;
    }

    /**
     * 计算拍照JPEG旋转角度
     */
    private int calculateJpegRotation() {
        int devRot = (mDeviceOrientation != OrientationEventListener.ORIENTATION_UNKNOWN)
                ? (mDeviceOrientation + 45) / 90 * 90 : 0;
        int rotation;
        if (mCameraFacing == CameraCharacteristics.LENS_FACING_FRONT) {
            rotation = (mSensorOrientation + devRot) % 360;
            rotation = (360 - rotation) % 360; // 镜像补偿
        } else {
            rotation = (mSensorOrientation - devRot + 360) % 360;
        }
        return rotation;
    }

    // ==================== 尺寸和能力选择 ====================

    private void chooseOptimalSizes() throws CameraAccessException {
        CameraManager mgr = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        String[] idList = mgr.getCameraIdList();
        if (idList.length == 0) {
            PhoneLog.e(TAG, "❌ 设备上没有任何相机");
            throw new CameraAccessException(CameraAccessException.CAMERA_DISCONNECTED, "No cameras found");
        }

        // 默认选择后置主摄
        if (mCameraId == null) {
            PhoneLog.d(TAG, "📷 未指定相机ID，自动选择后置主摄...");
            mCameraId = findBestBackCamera(mgr, idList);
        }
        if (mCameraId == null) {
            mCameraId = idList[0];
            PhoneLog.d(TAG, "⚠️ 未找到合适后置相机，使用第一个: " + mCameraId);
        }

        CameraCharacteristics chars = mgr.getCameraCharacteristics(mCameraId);
        mCameraFacing = Objects.requireNonNullElse(chars.get(CameraCharacteristics.LENS_FACING), CameraMetadata.LENS_FACING_BACK);
        mMaxZoom = Objects.requireNonNullElse(chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM), 1.0f);
        mActiveArraySize = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
        mSensorOrientation = Objects.requireNonNullElse(chars.get(CameraCharacteristics.SENSOR_ORIENTATION), 0);

        StreamConfigurationMap map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (map != null) {
            // 1. 拍照尺寸：最大 JPEG
            Size[] photoSizes = map.getOutputSizes(ImageFormat.JPEG);
            if (photoSizes != null && photoSizes.length > 0) {
                mPhotoSize = Collections.max(Arrays.asList(photoSizes), SIZE_BY_AREA);
            }

            // 2. 预览尺寸：适合手表的低分辨率
            Size[] previewSizes = map.getOutputSizes(MediaCodec.class);
            if (previewSizes != null && previewSizes.length > 0) {
                mPreviewSize = chooseBestPreviewSize(previewSizes);
            }

            // 3. 录像尺寸和FPS：成对选择
            chooseVideoSizeAndFps(map, chars);
        }

        PhoneLog.d(TAG, "📊 尺寸选择结果: Facing=" + mCameraFacing + ", SensorOrient=" + mSensorOrientation +
                ", Photo=" + mPhotoSize + ", Preview=" + mPreviewSize +
                ", Video=" + mVideoSize + "@" + mVideoFps + "fps, HEVC=" + mUseHevcForRecording);
    }

    /**
     * 选择最佳后置相机（主摄优先）
     */
    private String findBestBackCamera(CameraManager mgr, String[] idList) {
        String bestId = null;
        long bestArea = 0;
        try {
            for (String id : idList) {
                CameraCharacteristics chars = mgr.getCameraCharacteristics(id);
                Integer facing = chars.get(CameraCharacteristics.LENS_FACING);
                if (facing == null || facing != CameraMetadata.LENS_FACING_BACK) continue;

                StreamConfigurationMap map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                if (map == null) continue;
                Size[] jpegSizes = map.getOutputSizes(ImageFormat.JPEG);
                if (jpegSizes == null || jpegSizes.length == 0) continue;

                Size maxJpeg = Collections.max(Arrays.asList(jpegSizes), SIZE_BY_AREA);
                long area = (long) maxJpeg.getWidth() * maxJpeg.getHeight();
                if (area > bestArea) {
                    bestArea = area;
                    bestId = id;
                }
            }
        } catch (Exception e) {
            PhoneLog.e(TAG, "❌ findBestBackCamera error", e);
        }
        return bestId;
    }

    /**
     * 成对选择录像分辨率和FPS
     * 优先最高分辨率，然后确认该分辨率支持的最大FPS
     */
    private void chooseVideoSizeAndFps(StreamConfigurationMap map, CameraCharacteristics chars) {
        Size[] videoSizes = map.getOutputSizes(MediaRecorder.class);
        if (videoSizes == null || videoSizes.length == 0) {
            mVideoSize = new Size(1920, 1080);
            mVideoFps = 30;
            return;
        }

        // 按面积降序排列，优先选择最高分辨率
        List<Size> sortedSizes = new ArrayList<>(Arrays.asList(videoSizes));
        Collections.sort(sortedSizes, SIZE_BY_AREA.reversed());

        // 获取所有支持的FPS范围
        Range<Integer>[] fpsRanges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);

        // 优先选择 4K，然后 1440p，然后 1080p
        Size[] preferredSizes = {
                new Size(3840, 2160),
                new Size(2560, 1440),
                new Size(1920, 1080)
        };

        for (Size preferred : preferredSizes) {
            for (Size available : sortedSizes) {
                if (available.getWidth() == preferred.getWidth() && available.getHeight() == preferred.getHeight()) {
                    mVideoSize = available;
                    mVideoFps = getMaxFpsForSize(map, available, fpsRanges);
                    return;
                }
            }
        }

        // 如果以上都不支持，选择最大的可用尺寸
        mVideoSize = sortedSizes.get(0);
        mVideoFps = getMaxFpsForSize(map, mVideoSize, fpsRanges);
    }

    /**
     * 获取指定分辨率支持的最大FPS
     * 基于 Camera2 的 FPS 范围来判断
     */
    private int getMaxFpsForSize(StreamConfigurationMap map, Size size, Range<Integer>[] fpsRanges) {
        // 默认30fps
        int maxFps = 30;

        if (fpsRanges != null) {
            // 对于4K分辨率，通常最大只支持30fps
            long pixels = (long) size.getWidth() * size.getHeight();
            if (pixels > 2560L * 1440L) {
                // 4K: 限制为30fps
                for (Range<Integer> range : fpsRanges) {
                    if (range.getUpper() >= 30 && range.getLower() <= 30) {
                        maxFps = 30;
                        break;
                    }
                }
            } else if (pixels > 1920L * 1080L) {
                // 1440p: 最大60fps
                for (Range<Integer> range : fpsRanges) {
                    if (range.getUpper() > maxFps && range.getUpper() <= 60) {
                        maxFps = range.getUpper();
                    }
                }
            } else {
                // 1080p及以下: 取最大支持FPS
                for (Range<Integer> range : fpsRanges) {
                    if (range.getUpper() > maxFps) {
                        maxFps = range.getUpper();
                    }
                }
                // 限制不超过60，避免极端值
                maxFps = Math.min(maxFps, 60);
            }
        }
        return maxFps;
    }

    /**
     * 检查 HEVC 编码器是否支持当前录像分辨率和FPS
     */
    private boolean checkHevcSupport(Size videoSize, int fps) {
        try {
            MediaCodecList list = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
            for (MediaCodecInfo info : list.getCodecInfos()) {
                if (!info.isEncoder()) continue;
                for (String type : info.getSupportedTypes()) {
                    if (!type.equalsIgnoreCase(MediaFormat.MIMETYPE_VIDEO_HEVC)) continue;

                    MediaCodecInfo.CodecCapabilities caps = info.getCapabilitiesForType(type);
                    MediaCodecInfo.VideoCapabilities videoCaps = caps.getVideoCapabilities();
                    if (videoCaps == null) continue;

                    // 检查分辨率是否支持
                    if (!videoCaps.isSizeSupported(videoSize.getWidth(), videoSize.getHeight())) {
                        continue;
                    }

                    // 检查帧率是否支持
                    Range<Integer> fpsRange = videoCaps.getSupportedFrameRatesFor(
                            videoSize.getWidth(), videoSize.getHeight());
                    if (fpsRange != null && fpsRange.contains(fps)) {
                        PhoneLog.d(TAG, "✅ HEVC 编码器 [" + info.getName() + "] 支持 " +
                                videoSize + "@" + fps + "fps");
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            PhoneLog.w(TAG, "⚠️ HEVC 能力检查异常", e);
        }
        return false;
    }

    private Size chooseBestPreviewSize(Size[] sizes) {
        double targetRatio = (double) mPhotoSize.getWidth() / mPhotoSize.getHeight();
        Size bestMatch = null;
        double minDiff = Double.MAX_VALUE;

        List<Size> candidates = new ArrayList<>();
        for (Size s : sizes) {
            if (s.getWidth() >= 240 && s.getWidth() <= 450) {
                candidates.add(s);
            }
        }

        if (candidates.isEmpty()) {
            Size smallest = sizes[0];
            for (Size s : sizes) {
                if (s.getWidth() * s.getHeight() < smallest.getWidth() * smallest.getHeight()) smallest = s;
            }
            return smallest;
        }

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

    // ==================== Camera 硬件操作 ====================

    @SuppressLint("MissingPermission")
    private void startCameraHardware() {
        CameraManager mgr = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            PhoneLog.d(TAG, "📱 正在打开相机: " + mCameraId);
            mgr.openCamera(mCameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(@NonNull CameraDevice camera) {
                    PhoneLog.d(TAG, "✅ 相机已打开 [ID: " + camera.getId() + "]");
                    if (!mIsStreaming.get()) {
                        PhoneLog.w(TAG, "⚠️ 相机已打开但 Streaming 已停止，关闭相机...");
                        camera.close();
                        return;
                    }
                    mCameraDevice = camera;
                    mIsCameraOpened.set(true);
                    createPreviewSession();
                }

                @Override
                public void onDisconnected(@NonNull CameraDevice camera) {
                    PhoneLog.w(TAG, "⚠️ 相机断开连接");
                    camera.close();
                    mCameraDevice = null;
                    mIsCameraOpened.set(false);
                    stopStreamingAndRelease();
                }

                @Override
                public void onError(@NonNull CameraDevice camera, int error) {
                    PhoneLog.e(TAG, "❌ 相机打开错误: " + error);
                    camera.close();
                    mCameraDevice = null;
                    mIsCameraOpened.set(false);
                    stopStreamingAndRelease();
                }
            }, mBgHandler);
        } catch (Exception e) {
            PhoneLog.e(TAG, "❌ Hardware Open Failed", e);
        }
    }

    /**
     * 创建纯预览 CaptureSession（仅 Encoder Surface + PhotoReader）
     * 用于初始启动和录像停止后恢复
     */
    private void createPreviewSession() {
        if (mCameraDevice == null || mEncoderSurface == null || mPhotoReader == null || !mIsStreaming.get()) {
            PhoneLog.e(TAG, "❌ createPreviewSession: 前置条件不满足");
            return;
        }

        // 先关闭旧 Session
        closeCurrentSession();

        try {
            PhoneLog.d(TAG, "🔧 创建预览 CaptureSession (SessionConfiguration)...");
            List<OutputConfiguration> outputs = new ArrayList<>();
            if (mEncoderSurface.isValid()) outputs.add(new OutputConfiguration(mEncoderSurface));
            if (mPhotoReader.getSurface().isValid()) outputs.add(new OutputConfiguration(mPhotoReader.getSurface()));

            SessionConfiguration sessionConfig = new SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    outputs,
                    command -> mBgHandler.post(command),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession session) {
                            PhoneLog.d(TAG, "✅ 预览 CaptureSession 配置成功");
                            if (!mIsStreaming.get()) {
                                session.close();
                                return;
                            }
                            mCaptureSession = session;
                            mSessionConfigured.set(true);
                            startPreviewRequest();
                            openChannelStream();
                        }

                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                            PhoneLog.e(TAG, "❌ 预览 CaptureSession 配置失败");
                            mSessionConfigured.set(false);
                            tryRecoverFromConfigFailure();
                        }
                    }
            );

            mCameraDevice.createCaptureSession(sessionConfig);
        } catch (Exception e) {
            PhoneLog.e(TAG, "❌ Preview Session creation failed", e);
        }
    }

    /**
     * 创建录像 CaptureSession（Encoder Surface + MediaRecorder Surface + PhotoReader）
     */
    private void createRecordingSession() {
        if (mCameraDevice == null || mEncoderSurface == null || mVideoRecorder == null || !mIsStreaming.get()) {
            PhoneLog.e(TAG, "❌ createRecordingSession: 前置条件不满足");
            return;
        }

        // 先关闭旧 Session
        closeCurrentSession();

        try {
            PhoneLog.d(TAG, "🔧 创建录像 CaptureSession...");
            List<OutputConfiguration> outputs = new ArrayList<>();
            if (mEncoderSurface.isValid()) outputs.add(new OutputConfiguration(mEncoderSurface));

            Surface recSurface = mVideoRecorder.getSurface();
            if (recSurface != null && recSurface.isValid()) {
                outputs.add(new OutputConfiguration(recSurface));
            } else {
                PhoneLog.e(TAG, "❌ MediaRecorder Surface 无效");
                return;
            }

            if (mPhotoReader != null && mPhotoReader.getSurface().isValid()) {
                outputs.add(new OutputConfiguration(mPhotoReader.getSurface()));
            }

            SessionConfiguration sessionConfig = new SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    outputs,
                    command -> mBgHandler.post(command),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession session) {
                            PhoneLog.d(TAG, "✅ 录像 CaptureSession 配置成功");
                            if (!mIsStreaming.get()) {
                                session.close();
                                return;
                            }
                            mCaptureSession = session;
                            mSessionConfigured.set(true);

                            // 提交录像 repeating request
                            startRecordingRequest();

                            // Session 配置成功且 repeating request 提交后，启动 MediaRecorder
                            if (mRecorderPrepared.get() && !mRecorderStarted.get()) {
                                try {
                                    mVideoRecorder.start();
                                    mRecorderStarted.set(true);
                                    mIsRecording.set(true);
                                    PhoneLog.d(TAG, "🎬 MediaRecorder 已启动，录像正式开始");
                                    notifyWearVideoStatus(true);
                                } catch (Exception e) {
                                    PhoneLog.e(TAG, "❌ MediaRecorder 启动失败", e);
                                    mIsRecording.set(false);
                                    mRecorderStarted.set(false);
                                    cleanupFailedRecording();
                                }
                            }
                        }

                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                            PhoneLog.e(TAG, "❌ 录像 CaptureSession 配置失败");
                            mSessionConfigured.set(false);
                            mIsRecording.set(false);
                            cleanupFailedRecording();
                        }
                    }
            );

            mCameraDevice.createCaptureSession(sessionConfig);
        } catch (Exception e) {
            PhoneLog.e(TAG, "❌ Recording Session creation failed", e);
            mIsRecording.set(false);
            cleanupFailedRecording();
        }
    }

    /**
     * 关闭当前 CaptureSession（安全方式）
     */
    private void closeCurrentSession() {
        if (mCaptureSession != null) {
            try {
                mCaptureSession.stopRepeating();
            } catch (Exception ignored) {
            }
            try {
                mCaptureSession.abortCaptures();
            } catch (Exception ignored) {
            }
            try {
                mCaptureSession.close();
            } catch (Exception ignored) {
            }
            mCaptureSession = null;
            mSessionConfigured.set(false);
        }
    }

    // ==================== CaptureRequest ====================

    /**
     * 提交预览 RepeatingRequest（仅 Encoder Surface）
     */
    private void startPreviewRequest() {
        if (mCaptureSession == null || mCameraDevice == null || !mIsStreaming.get()) {
            PhoneLog.e(TAG, "❌ startPreviewRequest: 前置条件不满足");
            return;
        }
        try {
            PhoneLog.d(TAG, "▶ 提交预览 RepeatingRequest...");
            CaptureRequest.Builder b = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            b.addTarget(mEncoderSurface);
            applyZoom(b);
            applyCommonControls(b);
            mCaptureSession.setRepeatingRequest(b.build(), null, mBgHandler);
        } catch (Exception e) {
            PhoneLog.e(TAG, "❌ Preview RepeatingRequest 提交失败", e);
        }
    }

    /**
     * 提交录像 RepeatingRequest（Encoder Surface + Recorder Surface）
     */
    private void startRecordingRequest() {
        if (mCaptureSession == null || mCameraDevice == null || mVideoRecorder == null || !mIsStreaming.get()) {
            PhoneLog.e(TAG, "❌ startRecordingRequest: 前置条件不满足");
            return;
        }
        try {
            PhoneLog.d(TAG, "▶ 提交录像 RepeatingRequest...");
            CaptureRequest.Builder b = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            b.addTarget(mEncoderSurface);

            Surface recSurface = mVideoRecorder.getSurface();
            if (recSurface != null && recSurface.isValid()) {
                b.addTarget(recSurface);
            }

            applyZoom(b);
            applyCommonControls(b);

            // 设置录像帧率
            b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, new Range<>(mVideoFps, mVideoFps));

            mCaptureSession.setRepeatingRequest(b.build(), null, mBgHandler);
            PhoneLog.d(TAG, "✅ 录像 RepeatingRequest 已提交");
        } catch (Exception e) {
            PhoneLog.e(TAG, "❌ Recording RepeatingRequest 提交失败", e);
        }
    }

    /**
     * 应用通用相机控制参数
     */
    private void applyCommonControls(CaptureRequest.Builder b) {
        b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
        b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
        b.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO);
    }

    private void applyZoom(CaptureRequest.Builder b) {
        if (mActiveArraySize == null) return;
        int cx = mActiveArraySize.centerX(), cy = mActiveArraySize.centerY();
        int dx = (int) (0.5f * mActiveArraySize.width() / mCurrentZoom);
        int dy = (int) (0.5f * mActiveArraySize.height() / mCurrentZoom);
        b.set(CaptureRequest.SCALER_CROP_REGION, new Rect(cx - dx, cy - dy, cx + dx, cy + dy));
    }

    // ==================== 录像流程 ====================

    private void toggleVideoRecording() {
        if (mIsRecording.get()) {
            stopVideoRecording();
        } else {
            startVideoRecording();
        }
    }

    /**
     * 开始录像 - 完整重构版
     * 流程：
     * 1. 创建并配置 MediaRecorder
     * 2. 关闭当前 Preview Session
     * 3. 创建新的 Recording Session（包含 Encoder + Recorder Surface）
     * 4. Session 配置成功后提交 Recording Request
     * 5. Request 提交成功后启动 MediaRecorder
     */
    private void startVideoRecording() {
        if (mIsRecording.get() || !mIsStreaming.get() || mCameraDevice == null) {
            PhoneLog.w(TAG, "⚠️ 无法开始录像: recording=" + mIsRecording.get() +
                    ", streaming=" + mIsStreaming.get() + ", camera=" + (mCameraDevice != null));
            return;
        }

        PhoneLog.d(TAG, "🎬 开始录像流程...");

        // 检查 HEVC 支持（基于当前录像分辨率和FPS）
        mUseHevcForRecording = checkHevcSupport(mVideoSize, mVideoFps);

        Uri uri = null;
        try {
            String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            String fileName = "VID_" + timeStamp + ".mp4";
            ContentValues values = new ContentValues();
            values.put(MediaStore.Video.Media.DISPLAY_NAME, fileName);
            values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
            values.put(MediaStore.Video.Media.RELATIVE_PATH, "DCIM/WearSync");
            uri = getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) {
                PhoneLog.e(TAG, "❌ 无法创建视频文件 URI");
                return;
            }
            mCurrentVideoUri = uri;

            try (ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(uri, "rw")) {
                if (pfd == null) {
                    PhoneLog.e(TAG, "❌ 无法打开文件描述符");
                    cleanupEmptyVideo(uri);
                    return;
                }

                // 创建并配置 MediaRecorder
                mVideoRecorder = new MediaRecorder(this);
                mVideoRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
                mVideoRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
                mVideoRecorder.setOutputFile(pfd.getFileDescriptor());

                if (mUseHevcForRecording) {
                    mVideoRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.HEVC);
                    PhoneLog.d(TAG, "🎬 录像编码器: HEVC/H.265");
                } else {
                    mVideoRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
                    PhoneLog.d(TAG, "🎬 录像编码器: H.264");
                }

                mVideoRecorder.setVideoSize(mVideoSize.getWidth(), mVideoSize.getHeight());
                mVideoRecorder.setVideoFrameRate(mVideoFps);

                // 码率计算
                int bitRate = (int) (mVideoSize.getWidth() * mVideoSize.getHeight() * 4L);
                mVideoRecorder.setVideoEncodingBitRate(Math.min(bitRate, 50_000_000));

                mVideoRecorder.prepare();
                mRecorderPrepared.set(true);
                PhoneLog.d(TAG, "✅ MediaRecorder prepare 成功");

                // 创建录像专用 CaptureSession
                // 这会先关闭旧的 Preview Session，再创建包含 Recorder Surface 的新 Session
                createRecordingSession();
            }
        } catch (Exception e) {
            PhoneLog.e(TAG, "❌ 录像启动失败", e);
            mRecorderPrepared.set(false);
            mRecorderStarted.set(false);
            mIsRecording.set(false);
            cleanupFailedRecording();
            if (uri != null) cleanupEmptyVideo(uri);
            // 恢复预览
            createPreviewSession();
        }
    }

    /**
     * 停止录像 - 完整重构版
     * 流程：
     * 1. 停止当前 Recording Session
     * 2. 停止并释放 MediaRecorder
     * 3. 清除录像状态
     * 4. 重新创建 Preview Session
     * 5. 恢复 H.264 预览
     */
    private void stopVideoRecording() {
        if (!mIsRecording.get() && !mRecorderStarted.get()) {
            PhoneLog.w(TAG, "⚠️ stopVideoRecording: 当前未在录像");
            return;
        }

        PhoneLog.d(TAG, "⏹ 停止录像...");

        try {
            // 1. 停止当前 repeating request 并关闭录像 Session
            closeCurrentSession();

            // 2. 停止 MediaRecorder
            if (mVideoRecorder != null) {
                if (mRecorderStarted.get()) {
                    try {
                        mVideoRecorder.stop();
                        PhoneLog.d(TAG, "✅ MediaRecorder stop 成功");
                    } catch (Exception e) {
                        PhoneLog.e(TAG, "❌ MediaRecorder stop 异常", e);
                        // 如果 stop 失败，删除文件
                        if (mCurrentVideoUri != null) {
                            cleanupEmptyVideo(mCurrentVideoUri);
                        }
                    }
                }
                mVideoRecorder.release();
                mVideoRecorder = null;
            }

            // 3. 清除录像状态
            mIsRecording.set(false);
            mRecorderPrepared.set(false);
            mRecorderStarted.set(false);
            mCurrentVideoUri = null;

            // 4. 重新创建预览 Session，恢复 H.264 预览
            if (mIsStreaming.get()) {
                createPreviewSession();
            }

            notifyWearVideoStatus(false);
            PhoneLog.d(TAG, "✅ 录像已停止，预览恢复中...");
        } catch (Exception e) {
            PhoneLog.e(TAG, "❌ 停止录像异常", e);
            mIsRecording.set(false);
            mRecorderPrepared.set(false);
            mRecorderStarted.set(false);
            if (mVideoRecorder != null) {
                try {
                    mVideoRecorder.release();
                } catch (Exception ignored) {
                }
                mVideoRecorder = null;
            }
            // 尝试恢复预览
            if (mIsStreaming.get()) {
                createPreviewSession();
            }
        }
    }

    /**
     * 清理失败的录像（未成功 start 的情况）
     */
    private void cleanupFailedRecording() {
        PhoneLog.w(TAG, "🧹 清理失败的录像...");
        if (mVideoRecorder != null) {
            try {
                // 如果从未 start，直接 release
                if (mRecorderStarted.get()) {
                    mVideoRecorder.stop();
                }
            } catch (Exception ignored) {
            }
            try {
                mVideoRecorder.release();
            } catch (Exception ignored) {
            }
            mVideoRecorder = null;
        }
        mRecorderPrepared.set(false);
        mRecorderStarted.set(false);
        if (mCurrentVideoUri != null) {
            cleanupEmptyVideo(mCurrentVideoUri);
            mCurrentVideoUri = null;
        }
    }

    private void cleanupEmptyVideo(Uri uri) {
        if (uri != null) {
            try {
                getContentResolver().delete(uri, null, null);
                PhoneLog.d(TAG, "🗑 已删除无效视频文件");
            } catch (Exception ignored) {
            }
        }
    }

    private void notifyWearVideoStatus(boolean rec) {
        if (mCachedNodeId == null) return;
        try {
            JSONObject j = new JSONObject();
            j.put("type", "video_status");
            j.put("isRecording", rec);
            Wearable.getMessageClient(this).sendMessage(mCachedNodeId, "/camera/video_status",
                    j.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }

    // ==================== 拍照 ====================

    private void captureHighResPhoto() {
        if (mCaptureSession == null || !mIsStreaming.get()) {
            PhoneLog.e(TAG, "❌ captureHighResPhoto: 相机会话不可用，尝试重新初始化...");
            initCameraAndStartStreaming();
            return;
        }
        try {
            CaptureRequest.Builder b = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            b.addTarget(mPhotoReader.getSurface());
            applyZoom(b);

            int rotation = calculateJpegRotation();
            b.set(CaptureRequest.JPEG_ORIENTATION, rotation);
            b.set(CaptureRequest.JPEG_QUALITY, (byte) 100);

            mCaptureSession.capture(b.build(), null, mBgHandler);
            PhoneLog.d(TAG, "📸 Photo Capture Triggered with rotation: " + rotation);
        } catch (Exception e) {
            PhoneLog.e(TAG, "❌ 拍照请求失败", e);
        }
    }

    private void savePhoto(Image image) {
        try {
            ByteBuffer buf = image.getPlanes()[0].getBuffer();
            byte[] data = new byte[buf.remaining()];
            buf.get(data);
            String fileName = "IMG_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".jpg";
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, fileName);
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            values.put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/WearSync");
            Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (uri != null) {
                try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                    if (os != null) os.write(data);
                }
                writeLocationExifFromUri(uri);
                PhoneLog.d(TAG, "✅ Photo saved to MediaStore: " + fileName);
            }
        } catch (Exception e) {
            PhoneLog.e(TAG, "Save Photo Error", e);
        }
    }

    @SuppressLint("MissingPermission")
    private void writeLocationExifFromUri(Uri uri) {
        if (!getSharedPreferences("dndsync_prefs", Context.MODE_PRIVATE)
                .getBoolean("save_location_enabled", false)) return;
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
                exif.setGpsInfo(best);
                exif.saveAttributes();
            }
        } catch (Exception ignored) {
        }
    }

    // ==================== Camera 切换 ====================

    /**
     * 切换相机 - 完整重建流程
     * 1. 停止当前录像（如果在录）
     * 2. 停止 Preview
     * 3. 关闭 CaptureSession
     * 4. 关闭 CameraDevice
     * 5. 释放 Encoder/Surface/Reader
     * 6. 更新 CameraId
     * 7. 重新初始化全部流程
     */
    private void switchCamera(String id) {
        if (id == null || id.equals(mCameraId)) return;

        PhoneLog.d(TAG, "🔄 切换相机: " + mCameraId + " → " + id);

        // 如果正在录像，先停止录像
        if (mIsRecording.get()) {
            stopVideoRecording();
        }

        // 停止当前流
        stopStreamingAndRelease();

        // 更新 Camera ID
        mCameraId = id;

        // 重新初始化
        initCameraAndStartStreaming();
    }

    // ==================== Zoom ====================

    private void setZoom(float z) {
        mCurrentZoom = Math.max(1.0f, Math.min(z, mMaxZoom));
        PhoneLog.d(TAG, "🔍 Zoom 设置为: " + mCurrentZoom + " (最大: " + mMaxZoom + ")");
        // 更新当前 repeating request
        if (mIsRecording.get()) {
            startRecordingRequest();
        } else {
            startPreviewRequest();
        }
    }

    // ==================== 手动对焦 ====================

    private void manualFocus(double x, double y) {
        if (mCameraDevice == null || mCaptureSession == null) return;
        try {
            PhoneLog.d(TAG, "🎯 手动对焦: x=" + x + ", y=" + y);
            CameraCharacteristics chars = ((CameraManager) getSystemService(Context.CAMERA_SERVICE))
                    .getCameraCharacteristics(mCameraDevice.getId());
            Rect sensor = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
            if (sensor != null) {
                int cx = (int) (x * sensor.width()), cy = (int) (y * sensor.height());
                MeteringRectangle area = new MeteringRectangle(
                        Math.max(0, cx - 100), Math.max(0, cy - 100),
                        Math.min(sensor.width(), 200), Math.min(sensor.height(), 200),
                        MeteringRectangle.METERING_WEIGHT_MAX);

                int template = mIsRecording.get() ? CameraDevice.TEMPLATE_RECORD : CameraDevice.TEMPLATE_PREVIEW;
                CaptureRequest.Builder b = mCameraDevice.createCaptureRequest(template);
                b.addTarget(mEncoderSurface);

                if (mIsRecording.get() && mVideoRecorder != null) {
                    Surface recSurface = mVideoRecorder.getSurface();
                    if (recSurface != null && recSurface.isValid()) b.addTarget(recSurface);
                }

                applyZoom(b);
                b.set(CaptureRequest.CONTROL_AF_REGIONS, new MeteringRectangle[]{area});
                b.set(CaptureRequest.CONTROL_AE_REGIONS, new MeteringRectangle[]{area});
                b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO);
                b.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START);

                mCaptureSession.setRepeatingRequest(b.build(), null, mBgHandler);
                PhoneLog.d(TAG, "✅ 对焦请求已发送");
            }
        } catch (Exception e) {
            PhoneLog.e(TAG, "❌ 对焦失败", e);
        }
    }

    // ==================== Camera 列表 ====================

    /**
     * 发送相机列表到手表
     * 完善版：包含能力筛选和动态命名
     */
    private void sendCameraListToWear() {
        if (mCachedNodeId == null) {
            PhoneLog.w(TAG, "⚠️ sendCameraListToWear: NodeID 不存在");
            return;
        }
        PhoneLog.d(TAG, "📋 正在构建相机列表...");
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

                    // 筛选：必须有 JPEG 输出
                    Size[] jpegSizes = map.getOutputSizes(ImageFormat.JPEG);
                    if (jpegSizes == null || jpegSizes.length == 0) continue;

                    // 筛选：JPEG 最大分辨率不能太小（至少 1MP）
                    Size maxJpeg = Collections.max(Arrays.asList(jpegSizes), SIZE_BY_AREA);
                    if ((long) maxJpeg.getWidth() * maxJpeg.getHeight() < 1_000_000L) continue;

                    // 筛选：必须有合理的 Preview 能力
                    Size[] previewSizes = map.getOutputSizes(MediaCodec.class);
                    if (previewSizes == null || previewSizes.length == 0) continue;

                    // 筛选：检查录像能力
                    Size[] videoSizes = map.getOutputSizes(MediaRecorder.class);
                    boolean hasVideoCapability = (videoSizes != null && videoSizes.length > 0);

                    // 筛选：硬件级别不能太低
                    Integer hwLevel = chars.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);
                    if (hwLevel != null && hwLevel == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY) {
                        // LEGACY 级别能力太弱，跳过（除非没有其他选择）
                        continue;
                    }

                    Integer f = chars.get(CameraCharacteristics.LENS_FACING);
                    float[] fl = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
                    float maxZoom = Objects.requireNonNullElse(
                            chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM), 1.0f);
                    float minFocusDist = Objects.requireNonNullElse(
                            chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE), 0.0f);

                    // 获取最大录像分辨率和FPS
                    Size maxVideoSize = new Size(0, 0);
                    int maxVideoFps = 0;
                    if (hasVideoCapability) {
                        maxVideoSize = Collections.max(Arrays.asList(videoSizes), SIZE_BY_AREA);
                        Range<Integer>[] fpsRanges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
                        if (fpsRanges != null) {
                            for (Range<Integer> range : fpsRanges) {
                                if (range.getUpper() > maxVideoFps) maxVideoFps = range.getUpper();
                            }
                        }
                    }

                    JSONObject o = new JSONObject();
                    o.put("id", id);
                    o.put("maxZoom", (double) maxZoom);
                    o.put("maxVideoWidth", maxVideoSize.getWidth());
                    o.put("maxVideoHeight", maxVideoSize.getHeight());
                    o.put("maxVideoFps", maxVideoFps);
                    o.put("maxJpegWidth", maxJpeg.getWidth());
                    o.put("maxJpegHeight", maxJpeg.getHeight());
                    o.put("hasVideo", hasVideoCapability);

                    // 动态生成名称
                    String name;
                    if (f != null && f == CameraMetadata.LENS_FACING_FRONT) {
                        name = "前置";
                    } else {
                        if (fl != null && fl.length > 0) {
                            float focal = fl[0];
                            // 使用更合理的焦距判断（基于等效焦距经验值）
                            if (focal < 3.5f) {
                                wideCount++;
                                name = wideCount > 1 ? "超广角 " + wideCount : "超广角";
                            } else if (focal > 7.0f) {
                                teleCount++;
                                name = teleCount > 1 ? "长焦 " + teleCount : "长焦";
                            } else {
                                mainCount++;
                                name = mainCount > 1 ? "主摄 " + mainCount : "主摄";
                            }
                        } else {
                            mainCount++;
                            name = "主摄";
                        }
                    }

                    o.put("name", name);
                    o.put("facing", (f != null) ? f : CameraMetadata.LENS_FACING_BACK);
                    o.put("sensorOrientation", Objects.requireNonNullElse(
                            chars.get(CameraCharacteristics.SENSOR_ORIENTATION), 0));
                    arr.put(o);
                }

                PhoneLog.d(TAG, "✅ 相机列表构建完成，共 " + arr.length() + " 个可用相机");
                Wearable.getMessageClient(this).sendMessage(mCachedNodeId, WEAR_MSG_PATH_CAMERA_LIST,
                                arr.toString().getBytes(StandardCharsets.UTF_8))
                        .addOnSuccessListener(taskResult -> PhoneLog.d(TAG, "✅ 相机列表已发送到手表"))
                        .addOnFailureListener(e -> PhoneLog.e(TAG, "❌ 发送相机列表失败", e));
            } catch (Exception e) {
                PhoneLog.e(TAG, "❌ 构建相机列表失败", e);
            }
        }).start();
    }

    // ==================== 数据通道 ====================

    private void openChannelStream() {
        if (mDataOutputStream != null) {
            PhoneLog.d(TAG, "ℹ️ 数据通道已存在");
            return;
        }
        if (mCachedNodeId == null) {
            PhoneLog.e(TAG, "❌ openChannelStream: NodeID 不存在");
            return;
        }

        PhoneLog.d(TAG, "📡 正在打开 Wearable Channel 数据通道: " + WEAR_CHANNEL_PATH);
        mChannelClient.openChannel(mCachedNodeId, WEAR_CHANNEL_PATH)
                .addOnSuccessListener(c -> {
                    PhoneLog.d(TAG, "✅ Channel 已打开，正在获取输出流...");
                    mChannelClient.getOutputStream(c).addOnSuccessListener(os -> {
                        PhoneLog.d(TAG, "✅ 输出流已获取");
                        mDataOutputStream = new DataOutputStream(os);

                        try {
                            mEncoder.setCallback(new EncoderCallback(), mBgHandler);
                            mEncoder.start();
                            PhoneLog.d(TAG, "🎬 H.264 编码器已启动，开始实时预览");

                            sendCameraListToWear();
                            notifyStreamReadyToWear();
                        } catch (Exception e) {
                            PhoneLog.e(TAG, "❌ 编码器启动失败", e);
                        }
                    }).addOnFailureListener(e -> PhoneLog.e(TAG, "❌ 获取 Channel 输出流失败", e));
                })
                .addOnFailureListener(e -> PhoneLog.e(TAG, "❌ 打开 Channel 失败", e));
    }

    private void notifyStreamReadyToWear() {
        if (mCachedNodeId == null) return;
        try {
            JSONObject j = new JSONObject();
            j.put("type", "camera_status");
            j.put("action", "STREAM_READY");
            Wearable.getMessageClient(this).sendMessage(mCachedNodeId, "/camera/status",
                    j.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }

    // ==================== 资源释放 ====================

    private synchronized void stopStreamingAndRelease() {
        PhoneLog.d(TAG, "🛑 开始释放所有资源...");
        mIsStreaming.set(false);
        mIsCameraOpened.set(false);

        // 1. 停止录像
        if (mIsRecording.get() || mRecorderStarted.get()) {
            try {
                if (mVideoRecorder != null) {
                    if (mRecorderStarted.get()) {
                        mVideoRecorder.stop();
                    }
                    mVideoRecorder.release();
                    mVideoRecorder = null;
                }
            } catch (Exception ignored) {
            }
            mIsRecording.set(false);
            mRecorderPrepared.set(false);
            mRecorderStarted.set(false);
        }

        // 2. 关闭 CaptureSession
        closeCurrentSession();

        // 3. 关闭 CameraDevice
        if (mCameraDevice != null) {
            try {
                mCameraDevice.close();
                PhoneLog.d(TAG, "✅ CameraDevice 已关闭");
            } catch (Exception ignored) {
            }
            mCameraDevice = null;
        }

        // 4. 释放编码器
        if (mEncoder != null) {
            try {
                mEncoder.stop();
                mEncoder.release();
            } catch (Exception ignored) {
            }
            mEncoder = null;
        }

        // 5. 释放 Surface 和 Reader
        if (mEncoderSurface != null) {
            mEncoderSurface.release();
            mEncoderSurface = null;
        }
        if (mPhotoReader != null) {
            mPhotoReader.close();
            mPhotoReader = null;
        }

        // 6. 清理通信和后台线程
        mDataOutputStream = null;
        if (mBgThread != null) {
            mBgThread.quitSafely();
            mBgThread = null;
        }
        if (mOrientationEventListener != null) {
            mOrientationEventListener.disable();
            mOrientationEventListener = null;
        }
        PhoneLog.d(TAG, "✅ 所有资源已释放");
    }

    // ==================== 恢复和辅助 ====================

    private void tryRecoverFromConfigFailure() {
        if (mCameraDevice == null || !mIsStreaming.get()) return;
        PhoneLog.w(TAG, "⚠️ Session 配置失败，尝试恢复...");
        try {
            List<OutputConfiguration> outputs = new ArrayList<>();
            outputs.add(new OutputConfiguration(mEncoderSurface));

            SessionConfiguration config = new SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    outputs,
                    command -> mBgHandler.post(command),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession session) {
                            PhoneLog.d(TAG, "✅ 恢复 Session 配置成功");
                            mCaptureSession = session;
                            mSessionConfigured.set(true);
                            startPreviewRequest();
                            openChannelStream();
                        }

                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                            PhoneLog.e(TAG, "❌ 恢复 Session 也失败，放弃");
                            stopStreamingAndRelease();
                        }
                    }
            );
            mCameraDevice.createCaptureSession(config);
        } catch (Exception e) {
            PhoneLog.e(TAG, "❌ 恢复失败", e);
            stopStreamingAndRelease();
        }
    }

    private void startBackgroundThread() {
        if (mBgThread == null) {
            mBgThread = new HandlerThread("CamBg");
            mBgThread.start();
            mBgHandler = new Handler(mBgThread.getLooper());
        }
    }

    private void startOrientationListener() {
        mOrientationEventListener = new OrientationEventListener(this) {
            @Override
            public void onOrientationChanged(int o) {
                if (o != ORIENTATION_UNKNOWN) mDeviceOrientation = o;
            }
        };
        mOrientationEventListener.enable();
    }

    private void resetWatchdog() {
        mWatchdogHandler.removeCallbacks(mWatchdogRunnable);
        mWatchdogHandler.postDelayed(mWatchdogRunnable, WATCHDOG_TIMEOUT);
    }

    private void createNotificationChannel() {
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(
                new NotificationChannel("camera_service_channel", "Camera", NotificationManager.IMPORTANCE_LOW));
    }

    private Notification buildNotification() {
        return new NotificationCompat.Builder(this, "camera_service_channel")
                .setContentTitle("WearSync Camera")
                .setSmallIcon(R.drawable.ic_notification)
                .setOngoing(true).build();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ==================== 编码器回调 ====================

    private class EncoderCallback extends MediaCodec.Callback {
        @Override
        public void onInputBufferAvailable(@NonNull MediaCodec c, int i) {
        }

        @Override
        public void onOutputBufferAvailable(@NonNull MediaCodec c, int i, @NonNull MediaCodec.BufferInfo info) {
            try {
                ByteBuffer b = c.getOutputBuffer(i);
                if (b != null && info.size > 0) {
                    byte[] d = new byte[info.size];
                    b.get(d);
                    synchronized (this) {
                        if (mDataOutputStream != null) {
                            mDataOutputStream.writeInt(info.size);
                            mDataOutputStream.writeLong(info.presentationTimeUs);
                            mDataOutputStream.writeInt(info.flags);
                            mDataOutputStream.write(d);
                            mDataOutputStream.flush();
                        }
                    }
                }
                c.releaseOutputBuffer(i, false);
            } catch (Exception ignored) {
            }
        }

        @Override
        public void onError(@NonNull MediaCodec c, @NonNull MediaCodec.CodecException e) {
            PhoneLog.e(TAG, "❌ Encoder Error: " + e.getMessage());
        }

        @Override
        public void onOutputFormatChanged(@NonNull MediaCodec c, @NonNull MediaFormat f) {
        }
    }
}
