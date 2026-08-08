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
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.view.Surface;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.google.android.gms.wearable.ChannelClient;
import com.google.android.gms.wearable.MessageClient;
import com.google.android.gms.wearable.Wearable;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

public class PhoneSyncCameraService extends Service {
    private static final String TAG = "WearSync_CameraSvc";

    // ==================== 常量定義 ====================
    public static final String ACTION_START_CAMERA = "cn.luke.wearsync.action.START_CAMERA";
    public static final String ACTION_STOP_CAMERA = "cn.luke.wearsync.action.STOP_CAMERA";
    public static final String WEAR_MSG_PATH_TAKE_PHOTO = "/camera/take_photo";
    public static final String WEAR_CHANNEL_PATH = "/wear_data_channel/camera";

    // 低延遲預覽參數
    private static final int PREVIEW_WIDTH = 320;
    private static final int PREVIEW_HEIGHT = 240;
    private static final int BIT_RATE = 300_000; // 300Kbps
    private static final int FRAME_RATE = 25;
    private static final int I_FRAME_INTERVAL = 1;

    // 高清拍照參數
    private static final int PHOTO_WIDTH = 1920;
    private static final int PHOTO_HEIGHT = 1080;

    // 通知相關
    private static final String CHANNEL_ID = "camera_service_channel";
    private static final int NOTIFICATION_ID = 101;

    // ==================== 核心組件 ====================
    private HandlerThread mBgThread;
    private Handler mBgHandler;
    private Handler mMainHandler;
    
    private CameraDevice mCameraDevice;
    private CameraCaptureSession mCaptureSession;
    private MediaCodec mEncoder;
    private Surface mEncoderSurface;
    private ImageReader mPhotoReader;
    
    private final AtomicBoolean mIsStreaming = new AtomicBoolean(false);
    private final AtomicBoolean mIsCameraOpened = new AtomicBoolean(false);
    
    private String mCachedNodeId;
    private ChannelClient mChannelClient;
    private OutputStream mChannelOutputStream;

    // ==================== 監聽器 ====================
    private final MessageClient.OnMessageReceivedListener mMessageListener = event -> {
        if (WEAR_MSG_PATH_TAKE_PHOTO.equals(event.getPath())) {
            PhoneLog.d(TAG, "📸 收到手錶端拍照指令，準備執行高清拍攝");
            captureHighResPhoto();
        }
    };

