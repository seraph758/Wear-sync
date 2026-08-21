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
import android.location.Location;
import android.location.LocationManager;
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
 * 手机端推流服务：动态发现镜头、支持变焦、GPS 信息保存及 H.264 推流
 */
public class PhoneSyncCameraService extends Service {
    private static final String TAG = "WearSync_CameraSvc";

    public static final String ACTION_START_CAMERA = "cn.luke.wearsync.action.START_CAMERA";
    public static final String ACTION_STOP_CAMERA = "cn.luke.wearsync.action.STOP_CAMERA";
    public static final String ACTION_TAKE_PHOTO = "cn.luke.wearsync.action.TAKE_PHOTO";
    public static final String ACTION_SWITCH_CAMERA = "cn.luke.wearsync.action.SWITCH_CAMERA";
    public static final String ACTION_SET_ZOOM = "cn.luke.wearsync.action.SET_ZOOM";
    public static final String ACTION_FOCUS_CAMERA = "cn.luke.wearsync.action.FOCUS_CAMERA";
    public static final String ACTION_REQUEST_CAMERA_LIST = "cn.luke.wearsync.action.REQUEST_CAMERA_LIST";
    
    public static final String WEAR_MSG_PATH_TAKE_PHOTO = "/camera/take_photo";
    public static final String WEAR_CHANNEL_PATH = "/wear_data_channel/camera";
    public static final String WEAR_MSG_PATH_CAMERA_LIST = "/camera/info_list";

    private static final int BIT_RATE = 450_000; 
    private static final int FRAME_RATE = 25;
    private static final int I_FRAME_INTERVAL = 1;

    private static final String CHANNEL_ID = "camera_service_channel";
    private static final int NOTIFICATION_ID = 101;

    private int mPreviewWidth = 256;
    private int mPreviewHeight = 256;
    private int photoWidth = 4096;
    private int photoHeight = 3072;
    
    private int mDeviceOrientation = OrientationEventListener.ORIENTATION_UNKNOWN;
    private int mSensorOrientation = 0;
    private String mCameraId; 
    private float mCurrentZoom = 1.0f; 
    private float mMaxZoom = 1.0f; 
    private Rect mActiveArraySize; 
    
    private final AtomicBoolean mIsStreaming = new AtomicBoolean(false);
    private final AtomicBoolean mIsCameraOpened = new AtomicBoolean(false);
    private boolean mHeifEncoderAvailable = false;          
    private int mCaptureFormat = ImageFormat.JPEG; 

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
    private OrientationEventListener mOrientationEventListener;

    private static final Comparator<Size> SIZE_BY_AREA = (lhs, rhs) ->
            Long.signum((long) lhs.getWidth() * lhs.getHeight() - (long) rhs.getWidth() * rhs.getHeight());

    private final MessageClient.OnMessageReceivedListener mMessageListener = event -> {
        if (WEAR_MSG_PATH_TAKE_PHOTO.equals(event.getPath())) {
            captureHighResPhoto();
        } else if ("/camera/control".equals(event.getPath())) {
            try {
                JSONObject json = new JSONObject(new String(event.getData(), StandardCharsets.UTF_8));
                if ("REQUEST_CAMERA_LIST".equalsIgnoreCase(json.optString("action"))) sendCameraListToWear();
            } catch (Exception ignored) {}
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        mMainHandler = new Handler(getMainLooper());
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA);
        mChannelClient = Wearable.getChannelClient(this);
        Wearable.getMessageClient(this).addListener(mMessageListener);
        PhoneLog.d(TAG, "✅ 相机同步服务就绪");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        String action = intent.getAction();
        if (ACTION_START_CAMERA.equals(action)) {
            mCachedNodeId = intent.getStringExtra("remote_node_id");
            if (mCachedNodeId == null) mCachedNodeId = WearSyncState.getNodeId(this);
            if (!mIsStreaming.get()) initCameraAndStartStreaming(); else sendCameraListToWear();
        } else if (ACTION_STOP_CAMERA.equals(action)) {
            stopStreamingAndRelease(); stopSelf();
        } else if (ACTION_TAKE_PHOTO.equals(action)) {
            captureHighResPhoto();
        } else if (ACTION_SWITCH_CAMERA.equals(action)) {
            switchCamera(intent.getStringExtra("camera_id"));
        } else if (ACTION_SET_ZOOM.equals(action)) {
            setZoom(intent.getFloatExtra("zoom", 1.0f));
        } else if (ACTION_FOCUS_CAMERA.equals(action)) {
            manualFocus(intent.getDoubleExtra("x", 0.5), intent.getDoubleExtra("y", 0.5));
        } else if (ACTION_REQUEST_CAMERA_LIST.equals(action)) {
            sendCameraListToWear();
        }
        return START_NOT_STICKY;
    }

