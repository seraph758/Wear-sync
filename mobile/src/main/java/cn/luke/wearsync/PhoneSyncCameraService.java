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
 * 录像规格选择逻辑：
 * - 完全基于 Camera2 StreamConfigurationMap 的真实能力
 * - 使用 getOutputMinFrameDuration / getHighSpeedVideoFpsRangesFor 获取每个 Size 的实际 FPS
 * - 不使用全局 CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES
 * - 不写死任何分辨率对应的 FPS
 * - 分辨率优先 → FPS 次优先 → HEVC 优先 → H264 兜底
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

    // ==================== 尺寸配置（每次针对当前 Camera ID 重新计算） ====================
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

    private int calculatePreviewRotation() {
        int devRot = (mDeviceOrientation != OrientationEventListener.ORIENTATION_UNKNOWN)
                ? (mDeviceOrientation + 45) / 90 * 90 : 0;
        int rotation;
        if (mCameraFacing == CameraCharacteristics.LENS_FACING_FRONT) {
            rotation = (360 - (mSensorOrientation + devRot) % 360) % 360;
        } else {
            rotation = (mSensorOrientation - devRot + 360) % 360;
        }
        return rotation;
    }

    private int calculateJpegRotation() {
        int devRot = (mDeviceOrientation != OrientationEventListener.ORIENTATION_UNKNOWN)
                ? (mDeviceOrientation + 45) / 90 * 90 : 0;
        int rotation;
        if (mCameraFacing == CameraCharacteristics.LENS_FACING_FRONT) {
            rotation = (mSensorOrientation + devRot) % 360;
            rotation = (360 - rotation) % 360;
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

            // 2. 预览尺寸：适合手表的低分辨率（约320px）
            Size[] previewSizes = map.getOutputSizes(MediaCodec.class);
            if (previewSizes != null && previewSizes.length > 0) {
                mPreviewSize = chooseBestPreviewSize(previewSizes);
            }

            // 3. 录像尺寸和FPS：基于当前 Camera ID 的真实能力动态选择
            chooseVideoSizeAndFps(map);
        }

        PhoneLog.d(TAG, "📊 尺寸选择结果: Camera=" + mCameraId + ", Facing=" + mCameraFacing +
                ", SensorOrient=" + mSensorOrientation +
                ", Photo=" + mPhotoSize + ", Preview=" + mPreviewSize);
    }

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

    // ==================================================================
    // 核心：录像规格动态选择（完全基于 Camera2 真实能力）
    // ==================================================================

    /**
     * 基于当前 Camera ID 的 StreamConfigurationMap，动态选择最优录像规格。
     *
     * 优先级：
     *   1. 分辨率（面积最大优先）
     *   2. 该分辨率下 Camera2 实际支持的最高 FPS
     *   3. HEVC 编码器优先
     *   4. H.264 兜底
     *
     * 不使用任何硬编码的分辨率→FPS映射。
     * 不使用全局 CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES。
     */
    private void chooseVideoSizeAndFps(StreamConfigurationMap map) {
        // 第一步：获取当前 Camera 真正支持的 MediaRecorder 输出尺寸
        Size[] videoSizes = map.getOutputSizes(MediaRecorder.class);
        if (videoSizes == null || videoSizes.length == 0) {
            PhoneLog.e(TAG, "❌ [VideoSelection] Camera=" + mCameraId + " 不支持 MediaRecorder 输出");
            mVideoSize = new Size(1920, 1080);
            mVideoFps = 30;
            mUseHevcForRecording = false;
            return;
        }

        // 第二步：按面积从大到小排序（分辨率优先）
        List<Size> sortedSizes = new ArrayList<>(Arrays.asList(videoSizes));
        Collections.sort(sortedSizes, SIZE_BY_AREA.reversed());

        // 第三步：获取高速录像尺寸集合（用于后续判断）
        Size[] highSpeedSizes = map.getHighSpeedVideoSizes();
        List<Size> hsSizeList = (highSpeedSizes != null)
                ? Arrays.asList(highSpeedSizes) : Collections.emptyList();

        Size selectedSize = null;
        int selectedFps = 0;
        boolean selectedHevc = false;

        PhoneLog.d(TAG, "═══════════════════════════════════════════════════");
        PhoneLog.d(TAG, "[VideoCapability] 开始评估 Camera=" + mCameraId + " 的录像能力");
        PhoneLog.d(TAG, "═══════════════════════════════════════════════════");

        // 第四步：遍历每个候选尺寸，查询该尺寸的真实 FPS 和编码器支持
        for (Size size : sortedSizes) {
            // 获取该 Size 下 Camera2 实际支持的所有 FPS
            List<Integer> supportedFpsList = getSupportedFpsForSize(map, size, hsSizeList);

            if (supportedFpsList.isEmpty()) {
                PhoneLog.d(TAG, "[VideoCapability] Camera=" + mCameraId +
                        " Size=" + size.getWidth() + "x" + size.getHeight() +
                        " CameraFPS=UNKNOWN → 跳过（无法确定帧率）");
                continue;
            }

            // 取该分辨率下最高的合法 FPS
            int bestFps = Collections.max(supportedFpsList);

            // 检查编码器能力
            boolean hevcOk = checkEncoderSupport(MediaFormat.MIMETYPE_VIDEO_HEVC, size, bestFps);
            boolean h264Ok = checkEncoderSupport(MediaFormat.MIMETYPE_VIDEO_AVC, size, bestFps);

            PhoneLog.d(TAG, "[VideoCapability] Camera=" + mCameraId +
                    " Size=" + size.getWidth() + "x" + size.getHeight() +
                    " CameraFPS=" + bestFps +
                    " HEVC=" + hevcOk +
                    " H264=" + h264Ok);

            // 只要有任一编码器支持，该组合就是合法的
            if (hevcOk || h264Ok) {
                selectedSize = size;
                selectedFps = bestFps;
                selectedHevc = hevcOk;
                break; // 分辨率优先，找到第一个合法组合即停止
            } else {
                PhoneLog.d(TAG, "  ↳ 无可用编码器支持 " + size + "@" + bestFps + "，降级到下一分辨率");
            }
        }

        // 第五步：应用最终结果
        if (selectedSize != null) {
            mVideoSize = selectedSize;
            mVideoFps = selectedFps;
            mUseHevcForRecording = selectedHevc;
            String reason = "Highest supported resolution with valid FPS and encoder";
            PhoneLog.d(TAG, "═══════════════════════════════════════════════════");
            PhoneLog.d(TAG, "[VideoSelection]");
            PhoneLog.d(TAG, "  Camera=" + mCameraId);
            PhoneLog.d(TAG, "  Resolution=" + mVideoSize.getWidth() + "x" + mVideoSize.getHeight());
            PhoneLog.d(TAG, "  FPS=" + mVideoFps);
            PhoneLog.d(TAG, "  Codec=" + (mUseHevcForRecording ? "HEVC" : "H264"));
            PhoneLog.d(TAG, "  Reason=" + reason);
            PhoneLog.d(TAG, "═══════════════════════════════════════════════════");
        } else {
            // 极端情况：所有组合都不被编码器支持
            PhoneLog.e(TAG, "⚠️ [VideoSelection] Camera=" + mCameraId +
                    " 无任何合法录像组合，降级为 1920x1080@30 H264");
            mVideoSize = new Size(1920, 1080);
            mVideoFps = 30;
            mUseHevcForRecording = false;
        }
    }

    /**
     * 获取指定 Size 下 Camera2 实际支持的所有合法 FPS 值。
     *
     * - 如果 Size 属于 High Speed Video：使用 getHighSpeedVideoFpsRangesFor(size)
     * - 否则：使用 getOutputMinFrameDuration(MediaRecorder.class, size) 计算最大 FPS
     *
     * 返回降序排列的 FPS 列表。
     */
    private List<Integer> getSupportedFpsForSize(StreamConfigurationMap map, Size size, List<Size> hsSizeList) {
        List<Integer> fpsList = new ArrayList<>();

        // 判断是否属于高速录像配置
        boolean isHighSpeed = false;
        for (Size hs : hsSizeList) {
            if (hs.getWidth() == size.getWidth() && hs.getHeight() == size.getHeight()) {
                isHighSpeed = true;
                break;
            }
        }

        if (isHighSpeed) {
            // 高速录像：使用 getHighSpeedVideoFpsRangesFor 获取该 Size 的 FPS 范围
            Range<Integer>[] fpsRanges = map.getHighSpeedVideoFpsRangesFor(size);
            if (fpsRanges != null) {
                for (Range<Integer> range : fpsRanges) {
                    // 取范围的上限作为候选 FPS
                    int upper = range.getUpper();
                    if (upper > 0 && !fpsList.contains(upper)) {
                        fpsList.add(upper);
                    }
                    // 也加入下限（如果不同于上限）
                    int lower = range.getLower();
                    if (lower > 0 && lower != upper && !fpsList.contains(lower)) {
                        fpsList.add(lower);
                    }
                }
            }
        } else {
            // 普通录像：使用 getOutputMinFrameDuration 计算最大 FPS
            try {
                long minFrameDurationNs = map.getOutputMinFrameDuration(MediaRecorder.class, size);
                if (minFrameDurationNs > 0) {
                    // FPS = 1,000,000,000 / minFrameDuration(ns)
                    int maxFps = (int) (1_000_000_000L / minFrameDurationNs);
                    if (maxFps > 0) {
                        fpsList.add(maxFps);
                    }
                } else {
                    PhoneLog.d(TAG, "  ↳ Size=" + size.getWidth() + "x" + size.getHeight() +
                            " minFrameDuration=0，无法确定FPS");
                }
            } catch (Exception e) {
                PhoneLog.w(TAG, "  ↳ 查询 minFrameDuration 异常: " + e.getMessage());
            }
        }

        // 降序排列
        Collections.sort(fpsList, Collections.reverseOrder());
        return fpsList;
    }

    /**
     * 检查指定编码格式是否支持给定的 Size + FPS 组合。
     *
     * 使用 MediaCodecList → MediaCodecInfo → VideoCapabilities
     * 验证 isSizeSupported 和 getSupportedFrameRatesFor。
     *
     * @param mimeType MediaFormat.MIMETYPE_VIDEO_HEVC 或 MIMETYPE_VIDEO_AVC
     * @param size     真实的 Camera 输出尺寸
     * @param fps      该尺寸下 Camera2 实际支持的帧率
     * @return true 如果至少有一个硬件编码器支持该组合
     */
    private boolean checkEncoderSupport(String mimeType, Size size, int fps) {
        try {
            MediaCodecList codecList = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
            for (MediaCodecInfo info : codecList.getCodecInfos()) {
                if (!info.isEncoder()) continue;

                for (String type : info.getSupportedTypes()) {
                    if (!type.equalsIgnoreCase(mimeType)) continue;

                    try {
                        MediaCodecInfo.CodecCapabilities caps = info.getCapabilitiesForType(type);
                        if (caps == null) continue;

                        MediaCodecInfo.VideoCapabilities videoCaps = caps.getVideoCapabilities();
                        if (videoCaps == null) continue;

                        // 检查分辨率是否支持
                        if (!videoCaps.isSizeSupported(size.getWidth(), size.getHeight())) {
                            continue;
                        }

                        // 检查该分辨率下的帧率范围
                        Range<Integer> fpsRange = videoCaps.getSupportedFrameRatesFor(
                                size.getWidth(), size.getHeight());
                        if (fpsRange != null && fpsRange.contains(fps)) {
                            return true;
                        }
                    } catch (Exception ignored) {
                        // 某个编码器查询异常，继续检查下一个
                    }
                }
            }
        } catch (Exception e) {
            PhoneLog.w(TAG, "⚠️ checkEncoderSupport 异常: " + e.getMessage());
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
                if ((long) s.getWidth() * s.getHeight() < (long) smallest.getWidth() * smallest.getHeight()) {
                    smallest = s;
                }
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
     */
    private void createPreviewSession() {
        if (mCameraDevice == null || mEncoderSurface == null || mPhotoReader == null || !mIsStreaming.get()) {
            PhoneLog.e(TAG, "❌ createPreviewSession: 前置条件不满足");
            return;
        }

        closeCurrentSession();

        try {
            PhoneLog.d(TAG, "🔧 创建预览 CaptureSession...");
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
                                    // 恢复预览
                                    createPreviewSession();
                                }
                            }
                        }

                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                            PhoneLog.e(TAG, "❌ 录像 CaptureSession 配置失败");
                            mSessionConfigured.set(false);
                            mIsRecording.set(false);
                            cleanupFailedRecording();
                            createPreviewSession();
                        }
                    }
            );

            mCameraDevice.createCaptureSession(sessionConfig);
        } catch (Exception e) {
            PhoneLog.e(TAG, "❌ Recording Session creation failed", e);
            mIsRecording.set(false);
            cleanupFailedRecording();
            createPreviewSession();
        }
    }

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

    private void startPreviewRequest() {
        if (mCaptureSession == null || mCameraDevice == null || !mIsStreaming.get()) return;
        try {
            CaptureRequest.Builder b = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            b.addTarget(mEncoderSurface);
            applyZoom(b);
            applyCommonControls(b);
            mCaptureSession.setRepeatingRequest(b.build(), null, mBgHandler);
        } catch (Exception e) {
            PhoneLog.e(TAG, "❌ Preview RepeatingRequest 提交失败", e);
        }
    }

    private void startRecordingRequest() {
        if (mCaptureSession == null || mCameraDevice == null || mVideoRecorder == null || !mIsStreaming.get()) return;
        try {
            CaptureRequest.Builder b = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            b.addTarget(mEncoderSurface);

            Surface recSurface = mVideoRecorder.getSurface();
            if (recSurface != null && recSurface.isValid()) {
                b.addTarget(recSurface);
            }

            applyZoom(b);
            applyCommonControls(b);
            b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, new Range<>(mVideoFps, mVideoFps));

            mCaptureSession.setRepeatingRequest(b.build(), null, mBgHandler);
        } catch (Exception e) {
            PhoneLog.e(TAG, "❌ Recording RepeatingRequest 提交失败", e);
        }
    }

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
     * 开始录像
     *
     * 【关键】：直接使用 chooseVideoSizeAndFps() 已经计算好的
     * mVideoSize / mVideoFps / mUseHevcForRecording，
     * 不在此处重新计算或覆盖。
     */
    private void startVideoRecording() {
        if (mIsRecording.get() || !mIsStreaming.get() || mCameraDevice == null) {
            PhoneLog.w(TAG, "⚠️ 无法开始录像: recording=" + mIsRecording.get() +
                    ", streaming=" + mIsStreaming.get() + ", camera=" + (mCameraDevice != null));
            return;
        }

        PhoneLog.d(TAG, "🎬 开始录像: " + mVideoSize + " @ " + mVideoFps + "fps, Codec=" +
                (mUseHevcForRecording ? "HEVC" : "H264"));

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
                // 【关键】：直接使用 chooseVideoSizeAndFps() 的结果，不重新计算
                mVideoRecorder = new MediaRecorder(this);
                mVideoRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
                mVideoRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
                mVideoRecorder.setOutputFile(pfd.getFileDescriptor());

                // 编码器：直接使用选择结果
                if (mUseHevcForRecording) {
                    mVideoRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.HEVC);
                } else {
                    mVideoRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
                }

                // 分辨率：直接使用选择结果
                mVideoRecorder.setVideoSize(mVideoSize.getWidth(), mVideoSize.getHeight());

                // 帧率：直接使用选择结果
                mVideoRecorder.setVideoFrameRate(mVideoFps);

                // 码率计算（基于分辨率）
                int bitRate = (int) ((long) mVideoSize.getWidth() * mVideoSize.getHeight() * 4L);
                mVideoRecorder.setVideoEncodingBitRate(Math.min(bitRate, 100_000_000));

                mVideoRecorder.prepare();
                mRecorderPrepared.set(true);
                PhoneLog.d(TAG, "✅ MediaRecorder prepare 成功: " +
                        mVideoSize.getWidth() + "x" + mVideoSize.getHeight() +
                        " @" + mVideoFps + "fps " +
                        (mUseHevcForRecording ? "HEVC" : "H264"));

                // 创建录像专用 CaptureSession
                createRecordingSession();
            }
        } catch (Exception e) {
            PhoneLog.e(TAG, "❌ 录像启动失败", e);
            mRecorderPrepared.set(false);
            mRecorderStarted.set(false);
            mIsRecording.set(false);
            cleanupFailedRecording();
            if (uri != null) cleanupEmptyVideo(uri);
            createPreviewSession();
        }
    }

    /**
     * 停止录像 - 对称流程
     */
    private void stopVideoRecording() {
        if (!mIsRecording.get() && !mRecorderStarted.get()) {
            PhoneLog.w(TAG, "⚠️ stopVideoRecording: 当前未在录像");
            return;
        }

        PhoneLog.d(TAG, "⏹ 停止录像...");

        try {
            // 1. 关闭录像 Session
            closeCurrentSession();

            // 2. 停止 MediaRecorder
            if (mVideoRecorder != null) {
                if (mRecorderStarted.get()) {
                    try {
                        mVideoRecorder.stop();
                        PhoneLog.d(TAG, "✅ MediaRecorder stop 成功");
                    } catch (Exception e) {
                        PhoneLog.e(TAG, "❌ MediaRecorder stop 异常", e);
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

            // 4. 恢复预览
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
            if (mIsStreaming.get()) {
                createPreviewSession();
            }
        }
    }

    private void cleanupFailedRecording() {
        PhoneLog.w(TAG, "🧹 清理失败的录像...");
        if (mVideoRecorder != null) {
            try {
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
            PhoneLog.e(TAG, "❌ captureHighResPhoto: 相机会话不可用");
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
            PhoneLog.d(TAG, "📸 Photo Capture Triggered, rotation=" + rotation);
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
                PhoneLog.d(TAG, "✅ Photo saved: " + fileName);
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
     * 切换相机 - 严格串行重建
     * 确保旧资源完全释放后再初始化新相机
     */
    private void switchCamera(String id) {
        if (id == null || id.equals(mCameraId)) return;

        PhoneLog.d(TAG, "🔄 切换相机: " + mCameraId + " → " + id);

        // 1. 如果正在录像，先完整停止
        if (mIsRecording.get()) {
            PhoneLog.d(TAG, "⏹ 切换前：停止录像...");
            stopVideoRecording();
        }

        // 2. 停止当前流并彻底释放所有资源（含 Channel）
        PhoneLog.d(TAG, "🧹 切换前：释放旧资源...");
        stopStreamingAndRelease();

        // 3. 更新 Camera ID
        mCameraId = id;

        // 4. 重新初始化（内部会重新调用 chooseVideoSizeAndFps 针对新 Camera 计算）
        PhoneLog.d(TAG, "🚀 切换后：重新初始化...");
        initCameraAndStartStreaming();
    }

    // ==================== Zoom ====================

    private void setZoom(float z) {
        mCurrentZoom = Math.max(1.0f, Math.min(z, mMaxZoom));
        PhoneLog.d(TAG, "🔍 Zoom=" + mCurrentZoom + " (max=" + mMaxZoom + ")");
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
            }
        } catch (Exception e) {
            PhoneLog.e(TAG, "❌ 对焦失败", e);
        }
    }

    // ==================== Camera 列表 ====================

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

                    Size[] jpegSizes = map.getOutputSizes(ImageFormat.JPEG);
                    if (jpegSizes == null || jpegSizes.length == 0) continue;

                    Size maxJpeg = Collections.max(Arrays.asList(jpegSizes), SIZE_BY_AREA);
                    if ((long) maxJpeg.getWidth() * maxJpeg.getHeight() < 1_000_000L) continue;

                    Size[] previewSizes = map.getOutputSizes(MediaCodec.class);
                    if (previewSizes == null || previewSizes.length == 0) continue;

                    Integer hwLevel = chars.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);
                    if (hwLevel != null && hwLevel == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY) {
                        continue;
                    }

                    Integer f = chars.get(CameraCharacteristics.LENS_FACING);
                    float[] fl = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
                    float maxZoom = Objects.requireNonNullElse(
                            chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM), 1.0f);

                    Size[] videoSizes = map.getOutputSizes(MediaRecorder.class);
                    boolean hasVideo = (videoSizes != null && videoSizes.length > 0);
                    Size maxVideoSize = new Size(0, 0);
                    if (hasVideo) {
                        maxVideoSize = Collections.max(Arrays.asList(videoSizes), SIZE_BY_AREA);
                    }

                    JSONObject o = new JSONObject();
                    o.put("id", id);
                    o.put("maxZoom", (double) maxZoom);
                    o.put("maxVideoWidth", maxVideoSize.getWidth());
                    o.put("maxVideoHeight", maxVideoSize.getHeight());
                    o.put("maxJpegWidth", maxJpeg.getWidth());
                    o.put("maxJpegHeight", maxJpeg.getHeight());
                    o.put("hasVideo", hasVideo);

                    String name;
                    if (f != null && f == CameraMetadata.LENS_FACING_FRONT) {
                        name = "前置";
                    } else {
                        if (fl != null && fl.length > 0) {
                            float focal = fl[0];
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

                PhoneLog.d(TAG, "✅ 相机列表构建完成，共 " + arr.length() + " 个");
                Wearable.getMessageClient(this).sendMessage(mCachedNodeId, WEAR_MSG_PATH_CAMERA_LIST,
                                arr.toString().getBytes(StandardCharsets.UTF_8))
                        .addOnSuccessListener(r -> PhoneLog.d(TAG, "✅ 相机列表已发送"))
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

        PhoneLog.d(TAG, "📡 正在打开 Wearable Channel: " + WEAR_CHANNEL_PATH);
        mChannelClient.openChannel(mCachedNodeId, WEAR_CHANNEL_PATH)
                .addOnSuccessListener(c -> {
                    mChannelClient.getOutputStream(c).addOnSuccessListener(os -> {
                        PhoneLog.d(TAG, "✅ Channel 输出流已获取");
                        mDataOutputStream = new DataOutputStream(os);

                        try {
                            mEncoder.setCallback(new EncoderCallback(), mBgHandler);
                            mEncoder.start();
                            PhoneLog.d(TAG, "🎬 H.264 编码器已启动");

                            sendCameraListToWear();
                            notifyStreamReadyToWear();
                        } catch (Exception e) {
                            PhoneLog.e(TAG, "❌ 编码器启动失败", e);
                        }
                    }).addOnFailureListener(e -> PhoneLog.e(TAG, "❌ 获取输出流失败", e));
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

    // ==================== Channel 生命周期 ====================

    private synchronized void closeCurrentChannel() {
        if (mDataOutputStream != null) {
            try {
                mDataOutputStream.flush();
                mDataOutputStream.close();
            } catch (Exception ignored) {
            }
            mDataOutputStream = null;
            PhoneLog.d(TAG, "🔌 Channel OutputStream 已关闭");
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

        // 6. 安全关闭 Channel
        closeCurrentChannel();

        // 7. 清理后台线程
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
                            mCaptureSession = session;
                            mSessionConfigured.set(true);
                            startPreviewRequest();
                            openChannelStream();
                        }

                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                            PhoneLog.e(TAG, "❌ 恢复 Session 也失败");
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

    // ==================== 编码器回调（H.264 手表预览专用） ====================

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
