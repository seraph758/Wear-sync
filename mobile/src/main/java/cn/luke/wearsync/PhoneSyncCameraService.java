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
import android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession;
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
import android.os.Build;
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
 * PhoneSyncCameraService - 手机端远程相机服务
 * 录像规格选择：Size(大→小) → FPS(高→低) → HEVC → H264 → 下一FPS → 下一Size
 * High Speed Video：若选中组合属于 HighSpeed，自动使用 SESSION_HIGH_SPEED
 *
 * 核心设计：
 * - 录像期间 mEncoderSurface 继续作为 Camera CaptureRequest 的输出目标，手表 H.264 预览不中断
 * - 录像 Session 不加入 mPhotoReader（JPEG 拍照用）
 * - 普通录像使用 SESSION_REGULAR + TEMPLATE_RECORD，同时输出到 mEncoderSurface 和 MediaRecorder
 * - High Speed 录像严格使用 SESSION_HIGH_SPEED，失败时销毁重建普通 Session
 * - 录像开始/停止不操作 mEncoder 和 mDataOutputStream
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
    /** 标记当前录像是否使用了 High Speed Session */
    private volatile boolean mIsHighSpeedRecording;

    // ==================== Camera 相关 ====================
    private String mCameraId;
    private float mCurrentZoom = 1.0f;
    private float mMaxZoom = 1.0f;
    private Rect mActiveArraySize;
    private int mCameraFacing = CameraCharacteristics.LENS_FACING_BACK;
    private int mSensorOrientation;
    private int mDeviceOrientation = OrientationEventListener.ORIENTATION_UNKNOWN;

    // ==================== 尺寸配置 ====================
    private Size mPhotoSize = new Size(1920, 1080);
    private Size mPreviewSize = new Size(320, 240);
    private Size mVideoSize = new Size(1920, 1080);
    private int mVideoFps = 30;
    private boolean mUseHevcForRecording;

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
    /** ParcelFileDescriptor for MediaRecorder output — must stay open until MediaRecorder.stop()/release() */
    private ParcelFileDescriptor mVideoPfd;

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

    // ==================== 录像期间 H.264 状态确认日志 ====================
    private final Handler mLogHandler = new Handler(Looper.getMainLooper());
    private final Runnable mLogRunnable = new Runnable() {
        @Override
        public void run() {
            if (mIsRecording.get() && mEncoder != null) {
                PhoneLog.d(TAG, "[VideoPreview] H264 encoder still running");
                mLogHandler.postDelayed(this, 5000); // 每5秒打印一次
            }
        }
    };

    // ==================== 消息监听 ====================
    private final MessageClient.OnMessageReceivedListener mMessageListener = event -> {
        String path = event.getPath();
        switch (path) {
            case "/camera/take_photo": captureHighResPhoto(); break;
            case "/camera/toggle_video": toggleVideoRecording(); break;
            case "/camera/control":
                try {
                    JSONObject json = new JSONObject(new String(event.getData(), StandardCharsets.UTF_8));
                    switch (json.optString("action").toUpperCase(Locale.US)) {
                        case "REQUEST_CAMERA_LIST": sendCameraListToWear(); break;
                        case "SELECT_CAMERA": switchCamera(json.optString("camera_id")); break;
                        case "SET_ZOOM": setZoom((float) json.optDouble("zoom", 1.0)); break;
                        default: break;
                    }
                } catch (Exception ignored) {}
                break;
            default: break;
        }
    };

    // ==================== Service 生命周期 ====================
    @Override
    public void onCreate() {
        super.onCreate();
        PhoneLog.d(TAG, "🟢 onCreate");
        createNotificationChannel();
        try {
            startForeground(101, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA);
            sendCameraFgsReady();
        } catch (Exception e) {
            PhoneLog.e(TAG, "❌ Camera FGS 启动失败", e);
        }
        mChannelClient = Wearable.getChannelClient(this);
        Wearable.getMessageClient(this).addListener(mMessageListener);
    }

    private void sendCameraFgsReady() {
        Intent intent = new Intent(ACTION_CAMERA_FGS_READY);
        intent.setPackage(getPackageName());
        sendBroadcast(intent);
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
        resetWatchdog();
        String action = intent.getAction();
        if (ACTION_START_CAMERA.equals(action)) {
            mCachedNodeId = intent.getStringExtra("remote_node_id");
            if (mCachedNodeId == null) mCachedNodeId = WearSyncState.getNodeId(this);
            if (mCachedNodeId == null) { stopSelf(); return START_NOT_STICKY; }
            if (!mIsStreaming.get()) initCameraAndStartStreaming();
            else sendCameraListToWear();
        } else if (ACTION_STOP_CAMERA.equals(action)) {
            stopStreamingAndRelease();
            stopSelf();
        } else if (ACTION_TAKE_PHOTO.equals(action)) {
            captureHighResPhoto();
        } else if (ACTION_SWITCH_CAMERA.equals(action)) {
            switchCamera(intent.getStringExtra("camera_id"));
        } else if (ACTION_TOGGLE_VIDEO.equals(action)) {
            toggleVideoRecording();
        } else if (ACTION_SET_ZOOM.equals(action)) {
            setZoom(intent.getFloatExtra("zoom", 1.0f));
        } else if (ACTION_FOCUS_CAMERA.equals(action)) {
            manualFocus(intent.getDoubleExtra("x", 0.5), intent.getDoubleExtra("y", 0.5));
        } else if (ACTION_REQUEST_CAMERA_LIST.equals(action)) {
            sendCameraListToWear();
        }
        return START_NOT_STICKY;
    }

    // ==================== 初始化与启动 ====================
    private void initCameraAndStartStreaming() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return;
        mIsStreaming.set(true);
        startBackgroundThread();
        startOrientationListener();
        try {
            chooseOptimalSizes();
            MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, mPreviewSize.getWidth(), mPreviewSize.getHeight());
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            format.setInteger(MediaFormat.KEY_BIT_RATE, 450_000);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, 25);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
            format.setInteger(MediaFormat.KEY_ROTATION, calculatePreviewRotation());
            mEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            mEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            mEncoderSurface = mEncoder.createInputSurface();
            mPhotoReader = ImageReader.newInstance(mPhotoSize.getWidth(), mPhotoSize.getHeight(), ImageFormat.JPEG, 2);
            mPhotoReader.setOnImageAvailableListener(reader -> {
                Image img = reader.acquireLatestImage();
                if (img != null) { savePhoto(img); img.close(); }
            }, mBgHandler);
            startCameraHardware();
        } catch (Exception e) {
            PhoneLog.e(TAG, "❌ Streaming Init Failed", e);
            stopStreamingAndRelease();
            stopSelf();
        }
    }

    private int calculatePreviewRotation() {
        int devRot = (mDeviceOrientation != OrientationEventListener.ORIENTATION_UNKNOWN) ? (mDeviceOrientation + 45) / 90 * 90 : 0;
        if (mCameraFacing == CameraCharacteristics.LENS_FACING_FRONT) {
            return (360 - (mSensorOrientation + devRot) % 360) % 360;
        }
        return (mSensorOrientation - devRot + 360) % 360;
    }

    private int calculateJpegRotation() {
        int devRot = (mDeviceOrientation != OrientationEventListener.ORIENTATION_UNKNOWN) ? (mDeviceOrientation + 45) / 90 * 90 : 0;
        if (mCameraFacing == CameraCharacteristics.LENS_FACING_FRONT) {
            return (360 - (mSensorOrientation + devRot) % 360) % 360;
        }
        return (mSensorOrientation - devRot + 360) % 360;
    }

    // ==================== 尺寸和能力选择 ====================
    private void chooseOptimalSizes() throws CameraAccessException {
        CameraManager mgr = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        String[] idList = mgr.getCameraIdList();
        if (idList.length == 0) throw new CameraAccessException(CameraAccessException.CAMERA_DISCONNECTED);
        if (mCameraId == null) mCameraId = findBestBackCamera(mgr, idList);
        if (mCameraId == null) mCameraId = idList[0];
        CameraCharacteristics chars = mgr.getCameraCharacteristics(mCameraId);
        mCameraFacing = Objects.requireNonNullElse(chars.get(CameraCharacteristics.LENS_FACING), CameraMetadata.LENS_FACING_BACK);
        mMaxZoom = Objects.requireNonNullElse(chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM), 1.0f);
        mActiveArraySize = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
        mSensorOrientation = Objects.requireNonNullElse(chars.get(CameraCharacteristics.SENSOR_ORIENTATION), 0);
        StreamConfigurationMap map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (map != null) {
            Size[] photoSizes = map.getOutputSizes(ImageFormat.JPEG);
            if (photoSizes != null && photoSizes.length > 0) {
                mPhotoSize = Collections.max(Arrays.asList(photoSizes), SIZE_BY_AREA);
            }
            Size[] previewSizes = map.getOutputSizes(MediaCodec.class);
            if (previewSizes != null && previewSizes.length > 0) {
                mPreviewSize = chooseBestPreviewSize(previewSizes);
            }
            chooseVideoSizeAndFps(map, chars);
        }
    }

    private String findBestBackCamera(CameraManager mgr, String[] idList) {
        String bestId = null;
        long bestArea = 0;
        try {
            for (String id : idList) {
                CameraCharacteristics c = mgr.getCameraCharacteristics(id);
                Integer f = c.get(CameraCharacteristics.LENS_FACING);
                if (f == null || f != CameraMetadata.LENS_FACING_BACK) continue;
                StreamConfigurationMap m = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                if (m == null) continue;
                Size[] js = m.getOutputSizes(ImageFormat.JPEG);
                if (js == null || js.length == 0) continue;
                Size max = Collections.max(Arrays.asList(js), SIZE_BY_AREA);
                long a = (long) max.getWidth() * max.getHeight();
                if (a > bestArea) { bestArea = a; bestId = id; }
            }
        } catch (Exception ignored) {}
        return bestId;
    }

    // ==================================================================
    // 【核心修改1】录像规格选择：Size→FPS→HEVC→H264→下一FPS→下一Size
    // ==================================================================
    /**
     * 录像规格选择：Resolution(大→小) → FPS(高→低) → HEVC → H264
     * 普通录像 FPS 来源：CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES
     * High Speed FPS 来源：StreamConfigurationMap.getHighSpeedVideoFpsRangesFor(size)
     * 最终验证：MediaRecorder.prepare()
     */
    private void chooseVideoSizeAndFps(StreamConfigurationMap map, CameraCharacteristics chars) {
        Size[] videoSizes = map.getOutputSizes(MediaRecorder.class);
        if (videoSizes == null || videoSizes.length == 0) {
            PhoneLog.e(TAG, "❌ [VideoSelection] Camera=" + mCameraId + " 不支持 MediaRecorder");
            mVideoSize = new Size(1920, 1080);
            mVideoFps = 30;
            mUseHevcForRecording = false;
            mIsHighSpeedRecording = false;
            return;
        }
        List<Size> sortedSizes = new ArrayList<>(Arrays.asList(videoSizes));
        sortedSizes.sort(SIZE_BY_AREA.reversed());

        // 普通录像 FPS：来自 CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES
        Range<Integer>[] aeFpsRanges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
        List<Integer> aeFpsValues = new ArrayList<>();
        if (aeFpsRanges != null) {
            for (Range<Integer> r : aeFpsRanges) {
                Integer upper = r.getUpper();
                Integer lower = r.getLower();
                if (upper != null && upper > 0) aeFpsValues.add(upper);
                if (lower != null && lower > 0) aeFpsValues.add(lower);
            }
        }
        // 补充常用 FPS 值
        for (int fps : new int[]{240, 120, 60, 30, 24}) {
            if (!aeFpsValues.contains(fps)) aeFpsValues.add(fps);
        }
        aeFpsValues.sort(Collections.reverseOrder());

        // High Speed 能力
        Size[] hsSizes = map.getHighSpeedVideoSizes();
        List<Size> hsSizeList = (hsSizes != null) ? Arrays.asList(hsSizes) : Collections.emptyList();
        Integer[] capabilities = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
        boolean hasHsCapability = capabilities != null
                && Arrays.asList(capabilities).contains(
                        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO);

        PhoneLog.d(TAG, "═══════════════════════════════════════");
        PhoneLog.d(TAG, "[VideoCapability] Camera=" + mCameraId + " 开始评估");
        PhoneLog.d(TAG, "[VideoCapability] AE FPS Ranges=" + Arrays.toString(aeFpsRanges));
        PhoneLog.d(TAG, "[VideoCapability] Has HS Capability=" + hasHsCapability);
        PhoneLog.d(TAG, "[VideoCapability] HS Sizes=" + hsSizeList);
        PhoneLog.d(TAG, "═══════════════════════════════════════");

        Size selectedSize = null;
        int selectedFps = 0;
        boolean selectedHevc = false;
        boolean selectedHighSpeed = false;

        for (Size size : sortedSizes) {
            // 获取当前 Size 的 FPS 列表
            List<Integer> fpsList = getSupportedFpsForSize(map, size, hsSizeList, aeFpsRanges, hasHsCapability);
            if (fpsList.isEmpty()) {
                PhoneLog.d(TAG, "[VideoCapability] Size=" + size + " FPS=UNKNOWN → 跳过");
                continue;
            }
            boolean sizeResolved = false;
            for (int fps : fpsList) {
                boolean hevcOk = checkEncoderSupport(MediaFormat.MIMETYPE_VIDEO_HEVC, size, fps);
                if (hevcOk) {
                    selectedSize = size;
                    selectedFps = fps;
                    selectedHevc = true;
                    selectedHighSpeed = shouldUseHighSpeedSession(map, size, fps, hsSizeList, hasHsCapability);
                    sizeResolved = true;
                    PhoneLog.d(TAG, "[VideoCapability] Size=" + size + " FPS=" + fps
                            + " HEVC=true HighSpeed=" + selectedHighSpeed + " → ✅ SELECTED");
                    break;
                }
                boolean h264Ok = checkEncoderSupport(MediaFormat.MIMETYPE_VIDEO_AVC, size, fps);
                if (h264Ok) {
                    selectedSize = size;
                    selectedFps = fps;
                    selectedHevc = false;
                    selectedHighSpeed = shouldUseHighSpeedSession(map, size, fps, hsSizeList, hasHsCapability);
                    sizeResolved = true;
                    PhoneLog.d(TAG, "[VideoCapability] Size=" + size + " FPS=" + fps
                            + " H264=true HighSpeed=" + selectedHighSpeed + " → ✅ SELECTED");
                    break;
                }
                PhoneLog.d(TAG, "[VideoCapability] Size=" + size + " FPS=" + fps
                        + " HEVC=false H264=false → 尝试下一FPS");
            }
            if (sizeResolved) break;
            PhoneLog.d(TAG, "[VideoCapability] Size=" + size + " 所有FPS均无可用编码器 → 降级");
        }

        if (selectedSize != null) {
            mVideoSize = selectedSize;
            mVideoFps = selectedFps;
            mUseHevcForRecording = selectedHevc;
            mIsHighSpeedRecording = selectedHighSpeed;
            PhoneLog.d(TAG, "═══════════════════════════════════════");
            PhoneLog.d(TAG, "[VideoSelection] Camera=" + mCameraId
                    + " Resolution=" + mVideoSize + " FPS=" + mVideoFps
                    + " Codec=" + (mUseHevcForRecording ? "HEVC" : "H264")
                    + " Session=" + (mIsHighSpeedRecording ? "HIGH_SPEED" : "REGULAR"));
            PhoneLog.d(TAG, "═══════════════════════════════════════");
        } else {
            PhoneLog.e(TAG, "⚠️ [VideoSelection] 无合法组合，降级 1920x1080@30 H264");
            mVideoSize = new Size(1920, 1080);
            mVideoFps = 30;
            mUseHevcForRecording = false;
            mIsHighSpeedRecording = false;
        }
    }

    private boolean isHighSpeedSize(Size size, List<Size> hsSizeList) {
        for (Size hs : hsSizeList) {
            if (hs.getWidth() == size.getWidth() && hs.getHeight() == size.getHeight()) return true;
        }
        return false;
    }

    /**
     * 判断是否应该使用 High Speed Session。
     * 【修复】不能仅因为 Size 出现在 getHighSpeedVideoSizes() 中就使用 High Speed Session。
     * 4K60 / 1080p60 等应该优先使用普通 SESSION_REGULAR + CONTROL_AE_TARGET_FPS_RANGE。
     * 只有真正的高帧率（>=120fps）才使用 SESSION_HIGH_SPEED。
     */
    /**
     * 判断是否应该使用 High Speed Session。
     * 必须同时满足：
     * 1. 设备具有 REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO
     * 2. 当前 size 出现在 StreamConfigurationMap.getHighSpeedVideoSizes()
     * 3. 当前 FPS 是该 size 对应的 getHighSpeedVideoFpsRangesFor(size) 中合法的 FPS Range
     * 4. 当前录像确实需要 High Speed Session（如 FPS >= 120）
     */
    private boolean shouldUseHighSpeedSession(StreamConfigurationMap map, Size size, int fps,
                                               List<Size> hsSizeList, boolean hasHsCapability) {
        if (!hasHsCapability) {
            PhoneLog.d(TAG, "[HS] Device does not support CONSTRAINED_HIGH_SPEED_VIDEO → Regular");
            return false;
        }
        if (!isHighSpeedSize(size, hsSizeList)) {
            PhoneLog.d(TAG, "[HS] Size=" + size + " not in HS sizes → Regular");
            return false;
        }
        // 检查 FPS 是否在该 size 的 High Speed FPS Range 中
        boolean fpsInHsRange = false;
        Range<Integer>[] hsRanges = map.getHighSpeedVideoFpsRangesFor(size);
        if (hsRanges != null) {
            for (Range<Integer> r : hsRanges) {
                int lower = r.getLower();
                int upper = r.getUpper();
                if (fps >= lower && fps <= upper) {
                    fpsInHsRange = true;
                    break;
                }
            }
        }
        if (!fpsInHsRange) {
            PhoneLog.d(TAG, "[HS] Size=" + size + " FPS=" + fps + " not in HS FPS range → Regular");
            return false;
        }
        if (fps < 120) {
            PhoneLog.d(TAG, "[HS] Size=" + size + " FPS=" + fps + " < 120 → Regular");
            return false;
        }
        PhoneLog.d(TAG, "[HS] Size=" + size + " FPS=" + fps + " → HIGH_SPEED Session");
        return true;
    }

    /**
     * 获取指定 Size 下 Camera2 实际支持的所有合法 FPS（降序）。
     * High Speed → getHighSpeedVideoFpsRangesFor(size)
     * 普通       → CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES
     */
    /**
     * 获取指定 Size 下 Camera2 实际支持的所有合法 FPS（降序）。
     * 普通录像：来自 CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES
     * High Speed：来自 StreamConfigurationMap.getHighSpeedVideoFpsRangesFor(size)
     */
    private List<Integer> getSupportedFpsForSize(StreamConfigurationMap map, Size size,
                                                  List<Size> hsSizeList,
                                                  Range<Integer>[] aeFpsRanges,
                                                  boolean isHsSize) {
        List<Integer> fpsList = new ArrayList<>();

        if (isHsSize) {
            // High Speed：使用 getHighSpeedVideoFpsRangesFor
            Range<Integer>[] hsRanges = map.getHighSpeedVideoFpsRangesFor(size);
            if (hsRanges != null) {
                for (Range<Integer> r : hsRanges) {
                    int upper = r.getUpper();
                    if (upper > 0 && !fpsList.contains(upper)) fpsList.add(upper);
                    int lower = r.getLower();
                    if (lower > 0 && lower != upper && !fpsList.contains(lower)) fpsList.add(lower);
                }
            }
        }
        // 普通录像：使用 CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES
        if (aeFpsRanges != null) {
            for (Range<Integer> r : aeFpsRanges) {
                int upper = r.getUpper();
                int lower = r.getLower();
                if (upper > 0 && !fpsList.contains(upper)) fpsList.add(upper);
                if (lower > 0 && lower != upper && !fpsList.contains(lower)) fpsList.add(lower);
            }
        }

        fpsList.sort(Collections.reverseOrder());
        return fpsList;
    }

    /**
     * 检查编码器是否支持给定 Size + FPS。
     * 【修复】VideoCapabilities.getSupportedFrameRatesFor 返回 Range<Double>，
     * 需要用 double 比较而非 Range<Integer>。
     */
    /**
     * 检查编码器是否支持给定 Size + FPS。
     * 先使用 MediaCodecList 做预检查，再使用 MediaRecorder.prepare() 做最终验证。
     * 【修复】VideoCapabilities.getSupportedFrameRatesFor 返回 Range<Double>，需要用 double 比较。
     */
    private boolean checkEncoderSupport(String mimeType, Size size, int fps) {
        // 1. MediaCodecList 预检查
        boolean codecOk = false;
        try {
            MediaCodecList codecList = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
            for (MediaCodecInfo info : codecList.getCodecInfos()) {
                if (!info.isEncoder()) continue;
                for (String type : info.getSupportedTypes()) {
                    if (!type.equalsIgnoreCase(mimeType)) continue;
                    try {
                        MediaCodecInfo.VideoCapabilities vc = info.getCapabilitiesForType(type).getVideoCapabilities();
                        if (vc == null) continue;
                        if (!vc.isSizeSupported(size.getWidth(), size.getHeight())) continue;
                        Range<Double> fpsRange = vc.getSupportedFrameRatesFor(size.getWidth(), size.getHeight());
                        if (fpsRange != null && fpsRange.contains((double) fps)) {
                            codecOk = true;
                            break;
                        }
                    } catch (Exception ignored) {}
                }
                if (codecOk) break;
            }
        } catch (Exception e) {
            PhoneLog.w(TAG, "checkEncoderSupport MediaCodecList 异常: " + e.getMessage());
            return false;
        }
        if (!codecOk) return false;

        // 2. MediaRecorder.prepare() 最终验证
        return verifyRecordingCombo(size, fps, mimeType.equalsIgnoreCase(MediaFormat.MIMETYPE_VIDEO_HEVC));
    }

    /**
     * 实际验证 MediaRecorder 是否能够接受给定的 Size + FPS + Encoder 组合。
     */
    private boolean verifyRecordingCombo(Size size, int fps, boolean hevc) {
        MediaRecorder mr = null;
        File tempFile = null;
        try {
            tempFile = File.createTempFile("video_verify_", ".mp4");
            mr = new MediaRecorder(this);
            mr.setVideoSource(MediaRecorder.VideoSource.SURFACE);
            mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            mr.setOutputFile(tempFile.getAbsolutePath());
            mr.setVideoEncoder(hevc ? MediaRecorder.VideoEncoder.HEVC : MediaRecorder.VideoEncoder.H264);
            mr.setVideoSize(size.getWidth(), size.getHeight());
            mr.setVideoFrameRate(fps);
            mr.prepare();
            return true;
        } catch (Exception e) {
            PhoneLog.d(TAG, "[VideoVerify] " + size + "@" + fps + " "
                    + (hevc ? "HEVC" : "H264") + " prepare failed: " + e.getMessage());
            return false;
        } finally {
            if (mr != null) {
                try { mr.release(); } catch (Exception ignored) {}
            }
            if (tempFile != null) {
                try { tempFile.delete(); } catch (Exception ignored) {}
            }
        }
    }

    private Size chooseBestPreviewSize(Size[] sizes) {
        double targetRatio = (double) mPhotoSize.getWidth() / mPhotoSize.getHeight();
        Size best = null;
        double minDiff = Double.MAX_VALUE;
        List<Size> candidates = new ArrayList<>();
        for (Size s : sizes) {
            if (s.getWidth() >= 240 && s.getWidth() <= 450) candidates.add(s);
        }
        if (candidates.isEmpty()) {
            Size sm = sizes[0];
            for (Size s : sizes) {
                if ((long) s.getWidth() * s.getHeight() < (long) sm.getWidth() * sm.getHeight()) sm = s;
            }
            return sm;
        }
        for (Size s : candidates) {
            double d = Math.abs((double) s.getWidth() / s.getHeight() - targetRatio);
            if (d < minDiff) { minDiff = d; best = s; }
        }
        return best != null ? best : candidates.get(0);
    }

    // ==================== Camera 硬件操作 ====================
    @SuppressLint("MissingPermission")
    private void startCameraHardware() {
        CameraManager mgr = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            mgr.openCamera(mCameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(@NonNull CameraDevice camera) {
                    if (!mIsStreaming.get()) { camera.close(); return; }
                    mCameraDevice = camera;
                    mIsCameraOpened.set(true);
                    createPreviewSession();
                }
                @Override
                public void onDisconnected(@NonNull CameraDevice camera) {
                    camera.close(); mCameraDevice = null; mIsCameraOpened.set(false);
                    stopStreamingAndRelease();
                }
                @Override
                public void onError(@NonNull CameraDevice camera, int error) {
                    camera.close(); mCameraDevice = null; mIsCameraOpened.set(false);
                    stopStreamingAndRelease();
                }
            }, mBgHandler);
        } catch (Exception e) {
            PhoneLog.e(TAG, "❌ Hardware Open Failed", e);
        }
    }

    // ==================== Preview Session ====================
    /**
     * 创建预览 CaptureSession。
     * 输出：mEncoderSurface + mPhotoReader（拍照用）
     */
    private void createPreviewSession() {
        if (mCameraDevice == null || mEncoderSurface == null || mPhotoReader == null || !mIsStreaming.get()) return;
        closeCurrentSession();
        try {
            List<OutputConfiguration> outputs = new ArrayList<>();
            if (mEncoderSurface.isValid()) outputs.add(new OutputConfiguration(mEncoderSurface));
            if (mPhotoReader.getSurface().isValid()) outputs.add(new OutputConfiguration(mPhotoReader.getSurface()));
            SessionConfiguration config = new SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR, outputs,
                    command -> mBgHandler.post(command),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession session) {
                            if (!mIsStreaming.get()) { session.close(); return; }
                            mCaptureSession = session;
                            mSessionConfigured.set(true);
                            startPreviewRequest();
                            openChannelStream();
                        }
                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                            mSessionConfigured.set(false);
                            tryRecoverFromConfigFailure();
                        }
                    });
            mCameraDevice.createCaptureSession(config);
        } catch (Exception e) {
            PhoneLog.e(TAG, "❌ Preview Session failed", e);
        }
    }

    // ==================================================================
    // 【核心修改2】录像 Session：与 Preview 并行，不加入 PhotoReader
    // ==================================================================

    /**
     * 创建录像 CaptureSession。
     * 普通录像 → SessionConfiguration(SESSION_REGULAR) + TEMPLATE_RECORD
     *   输出：mEncoderSurface + MediaRecorder Surface（不含 PhotoReader）
     * High Speed → SessionConfiguration(SESSION_HIGH_SPEED)
     *   输出：mEncoderSurface + MediaRecorder Surface（不含 PhotoReader）
     *
     * 录像期间 mEncoder 和 mDataOutputStream 不得停止/关闭。
     */
    private void createRecordingSession() {
        if (mCameraDevice == null || mEncoderSurface == null || mVideoRecorder == null || !mIsStreaming.get()) return;
        closeCurrentSession();
        try {
            List<OutputConfiguration> outputs = new ArrayList<>();
            // 1. mEncoderSurface —— 手表 H.264 Preview（录像期间必须继续工作）
            if (mEncoderSurface.isValid()) outputs.add(new OutputConfiguration(mEncoderSurface));
            // 2. MediaRecorder Surface —— 手机录像
            Surface recSurface = mVideoRecorder.getSurface();
            if (recSurface != null && recSurface.isValid()) {
                outputs.add(new OutputConfiguration(recSurface));
            } else {
                PhoneLog.e(TAG, "❌ MediaRecorder Surface 无效");
                return;
            }
            // 3. 不加入 mPhotoReader（JPEG 拍照用，录像 Session 不需要）

            // 统一使用 SessionConfiguration，通过 sessionType 区分普通/高速
            int sessionType = mIsHighSpeedRecording
                    ? SessionConfiguration.SESSION_HIGH_SPEED
                    : SessionConfiguration.SESSION_REGULAR;

            PhoneLog.d(TAG, "🔧 [VideoSession] START");
            PhoneLog.d(TAG, "Resolution=" + mVideoSize + " FPS=" + mVideoFps);
            PhoneLog.d(TAG, "Codec=" + (mUseHevcForRecording ? "HEVC" : "H264"));
            PhoneLog.d(TAG, "SessionType=" + (sessionType == SessionConfiguration.SESSION_HIGH_SPEED ? "HIGH_SPEED" : "REGULAR"));
            PhoneLog.d(TAG, "PreviewSurface=true RecorderSurface=true PhotoReader=false");
            if (sessionType == SessionConfiguration.SESSION_HIGH_SPEED) {
                PhoneLog.d(TAG, "[HighSpeed] Size=" + mVideoSize + " FPS=" + mVideoFps + " HighSpeedSession=true");
            }

            SessionConfiguration config = new SessionConfiguration(
                    sessionType, outputs,
                    command -> mBgHandler.post(command),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession session) {
                            if (!mIsStreaming.get()) { session.close(); return; }
                            mCaptureSession = session;
                            mSessionConfigured.set(true);
                            if (mIsHighSpeedRecording) {
                                startHighSpeedRecordingRequest(session);
                            } else {
                                startRecordingRequest();
                            }
                            if (mRecorderPrepared.get() && !mRecorderStarted.get()) {
                                try {
                                    mVideoRecorder.start();
                                    mRecorderStarted.set(true);
                                    mIsRecording.set(true);
                                    PhoneLog.d(TAG, "🎬 [VideoRecording] MediaRecorder.start() success");
                                    notifyWearVideoStatus(true);
                                    // 启动 H.264 状态确认日志（每5秒打印一次）
                                    mLogHandler.removeCallbacks(mLogRunnable);
                                    mLogHandler.post(mLogRunnable);
                                } catch (Exception e) {
                                    PhoneLog.e(TAG, "❌ MediaRecorder 启动失败", e);
                                    mIsRecording.set(false);
                                    mRecorderStarted.set(false);
                                    cleanupFailedRecording();
                                    createPreviewSession();
                                }
                            }
                        }
                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                            PhoneLog.e(TAG, "❌ 录像 Session 配置失败");
                            mSessionConfigured.set(false);
                            mIsRecording.set(false);
                            cleanupFailedRecording();
                            createPreviewSession();
                        }
                    });
            mCameraDevice.createCaptureSession(config);
        } catch (Exception e) {
            PhoneLog.e(TAG, "❌ Recording Session creation failed", e);
            mIsRecording.set(false);
            cleanupFailedRecording();
            createPreviewSession();
        }
    }

    /**
     * High Speed 专用 request 提交。
     * 必须使用 CameraConstrainedHighSpeedCaptureSession.createHighSpeedRequestList() + setRepeatingBurst()。
     * 失败时：销毁当前 High Speed Session，重建普通 SESSION_REGULAR。
     */
    /**
     * High Speed 专用 request 提交。
     * 必须使用 CameraConstrainedHighSpeedCaptureSession.createHighSpeedRequestList() + setRepeatingBurst()。
     * 失败时：销毁当前 High Speed Session，重新执行完整录像规格选择（Regular），而非简单设置 mIsHighSpeedRecording=false。
     */
    private void startHighSpeedRecordingRequest(CameraCaptureSession session) {
        try {
            CameraConstrainedHighSpeedCaptureSession hsSession =
                    (CameraConstrainedHighSpeedCaptureSession) session;

            CaptureRequest.Builder b = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            b.addTarget(mEncoderSurface);
            Surface recSurface = mVideoRecorder.getSurface();
            if (recSurface != null && recSurface.isValid()) b.addTarget(recSurface);
            applyZoom(b);
            applyCommonControls(b);
            b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, new Range<>(mVideoFps, mVideoFps));

            List<CaptureRequest> burst = hsSession.createHighSpeedRequestList(b.build());
            hsSession.setRepeatingBurst(burst, null, mBgHandler);

            PhoneLog.d(TAG, "✅ [HighSpeed] RepeatingBurst 已提交, requestListSize=" + burst.size());
        } catch (Exception e) {
            PhoneLog.e(TAG, "❌ HighSpeed Request 提交失败，销毁 HighSpeed Session 并重新选择 Regular 配置", e);
            // 1. 关闭失败的 High Speed Session
            closeCurrentSession();
            // 2. 清理录像资源
            mIsRecording.set(false);
            mRecorderPrepared.set(false);
            mRecorderStarted.set(false);
            cleanupFailedRecording();
            // 3. 重新执行完整录像规格选择（Regular）
            PhoneLog.d(TAG, "🔄 HighSpeed 回退：重新选择 Regular 录像配置");
            reselectRegularRecording();
        }
    }

    /**
     * High Speed 失败后，重新选择合法的 Regular 录像配置。
     * 重新从 MediaRecorder.getSupportedSizes() 开始，查询 Regular FPS，验证 HEVC/H264。
     */
    private void reselectRegularRecording() {
        if (mCameraDevice == null) {
            createPreviewSession();
            return;
        }
        try {
            CameraManager mgr = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            CameraCharacteristics chars = mgr.getCameraCharacteristics(mCameraId);
            StreamConfigurationMap map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map == null) {
                PhoneLog.e(TAG, "❌ [HS Fallback] StreamConfigurationMap is null");
                createPreviewSession();
                return;
            }
            // 重新选择录像规格（Regular）
            Size origSize = mVideoSize;
            int origFps = mVideoFps;
            boolean origHevc = mUseHevcForRecording;
            boolean origHs = mIsHighSpeedRecording;

            // 临时选择 Regular 配置
            chooseVideoSizeAndFpsRegularOnly(map, chars);

            PhoneLog.d(TAG, "🔄 [HS Fallback] 原配置=" + origSize + "@" + origFps + " HS=" + origHs
                    + " → 新配置=" + mVideoSize + "@" + mVideoFps + " HS=" + mIsHighSpeedRecording);

            // 重新创建 MediaRecorder
            Uri fallbackUri = null;
            try {
                String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
                ContentValues values = new ContentValues();
                values.put(MediaStore.Video.Media.DISPLAY_NAME, "VIDfallback_" + ts + ".mp4");
                values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
                values.put(MediaStore.Video.Media.RELATIVE_PATH, "DCIM/WearSync");
                fallbackUri = getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
                if (fallbackUri == null) throw new IllegalStateException("fallback URI is null");

                mVideoPfd = getContentResolver().openFileDescriptor(fallbackUri, "rw");
                if (mVideoPfd == null) throw new IllegalStateException("fallback PFD is null");

                mVideoRecorder = new MediaRecorder(PhoneSyncCameraService.this);
                mVideoRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
                mVideoRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
                mVideoRecorder.setOutputFile(mVideoPfd.getFileDescriptor());
                mVideoRecorder.setVideoEncoder(mUseHevcForRecording ? MediaRecorder.VideoEncoder.HEVC : MediaRecorder.VideoEncoder.H264);
                mVideoRecorder.setVideoSize(mVideoSize.getWidth(), mVideoSize.getHeight());
                mVideoRecorder.setVideoFrameRate(mVideoFps);
                int bitRate = (int) ((long) mVideoSize.getWidth() * mVideoSize.getHeight() * 4L);
                mVideoRecorder.setVideoEncodingBitRate(Math.min(bitRate, 100_000_000));
                mVideoRecorder.prepare();
                mRecorderPrepared.set(true);

                // 创建普通录像 Session
                createRecordingSession();
            } catch (Exception retryEx) {
                PhoneLog.e(TAG, "❌ [HS Fallback] 重新选择 Regular 配置也失败", retryEx);
                if (mVideoPfd != null) {
                    try { mVideoPfd.close(); } catch (Exception ignored) {}
                    mVideoPfd = null;
                }
                if (fallbackUri != null) {
                    try { getContentResolver().delete(fallbackUri, null, null); } catch (Exception ignored) {}
                }
                cleanupFailedRecording();
                mIsRecording.set(false);
                mRecorderPrepared.set(false);
                mRecorderStarted.set(false);
                createPreviewSession();
            }
        } catch (Exception e) {
            PhoneLog.e(TAG, "❌ [HS Fallback] 重新选择异常", e);
            createPreviewSession();
        }
    }

    /**
     * 仅选择 Regular 录像配置（不使用 High Speed Session）。
     * 类似于 chooseVideoSizeAndFps 但强制 mIsHighSpeedRecording=false。
     */
    private void chooseVideoSizeAndFpsRegularOnly(StreamConfigurationMap map, CameraCharacteristics chars) {
        Size[] videoSizes = map.getOutputSizes(MediaRecorder.class);
        if (videoSizes == null || videoSizes.length == 0) {
            mVideoSize = new Size(1920, 1080);
            mVideoFps = 30;
            mUseHevcForRecording = false;
            mIsHighSpeedRecording = false;
            return;
        }
        List<Size> sortedSizes = new ArrayList<>(Arrays.asList(videoSizes));
        sortedSizes.sort(SIZE_BY_AREA.reversed());

        Range<Integer>[] aeFpsRanges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
        List<Integer> aeFpsValues = new ArrayList<>();
        if (aeFpsRanges != null) {
            for (Range<Integer> r : aeFpsRanges) {
                Integer upper = r.getUpper();
                Integer lower = r.getLower();
                if (upper != null && upper > 0) aeFpsValues.add(upper);
                if (lower != null && lower > 0) aeFpsValues.add(lower);
            }
        }
        for (int fps : new int[]{120, 60, 30, 24}) {
            if (!aeFpsValues.contains(fps)) aeFpsValues.add(fps);
        }
        aeFpsValues.sort(Collections.reverseOrder());

        // 不使用 High Speed
        List<Size> emptyHsList = Collections.emptyList();
        boolean hasHsCap = false;

        Size selectedSize = null;
        int selectedFps = 0;
        boolean selectedHevc = false;

        for (Size size : sortedSizes) {
            List<Integer> fpsList = getSupportedFpsForSize(map, size, emptyHsList, aeFpsRanges, false);
            if (fpsList.isEmpty()) continue;
            boolean sizeResolved = false;
            for (int fps : fpsList) {
                boolean hevcOk = checkEncoderSupport(MediaFormat.MIMETYPE_VIDEO_HEVC, size, fps);
                if (hevcOk) {
                    selectedSize = size;
                    selectedFps = fps;
                    selectedHevc = true;
                    sizeResolved = true;
                    break;
                }
                boolean h264Ok = checkEncoderSupport(MediaFormat.MIMETYPE_VIDEO_AVC, size, fps);
                if (h264Ok) {
                    selectedSize = size;
                    selectedFps = fps;
                    selectedHevc = false;
                    sizeResolved = true;
                    break;
                }
            }
            if (sizeResolved) break;
        }

        if (selectedSize != null) {
            mVideoSize = selectedSize;
            mVideoFps = selectedFps;
            mUseHevcForRecording = selectedHevc;
            mIsHighSpeedRecording = false;
        } else {
            mVideoSize = new Size(1920, 1080);
            mVideoFps = 30;
            mUseHevcForRecording = false;
            mIsHighSpeedRecording = false;
        }
    }

    private void closeCurrentSession() {
        if (mCaptureSession != null) {
            try { mCaptureSession.stopRepeating(); } catch (Exception ignored) {}
            try { mCaptureSession.abortCaptures(); } catch (Exception ignored) {}
            try { mCaptureSession.close(); } catch (Exception ignored) {}
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
            PhoneLog.e(TAG, "❌ Preview Request 失败", e);
        }
    }

    /**
     * 普通录像 Request。
     * 使用 SESSION_REGULAR + TEMPLATE_RECORD，同时输出到 mEncoderSurface 和 MediaRecorder。
     */
    /**
     * 普通录像 Request。
     * 使用 SESSION_REGULAR + TEMPLATE_RECORD，同时输出到 mEncoderSurface 和 MediaRecorder。
     * @return true 如果 Request 提交成功
     */
    private boolean startRecordingRequest() {
        if (mCaptureSession == null || mCameraDevice == null || mVideoRecorder == null || !mIsStreaming.get()) return false;
        try {
            CaptureRequest.Builder b = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            b.addTarget(mEncoderSurface);
            Surface recSurface = mVideoRecorder.getSurface();
            if (recSurface != null && recSurface.isValid()) b.addTarget(recSurface);
            applyZoom(b);
            applyCommonControls(b);
            b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, new Range<>(mVideoFps, mVideoFps));
            mCaptureSession.setRepeatingRequest(b.build(), null, mBgHandler);
            PhoneLog.d(TAG, "✅ 普通录像 Request 已提交（mEncoderSurface + MediaRecorder）");
            return true;
        } catch (Exception e) {
            PhoneLog.e(TAG, "❌ Recording Request 失败", e);
            return false;
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
        if (mIsRecording.get()) stopVideoRecording();
        else startVideoRecording();
    }

    /**
     * 开始录像。
     * 不操作 mEncoder / mEncoderSurface / mDataOutputStream，它们由 Preview Session 保持运行。
     */
    /**
     * 开始录像。
     * 不操作 mEncoder / mEncoderSurface / mDataOutputStream，它们由 Preview Session 保持运行。
     */
    private void startVideoRecording() {
        if (mIsRecording.get() || !mIsStreaming.get() || mCameraDevice == null) return;
        PhoneLog.d(TAG, "🎬 开始录像: " + mVideoSize + "@" + mVideoFps + "fps "
                + (mUseHevcForRecording ? "HEVC" : "H264") + " HighSpeed=" + mIsHighSpeedRecording);
        Uri uri = null;
        try {
            String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            ContentValues values = new ContentValues();
            values.put(MediaStore.Video.Media.DISPLAY_NAME, "VID_" + ts + ".mp4");
            values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
            values.put(MediaStore.Video.Media.RELATIVE_PATH, "DCIM/WearSync");
            uri = getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) return;
            mCurrentVideoUri = uri;
            // ParcelFileDescriptor 保存为成员变量，不在 try-with-resources 中关闭
            mVideoPfd = getContentResolver().openFileDescriptor(uri, "rw");
            if (mVideoPfd == null) { cleanupEmptyVideo(uri); return; }
            mVideoRecorder = new MediaRecorder(this);
            mVideoRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
            mVideoRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            mVideoRecorder.setOutputFile(mVideoPfd.getFileDescriptor());
            mVideoRecorder.setVideoEncoder(mUseHevcForRecording ? MediaRecorder.VideoEncoder.HEVC : MediaRecorder.VideoEncoder.H264);
            mVideoRecorder.setVideoSize(mVideoSize.getWidth(), mVideoSize.getHeight());
            mVideoRecorder.setVideoFrameRate(mVideoFps);
            int bitRate = (int) ((long) mVideoSize.getWidth() * mVideoSize.getHeight() * 4L);
            mVideoRecorder.setVideoEncodingBitRate(Math.min(bitRate, 100_000_000));
            mVideoRecorder.prepare();
            mRecorderPrepared.set(true);
            // 创建录像 Session（包含 mEncoderSurface + MediaRecorder Surface，不含 PhotoReader）
            createRecordingSession();
        } catch (Exception e) {
            PhoneLog.e(TAG, "❌ 录像启动失败", e);
            mRecorderPrepared.set(false);
            mRecorderStarted.set(false);
            mIsRecording.set(false);
            if (mVideoPfd != null) {
                try { mVideoPfd.close(); } catch (Exception ignored) {}
                mVideoPfd = null;
            }
            cleanupFailedRecording();
            if (uri != null) cleanupEmptyVideo(uri);
            createPreviewSession();
        }
    }

    /**
     * 停止录像。
     * 1. 停止 MediaRecorder
     * 2. 释放 MediaRecorder
     * 3. 关闭录像 CaptureSession
     * 4. 重新建立普通 Preview Session
     * 5. 继续使用原来的 mEncoder（不重新创建 H.264 Encoder）
     * 6. Channel 不关闭
     */
    private void stopVideoRecording() {
        if (!mIsRecording.get() && !mRecorderStarted.get()) return;
        try {
            closeCurrentSession();
            // 停止 H.264 状态确认日志
            mLogHandler.removeCallbacks(mLogRunnable);
            if (mVideoRecorder != null) {
                if (mRecorderStarted.get()) {
                    try { mVideoRecorder.stop(); } catch (Exception e) {
                        if (mCurrentVideoUri != null) cleanupEmptyVideo(mCurrentVideoUri);
                    }
                }
                mVideoRecorder.release();
                mVideoRecorder = null;
            }
            mIsRecording.set(false);
            mRecorderPrepared.set(false);
            mRecorderStarted.set(false);
            // 【修复】MediaRecorder.stop()/release() 完成后才关闭 ParcelFileDescriptor
            if (mVideoPfd != null) {
                try { mVideoPfd.close(); } catch (Exception ignored) {}
                mVideoPfd = null;
            }
            mCurrentVideoUri = null;
            if (mIsStreaming.get()) createPreviewSession();
            notifyWearVideoStatus(false);
            PhoneLog.d(TAG, "🛑 [VideoRecording] MediaRecorder.stop() success");
            PhoneLog.d(TAG, "🛑 [VideoSession] RESTORE_PREVIEW");
            PhoneLog.d(TAG, "MediaRecorder released");
            PhoneLog.d(TAG, "Preview session restoring");
            PhoneLog.d(TAG, "H264 encoder preserved=true");
            PhoneLog.d(TAG, "Channel preserved=" + (mDataOutputStream != null));
        } catch (Exception e) {
            PhoneLog.e(TAG, "❌ 停止录像异常", e);
            mIsRecording.set(false);
            mRecorderPrepared.set(false);
            mRecorderStarted.set(false);
            // 【修复】异常路径也关闭 ParcelFileDescriptor
            if (mVideoPfd != null) {
                try { mVideoPfd.close(); } catch (Exception ignored) {}
                mVideoPfd = null;
            }
            if (mVideoRecorder != null) { try { mVideoRecorder.release(); } catch (Exception ignored) {} mVideoRecorder = null; }
            if (mIsStreaming.get()) createPreviewSession();
        }
    }

    private void cleanupFailedRecording() {
        if (mVideoRecorder != null) {
            try { if (mRecorderStarted.get()) mVideoRecorder.stop(); } catch (Exception ignored) {}
            try { mVideoRecorder.release(); } catch (Exception ignored) {}
            mVideoRecorder = null;
        }
        // 【修复】清理时关闭 ParcelFileDescriptor
        if (mVideoPfd != null) {
            try { mVideoPfd.close(); } catch (Exception ignored) {}
            mVideoPfd = null;
        }
        mRecorderPrepared.set(false);
        mRecorderStarted.set(false);
        if (mCurrentVideoUri != null) { cleanupEmptyVideo(mCurrentVideoUri); mCurrentVideoUri = null; }
    }

    private void cleanupEmptyVideo(Uri uri) {
        if (uri != null) try { getContentResolver().delete(uri, null, null); } catch (Exception ignored) {}
    }

    private void notifyWearVideoStatus(boolean rec) {
        if (mCachedNodeId == null) return;
        try {
            JSONObject j = new JSONObject();
            j.put("type", "video_status");
            j.put("isRecording", rec);
            Wearable.getMessageClient(this).sendMessage(mCachedNodeId, "/camera/video_status", j.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {}
    }

    // ==================== 拍照 ====================
    private void captureHighResPhoto() {
        if (mCaptureSession == null || !mIsStreaming.get()) return;
        try {
            CaptureRequest.Builder b = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            b.addTarget(mPhotoReader.getSurface());
            applyZoom(b);
            b.set(CaptureRequest.JPEG_ORIENTATION, calculateJpegRotation());
            b.set(CaptureRequest.JPEG_QUALITY, (byte) 100);
            mCaptureSession.capture(b.build(), null, mBgHandler);
        } catch (Exception e) {
            PhoneLog.e(TAG, "❌ 拍照失败", e);
        }
    }

    private void savePhoto(Image image) {
        try {
            ByteBuffer buf = image.getPlanes()[0].getBuffer();
            byte[] data = new byte[buf.remaining()];
            buf.get(data);
            String fn = "IMG_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".jpg";
            ContentValues v = new ContentValues();
            v.put(MediaStore.Images.Media.DISPLAY_NAME, fn);
            v.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            v.put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/WearSync");
            Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
            if (uri != null) {
                try (OutputStream os = getContentResolver().openOutputStream(uri)) { if (os != null) os.write(data); }
                writeLocationExifFromUri(uri);
            }
        } catch (Exception e) {
            PhoneLog.e(TAG, "Save Photo Error", e);
        }
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
                exif.setGpsInfo(best);
                exif.saveAttributes();
            }
        } catch (Exception ignored) {}
    }

    // ==================== Camera 切换 ====================
    private void switchCamera(String id) {
        if (id == null || id.equals(mCameraId)) return;
        if (mIsRecording.get()) stopVideoRecording();
        stopStreamingAndRelease();
        mCameraId = id;
        initCameraAndStartStreaming();
    }

    // ==================== Zoom / Focus ====================
    private void setZoom(float z) {
        mCurrentZoom = Math.max(1.0f, Math.min(z, mMaxZoom));
        if (mIsRecording.get()) {
            if (mIsHighSpeedRecording) {
                // High Speed Session 不能调用普通 setRepeatingRequest
                // 暂时保持当前 Zoom，避免 IllegalStateException
                PhoneLog.w(TAG, "⚠️ High Speed 录像期间 Zoom 调整暂不生效（需重建 High Speed Request List）");
            } else {
                startRecordingRequest();
            }
        } else {
            startPreviewRequest();
        }
    }

    private void manualFocus(double x, double y) {
        if (mCameraDevice == null || mCaptureSession == null) return;
        try {
            CameraCharacteristics chars = ((CameraManager) getSystemService(Context.CAMERA_SERVICE)).getCameraCharacteristics(mCameraDevice.getId());
            Rect sensor = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
            if (sensor == null) return;
            int cx = (int) (x * sensor.width()), cy = (int) (y * sensor.height());
            MeteringRectangle area = new MeteringRectangle(Math.max(0, cx - 100), Math.max(0, cy - 100), Math.min(sensor.width(), 200), Math.min(sensor.height(), 200), MeteringRectangle.METERING_WEIGHT_MAX);
            // 【修复】High Speed 录像期间不能调用普通 setRepeatingRequest
            if (mIsRecording.get() && mIsHighSpeedRecording) {
                PhoneLog.w(TAG, "⚠️ High Speed 录像期间手动对焦暂不支持（需重建 High Speed Request List）");
                return;
            }
            int tpl = mIsRecording.get() ? CameraDevice.TEMPLATE_RECORD : CameraDevice.TEMPLATE_PREVIEW;
            CaptureRequest.Builder b = mCameraDevice.createCaptureRequest(tpl);
            b.addTarget(mEncoderSurface);
            if (mIsRecording.get() && mVideoRecorder != null) {
                Surface rs = mVideoRecorder.getSurface();
                if (rs != null && rs.isValid()) b.addTarget(rs);
            }
            applyZoom(b);
            b.set(CaptureRequest.CONTROL_AF_REGIONS, new MeteringRectangle[]{area});
            b.set(CaptureRequest.CONTROL_AE_REGIONS, new MeteringRectangle[]{area});
            b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO);
            b.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START);
            mCaptureSession.setRepeatingRequest(b.build(), null, mBgHandler);
        } catch (Exception e) {
            PhoneLog.e(TAG, "❌ 对焦失败", e);
        }
    }

    // ==================== Camera 列表 ====================
    private void sendCameraListToWear() {
        if (mCachedNodeId == null) return;
        new Thread(() -> {
            try {
                CameraManager mgr = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
                JSONArray arr = new JSONArray();
                for (String id : mgr.getCameraIdList()) {
                    CameraCharacteristics c = mgr.getCameraCharacteristics(id);
                    StreamConfigurationMap m = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                    if (m == null) continue;
                    Size[] js = m.getOutputSizes(ImageFormat.JPEG);
                    if (js == null || js.length == 0) continue;
                    Size mj = Collections.max(Arrays.asList(js), SIZE_BY_AREA);
                    if ((long) mj.getWidth() * mj.getHeight() < 1_000_000L) continue;
                    Size[] ps = m.getOutputSizes(MediaCodec.class);
                    if (ps == null || ps.length == 0) continue;
                    Integer hl = c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);
                    if (hl != null && hl == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY) continue;
                    Integer f = c.get(CameraCharacteristics.LENS_FACING);
                    float[] fl = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
                    float mz = Objects.requireNonNullElse(c.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM), 1.0f);
                    Size[] vs = m.getOutputSizes(MediaRecorder.class);
                    boolean hv = vs != null && vs.length > 0;
                    Size mv = hv ? Collections.max(Arrays.asList(vs), SIZE_BY_AREA) : new Size(0, 0);
                    JSONObject o = new JSONObject();
                    o.put("id", id);
                    o.put("maxZoom", (double) mz);
                    o.put("maxVideoWidth", mv.getWidth());
                    o.put("maxVideoHeight", mv.getHeight());
                    o.put("maxJpegWidth", mj.getWidth());
                    o.put("maxJpegHeight", mj.getHeight());
                    o.put("hasVideo", hv);
                    String name;
                    if (f != null && f == CameraMetadata.LENS_FACING_FRONT) name = "前置";
                    else if (fl != null && fl.length > 0) {
                        float fc = fl[0];
                        if (fc < 3.5f) name = "超广角";
                        else if (fc > 7.0f) name = "长焦";
                        else name = "主摄";
                    } else name = "主摄";
                    o.put("name", name);
                    o.put("facing", f != null ? f : CameraMetadata.LENS_FACING_BACK);
                    o.put("sensorOrientation", Objects.requireNonNullElse(c.get(CameraCharacteristics.SENSOR_ORIENTATION), 0));
                    arr.put(o);
                }
                Wearable.getMessageClient(this).sendMessage(mCachedNodeId, WEAR_MSG_PATH_CAMERA_LIST, arr.toString().getBytes(StandardCharsets.UTF_8));
            } catch (Exception e) {
                PhoneLog.e(TAG, "❌ 相机列表失败", e);
            }
        }).start();
    }

    // ==================== 数据通道 ====================
    /**
     * 打开 Wear 数据通道。
     * 仅在 Preview Session 配置完成后调用一次。
     * 录像期间不关闭、不重新创建。
     */
    private void openChannelStream() {
        if (mDataOutputStream != null || mCachedNodeId == null) return;
        mChannelClient.openChannel(mCachedNodeId, WEAR_CHANNEL_PATH)
                .addOnSuccessListener(c -> mChannelClient.getOutputStream(c).addOnSuccessListener(os -> {
                    mDataOutputStream = new DataOutputStream(os);
                    try {
                        mEncoder.setCallback(new EncoderCallback(), mBgHandler);
                        mEncoder.start();
                        sendCameraListToWear();
                        notifyStreamReadyToWear();
                    } catch (Exception e) {
                        PhoneLog.e(TAG, "❌ 编码器启动失败", e);
                    }
                }))
                .addOnFailureListener(e -> PhoneLog.e(TAG, "❌ Channel 失败", e));
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

    private synchronized void closeCurrentChannel() {
        if (mDataOutputStream != null) {
            try { mDataOutputStream.flush(); mDataOutputStream.close(); } catch (Exception ignored) {}
            mDataOutputStream = null;
        }
    }

    // ==================== 资源释放 ====================
    /**
     * 释放所有资源。
     * 仅在 Service 销毁 / 看门狗超时 / 切换相机时调用。
     * 录像开始/停止不得调用此方法中的 mEncoder 释放逻辑。
     */
    private synchronized void stopStreamingAndRelease() {
        mIsStreaming.set(false);
        mIsCameraOpened.set(false);
        // 停止录像日志
        mLogHandler.removeCallbacks(mLogRunnable);
        if (mIsRecording.get() || mRecorderStarted.get()) {
            try {
                if (mVideoRecorder != null) {
                    if (mRecorderStarted.get()) mVideoRecorder.stop();
                    mVideoRecorder.release();
                    mVideoRecorder = null;
                }
            } catch (Exception ignored) {}
            mIsRecording.set(false);
            mRecorderPrepared.set(false);
            mRecorderStarted.set(false);
        }
        closeCurrentSession();
        if (mCameraDevice != null) { try { mCameraDevice.close(); } catch (Exception ignored) {} mCameraDevice = null; }
        // 仅在彻底释放时停止和释放 Encoder
        if (mEncoder != null) { try { mEncoder.stop(); mEncoder.release(); } catch (Exception ignored) {} mEncoder = null; }
        if (mEncoderSurface != null) { mEncoderSurface.release(); mEncoderSurface = null; }
        if (mPhotoReader != null) { mPhotoReader.close(); mPhotoReader = null; }
        closeCurrentChannel();
        if (mBgThread != null) { mBgThread.quitSafely(); mBgThread = null; }
        if (mOrientationEventListener != null) { mOrientationEventListener.disable(); mOrientationEventListener = null; }
        // 【修复】彻底释放时关闭 ParcelFileDescriptor
        if (mVideoPfd != null) {
            try { mVideoPfd.close(); } catch (Exception ignored) {}
            mVideoPfd = null;
        }
    }

    // ==================== 辅助 ====================
    private void tryRecoverFromConfigFailure() {
        if (mCameraDevice == null || !mIsStreaming.get()) return;
        try {
            List<OutputConfiguration> out = new ArrayList<>();
            out.add(new OutputConfiguration(mEncoderSurface));
            mCameraDevice.createCaptureSession(new SessionConfiguration(SessionConfiguration.SESSION_REGULAR, out, cmd -> mBgHandler.post(cmd), new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(@NonNull CameraCaptureSession s) {
                    mCaptureSession = s;
                    mSessionConfigured.set(true);
                    startPreviewRequest();
                    openChannelStream();
                }
                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession s) { stopStreamingAndRelease(); }
            }));
        } catch (Exception e) {
            stopStreamingAndRelease();
        }
    }

    private void startBackgroundThread() {
        if (mBgThread == null) { mBgThread = new HandlerThread("CamBg"); mBgThread.start(); mBgHandler = new Handler(mBgThread.getLooper()); }
    }

    private void startOrientationListener() {
        mOrientationEventListener = new OrientationEventListener(this) {
            @Override
            public void onOrientationChanged(int o) { if (o != ORIENTATION_UNKNOWN) mDeviceOrientation = o; }
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
                .setContentTitle("WearSync Camera").setSmallIcon(R.drawable.ic_notification).setOngoing(true).build();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }

    // ==================== H.264 编码器回调（手表预览） ====================
    private class EncoderCallback extends MediaCodec.Callback {
        @Override
        public void onInputBufferAvailable(@NonNull MediaCodec c, int i) {}
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
            } catch (Exception ignored) {}
        }
        @Override
        public void onError(@NonNull MediaCodec c, @NonNull MediaCodec.CodecException e) { PhoneLog.e(TAG, "❌ Encoder: " + e.getMessage()); }
        @Override
        public void onOutputFormatChanged(@NonNull MediaCodec c, @NonNull MediaFormat f) {}
    }
}
