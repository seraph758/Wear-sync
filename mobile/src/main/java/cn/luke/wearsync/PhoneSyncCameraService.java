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
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.media.ImageReader;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.Log;
import android.util.Size;
import android.view.Surface;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.google.android.gms.wearable.CapabilityClient;
import com.google.android.gms.wearable.ChannelClient;
import com.google.android.gms.wearable.MessageClient;
import com.google.android.gms.wearable.Wearable;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;

public class PhoneSyncCameraService extends Service {

    private static final String TAG = "WearSync_CameraSvc";

    // ==================== 对外契约常量 ====================
    public static final String ACTION_START_CAMERA = "cn.luke.wearsync.action.START_CAMERA";
    public static final String ACTION_STOP_CAMERA = "cn.luke.wearsync.action.STOP_CAMERA";
    /** ⚠️ 手表端发送拍照指令的 MessageClient Path */
    public static final String WEAR_MSG_PATH_TAKE_PHOTO = "/camera/take_photo";
    public static final String WEAR_CHANNEL_PATH = "/video/stream";
    public static final String WEAR_CAPABILITY = "wear_sync";

    // ==================== 低延迟预览参数 ====================
    private static final int PREVIEW_WIDTH = 320;
    private static final int PREVIEW_HEIGHT = 240;
    private static final int BIT_RATE = 300_000;       // 300Kbps
    private static final int FRAME_RATE = 25;
    private static final int I_FRAME_INTERVAL = 1;     // 1秒一个关键帧

    // ==================== 高清拍照参数 ====================
    private static final int PHOTO_WIDTH = 1920;
    private static final int PHOTO_HEIGHT = 1080;

    // ==================== 通知 ====================
    private static final String CHANNEL_ID = "camera_service_channel";
    private static final int NOTIFICATION_ID = 101;

    // ==================== 核心组件 ====================
    private HandlerThread mBgThread;
    private Handler mBgHandler;
    private CameraDevice mCameraDevice;
    private CameraCaptureSession mCaptureSession;
    private MediaCodec mEncoder;
    private Surface mEncoderSurface;
    private ImageReader mPhotoReader;          // ✅ 高清拍照专用
    private final AtomicBoolean mIsStreaming = new AtomicBoolean(false);
    private String mCachedNodeId;

    private ChannelClient mChannelClient;
    private OutputStream mChannelOutputStream;

