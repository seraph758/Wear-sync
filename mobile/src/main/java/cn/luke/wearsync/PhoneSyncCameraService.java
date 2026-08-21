package cn.luke.wearsync;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
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
import android.media.MediaRecorder;
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

import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
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

public class PhoneSyncCameraService extends Service {
    private static final String TAG = "PhoneSync_CameraSvc";

    public static final String ACTION_START_CAMERA = "cn.luke.wearsync.action.START_CAMERA";
    public static final String ACTION_STOP_CAMERA = "cn.luke.wearsync.action.STOP_CAMERA";
    public static final String ACTION_TAKE_PHOTO = "cn.luke.wearsync.action.TAKE_PHOTO";
    public static final String ACTION_SWITCH_CAMERA = "cn.luke.wearsync.action.SWITCH_CAMERA";
    public static final String ACTION_SET_ZOOM = "cn.luke.wearsync.action.SET_ZOOM";
    public static final String ACTION_TOGGLE_VIDEO = "cn.luke.wearsync.action.TOGGLE_VIDEO";
    public static final String ACTION_REQUEST_CAMERA_LIST = "cn.luke.wearsync.action.REQUEST_CAMERA_LIST";
    
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

    private HandlerThread mBgThread;
    private Handler mBgHandler;
    private CameraDevice mCameraDevice;
    private CameraCaptureSession mCaptureSession;
    private MediaCodec mEncoder;
    private Surface mEncoderSurface;
    private ImageReader mPhotoReader;
    private MediaRecorder mVideoRecorder;
    private File mVideoFile;
    private String mCachedNodeId;
    private ChannelClient mChannelClient;
    private DataOutputStream mDataOutputStream;

