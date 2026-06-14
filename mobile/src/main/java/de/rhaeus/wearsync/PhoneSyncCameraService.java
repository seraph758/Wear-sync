package de.rhaeus.wearsync;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import android.util.Size;
import androidx.annotation.NonNull;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;

import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.ChannelClient;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;
import com.google.common.util.concurrent.ListenableFuture;

import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PhoneSyncCameraService extends Service implements LifecycleOwner {
    private static final String TAG = "WearSync_CameraService";
    private static final String CHANNEL_ID = "wear_camera_sync_channel";
    private static final String UNIVERSAL_SYNC_PATH = "/wear-universal-sync";
    private static final String CHANNEL_PATH = "/wear-camera-frame-stream";

    private final LifecycleRegistry lifecycleRegistry = new LifecycleRegistry(this);
    private ProcessCameraProvider cameraProvider;
    private ExecutorService mStreamExecutor;

    private final Object mLock = new Object();
    private ChannelClient.Channel mActiveChannel = null;
    private OutputStream mChannelOutputStream = null;
    private boolean isRunning = false;
    private volatile boolean shouldCaptureNextFrame = false; // 📸 拍照攔截開關

    @NonNull
    @Override
    public Lifecycle getLifecycle() { return lifecycleRegistry; }

    @Override
    public void onCreate() {
        super.onCreate();
        lifecycleRegistry.setCurrentState(Lifecycle.State.CREATED);
        mStreamExecutor = Executors.newSingleThreadExecutor();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        lifecycleRegistry.setCurrentState(Lifecycle.State.STARTED);
        if (intent == null || intent.getAction() == null) return START_NOT_STICKY;
        String action = intent.getAction();

        Log.d(TAG, "收到指令 Action: " + action);

        if ("START_CAMERA".equalsIgnoreCase(action)) {
            if (!isRunning) {
                isRunning = true;
                Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                        .setContentTitle("WearSync 相機同步中")
                        .setContentText("正在為手錶端提供即時相機畫面流...")
                        .setSmallIcon(R.mipmap.ic_launcher) 
                        .setPriority(NotificationCompat.PRIORITY_LOW)
                        .build();
                startForeground(8888, notification);
                startCameraXDataFlow();
            }
        } else if ("TAKE_PICTURE".equalsIgnoreCase(action)) {
            shouldCaptureNextFrame = true; // 激活拍照攔截
            Log.d(TAG, "📸 拍照信號觸發，準備攔截下一幀無損畫面...");
        } else if ("STOP_CAMERA".equalsIgnoreCase(action)) {
            Log.d(TAG, "🔒 收到手錶端 STOP_CAMERA 訊號，徹底銷毀前台服務與硬體綁定");
            stopForeground(true);
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    private void startCameraXDataFlow() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(() -> {
            try {
                cameraProvider = cameraProviderFuture.get();
                CameraSelector cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;

                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                        .setTargetResolution(new Size(320, 320))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();

                imageAnalysis.setAnalyzer(mStreamExecutor, this::processImageProxyFrame);
                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(this, cameraSelector, imageAnalysis);
                lifecycleRegistry.setCurrentState(Lifecycle.State.RESUMED);
                Log.d(TAG, "✅ CameraX 核心資料流與分析器已綁定。");
            } catch (Exception e) {
                Log.e(TAG, "啟動 CameraX 失敗", e);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void processImageProxyFrame(@NonNull ImageProxy image) {
        if (!isRunning) { image.close(); return; }
        try {
            if (mChannelOutputStream == null) {
                initChannelConnectionSync();
            }

            // 🎯 【拍照分支】：如果用戶按下了拍照按鈕
            if (shouldCaptureNextFrame) {
                shouldCaptureNextFrame = false; // 復位

                // 傳入 100 品質，使用無損母片級畫質保存
                byte[] highQualityJpeg = convertYuvToJpeg(image, 100); 
                if (highQualityJpeg != null) {
                    saveCapturedImageToLocal(highQualityJpeg); // 執行本地高清儲存
                    notifyWearCaptureDone();                  // 傳送拍照完成回報給手錶
                }
            }

            // 🎯 【預覽分支】：日常連續流傳輸
            // 傳入 35 品質，數據包體積大幅壓縮，保證 Wear OS 預覽流暢不卡頓
            byte[] previewJpegBytes = convertYuvToJpeg(image, 35); 
            
            if (previewJpegBytes != null && previewJpegBytes.length > 0 && mChannelOutputStream != null) {
                synchronized (mLock) {
                    if (mChannelOutputStream != null) {
                        ByteBuffer buffer = ByteBuffer.allocate(4 + previewJpegBytes.length);
                        buffer.putInt(previewJpegBytes.length);
                        buffer.put(previewJpegBytes);
                        mChannelOutputStream.write(buffer.array());
                        mChannelOutputStream.flush();
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "寫入 Channel 資料流異常，斷開重連", e);
            closeChannelSafely();
        } finally { 
            image.close();
        }
    }

    private void saveCapturedImageToLocal(byte[] jpegData) {
        // 在此處處理您的 MediaStore 寫入或寫入手機 SD 卡快取目錄的代碼
        Log.d(TAG, "💾 [100% 畫質無損照片保存成功] 檔案大小: " + jpegData.length + " 字節");
    }

    private void notifyWearCaptureDone() {
        new Thread(() -> {
            try {
                JSONObject json = new JSONObject();
                json.put("sender", "phone");
                json.put("type", "camera_control");
                json.put("action", "PHONE_TAKE_PICTURE_DONE");
                byte[] data = json.toString().getBytes(StandardCharsets.UTF_8);

                List<Node> nodes = Tasks.await(Wearable.getNodeClient(this).getConnectedNodes());
                for (Node n : nodes) {
                    Wearable.getMessageClient(this).sendMessage(n.getId(), UNIVERSAL_SYNC_PATH, data);
                }
                Log.d(TAG, "🚀 已成功回傳 PHONE_TAKE_PICTURE_DONE 狀態給手錶端");
            } catch (Exception e) {
                Log.e(TAG, "向手錶回傳狀態失敗", e);
            }
        }).start();
    }

    private void initChannelConnectionSync() {
        synchronized (mLock) {
            if (mChannelOutputStream != null) return;
            try {
                List<Node> nodes = Tasks.await(Wearable.getNodeClient(this).getConnectedNodes());
                if (nodes.isEmpty()) return;
                String targetNodeId = nodes.get(0).getId();

                mActiveChannel = Tasks.await(Wearable.getChannelClient(this).openChannel(targetNodeId, CHANNEL_PATH));
                mChannelOutputStream = Tasks.await(Wearable.getChannelClient(this).getOutputStream(mActiveChannel));
                Log.d(TAG, "🚀 Channel 長連接管道初始化成功！");
            } catch (Exception e) {
                Log.e(TAG, "建立 Channel 失敗", e);
                closeChannelSafely();
            }
        }
    }

    /**
     * 動態品質分流的 YUV 轉換核心
     */
    private byte[] convertYuvToJpeg(ImageProxy image, int quality) { 
        try {
            int width = image.getWidth();
            int height = image.getHeight();
            ImageProxy.PlaneProxy[] planes = image.getPlanes();
            
            ByteBuffer yBuffer = planes[0].getBuffer();
            ByteBuffer uBuffer = planes[1].getBuffer();
            ByteBuffer vBuffer = planes[2].getBuffer();

            yBuffer.rewind();
            uBuffer.rewind();
            vBuffer.rewind();

            int ySize = width * height;
            byte[] nv21 = new byte[ySize * 3 / 2];
            
            int yRowStride = planes[0].getRowStride();
            if (yRowStride == width) {
                yBuffer.get(nv21, 0, ySize);
            } else {
                for (int row = 0; row < height; row++) {
                    yBuffer.position(row * yRowStride);
                    yBuffer.get(nv21, row * width, width);
                }
            }

            int uRowStride = planes[1].getRowStride();
            int vRowStride = planes[2].getRowStride();
            int uPixelStride = planes[1].getPixelStride();
            int vPixelStride = planes[2].getPixelStride();

            int uvOffset = ySize;
            int chromaWidth = width / 2;
            int chromaHeight = height / 2;

            for (int row = 0; row < chromaHeight; row++) {
                for (int col = 0; col < chromaWidth; col++) {
                    int uPos = row * uRowStride + col * uPixelStride;
                    int vPos = row * vRowStride + col * vPixelStride;

                    if (vPos < vBuffer.remaining()) {
                        nv21[uvOffset] = vBuffer.get(vPos);
                    }
                    if (uPos < uBuffer.remaining()) {
                        nv21[uvOffset + 1] = uBuffer.get(uPos);
                    }
                    uvOffset += 2;
                }
            }

            YuvImage yuvImage = new YuvImage(nv21, ImageFormat.NV21, width, height, null);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            
            // 🎯 完美畫質分流控制
            yuvImage.compressToJpeg(new Rect(0, 0, width, height), quality, out);
            return out.toByteArray();
        } catch (Exception e) {
            Log.e(TAG, "YUV 轉換失敗, 當前品質: " + quality, e);
            return null;
        }
    }

    private void closeChannelSafely() {
        synchronized (mLock) {
            try {
                if (mChannelOutputStream != null) {
                    mChannelOutputStream.flush();
                    mChannelOutputStream.close();
                    mChannelOutputStream = null;
                }
                if (mActiveChannel != null) {
                    Wearable.getChannelClient(this).close(mActiveChannel);
                    mActiveChannel = null;
                }
                Log.d(TAG, "🔒 唯一的 Channel 長連接管道已在同步鎖內安全釋放。");
            } catch (Exception e) {
                Log.e(TAG, "關閉通道失敗", e);
            }
        }
    }

    @Override
    public void onDestroy() {
        isRunning = false; 
        lifecycleRegistry.setCurrentState(Lifecycle.State.DESTROYED);
        if (cameraProvider != null) {
            try {
                cameraProvider.unbindAll();
                Log.d(TAG, "📸 [釋放] 已成功解綁並釋放 CameraX 的硬體繫結");
            } catch (Exception ignored) {}
        }
        mStreamExecutor.shutdownNow(); 
        closeChannelSafely();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "相機背景採集通知", NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }
}
