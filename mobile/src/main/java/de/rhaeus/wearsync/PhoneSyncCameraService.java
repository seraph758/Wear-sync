package de.rhaeus.wearsync;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import android.util.Size;
import android.view.Surface;
import android.view.WindowManager;
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
        } else if ("STOP_CAMERA".equalsIgnoreCase(action)) {
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
            if (mChannelOutputStream != null) {
                byte[] jpegBytes = convertYuvToJpeg(image);
                if (jpegBytes != null && jpegBytes.length > 0) {
                    synchronized (mLock) {
                        if (mChannelOutputStream != null) {
                            ByteBuffer buffer = ByteBuffer.allocate(4 + jpegBytes.length);
                            buffer.putInt(jpegBytes.length);
                            buffer.put(jpegBytes);
                            mChannelOutputStream.write(buffer.array());
                            mChannelOutputStream.flush();
                        }
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

    private byte[] convertYuvToJpeg(ImageProxy image) {
        try {
            int width = image.getWidth();
            int height = image.getHeight();
            
            // 🎯 核心新增：直接從 CameraX 的 image 中動態拿到相機傳感器相對於當前屏幕的物理旋轉角
            int sensorRotationDegrees = image.getImageInfo().getRotationDegrees();
            
            ImageProxy.PlaneProxy[] planes = image.getPlanes();
            
            ByteBuffer yBuffer = planes[0].getBuffer();
            ByteBuffer uBuffer = planes[1].getBuffer();
            ByteBuffer vBuffer = planes[2].getBuffer();

            int ySize = yBuffer.remaining();
            byte[] nv21 = new byte[width * height * 3 / 2];
            
            yBuffer.get(nv21, 0, ySize);

            int vRowStride = planes[2].getRowStride();
            int vPixelStride = planes[2].getPixelStride();
            
            int uvOffset = ySize;
            int uRemaining = uBuffer.remaining();
            int vRemaining = vBuffer.remaining();

            for (int row = 0; row < height / 2; row++) {
                for (int col = 0; col < width / 2; col++) {
                    int vIdx = row * vRowStride + col * vPixelStride;
                    if (vIdx < vRemaining) {
                        nv21[uvOffset] = vBuffer.get(vIdx);
                    }
                    if (vIdx + 1 < uRemaining) {
                        nv21[uvOffset + 1] = uBuffer.get(vIdx); 
                    }
                    uvOffset += 2;
                }
            }

            YuvImage yuvImage = new YuvImage(nv21, ImageFormat.NV21, width, height, null);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            // 壓縮成 JPEG 緩衝數據
            yuvImage.compressToJpeg(new Rect(0, 0, width, height), 65, out);
            byte[] rawJpeg = out.toByteArray();

            // 🎯 核心旋轉操控區：根據手機物理擺放動態計算並旋轉
            return rotateImagePayloadIfNeed(rawJpeg, sensorRotationDegrees);

        } catch (Exception e) {
            Log.e(TAG, "YUV 轉換 JPEG 並旋轉失敗", e);
            return null;
        }
    }

    /**
     * 🎯 核心新增方法：由手機端代勞完成所有的畫面倒置、旋轉補償，減輕手錶端負擔。
     */
    private byte[] rotateImagePayloadIfNeed(byte[] originalData, int cameraSensorRotation) {
        try {
            // 1. 獲取手機屏幕當前的抗鋸齒物理旋轉狀態（用戶是橫拿還是豎拿）
            WindowManager windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
            if (windowManager == null) return originalData;
            int displayRotation = windowManager.getDefaultDisplay().getRotation();
            
            int deviceDegrees = 0;
            switch (displayRotation) {
                case Surface.ROTATION_0: deviceDegrees = 0; break;
                case Surface.ROTATION_90: deviceDegrees = 90; break;
                case Surface.ROTATION_180: deviceDegrees = 180; break;
                case Surface.ROTATION_270: deviceDegrees = 270; break;
            }

            // 2. 結合 CameraX 的 sensor 夾角動態算出應旋轉的最終絕對角度
            int finalRotateDegree = (cameraSensorRotation - deviceDegrees + 360) % 360;

            // 如果剛好是 0 度（正向），則不需要消耗 CPU 重新矩陣繪製，直接投遞
            if (finalRotateDegree == 0) {
                return originalData;
            }

            // 3. 利用 Matrix 在手機後台內存中將畫面代勞旋轉好
            Bitmap srcBitmap = BitmapFactory.decodeByteArray(originalData, 0, originalData.length);
            if (srcBitmap == null) return originalData;

            Matrix matrix = new Matrix();
            matrix.postRotate(finalRotateDegree);
            
            Bitmap rotatedBitmap = Bitmap.createBitmap(
                    srcBitmap, 0, 0, srcBitmap.getWidth(), srcBitmap.getHeight(), matrix, true
            );
            
            // 4. 將旋轉正向後的 Bitmap 重新壓回 JPEG 流
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            rotatedBitmap.compress(Bitmap.CompressFormat.JPEG, 65, baos);
            
            // 5. 垃圾回收，防止內存溢出 (OOM)
            srcBitmap.recycle();
            rotatedBitmap.recycle();
            
            return baos.toByteArray();
        } catch (Exception e) {
            Log.e(TAG, "手機端後台代勞圖像矩陣旋轉失敗，採用原圖降位發送", e);
            return originalData;
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
                Log.d(TAG, "🔒 Channel 長連接管道安全釋放。");
            } catch (Exception e) {
                Log.e(TAG, "關閉通道失敗", e);
            }
        }
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "🛑 Camera 采集服务正在执行 onDestroy 销毁流程...");
        isRunning = false; 
        lifecycleRegistry.setCurrentState(Lifecycle.State.DESTROYED);
        
        if (cameraProvider != null) {
            cameraProvider.unbindAll();
        }
        if (mStreamExecutor != null) {
            mStreamExecutor.shutdownNow(); 
        }
        
        closeChannelSafely();

        // 🎯 【核心新增联动】：当相机采集服务下线时，强制通知主 Activity 卸载屏幕长亮锁并自杀！
        PhoneSyncMainActivity.closeAndReleaseScreenLock();

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