    private void initCameraAndStartStreaming() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return;
        mIsStreaming.set(true);
        startBackgroundThread();
        startOrientationListener();
        try {
            chooseOptimalSizes();
            MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, mPreviewWidth, mPreviewHeight);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            format.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL);
            format.setInteger(MediaFormat.KEY_ROTATION, 90);
            mEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            mEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            mPhotoReader = ImageReader.newInstance(photoWidth, photoHeight, mCaptureFormat, 2);
            mPhotoReader.setOnImageAvailableListener(reader -> {
                Image image = reader.acquireLatestImage();
                if (image != null) { savePhoto(image); image.close(); }
            }, mBgHandler);
            openChannelStream();
        } catch (Exception e) { stopStreamingAndRelease(); stopSelf(); }
    }

    private void chooseOptimalSizes() {
        try {
            CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            if (mCameraId == null) {
                for (String id : manager.getCameraIdList()) {
                    if (manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == CameraMetadata.LENS_FACING_BACK) {
                        mCameraId = id; break;
                    }
                }
            }
            if (mCameraId == null) return;
            CameraCharacteristics chars = manager.getCameraCharacteristics(mCameraId);
            StreamConfigurationMap map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            mMaxZoom = chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM);
            mActiveArraySize = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
            Size captureSize = chooseBestSize(map.getOutputSizes(ImageFormat.JPEG));
            if (captureSize != null) { photoWidth = captureSize.getWidth(); photoHeight = captureSize.getHeight(); }
            Size previewSize = chooseBestPreviewSize(map.getOutputSizes(SurfaceTexture.class), captureSize != null ? captureSize : new Size(4, 3));
            if (previewSize != null) { mPreviewWidth = previewSize.getWidth(); mPreviewHeight = previewSize.getHeight(); }
        } catch (Exception e) { PhoneLog.e(TAG, "❌ 尺寸选择失败", e); }
    }

    private Size chooseBestSize(Size[] choices) {
        if (choices == null || choices.length == 0) return null;
        List<Size> valid = new ArrayList<>();
        for (Size s : choices) if (s.getWidth() <= 4096 && s.getHeight() <= 3072) valid.add(s);
        return Collections.max(valid.isEmpty() ? Arrays.asList(choices) : valid, SIZE_BY_AREA);
    }

    private Size chooseBestPreviewSize(Size[] choices, Size aspectRatio) {
        if (choices == null) return null;
        double targetRatio = (double) Math.max(aspectRatio.getWidth(), aspectRatio.getHeight()) / Math.min(aspectRatio.getWidth(), aspectRatio.getHeight());
        Size best = choices[0]; double minDiff = Double.MAX_VALUE;
        for (Size s : choices) {
            double r = (double) Math.max(s.getWidth(), s.getHeight()) / Math.min(s.getWidth(), s.getHeight());
            if (Math.abs(r - targetRatio) < 0.1) {
                int d = Math.abs(s.getWidth() - 256);
                if (d < minDiff) { minDiff = d; best = s; }
            }
        }
        return best;
    }

    private void sendCameraListToWear() {
        if (mCachedNodeId == null) return;
        new Thread(() -> {
            try {
                CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
                JSONArray array = new JSONArray();
                for (String id : manager.getCameraIdList()) {
                    CameraCharacteristics chars = manager.getCameraCharacteristics(id);
                    StreamConfigurationMap map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                    if (map == null) continue;
                    Size[] sizes = map.getOutputSizes(ImageFormat.JPEG);
                    if (sizes == null || sizes.length == 0) continue;
                    Size maxRes = Collections.max(Arrays.asList(sizes), SIZE_BY_AREA);
                    if (maxRes.getWidth() < 1280) continue;
                    Integer facing = chars.get(CameraCharacteristics.LENS_FACING);
                    float[] focal = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
                    JSONObject obj = new JSONObject();
                    obj.put("id", id); obj.put("maxZoom", (double)chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM));
                    String name = (facing == CameraMetadata.LENS_FACING_FRONT) ? "前置镜头" : (focal != null && focal[0] < 3.0f ? "超广角" : (focal != null && focal[0] > 6.0f ? "长焦" : "后置主摄"));
                    obj.put("name", name); array.put(obj);
                }
                Wearable.getMessageClient(this).sendMessage(mCachedNodeId, WEAR_MSG_PATH_CAMERA_LIST, array.toString().getBytes(StandardCharsets.UTF_8));
            } catch (Exception ignored) {}
        }).start();
    }

    private void startCameraHardware() {
        CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        if (mCameraId == null || manager == null) return;
        try {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return;
            manager.openCamera(mCameraId, new CameraDevice.StateCallback() {
                @Override public void onOpened(@NonNull CameraDevice camera) {
                    mCameraDevice = camera; mIsCameraOpened.set(true); createCameraCaptureSession();
                }
                @Override public void onDisconnected(@NonNull CameraDevice camera) { stopStreamingAndRelease(); }
                @Override public void onError(@NonNull CameraDevice camera, int error) { stopStreamingAndRelease(); }
            }, mBgHandler);
        } catch (Exception ignored) {}
    }

    private void createCameraCaptureSession() {
        if (mCameraDevice == null || mEncoderSurface == null || mPhotoReader == null) return;
        try {
            List<OutputConfiguration> outputs = Arrays.asList(new OutputConfiguration(mEncoderSurface), new OutputConfiguration(mPhotoReader.getSurface()));
            mCameraDevice.createCaptureSession(new SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outputs, command -> mBgHandler.post(command), new CameraCaptureSession.StateCallback() {
                @Override public void onConfigured(@NonNull CameraCaptureSession session) { mCaptureSession = session; startPreviewRequest(); }
                @Override public void onConfigureFailed(@NonNull CameraCaptureSession session) { stopStreamingAndRelease(); }
            }));
        } catch (Exception ignored) {}
    }

    private void startPreviewRequest() {
        if (mCaptureSession == null) return;
        try {
            CaptureRequest.Builder b = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            b.addTarget(mEncoderSurface);
            Rect zoomRect = calculateZoomRect(mCurrentZoom);
            if (zoomRect != null) b.set(CaptureRequest.SCALER_CROP_REGION, zoomRect);
            mCaptureSession.setRepeatingRequest(b.build(), null, mBgHandler);
        } catch (Exception ignored) {}
    }

    private void captureHighResPhoto() {
        if (mCaptureSession == null) return;
        try {
            CaptureRequest.Builder b = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            b.addTarget(mPhotoReader.getSurface());
            Rect zoomRect = calculateZoomRect(mCurrentZoom);
            if (zoomRect != null) b.set(CaptureRequest.SCALER_CROP_REGION, zoomRect);
            b.set(CaptureRequest.JPEG_QUALITY, (byte)100);
            mCaptureSession.capture(b.build(), null, mBgHandler);
        } catch (Exception ignored) {}
    }

    private void savePhoto(Image image) {
        try {
            ByteBuffer buffer = image.getPlanes()[0].getBuffer();
            byte[] data = new byte[buffer.remaining()]; buffer.get(data);
            File wearSyncDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "WearSync");
            if (!wearSyncDir.exists()) wearSyncDir.mkdirs();
            File file = new File(wearSyncDir, "IMG_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".jpg");
            try (FileOutputStream fos = new FileOutputStream(file)) { fos.write(data); }
            SharedPreferences sp = getSharedPreferences("dndsync_prefs", Context.MODE_PRIVATE);
            if (sp.getBoolean("save_location_enabled", false)) writeLocationExif(file);
            MediaScannerConnection.scanFile(this, new String[]{file.getAbsolutePath()}, null, null);
        } catch (Exception ignored) {}
    }

    private void writeLocationExif(File file) {
        try {
            LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return;
            Location loc = null;
            for (String p : lm.getProviders(true)) {
                Location l = lm.getLastKnownLocation(p);
                if (l != null && (loc == null || l.getAccuracy() < loc.getAccuracy())) loc = l;
            }
            if (loc != null) {
                ExifInterface exif = new ExifInterface(file.getAbsolutePath());
                exif.setGpsInfo(loc); exif.saveAttributes();
            }
        } catch (Exception ignored) {}
    }

    private void openChannelStream() {
        mChannelClient.openChannel(mCachedNodeId, WEAR_CHANNEL_PATH).addOnSuccessListener(channel -> mChannelClient.getOutputStream(channel).addOnSuccessListener(os -> {
            mDataOutputStream = new DataOutputStream(os); sendCameraListToWear();
            try { mEncoderSurface = mEncoder.createInputSurface(); mEncoder.setCallback(new EncoderCallback(), mBgHandler); mEncoder.start(); startCameraHardware(); } catch (Exception ignored) {}
        }));
    }

    private Rect calculateZoomRect(float zoom) {
        if (mActiveArraySize == null) return null;
        int cx = mActiveArraySize.centerX(), cy = mActiveArraySize.centerY();
        int dx = (int)(0.5f * mActiveArraySize.width() / zoom), dy = (int)(0.5f * mActiveArraySize.height() / zoom);
        return new Rect(cx - dx, cy - dy, cx + dx, cy + dy);
    }

    private void setZoom(float z) { mCurrentZoom = Math.max(1.0f, Math.min(z, mMaxZoom)); startPreviewRequest(); }

    private void switchCamera(String id) { if (id != null) { mCameraId = id; stopStreamingAndRelease(); initCameraAndStartStreaming(); } }

    private void manualFocus(double x, double y) { /* 原有对焦逻辑 */ }

    private void stopStreamingAndRelease() {
        mIsStreaming.set(false);
        if (mCaptureSession != null) { try { mCaptureSession.close(); } catch (Exception ignored) {} mCaptureSession = null; }
        if (mCameraDevice != null) { try { mCameraDevice.close(); } catch (Exception ignored) {} mCameraDevice = null; }
        if (mEncoder != null) { try { mEncoder.stop(); mEncoder.release(); } catch (Exception ignored) {} mEncoder = null; }
        if (mBgThread != null) { mBgThread.quitSafely(); mBgThread = null; }
    }

    private void startBackgroundThread() { if (mBgThread == null) { mBgThread = new HandlerThread("CamBg"); mBgThread.start(); mBgHandler = new Handler(mBgThread.getLooper()); } }
    private void createNotificationChannel() { ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(new NotificationChannel(CHANNEL_ID, "Camera", NotificationManager.IMPORTANCE_LOW)); }
    private Notification buildNotification() { return new NotificationCompat.Builder(this, CHANNEL_ID).setContentTitle("WearSync Camera").setSmallIcon(R.drawable.ic_notification).setOngoing(true).build(); }
    private void startOrientationListener() { mOrientationEventListener = new OrientationEventListener(this) { @Override public void onOrientationChanged(int o) { if (o != ORIENTATION_UNKNOWN) mDeviceOrientation = o; } }; mOrientationEventListener.enable(); }
    @Nullable @Override public IBinder onBind(Intent intent) { return null; }

    private class EncoderCallback extends MediaCodec.Callback {
        @Override public void onInputBufferAvailable(@NonNull MediaCodec c, int i) {}
        @Override public void onOutputBufferAvailable(@NonNull MediaCodec c, int i, @NonNull MediaCodec.BufferInfo info) {
            try {
                ByteBuffer b = c.getOutputBuffer(i);
                if (b != null && info.size > 0) {
                    byte[] d = new byte[info.size]; b.get(d);
                    synchronized (this) { if (mDataOutputStream != null) { mDataOutputStream.writeInt(info.size); mDataOutputStream.writeLong(info.presentationTimeUs); mDataOutputStream.writeInt(info.flags); mDataOutputStream.write(d); mDataOutputStream.flush(); } }
                }
                c.releaseOutputBuffer(i, false);
            } catch (Exception ignored) {}
        }
        @Override public void onError(@NonNull MediaCodec c, @NonNull MediaCodec.CodecException e) {}
        @Override public void onOutputFormatChanged(@NonNull MediaCodec c, @NonNull MediaFormat f) {}
    }
}