    private final ChannelClient.ChannelCallback mChannelListener = new ChannelClient.ChannelCallback() {
        @Override
        public void onChannelOpened(ChannelClient.Channel channel) {
            PhoneLog.d(TAG, "🔗 收到 Channel 打開回調, Path: " + channel.getPath());
        }

        @Override
        public void onChannelClosed(ChannelClient.Channel channel, int closeReason, int appSpecificErrorCode) {
            if (WEAR_CHANNEL_PATH.equals(channel.getPath())) {
                PhoneLog.d(TAG, "🔌 檢測到手錶端通道關閉 (Reason: " + closeReason + ", Code: " + appSpecificErrorCode + ")，準備停止服務");
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

        // 🎯 API 35 核心要求：前台服務必須指定 FOREGROUND_SERVICE_TYPE_CAMERA
        startForeground(
                NOTIFICATION_ID, 
                buildNotification(), 
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        );

        mChannelClient = Wearable.getChannelClient(this);
        
        // 註冊 Wearable 監聽器
        Wearable.getMessageClient(this).addListener(mMessageListener);
        Wearable.getChannelClient(this).registerChannelCallback(mChannelListener);
        
        PhoneLog.d(TAG, "✅ 相機同步服務 onCreate 成功，前台通知已啟動");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            PhoneLog.w(TAG, "⚠️ onStartCommand 收到 null Intent");
            return START_NOT_STICKY;
        }

        String action = intent.getAction();
        PhoneLog.d(TAG, "📩 收到 Intent 動作: " + action);

        if (ACTION_START_CAMERA.equals(action)) {
            // 🎯 核心節點獲取邏輯：優先讀 Intent，沒有則直接調用 WearSyncState 同步獲取
            String remoteNodeId = intent.getStringExtra("remote_node_id");
            if (remoteNodeId == null || remoteNodeId.isEmpty()) {
                remoteNodeId = WearSyncState.getNodeId(this);
            }

            if (remoteNodeId != null && !remoteNodeId.isEmpty()) {
                mCachedNodeId = remoteNodeId;
                PhoneLog.d(TAG, "📍 成功鎖定手錶 Node ID: " + mCachedNodeId);
            } else {
                PhoneLog.e(TAG, "❌ 無法獲取手錶 Node ID (WearSyncState 亦為空)，終止啟動流程");
                stopSelf();
                return START_NOT_STICKY;
            }

            if (!mIsStreaming.get()) {
                PhoneLog.d(TAG, "🚀 開始初始化相機並啟動推流流程");
                initCameraAndStartStreaming();
            } else {
                PhoneLog.w(TAG, "⚠️ 當前已在推流狀態中，忽略重複請求");
            }

        } else if (ACTION_STOP_CAMERA.equals(action)) {
            PhoneLog.d(TAG, "🛑 收到停止相機指令，清理資源並停止服務");
            stopStreamingAndRelease();
            stopSelf();
        }

        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        PhoneLog.d(TAG, "🛑 onDestroy 觸發，開始安全銷毀資源...");
        
        try {
            Wearable.getMessageClient(this).removeListener(mMessageListener);
            Wearable.getChannelClient(this).unregisterChannelCallback(mChannelListener);
            PhoneLog.d(TAG, "🧹 Wearable 監聽器注銷成功");
        } catch (Exception e) {
            PhoneLog.w(TAG, "⚠️ 注銷 Wearable 監聽器時發生異常: " + e.getMessage());
        }

        if (mMainHandler != null) {
            mMainHandler.removeCallbacksAndMessages(null);
        }

        stopStreamingAndRelease();
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
        PhoneLog.d(TAG, "✅ 相機同步服務已完全安全銷毀");
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ==================== 初始化與推流流程 ====================
    private void initCameraAndStartStreaming() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            PhoneLog.e(TAG, "❌ 缺少 CAMERA 權限，無法啟動相機");
            stopSelf();
            return;
        }

        mIsStreaming.set(true);
        startBackgroundThread();

        try {
            // 1. 配置 H.264 編碼器
            PhoneLog.d(TAG, "⚙️ [1/4] 配置 H.264 編碼器 (" + PREVIEW_WIDTH + "x" + PREVIEW_HEIGHT + ", " + BIT_RATE + "bps)");
            MediaFormat fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, PREVIEW_WIDTH, PREVIEW_HEIGHT);
            fmt.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE);
            fmt.setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE);
            fmt.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL);
            fmt.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            fmt.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR);

            mEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            mEncoder.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            mEncoderSurface = mEncoder.createInputSurface();
            mEncoder.setCallback(new EncoderCallback(), mBgHandler);
            PhoneLog.d(TAG, "⏸️ [1/4] 編碼器配置完成，等待建立 Channel 通道");

            // 2. 配置高清拍照 ImageReader
            PhoneLog.d(TAG, "📷 配置高清拍照 ImageReader (" + PHOTO_WIDTH + "x" + PHOTO_HEIGHT + ")");
            mPhotoReader = ImageReader.newInstance(PHOTO_WIDTH, PHOTO_HEIGHT, ImageFormat.JPEG, 2);
            mPhotoReader.setOnImageAvailableListener(reader -> {
                try (Image image = reader.acquireLatestImage()) {
                    if (image != null) {
                        savePhoto(image);
                    }
                } catch (Exception e) {
                    PhoneLog.e(TAG, "❌ 讀取拍照 Image 幀異常", e);
                }
            }, mBgHandler);

        } catch (Exception e) {
            PhoneLog.e(TAG, "❌ 初始化編碼器/ImageReader 失敗", e);
            stopStreamingAndRelease();
            stopSelf();
            return;
        }

        // 3. 發起 Channel 連接
        openChannelStream();
    }

    private void openChannelStream() {
        if (mCachedNodeId == null || mCachedNodeId.isEmpty()) {
            mCachedNodeId = WearSyncState.getNodeId(this);
        }

        if (mCachedNodeId == null || mCachedNodeId.isEmpty()) {
            PhoneLog.e(TAG, "❌ [2/4] 節點 ID 為空，取消 Channel 連接");
            stopStreamingAndRelease();
            stopSelf();
            return;
        }

        PhoneLog.d(TAG, "📡 [2/4] 正在發起 Channel 連接: Target=" + mCachedNodeId + ", Path=" + WEAR_CHANNEL_PATH);

        mChannelClient.openChannel(mCachedNodeId, WEAR_CHANNEL_PATH)
                .addOnSuccessListener(channel -> {
                    PhoneLog.d(TAG, "✅ [2/4] Channel 連接建立成功，獲取 OutputStream...");
                    mChannelClient.getOutputStream(channel)
                            .addOnSuccessListener(outputStream -> {
                                PhoneLog.d(TAG, "🎉 [2/4] OutputStream 就緒！開始啟動相機硬件與推流");
                                mChannelOutputStream = outputStream;
                                startCameraHardware();
                            })
                            .addOnFailureListener(e -> {
                                PhoneLog.e(TAG, "❌ 獲取 Channel OutputStream 失敗", e);
                                stopStreamingAndRelease();
                                stopSelf();
                            });
                })
                .addOnFailureListener(e -> {
                    PhoneLog.e(TAG, "❌ 打開 Channel 通道失敗", e);
                    stopStreamingAndRelease();
                    stopSelf();
                });
    }

    private void startCameraHardware() {
        CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        if (manager == null) {
            PhoneLog.e(TAG, "❌ 無法獲取 CameraManager");
            stopStreamingAndRelease();
            stopSelf();
            return;
        }

        try {
            String cameraId = manager.getCameraIdList()[0]; // 預設使用後置相機
            PhoneLog.d(TAG, "📷 [3/4] 正在開啟相機硬件, ID: " + cameraId);

            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                return;
            }

            manager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(@NonNull CameraDevice camera) {
                    mCameraDevice = camera;
                    mIsCameraOpened.set(true);
                    PhoneLog.d(TAG, "✅ [3/4] 相機硬件已成功開啟，建立 Session...");
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
            PhoneLog.e(TAG, "❌ 打開相機失敗", e);
            stopStreamingAndRelease();
            stopSelf();
        }
    }

    private void createCameraCaptureSession() {
        if (mCameraDevice == null || mEncoderSurface == null || mPhotoReader == null) {
            PhoneLog.e(TAG, "❌ 建立 Session 失敗：硬體或 Surface 未就緒");
            return;
        }

        try {
            List<OutputConfiguration> outputs = new ArrayList<>();
            outputs.add(new OutputConfiguration(mEncoderSurface));
            outputs.add(new OutputConfiguration(mPhotoReader.getSurface()));

            Executor executor = command -> mBgHandler.post(command);

            SessionConfiguration sessionConfig = new SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    outputs,
                    executor,
                   new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(@NonNull CameraCaptureSession session) {
                    // 🎯 保護 2：如果在配置完成前 Session/Service 已關閉，直接釋放 Session
                    if (!mIsStreaming.get() || mCameraDevice == null) {
                        PhoneLog.w(TAG, "⚠️ Session 配置完成時相機已關閉，關閉無效 Session");
                        try { session.close(); } catch (Exception ignored) {}
                        return;
                    }
            
                    mCaptureSession = session;
                    PhoneLog.d(TAG, "🎉 Camera CaptureSession 配置完成，啟動預覽推流！");
                    startPreviewRequest();
                }
            
                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                    PhoneLog.e(TAG, "❌ Session 配置失敗");
                    stopStreamingAndRelease();
                    stopSelf();
                }
            }
            
                    );

            mCameraDevice.createCaptureSession(sessionConfig);

        } catch (CameraAccessException e) {
            PhoneLog.e(TAG, "❌ 創建 CaptureSession 異常", e);
            stopStreamingAndRelease();
            stopSelf();
        }
    }

     private void startPreviewRequest() {
        // 🎯 保護 1：如果相機已經關閉或推流已終止，直接退出，不執行 capture
        if (!mIsStreaming.get() || mCameraDevice == null || mCaptureSession == null || mEncoderSurface == null) {
            PhoneLog.w(TAG, "⚠️ 相機已關閉或推流已停止，放棄發起預覽 Request");
            return;
        }
    
        try {
            CaptureRequest.Builder builder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            builder.addTarget(mEncoderSurface);
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
    
            mEncoder.start(); // 啟動編碼器
            mCaptureSession.setRepeatingRequest(builder.build(), null, mBgHandler);
            PhoneLog.d(TAG, "🚀 預覽 CaptureRequest 已成功提交！");
    
        } catch (IllegalStateException e) {
            PhoneLog.e(TAG, "⚠️ CameraDevice 已關閉，無法建立 CaptureRequest: " + e.getMessage());
        } catch (Exception e) {
            PhoneLog.e(TAG, "❌ 啟動預覽 Request 失敗", e);
            stopStreamingAndRelease();
            stopSelf();
        }
    }

    
    private void captureHighResPhoto() {
        if (mCameraDevice == null || mCaptureSession == null || mPhotoReader == null) {
            PhoneLog.w(TAG, "⚠️ 相機未就緒，無法執行拍照");
            return;
        }
        try {
            CaptureRequest.Builder builder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            builder.addTarget(mPhotoReader.getSurface());
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            builder.set(CaptureRequest.JPEG_QUALITY, (byte) 95);

            mCaptureSession.capture(builder.build(), null, mBgHandler);
            PhoneLog.d(TAG, "📸 高清拍照請求已提交給 Session");
        } catch (Exception e) {
            PhoneLog.e(TAG, "❌ 提交拍照請求失敗", e);
        }
    }

    private void savePhoto(Image image) {
        try {
            ByteBuffer buffer = image.getPlanes()[0].getBuffer();
            byte[] data = new byte[buffer.remaining()];
            buffer.get(data);
            PhoneLog.d(TAG, "✅ 高清照片捕獲成功，數據大小: " + data.length + " bytes");
        } catch (Exception e) {
            PhoneLog.e(TAG, "❌ 讀取照片數據失敗", e);
        }
    }

    // ==================== 資源清理與輔助函數 ====================
    private void startBackgroundThread() {
        if (mBgThread == null) {
            mBgThread = new HandlerThread("CameraBgThread");
            mBgThread.start();
            mBgHandler = new Handler(mBgThread.getLooper());
        }
    }

    private void stopStreamingAndRelease() {
        PhoneLog.d(TAG, "🧹 開始安全釋放相機與推流資源...");
        mIsStreaming.set(false);
        mIsCameraOpened.set(false);
    
        // 1. 先關閉 Session（底層仍然需要 mBgHandler 處理 onClosed 回調）
        if (mCaptureSession != null) {
            try {
                mCaptureSession.close();
                PhoneLog.d(TAG, "✅ CameraCaptureSession 已關閉");
            } catch (Exception e) {
                PhoneLog.w(TAG, "⚠️ 關閉 Session 異常: " + e.getMessage());
            }
            mCaptureSession = null;
        }
    
        // 2. 關閉 Camera 設備
        if (mCameraDevice != null) {
            try {
                mCameraDevice.close();
                PhoneLog.d(TAG, "✅ CameraDevice 已關閉");
            } catch (Exception e) {
                PhoneLog.w(TAG, "⚠️ 關閉 CameraDevice 異常: " + e.getMessage());
            }
            mCameraDevice = null;
        }
    
        // 3. 停止並釋放編碼器
        if (mEncoder != null) {
            try {
                mEncoder.stop();
                mEncoder.release();
                PhoneLog.d(TAG, "✅ MediaCodec 編碼器已釋放");
            } catch (Exception e) {
                PhoneLog.w(TAG, "⚠️ 釋放 MediaCodec 異常: " + e.getMessage());
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
    
        if (mChannelOutputStream != null) {
            try {
                mChannelOutputStream.close();
            } catch (Exception ignored) {}
            mChannelOutputStream = null;
        }
    
        // 4. 🔥 核心修復：最後才能銷毀背景線程与 Handler！
        if (mBgThread != null) {
            mBgThread.quitSafely();
            try {
                mBgThread.join();
                PhoneLog.d(TAG, "🧵 HandlerThread 已退出");
            } catch (InterruptedException e) {
                PhoneLog.w(TAG, "⚠️ 等待 HandlerThread 退出被打斷: " + e.getMessage());
            }
            mBgThread = null;
            mBgHandler = null; // ✅ 最後置空，防止 CallbackProxies 空指針
        }
    
        PhoneLog.d(TAG, "🎉 所有資源已安全清理完畢");
    }

    private void createNotificationChannel() {
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "相機同步", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("用於相機預覽與推流的前台服務通知通道");
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.createNotificationChannel(ch);
        }
    }

    private Notification buildNotification() {
        Intent notificationIntent = new Intent(this, PhoneSyncMainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("相機同步服務運行中")
                .setContentText("正在與手錶保持實時連接與推流...")
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
            ByteBuffer buffer = codec.getOutputBuffer(index);
            if (buffer != null && mChannelOutputStream != null && mIsStreaming.get()) {
                try {
                    byte[] data = new byte[info.size];
                    buffer.get(data);
                    mChannelOutputStream.write(data);
                    mChannelOutputStream.flush();
                } catch (IOException e) {
                    PhoneLog.e(TAG, "❌ 發送編碼數據失敗", e);
                }
            }
            codec.releaseOutputBuffer(index, false);
        }

        @Override
        public void onError(@NonNull MediaCodec codec, @NonNull MediaCodec.CodecException e) {
            PhoneLog.e(TAG, "❌ MediaCodec 異常", e);
        }

        @Override
        public void onOutputFormatChanged(@NonNull MediaCodec codec, @NonNull MediaFormat format) {}
    }
}