    private static final Comparator<Size> SIZE_BY_AREA = (lhs, rhs) -> Long.signum((long) lhs.getWidth() * lhs.getHeight() - (long) rhs.getWidth() * rhs.getHeight());

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
                    JSONObject json = new JSONObject(new String(event.getData(), StandardCharsets.UTF_8));
                    String action = json.optString("action");
                    switch (action.toUpperCase()) {
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
                } catch (Exception ignored) {}
                break;
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startForeground(101, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA);
        mChannelClient = Wearable.getChannelClient(this);
        Wearable.getMessageClient(this).addListener(mMessageListener);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        String action = intent.getAction();
        if (ACTION_START_CAMERA.equals(action)) {
            mCachedNodeId = intent.getStringExtra("remote_node_id");
            if (mCachedNodeId == null) mCachedNodeId = WearSyncState.getNodeId(this);
            if (mCachedNodeId == null) { stopSelf(); return START_NOT_STICKY; }
            if (!mIsStreaming.get()) initCameraAndStartStreaming(); else sendCameraListToWear();
        } else if (ACTION_STOP_CAMERA.equals(action)) {
            stopStreamingAndRelease(); stopSelf();
        } else if (ACTION_TAKE_PHOTO.equals(action)) captureHighResPhoto();
        else if (ACTION_SWITCH_CAMERA.equals(action)) switchCamera(intent.getStringExtra("camera_id"));
        else if (ACTION_TOGGLE_VIDEO.equals(action)) toggleVideoRecording();
        else if (ACTION_SET_ZOOM.equals(action)) setZoom(intent.getFloatExtra("zoom", 1.0f));
        else if (ACTION_REQUEST_CAMERA_LIST.equals(action)) sendCameraListToWear();
        return START_NOT_STICKY;
    }

    private void initCameraAndStartStreaming() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return;
        mIsStreaming.set(true); 
        startBackgroundThread();
        startOrientationListener();
        try {
            chooseOptimalSizes();
            MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 256, 256);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            format.setInteger(MediaFormat.KEY_BIT_RATE, 450_000); 
            format.setInteger(MediaFormat.KEY_FRAME_RATE, 25); 
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
            int rot = (mCameraFacing == CameraCharacteristics.LENS_FACING_FRONT) ? 270 : 90;
            format.setInteger(MediaFormat.KEY_ROTATION, rot);
            mEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            mEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            mPhotoReader = ImageReader.newInstance(4096, 3072, ImageFormat.JPEG, 2);
            mPhotoReader.setOnImageAvailableListener(reader -> { 
                Image img = reader.acquireLatestImage(); 
                if (img != null) { savePhoto(img); img.close(); } 
            }, mBgHandler);
            openChannelStream();
        } catch (Exception e) { PhoneLog.e(TAG, "Streaming Init Failed", e); stopStreamingAndRelease(); stopSelf(); }
    }

    private void chooseOptimalSizes() throws CameraAccessException {
        CameraManager mgr = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        if (mCameraId == null) { 
            for (String id : mgr.getCameraIdList()) { 
                Integer f = mgr.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
                if (f != null && f == CameraMetadata.LENS_FACING_BACK) { mCameraId = id; break; } 
            } 
        }
        if (mCameraId == null) mCameraId = mgr.getCameraIdList()[0];
        CameraCharacteristics chars = mgr.getCameraCharacteristics(mCameraId);
        mCameraFacing = Objects.requireNonNullElse(chars.get(CameraCharacteristics.LENS_FACING), CameraMetadata.LENS_FACING_BACK);
        mMaxZoom = Objects.requireNonNullElse(chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM), 1.0f);
        mActiveArraySize = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
        mSensorOrientation = Objects.requireNonNullElse(chars.get(CameraCharacteristics.SENSOR_ORIENTATION), 0);
    }

    private void startCameraHardware() {
        CameraManager mgr = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return;
            mgr.openCamera(mCameraId, new CameraDevice.StateCallback() {
                @Override public void onOpened(@NonNull CameraDevice camera) { mCameraDevice = camera; mIsCameraOpened.set(true); createCameraCaptureSession(); }
                @Override public void onDisconnected(@NonNull CameraDevice camera) { stopStreamingAndRelease(); }
                @Override public void onError(@NonNull CameraDevice camera, int error) { stopStreamingAndRelease(); }
            }, mBgHandler);
        } catch (Exception ignored) {}
    }

    private void createCameraCaptureSession() {
        if (mCameraDevice == null || mEncoderSurface == null) return;
        try {
            List<Surface> targets = new ArrayList<>();
            targets.add(mEncoderSurface); targets.add(mPhotoReader.getSurface());
            if (mIsRecording.get() && mVideoRecorder != null) targets.add(mVideoRecorder.getSurface());
            List<OutputConfiguration> configs = new ArrayList<>();
            for (Surface s : targets) configs.add(new OutputConfiguration(s));
            mCameraDevice.createCaptureSession(new SessionConfiguration(SessionConfiguration.SESSION_REGULAR, configs, command -> mBgHandler.post(command), new CameraCaptureSession.StateCallback() {
                @Override public void onConfigured(@NonNull CameraCaptureSession session) { mCaptureSession = session; startPreviewRequest(); }
                @Override public void onConfigureFailed(@NonNull CameraCaptureSession session) { stopStreamingAndRelease(); }
            }));
        } catch (Exception ignored) {}
    }

    private void startPreviewRequest() {
        if (mCaptureSession == null) return;
        try {
            CaptureRequest.Builder b = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            b.addTarget(mEncoderSurface); if (mIsRecording.get() && mVideoRecorder != null) b.addTarget(mVideoRecorder.getSurface());
            applyZoom(b); mCaptureSession.setRepeatingRequest(b.build(), null, mBgHandler);
        } catch (Exception ignored) {}
    }

    private void captureHighResPhoto() {
        if (mCaptureSession == null) return;
        try {
            CaptureRequest.Builder b = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            b.addTarget(mPhotoReader.getSurface()); applyZoom(b);
            int devRot = (mDeviceOrientation != OrientationEventListener.ORIENTATION_UNKNOWN) ? (mDeviceOrientation + 45) / 90 * 90 : 0;
            b.set(CaptureRequest.JPEG_ORIENTATION, (mSensorOrientation + devRot) % 360);
            b.set(CaptureRequest.JPEG_QUALITY, (byte)100);
            mCaptureSession.capture(b.build(), null, mBgHandler);
        } catch (Exception ignored) {}
    }

    private void toggleVideoRecording() { if (mIsRecording.get()) stopVideoRecording(); else startVideoRecording(); }

    private void startVideoRecording() {
        try {
            File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "WearSync");
            if (!dir.exists() && !dir.mkdirs()) PhoneLog.w(TAG, "Dir Creation Failed");
            mVideoFile = new File(dir, "VID_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".mp4");
            mVideoRecorder = new MediaRecorder(this);
            mVideoRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
            mVideoRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            mVideoRecorder.setOutputFile(mVideoFile.getAbsolutePath());
            mVideoRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
            mVideoRecorder.setVideoSize(1920, 1080); mVideoRecorder.setVideoFrameRate(30); mVideoRecorder.setVideoEncodingBitRate(8_000_000);
            mVideoRecorder.prepare(); mIsRecording.set(true); createCameraCaptureSession(); mVideoRecorder.start(); notifyWearVideoStatus(true);
        } catch (Exception e) { PhoneLog.e(TAG, "Record Start Failed", e); mIsRecording.set(false); }
    }

    private void stopVideoRecording() {
        if (!mIsRecording.get()) return;
        try {
            mVideoRecorder.stop(); mVideoRecorder.release(); mVideoRecorder = null; mIsRecording.set(false);
            createCameraCaptureSession(); if (mVideoFile != null) MediaScannerConnection.scanFile(this, new String[]{mVideoFile.getAbsolutePath()}, null, null);
            notifyWearVideoStatus(false);
        } catch (Exception ignored) {}
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
        if (mCachedNodeId == null) return;
        new Thread(() -> {
            try {
                CameraManager mgr = (CameraManager) getSystemService(Context.CAMERA_SERVICE); JSONArray arr = new JSONArray();
                for (String id : mgr.getCameraIdList()) {
                    CameraCharacteristics chars = mgr.getCameraCharacteristics(id);
                    StreamConfigurationMap map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                    if (map == null) continue;
                    Size[] sz = map.getOutputSizes(ImageFormat.JPEG); if (sz == null || sz.length == 0) continue;
                    if (Collections.max(Arrays.asList(sz), SIZE_BY_AREA).getWidth() < 1280) continue;
                    Integer f = chars.get(CameraCharacteristics.LENS_FACING); float[] fl = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
                    JSONObject o = new JSONObject(); o.put("id", id); 
                    Double mz = (double) Objects.requireNonNullElse(chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM), 1.0f);
                    o.put("maxZoom", mz);
                    String name = (f != null && f == CameraMetadata.LENS_FACING_FRONT) ? "前" : (fl != null && fl[0] < 3.0f ? "广" : (fl != null && fl[0] > 6.0f ? "长" : "主"));
                    o.put("name", name); arr.put(o);
                }
                Wearable.getMessageClient(this).sendMessage(mCachedNodeId, WEAR_MSG_PATH_CAMERA_LIST, arr.toString().getBytes(StandardCharsets.UTF_8));
            } catch (Exception ignored) {}
        }).start();
    }

    private void savePhoto(Image image) {
        try {
            ByteBuffer buf = image.getPlanes()[0].getBuffer(); byte[] d = new byte[buf.remaining()]; buf.get(d);
            File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "WearSync"); if (!dir.exists() && !dir.mkdirs()) PhoneLog.w(TAG, "Save Dir Create Failed");
            File f = new File(dir, "IMG_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".jpg");
            try (FileOutputStream fos = new FileOutputStream(f)) { fos.write(d); }
            if (getSharedPreferences("dndsync_prefs", Context.MODE_PRIVATE).getBoolean("save_location_enabled", false)) writeLocationExif(f);
            MediaScannerConnection.scanFile(this, new String[]{f.getAbsolutePath()}, null, null);
        } catch (Exception ignored) {}
    }

    private void writeLocationExif(File f) {
        try {
            LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return;
            Location bestLocation = null;
            for (String provider : lm.getProviders(true)) {
                Location l = lm.getLastKnownLocation(provider);
                if (l != null && (bestLocation == null || l.getAccuracy() < bestLocation.getAccuracy())) {
                    bestLocation = l;
                }
            }
            if (bestLocation != null) {
                ExifInterface exif = new ExifInterface(f.getAbsolutePath());
                exif.setGpsInfo(bestLocation);
                exif.saveAttributes();
                PhoneLog.d(TAG, "Location Saved");
            }
        } catch (Exception ignored) {}
    }

    private void setZoom(float z) { mCurrentZoom = Math.max(1.0f, Math.min(z, mMaxZoom)); startPreviewRequest(); }
    private void switchCamera(String id) { if (id != null) { mCameraId = id; stopStreamingAndRelease(); initCameraAndStartStreaming(); } }
    private void openChannelStream() { mChannelClient.openChannel(mCachedNodeId, WEAR_CHANNEL_PATH).addOnSuccessListener(c -> mChannelClient.getOutputStream(c).addOnSuccessListener(os -> { mDataOutputStream = new DataOutputStream(os); sendCameraListToWear(); try { mEncoderSurface = mEncoder.createInputSurface(); mEncoder.setCallback(new EncoderCallback(), mBgHandler); mEncoder.start(); startCameraHardware(); } catch (Exception ignored) {} })); }
    private void stopStreamingAndRelease() { mIsStreaming.set(false); stopVideoRecording(); if (mCaptureSession != null) { try { mCaptureSession.close(); } catch (Exception ignored) {} mCaptureSession = null; } if (mCameraDevice != null) { try { mCameraDevice.close(); } catch (Exception ignored) {} mCameraDevice = null; } if (mEncoder != null) { try { mEncoder.stop(); mEncoder.release(); } catch (Exception ignored) {} mEncoder = null; } if (mBgThread != null) { mBgThread.quitSafely(); mBgThread = null; } }
    private void startBackgroundThread() { if (mBgThread == null) { mBgThread = new HandlerThread("CamBg"); mBgThread.start(); mBgHandler = new Handler(mBgThread.getLooper()); } }
    private void startOrientationListener() { OrientationEventListener l = new OrientationEventListener(this) { @Override public void onOrientationChanged(int o) { if (o != ORIENTATION_UNKNOWN) mDeviceOrientation = o; } }; l.enable(); }
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