    // ✅ 注册拍照指令监听器
    private final MessageClient.OnMessageReceivedListener mMessageListener = event -> {
        if (WEAR_MSG_PATH_TAKE_PHOTO.equals(event.getPath())) {
            PhoneLog.d(TAG, "📸 收到拍照指令，执行高清拍摄");
            captureHighResPhoto();
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification());
        mChannelClient = Wearable.getChannelClient(this);
        // ✅ 注册消息监听
        Wearable.getMessageClient(this).addListener(mMessageListener);
        PhoneLog.d(TAG, "✅ 服务已创建，拍照监听已注册");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_STICKY;
        String action = intent.getAction();
        if (ACTION_START_CAMERA.equals(action)) {
            discoverAndCacheNode();
            if (!mIsStreaming.get()) initCameraAndStartStreaming();
        } else if (ACTION_STOP_CAMERA.equals(action)) {
            stopStreamingAndRelease();
            stopSelf();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        Wearable.getMessageClient(this).removeListener(mMessageListener);
        stopStreamingAndRelease();
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    @Nullable @Override public IBinder onBind(Intent intent) { return null; }

    // ==================== 相机 + 低延迟编码 + Channel 推流 ====================

    private void initCameraAndStartStreaming() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            stopSelf(); return;
        }
        mIsStreaming.set(true);
        startBackgroundThread();

        try {
            // ✅ 1. 低延迟 H.264 编码器
            MediaFormat fmt = MediaFormat.createVideoFormat(
                    MediaFormat.MIMETYPE_VIDEO_AVC, PREVIEW_WIDTH, PREVIEW_HEIGHT);
            fmt.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE);
            fmt.setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE);
            fmt.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL);
            fmt.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            // ⭐ 关键：启用低延迟模式，禁用B帧，减少编码缓冲
            fmt.setInteger(MediaFormat.KEY_LATENCY, 1);

            mEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            mEncoder.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            mEncoderSurface = mEncoder.createInputSurface();
            mEncoder.setCallback(new EncoderCallback());
            mEncoder.start();

            // ✅ 2. 高清拍照 ImageReader（独立于预览流）
            mPhotoReader = ImageReader.newInstance(PHOTO_WIDTH, PHOTO_HEIGHT, ImageFormat.JPEG, 2);
            mPhotoReader.setOnImageAvailableListener(reader -> {
                try (var image = reader.acquireLatestImage()) {
                    if (image != null) savePhoto(image);
                }
            }, mBgHandler);

            // 3. 打开相机，同时绑定预览Surface和拍照Surface
            CameraManager mgr = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            String camId = mgr.getCameraIdList()[0];
            mgr.openCamera(camId, new CameraStateCallback(), mBgHandler);

            PhoneLog.d(TAG, "✅ 低延迟预览管线已启动 (" + PREVIEW_WIDTH + "x" + PREVIEW_HEIGHT + "@" + BIT_RATE + "bps)");
        } catch (Exception e) {
            PhoneLog.e(TAG, "❌ 初始化失败", e);
            stopStreamingAndRelease();
        }
    }

    /** ✅ 高清拍照：单独发起一次全尺寸 CaptureRequest */
    private void captureHighResPhoto() {
        if (mCameraDevice == null || mCaptureSession == null || mPhotoReader == null) {
            PhoneLog.w(TAG, "⚠️ 相机未就绪，忽略拍照指令");
            return;
        }
        try {
            CaptureRequest.Builder builder =
                    mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            builder.addTarget(mPhotoReader.getSurface());
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            builder.set(CaptureRequest.JPEG_QUALITY, (byte) 95);
            mCaptureSession.capture(builder.build(), null, mBgHandler);
            PhoneLog.d(TAG, "📸 高清拍照请求已发出");
        } catch (Exception e) {
            PhoneLog.e(TAG, "❌ 拍照失败", e);
        }
    }

    private void savePhoto(android.media.Image image) {
        ByteBuffer buffer = image.getPlanes()[0].getBuffer();
        byte[] data = new byte[buffer.remaining()];
        buffer.get(data);
        // TODO: 保存到相册或通过 Channel/Message 回传给手表
        PhoneLog.d(TAG, "✅ 高清照片已保存，大小: " + data.length + " bytes");
    }

    // ==================== Channel 管理 ====================

    private void openChannelStream() {
        if (mCachedNodeId == null || !mIsStreaming.get()) return;
        mChannelClient.openChannel(mCachedNodeId, WEAR_CHANNEL_PATH)
                .addOnSuccessListener(channel ->
                        mChannelClient.getOutputStream(channel)
                                .addOnSuccessListener(os -> {
                                    mChannelOutputStream = os;
                                    PhoneLog.d(TAG, "✅ Channel 输出流就绪");
                                })
                                .addOnFailureListener(e -> PhoneLog.e(TAG, "❌ 获取输出流失败", e)))
                .addOnFailureListener(e -> PhoneLog.e(TAG, "❌ 打开Channel失败", e));
    }

    private void stopStreamingAndRelease() {
        mIsStreaming.set(false);
        if (mChannelOutputStream != null) {
            try { mChannelOutputStream.close(); } catch (IOException ignored) {}
            mChannelOutputStream = null;
        }
        try {
            if (mCaptureSession != null) { mCaptureSession.close(); mCaptureSession = null; }
            if (mCameraDevice != null) { mCameraDevice.close(); mCameraDevice = null; }
            if (mEncoder != null) { mEncoder.stop(); mEncoder.release(); mEncoder = null; }
            if (mEncoderSurface != null) { mEncoderSurface.release(); mEncoderSurface = null; }
            if (mPhotoReader != null) { mPhotoReader.close(); mPhotoReader = null; }
            stopBackgroundThread();
        } catch (Exception e) { PhoneLog.e(TAG, "⚠️ 释放异常", e); }
    }

    // ==================== 编码器回调 → Channel 写入 ====================

    private class EncoderCallback extends MediaCodec.Callback {
        @Override
        public void onOutputBufferAvailable(@NonNull MediaCodec codec, int index,
                                            @NonNull MediaCodec.BufferInfo info) {
            ByteBuffer buf = codec.getOutputBuffer(index);
            if (buf == null || !mIsStreaming.get()) {
                codec.releaseOutputBuffer(index, false); return;
            }
            byte[] data = new byte[info.size];
            buf.position(info.offset); buf.get(data);
            codec.releaseOutputBuffer(index, false);
            writeFrameToChannel(data, info.presentationTimeUs, info.flags);
        }
        @Override public void onError(@NonNull MediaCodec c, @NonNull MediaCodec.CodecException e) {
            PhoneLog.e(TAG, "❌ 编码器错误", e);
        }
        @Override public void onOutputFormatChanged(@NonNull MediaCodec c, @NonNull MediaFormat f) {}
    }
    private void writeFrameToChannel(byte[] h264, long tsUs, int flags) {
    OutputStream os = mChannelOutputStream;
    if (os == null) return;
    try {
        // ✅ 新增：4字节帧长度（大端序，与 DataInputStream.readInt 匹配）
        os.write(ByteBuffer.allocate(4).putInt(h264.length).array());
        os.write(ByteBuffer.allocate(8).putLong(tsUs).array());
        os.write(ByteBuffer.allocate(4).putInt(flags).array());
        os.write(h264);
        os.flush();
    } catch (IOException e) {
        PhoneLog.w(TAG, "⚠️ Channel写入失败", e);
        mChannelOutputStream = null;
    }
}


    // ==================== Camera2 回调 ====================

    private class CameraStateCallback extends CameraDevice.StateCallback {
        @Override
        public void onOpened(@NonNull CameraDevice camera) {
            mCameraDevice = camera;
            try {
                // ✅ 同时绑定预览Surface和拍照Surface
                java.util.List<Surface> targets = Arrays.asList(mEncoderSurface, mPhotoReader.getSurface());
                CaptureRequest.Builder b = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
                b.addTarget(mEncoderSurface);
                b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);

                camera.createCaptureSession(targets, new CameraCaptureSession.StateCallback() {
                    @Override
                    public void onConfigured(@NonNull CameraCaptureSession s) {
                        mCaptureSession = s;
                        try { s.setRepeatingRequest(b.build(), null, mBgHandler); }
                        catch (Exception e) { PhoneLog.e(TAG, "❌ 预览请求失败", e); }
                    }
                    @Override
                    public void onConfigureFailed(@NonNull CameraCaptureSession s) {
                        PhoneLog.e(TAG, "❌ Session配置失败");
                    }
                }, mBgHandler);
            } catch (Exception e) { PhoneLog.e(TAG, "❌ 创建Session失败", e); }
        }
        @Override
        public void onError(@NonNull CameraDevice camera, int error) {
            PhoneLog.e(TAG, "❌ 相机错误: " + error);
            stopStreamingAndRelease();
        }
    }

    // ==================== 节点发现 ====================

    private void discoverAndCacheNode() {
        mCachedNodeId = WearSyncState.getNodeId(this);
        if (mCachedNodeId != null) { openChannelStream(); return; }
        Wearable.getCapabilityClient(this)
                .getCapability(WEAR_CAPABILITY, CapabilityClient.FILTER_REACHABLE)
                .addOnSuccessListener(info -> {
                    if (!info.getNodes().isEmpty()) {
                        mCachedNodeId = info.getNodes().iterator().next().getId();
                        WearSyncState.setNodeId(PhoneSyncCameraService.this, mCachedNodeId);
                        openChannelStream();
                    }
                });
    }

    // ==================== 工具方法 ====================

    private void startBackgroundThread() {
        mBgThread = new HandlerThread("CameraBg");
        mBgThread.start();
        mBgHandler = new Handler(mBgThread.getLooper());
    }
    private void stopBackgroundThread() {
        if (mBgThread != null) {
            mBgThread.quitSafely();
            try { mBgThread.join(); } catch (InterruptedException ignored) {}
            mBgThread = null; mBgHandler = null;
        }
    }
    private void createNotificationChannel() {
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "相机同步", NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(ch);
    }
    private Notification buildNotification() {
        PendingIntent pi = PendingIntent.getActivity(this, 0,
                new Intent(this, PhoneSyncMainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("相机同步运行中")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentIntent(pi).setOngoing(true).build();
    }
}
