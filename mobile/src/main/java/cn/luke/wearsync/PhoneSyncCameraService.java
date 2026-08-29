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
import android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession;
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
 * 录像规格选择：
 *   Size(大→小) → FPS(高→低) → HEVC → H264 → 下一个FPS → 下一个Size
 *
 * High Speed Video：
 *   若选中的 Size+FPS 属于 HighSpeed 配置，自动使用
 *   CameraConstrainedHighSpeedCaptureSession
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
    private volatile boolean mIsHighSpeedRecording = false;

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
        String action = intent.getAction();
        resetWatchdog();

        if (ACTION_START_CAMERA.equals(action)) {
            mCachedNodeId = intent.getStringExtra("remote_node_id");
            if (mCachedNodeId == null) mCachedNodeId = WearSyncState.getNodeId(this);
            if (mCachedNodeId == null) { stopSelf(); return START_NOT_STICKY; }
            if (!mIsStreaming.get()) initCameraAndStartStreaming();
            else sendCameraListToWear();
        } else if (ACTION_STOP_CAMERA.equals(action)) {
            stopStreamingAndRelease(); stopSelf();
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

            MediaFormat format = MediaFormat.createVideoFormat(
                    MediaFormat.MIMETYPE_VIDEO_AVC, mPreviewSize.getWidth(), mPreviewSize.getHeight());
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
        int devRot = (mDeviceOrientation != OrientationEventListener.ORIENTATION_UNKNOWN)
                ? (mDeviceOrientation + 45) / 90 * 90 : 0;
        if (mCameraFacing == CameraCharacteristics.LENS_FACING_FRONT)
            return (360 - (mSensorOrientation + devRot) % 360) % 360;
        else
            return (mSensorOrientation - devRot + 360) % 360;
    }

    private int calculateJpegRotation() {
        int devRot = (mDeviceOrientation != OrientationEventListener.ORIENTATION_UNKNOWN)
                ? (mDeviceOrientation + 45) / 90 * 90 : 0;
        if (mCameraFacing == CameraCharacteristics.LENS_FACING_FRONT) {
            int r = (mSensorOrientation + devRot) % 360;
            return (360 - r) % 360;
        } else {
            return (mSensorOrientation - devRot + 360) % 360;
        }
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
            if (photoSizes != null && photoSizes.length > 0)
                mPhotoSize = Collections.max(Arrays.asList(photoSizes), SIZE_BY_AREA);

            Size[] previewSizes = map.getOutputSizes(MediaCodec.class);
            if (previewSizes != null && previewSizes.length > 0)
                mPreviewSize = chooseBestPreviewSize(previewSizes);

            chooseVideoSizeAndFps(map);
        }
    }

    private String findBestBackCamera(CameraManager mgr, String[] idList) {
        String bestId = null; long bestArea = 0;
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
    // 【修改点1】录像规格选择：Size→FPS→HEVC→H264→下一FPS→下一Size
    // ==================================================================

    /**
     * 动态选择最优录像规格。
     *
     * 遍历顺序：
     *   Size 从大到小
     *     → 该 Size 的 FPS 从高到低
     *       → HEVC 检查 → 成功则选中
     *       → H264 检查 → 成功则选中
     *       → 都失败 → 尝试下一个更低 FPS
     *     → 所有 FPS 都失败 → 下一个更低分辨率
     */
    private void chooseVideoSizeAndFps(StreamConfigurationMap map) {
        Size[] videoSizes = map.getOutputSizes(MediaRecorder.class);
        if (videoSizes == null || videoSizes.length == 0) {
            PhoneLog.e(TAG, "❌ [VideoSelection] Camera=" + mCameraId + " 不支持 MediaRecorder");
            mVideoSize = new Size(1920, 1080); mVideoFps = 30; mUseHevcForRecording = false;
            mIsHighSpeedRecording = false;
            return;
        }

        // Size 按面积降序
        List<Size> sortedSizes = new ArrayList<>(Arrays.asList(videoSizes));
        Collections.sort(sortedSizes, SIZE_BY_AREA.reversed());

        // 获取 High Speed 尺寸集合
        Size[] hsSizes = map.getHighSpeedVideoSizes();
        List<Size> hsSizeList = (hsSizes != null) ? Arrays.asList(hsSizes) : Collections.emptyList();

        Size selectedSize = null;
        int selectedFps = 0;
        boolean selectedHevc = false;
        boolean selectedHighSpeed = false;

        PhoneLog.d(TAG, "═══════════════════════════════════════════════════");
        PhoneLog.d(TAG, "[VideoCapability] Camera=" + mCameraId + " 开始评估");
        PhoneLog.d(TAG, "═══════════════════════════════════════════════════");

        // 外层：Size 从大到小
        for (Size size : sortedSizes) {
            // 获取该 Size 的所有合法 FPS（已降序排列）
            List<Integer> fpsList = getSupportedFpsForSize(map, size, hsSizeList);

            if (fpsList.isEmpty()) {
                PhoneLog.d(TAG, "[VideoCapability] Camera=" + mCameraId +
                        " Size=" + size.getWidth() + "x" + size.getHeight() +
                        " FPS=UNKNOWN → 跳过");
                continue;
            }

            boolean sizeResolved = false;

            // 内层：FPS 从高到低
            for (int fps : fpsList) {
                // 先检查 HEVC
                boolean hevcOk = checkEncoderSupport(MediaFormat.MIMETYPE_VIDEO_HEVC, size, fps);
                if (hevcOk) {
                    selectedSize = size;
                    selectedFps = fps;
                    selectedHevc = true;
                    selectedHighSpeed = isHighSpeedSize(size, hsSizeList);
                    sizeResolved = true;

                    PhoneLog.d(TAG, "[VideoCapability] Camera=" + mCameraId +
                            " Size=" + size.getWidth() + "x" + size.getHeight() +
                            " FPS=" + fps +
                            " HEVC=true H264=- HighSpeed=" + selectedHighSpeed +
                            " → ✅ SELECTED");
                    break;
                }

                // HEVC 失败，检查 H264
                boolean h264Ok = checkEncoderSupport(MediaFormat.MIMETYPE_VIDEO_AVC, size, fps);
                if (h264Ok) {
                    selectedSize = size;
                    selectedFps = fps;
                    selectedHevc = false;
                    selectedHighSpeed = isHighSpeedSize(size, hsSizeList);
                    sizeResolved = true;

                    PhoneLog.d(TAG, "[VideoCapability] Camera=" + mCameraId +
                            " Size=" + size.getWidth() + "x" + size.getHeight() +
                            " FPS=" + fps +
                            " HEVC=false H264=true HighSpeed=" + selectedHighSpeed +
                            " → ✅ SELECTED");
                    break;
                }

                // 都失败，记录日志，尝试下一个更低 FPS
                PhoneLog.d(TAG, "[VideoCapability] Camera=" + mCameraId +
                        " Size=" + size.getWidth() + "x" + size.getHeight() +
                        " FPS=" + fps +
                        " HEVC=false H264=false → 尝试下一FPS");
            }

            if (sizeResolved) break; // 该 Size 已找到合法组合，不再检查更低分辨率

            // 该 Size 所有 FPS 都失败
            PhoneLog.d(TAG, "[VideoCapability] Camera=" + mCameraId +
                    " Size=" + size.getWidth() + "x" + size.getHeight() +
                    " 所有FPS均无可用编码器 → 降级到下一分辨率");
        }

        // 应用结果
        if (selectedSize != null) {
            mVideoSize = selectedSize;
            mVideoFps = selectedFps;
            mUseHevcForRecording = selectedHevc;
            mIsHighSpeedRecording = selectedHighSpeed;

            PhoneLog.d(TAG, "═══════════════════════════════════════════════════");
            PhoneLog.dTAG, "[VideoSelection]");
            PhoneLog.d(TAG, "  Camera=" + mCameraId);
            PhoneLog.d(TAG, "  Resolution=" + mVideoSize.getWidth() + "x" + mVideoSize.getHeight());
            PhoneLog.d(TAG, "  FPS=" + mVideoFps);
            PhoneLog.d(TAG, "  Codec=" + (mUseHevcForRecording ? "HEVC" : "H264"));
            PhoneLog.d(TAG, "  HighSpeed=" + mIsHighSpeedRecording);
            PhoneLog.d(TAG, "  Reason=First valid combo at highest resolution");
            PhoneLog.d(TAG, "═══════════════════════════════════════════════════");
        } else {
            PhoneLog.e(TAG, "⚠️ [VideoSelection] 无合法组合，降级 1920x1080@30 H264");
            mVideoSize = new Size(1920, 1080);
            mVideoFps = 30;
            mUseHevcForRecording = false;
            mIsHighSpeedRecording = false;
        }
    }

    /**
     * 判断指定 Size 是否属于 High Speed Video 配置
     */
    private boolean isHighSpeedSize(Size size, List<Size> hsSizeList) {
        for (Size hs : hsSizeList) {
            if (hs.getWidth() == size.getWidth() && hs.getHeight() == size.getHeight()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 获取指定 Size 下 Camera2 实际支持的所有合法 FPS（降序）。
     *
     * High Speed Size → getHighSpeedVideoFpsRangesFor(size)
     * 普通 Size      → getOutputMinFrameDuration(MediaRecorder.class, size)
     */
    private List<Integer> getSupportedFpsForSize(StreamConfigurationMap map, Size size, List<Size> hsSizeList) {
        List<Integer> fpsList = new ArrayList<>();
        boolean isHS = isHighSpeedSize(size, hsSizeList);

        if (isHS) {
            Range<Integer>[] ranges = map.getHighSpeedVideoFpsRangesFor(size);
            if (ranges != null) {
                for (Range<Integer> r : ranges) {
                    int upper = r.getUpper();
                    if (upper > 0 && !fpsList.contains(upper)) fpsList.add(upper);
                    int lower = r.getLower();
                    if (lower > 0 && lower != upper && !fpsList.contains(lower)) fpsList.add(lower);
                }
            }
        } else {
            try {
                long minDur = map.getOutputMinFrameDuration(MediaRecorder.class, size);
                if (minDur > 0) {
                    int maxFps = (int) (1_000_000_000L / minDur);
                    if (maxFps > 0) fpsList.add(maxFps);
                }
            } catch (Exception e) {
                PhoneLog.w(TAG, "  ↳ minFrameDuration 查询异常: " + e.getMessage());
            }
        }

        Collections.sort(fpsList, Collections.reverseOrder());
        return fpsList;
    }

    /**
     * 检查编码器是否支持给定 Size + FPS
     */
    private boolean checkEncoderSupport(String mimeType, Size size, int fps) {
        try {
            MediaCodecList codecList = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
            for (MediaCodecInfo info : codecList.getCodecInfos()) {
                if (!info.isEncoder()) continue;
                for (String type : info.getSupportedTypes()) {
                    if (!type.equalsIgnoreCase(mimeType)) continue;
                    try {
                        MediaCodecInfo.VideoCapabilities vc =
                                info.getCapabilitiesForType(type).getVideoCapabilities();
                        if (vc == null) continue;
                        if (!vc.isSizeSupported(size.getWidth(), size.getHeight())) continue;
                        Range<Integer> fpsRange = vc.getSupportedFrameRatesFor(size.getWidth(), size.getHeight());
                        if (fpsRange != null && fpsRange.contains(fps)) return true;
                    } catch (Exception ignored) {}
                }
            }
        } catch (Exception e) {
            PhoneLog.w(TAG, "⚠️ checkEncoderSupport 异常: " + e.getMessage());
        }
        return false;
    }

    private Size chooseBestPreviewSize(Size[] sizes) {
        double targetRatio = (double) mPhotoSize.getWidth() / mPhotoSize.getHeight();
        Size best = null; double minDiff = Double.MAX_VALUE;
        List<Size> candidates = new ArrayList<>();
        for (Size s : sizes) {
            if (s.getWidth() >= 240 && s.getWidth() <= 450) candidates.add(s);
        }
        if (candidates.isEmpty()) {
            Size sm = sizes[0];
            for (Size s : sizes)
                if ((long)s.getWidth()*s.getHeight() < (long)sm.getWidth()*sm.getHeight()) sm = s;
            return sm;
        }
        for (Size s : candidates) {
            double d = Math.abs((double)s.getWidth()/s.getHeight() - targetRatio);
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
    // 【修改点2】录像 Session：根据 mIsHighSpeedRecording 选择创建方式
    // ==================================================================

    /**
     * 创建录像 CaptureSession。
     *
     * 如果 mIsHighSpeedRecording == true：
     *   使用 createConstrainedHighSpeedCaptureSession()
     *   并通过 CameraConstrainedHighSpeedCaptureSession.createHighSpeedRequestBuilder()
     *   构建 repeating request。
     *
     * 否则：
     *   使用普通 createCaptureSession() + TEMPLATE_RECORD。
     */
    private void createRecordingSession() {
        if (mCameraDevice == null || mEncoderSurface == null || mVideoRecorder == null || !mIsStreaming.get()) return;
        closeCurrentSession();

        try {
            List<Surface> surfaces = new ArrayList<>();
            if (mEncoderSurface.isValid()) surfaces.add(mEncoderSurface);

            Surface recSurface = mVideoRecorder.getSurface();
            if (recSurface != null && recSurface.isValid()) {
                surfaces.add(recSurface);
            } else {
                PhoneLog.e(TAG, "❌ MediaRecorder Surface 无效");
                return;
            }

            if (mPhotoReader != null && mPhotoReader.getSurface().isValid()) {
                surfaces.add(mPhotoReader.getSurface());
            }

            if (mIsHighSpeedRecording) {
                // ===== High Speed Session =====
                PhoneLog.d(TAG, "🔧 创建 ConstrainedHighSpeed CaptureSession (" +
                        mVideoSize + "@" + mVideoFps + "fps)...");

                mCameraDevice.createConstrainedHighSpeedCaptureSession(
                        surfaces,
                        new CameraCaptureSession.StateCallback() {
                            @Override
                            public void onConfigured(@NonNull CameraCaptureSession session) {
                                if (!mIsStreaming.get()) { session.close(); return; }
                                mCaptureSession = session;
                                mSessionConfigured.set(true);

                                // High Speed 必须使用专用 builder
                                startHighSpeedRecordingRequest(session);

                                if (mRecorderPrepared.get() && !mRecorderStarted.get()) {
                                    try {
                                        mVideoRecorder.start();
                                        mRecorderStarted.set(true);
                                        mIsRecording.set(true);
                                        PhoneLog.d(TAG, "🎬 HighSpeed MediaRecorder 已启动");
                                        notifyWearVideoStatus(true);
                                    } catch (Exception e) {
                                        PhoneLog.e(TAG, "❌ HighSpeed MediaRecorder 启动失败", e);
                                        mIsRecording.set(false);
                                        mRecorderStarted.set(false);
                                        cleanupFailedRecording();
                                        createPreviewSession();
                                    }
                                }
                            }

                            @Override
                            public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                                PhoneLog.e(TAG, "❌ HighSpeed Session 配置失败");
                                mSessionConfigured.set(false);
                                mIsRecording.set(false);
                                cleanupFailedRecording();
                                createPreviewSession();
                            }
                        },
                        mBgHandler
                );
            } else {
                // ===== 普通 Session =====
                PhoneLog.d(TAG, "🔧 创建普通录像 CaptureSession (" +
                        mVideoSize + "@" + mVideoFps + "fps)...");

                List<OutputConfiguration> outputs = new ArrayList<>();
                for (Surface s : surfaces) {
                    outputs.add(new OutputConfiguration(s));
                }

                SessionConfiguration config = new SessionConfiguration(
                        SessionConfiguration.SESSION_REGULAR, outputs,
                        command -> mBgHandler.post(command),
                        new CameraCaptureSession.StateCallback() {
                            @Override
                            public void onConfigured(@NonNull CameraCaptureSession session) {
                                if (!mIsStreaming.get()) { session.close(); return; }
                                mCaptureSession = session;
                                mSessionConfigured.set(true);

                                startRecordingRequest();

                                if (mRecorderPrepared.get() && !mRecorderStarted.get()) {
                                    try {
                                        mVideoRecorder.start();
                                        mRecorderStarted.set(true);
                                        mIsRecording.set(true);
                                        PhoneLog.d(TAG, "🎬 MediaRecorder 已启动");
                                        notifyWearVideoStatus(true);
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
            }
        } catch (Exception e) {
            PhoneLog.e(TAG, "❌ Recording Session creation failed", e);
            mIsRecording.set(false);
            cleanupFailedRecording();
            createPreviewSession();
        }
    }

    /**
     * High Speed 专用：使用 CameraConstrainedHighSpeedCaptureSession 的 builder
     * 构建并提交 repeating request。
     */
    private void startHighSpeedRecordingRequest(CameraCaptureSession session) {
        if (!(session instanceof CameraConstrainedHighSpeedCaptureSession)) {
            PhoneLog.e(TAG, "❌ Session 不是 HighSpeed 类型，回退到普通 request");
            startRecordingRequest();
            return;
        }

        try {
            CameraConstrainedHighSpeedCaptureSession hsSession =
                    (CameraConstrainedHighSpeedCaptureSession) session;

            CaptureRequest.Builder b = hsSession.createHighSpeedRequestBuilder(
                    CameraDevice.TEMPLATE_RECORD);
            b.addTarget(mEncoderSurface);

            Surface recSurface = mVideoRecorder.getSurface();
            if (recSurface != null && recSurface.isValid()) {
                b.addTarget(recSurface);
            }

            applyZoom(b);
            applyCommonControls(b);
            b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, new Range<>(mVideoFps, mVideoFps));

            // High Speed Session 要求使用 setRepeatingBurst 而非 setRepeatingRequest
            List<CaptureRequest> burst = hsSession.createHighSpeedRequestList(b.build());
            hsSession.setRepeatingBurst(burst, null, mBgHandler);

            PhoneLog.d(TAG, "✅ HighSpeed RepeatingBurst 已提交 (burstSize=" + burst.size() + ")");
        } catch (Exception e) {
            PhoneLog.e(TAG, "❌ HighSpeed Request 提交失败", e);
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

    private void startRecordingRequest() {
        if (mCaptureSession == null || mCameraDevice == null || mVideoRecorder == null || !mIsStreaming.get()) return;
        try {
            CaptureRequest.Builder b = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            b.addTarget(mEncoderSurface);
            Surface recSurface = mVideoRecorder.getSurface();
            if (recSurface != null && recSurface.isValid()) b.addTarget(recSurface);
            applyZoom(b);
            applyCommonControls(b);
            b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, new Range<>(mVideoFps, mVideoFps));
            mCaptureSession.setRepeatingRequest(b.build(), null, mBgHandler);
        } catch (Exception e) {
            PhoneLog.e(TAG, "❌ Recording Request 失败", e);
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

    private void startVideoRecording() {
        if (mIsRecording.get() || !mIsStreaming.get() || mCameraDevice == null) return;

        PhoneLog.d(TAG, "🎬 开始录像: " + mVideoSize + "@" + mVideoFps + "fps " +
                (mUseHevcForRecording ? "HEVC" : "H264") +
                " HighSpeed=" + mIsHighSpeedRecording);

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

            try (ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(uri, "rw")) {
                if (pfd == null) { cleanupEmptyVideo(uri); return; }

                mVideoRecorder = new MediaRecorder(this);
                mVideoRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
                mVideoRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
                mVideoRecorder.setOutputFile(pfd.getFileDescriptor());
                mVideoRecorder.setVideoEncoder(mUseHevcForRecording
                        ? MediaRecorder.VideoEncoder.HEVC : MediaRecorder.VideoEncoder.H264);
                mVideoRecorder.setVideoSize(mVideoSize.getWidth(), mVideoSize.getHeight());
                mVideoRecorder.setVideoFrameRate(mVideoFps);

                int bitRate = (int) ((long) mVideoSize.getWidth() * mVideoSize.getHeight() * 4L);
                mVideoRecorder.setVideoEncodingBitRate(Math.min(bitRate, 100_000_000));

                mVideoRecorder.prepare();
                mRecorderPrepared.set(true);

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

    private void stopVideoRecording() {
        if (!mIsRecording.get() && !mRecorderStarted.get()) return;
        try {
            closeCurrentSession();
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
            mCurrentVideoUri = null;
            if (mIsStreaming.get()) createPreviewSession();
            notifyWearVideoStatus(false);
        } catch (Exception e) {
            PhoneLog.e(TAG, "❌ 停止录像异常", e);
            mIsRecording.set(false); mRecorderPrepared.set(false); mRecorderStarted.set(false);
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
            Wearable.getMessageClient(this).sendMessage(mCachedNodeId, "/camera/video_status",
                    j.toString().getBytes(StandardCharsets.UTF_8));
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
        } catch (Exception e) { PhoneLog.e(TAG, "Save Photo Error", e); }
    }

    @SuppressLint("MissingPermission")
    private void writeLocationExifFromUri(Uri uri) {
        if (!getSharedPreferences("dndsync_prefs", Context.MODE_PRIVATE).getBoolean("save_location_enabled", false)) return;
        try (ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(uri, "rw")) {
            if (pfd == null) return;
            Location best = null;
            LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
                for (String p : lm.getProviders(true)) {
                    Location l = lm.getLastKnownLocation(p);
                    if (l != null && (best == null || l.getAccuracy() < best.getAccuracy())) best = l;
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
        if (mIsRecording.get()) startRecordingRequest();
        else startPreviewRequest();
    }

    private void manualFocus(double x, double y) {
        if (mCameraDevice == null || mCaptureSession == null) return;
        try {
            CameraCharacteristics chars = ((CameraManager) getSystemService(Context.CAMERA_SERVICE))
                    .getCameraCharacteristics(mCameraDevice.getId());
            Rect sensor = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
            if (sensor == null) return;
            int cx = (int)(x * sensor.width()), cy = (int)(y * sensor.height());
            MeteringRectangle area = new MeteringRectangle(
                    Math.max(0, cx-100), Math.max(0, cy-100),
                    Math.min(sensor.width(), 200), Math.min(sensor.height(), 200),
                    MeteringRectangle.METERING_WEIGHT_MAX);
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
        } catch (Exception e) { PhoneLog.e(TAG, "❌ 对焦失败", e); }
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
                    if ((long)mj.getWidth()*mj.getHeight() < 1_000_000L) continue;
                    Size[] ps = m.getOutputSizes(MediaCodec.class);
                    if (ps == null || ps.length == 0) continue;
                    Integer hl = c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);
                    if (hl != null && hl == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY) continue;
                    Integer f = c.get(CameraCharacteristics.LENS_FACING);
                    float[] fl = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
                    float mz = Objects.requireNonNullElse(c.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM), 1.0f);
                    Size[] vs = m.getOutputSizes(MediaRecorder.class);
                    boolean hv = vs != null && vs.length > 0;
                    Size mv = hv ? Collections.max(Arrays.asList(vs), SIZE_BY_AREA) : new Size(0,0);
                    JSONObject o = new JSONObject();
                    o.put("id", id); o.put("maxZoom", (double)mz);
                    o.put("maxVideoWidth", mv.getWidth()); o.put("maxVideoHeight", mv.getHeight());
                    o.put("maxJpegWidth", mj.getWidth()); o.put("maxJpegHeight", mj.getHeight());
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
                Wearable.getMessageClient(this).sendMessage(mCachedNodeId, WEAR_MSG_PATH_CAMERA_LIST,
                        arr.toString().getBytes(StandardCharsets.UTF_8));
            } catch (Exception e) { PhoneLog.e(TAG, "❌ 相机列表失败", e); }
        }).start();
    }

    // ==================== 数据通道 ====================

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
                    } catch (Exception e) { PhoneLog.e(TAG, "❌ 编码器启动失败", e); }
                }))
                .addOnFailureListener(e -> PhoneLog.e(TAG, "❌ Channel 失败", e));
    }

    private void notifyStreamReadyToWear() {
        if (mCachedNodeId == null) return;
        try {
            JSONObject j = new JSONObject(); j.put("type", "camera_status"); j.put("action", "STREAM_READY");
            Wearable.getMessageClient(this).sendMessage(mCachedNodeId, "/camera/status",
                    j.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {}
    }

    private synchronized void closeCurrentChannel() {
        if (mDataOutputStream != null) {
            try { mDataOutputStream.flush(); mDataOutputStream.close(); } catch (Exception ignored) {}
            mDataOutputStream = null;
        }
    }

    // ==================== 资源释放 ====================

    private synchronized void stopStreamingAndRelease() {
        mIsStreaming.set(false); mIsCameraOpened.set(false);
        if (mIsRecording.get() || mRecorderStarted.get()) {
            try { if (mVideoRecorder != null) { if (mRecorderStarted.get()) mVideoRecorder.stop(); mVideoRecorder.release(); mVideoRecorder = null; } } catch (Exception ignored) {}
            mIsRecording.set(false); mRecorderPrepared.set(false); mRecorderStarted.set(false);
        }
        closeCurrentSession();
        if (mCameraDevice != null) { try { mCameraDevice.close(); } catch (Exception ignored) {} mCameraDevice = null; }
        if (mEncoder != null) { try { mEncoder.stop(); mEncoder.release(); } catch (Exception ignored) {} mEncoder = null; }
        if (mEncoderSurface != null) { mEncoderSurface.release(); mEncoderSurface = null; }
        if (mPhotoReader != null) { mPhotoReader.close(); mPhotoReader = null; }
        closeCurrentChannel();
        if (mBgThread != null) { mBgThread.quitSafely(); mBgThread = null; }
        if (mOrientationEventListener != null) { mOrientationEventListener.disable(); mOrientationEventListener = null; }
    }

    // ==================== 辅助 ====================

    private void tryRecoverFromConfigFailure() {
        if (mCameraDevice == null || !mIsStreaming.get()) return;
        try {
            List<OutputConfiguration> out = new ArrayList<>();
            out.add(new OutputConfiguration(mEncoderSurface));
            mCameraDevice.createCaptureSession(new SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR, out,
                    cmd -> mBgHandler.post(cmd),
                    new CameraCaptureSession.StateCallback() {
                        @Override public void onConfigured(@NonNull CameraCaptureSession s) {
                            mCaptureSession = s; mSessionConfigured.set(true);
                            startPreviewRequest(); openChannelStream();
                        }
                        @Override public void onConfigureFailed(@NonNull CameraCaptureSession s) { stopStreamingAndRelease(); }
                    }));
        } catch (Exception e) { stopStreamingAndRelease(); }
    }

    private void startBackgroundThread() {
        if (mBgThread == null) { mBgThread = new HandlerThread("CamBg"); mBgThread.start(); mBgHandler = new Handler(mBgThread.getLooper()); }
    }

    private void startOrientationListener() {
        mOrientationEventListener = new OrientationEventListener(this) {
            @Override public void onOrientationChanged(int o) { if (o != ORIENTATION_UNKNOWN) mDeviceOrientation = o; }
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

    @Nullable @Override public IBinder onBind(Intent intent) { return null; }

    // ==================== H.264 编码器回调（手表预览） ====================

    private class EncoderCallback extends MediaCodec.Callback {
        @Override public void onInputBufferAvailable(@NonNull MediaCodec c, int i) {}
        @Override public void onOutputBufferAvailable(@NonNull MediaCodec c, int i, @NonNull MediaCodec.BufferInfo info) {
            try {
                ByteBuffer b = c.getOutputBuffer(i);
                if (b != null && info.size > 0) {
                    byte[] d = new byte[info.size]; b.get(d);
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
        @Override public void onError(@NonNull MediaCodec c, @NonNull MediaCodec.CodecException e) { PhoneLog.e(TAG, "❌ Encoder: " + e.getMessage()); }
        @Override public void onOutputFormatChanged(@NonNull MediaCodec c, @NonNull MediaFormat f) {}
    }
}
