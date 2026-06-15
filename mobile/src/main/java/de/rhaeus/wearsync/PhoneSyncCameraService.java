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
import androidx.annotation.NonNull;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.ImageCaptureException;
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
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PhoneSyncCameraService extends Service implements LifecycleOwner {
    private static final String TAG = "WearSync_CameraService";
    private static final String CHANNEL_ID = "wear_camera_sync_channel";
    private static final int NOTIFICATION_ID = 8848;

    private final LifecycleRegistry lifecycleRegistry = new LifecycleRegistry(this);
    private ProcessCameraProvider cameraProvider;
    private ImageCapture imageCapture;
    private ExecutorService mStreamExecutor;
    private final Object mChannelLock = new Object();

    private ChannelClient.Channel mActiveChannel = null;
    private OutputStream mChannelOutputStream = null;
    private volatile boolean isRunning = false;
    private long mLastFrameTime = 0;

    @NonNull
    @Override
    public Lifecycle getLifecycle() { return lifecycleRegistry; }

    @Override
    public void onCreate() {
        super.onCreate();
        lifecycleRegistry.setCurrentState(Lifecycle.State.INITIALIZED);
        mStreamExecutor = Executors.newSingleThreadExecutor();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        String action = intent.getAction();
        Log.d(TAG, "收到指令 Action: " + action);

        if ("START_CAMERA".equalsIgnoreCase(action)) {
            if (!isRunning) {
                isRunning = true;
                lifecycleRegistry.setCurrentState(Lifecycle.State.STARTED);
                lifecycleRegistry.setCurrentState(Lifecycle.State.RESUMED);

                Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                        .setContentTitle("手錶相機助手")
                        .setContentText("正在同步手機相機畫面至手錶...")
                        .setSmallIcon(android.R.drawable.ic_menu_camera)
                        .setPriority(NotificationCompat.PRIORITY_LOW)
                        .build();
                startForeground(NOTIFICATION_ID, notification);

                setupAndBindCamera();
                setupChannelPipeline();
            }
        } else if ("TAKE_PICTURE".equalsIgnoreCase(action)) {
            performCaptureAction();
        } else if ("STOP_CAMERA".equalsIgnoreCase(action)) {
            stopSelf();
        }

        return START_NOT_STICKY;
    }

    private void setupAndBindCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(() -> {
            try {
                cameraProvider = cameraProviderFuture.get();

                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                        .setTargetResolution(new Size(320, 240))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();

                imageAnalysis.setAnalyzer(mStreamExecutor, this::processCameraFrame);

                imageCapture = new ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .build();

                CameraSelector cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;

                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(this, cameraSelector, imageAnalysis, imageCapture);
                Log.d(TAG, "📸 [CameraX] 成功掛載核心生命週期");

            } catch (Exception e) {
                Log.e(TAG, "初始化 CameraX 失敗", e);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void processCameraFrame(@NonNull ImageProxy image) {
        long now = System.currentTimeMillis();
        if (now - mLastFrameTime < 65 || !isRunning) {
            image.close();
            return;
        }
        mLastFrameTime = now;

        try {
            byte[] jpegBytes = convertYuvToJpeg(image);
            image.close();

            if (jpegBytes == null) return;

            // 🎯 【同步大提權接力】：將解碼出來的相機幀傳遞回主 Activity 預覽小窗即時呈現
            PhoneSyncMainActivity mainActivity = PhoneSyncMainActivity.getInstance();
            if (mainActivity != null) {
                Bitmap previewBitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.length);
                if (previewBitmap != null) {
                    // 本地小窗通常需要順時針旋轉 90 度以適應直屏方向
                    Matrix matrix = new Matrix();
                    matrix.postRotate(90f);
                    Bitmap rotatedPreview = Bitmap.createBitmap(previewBitmap, 0, 0, previewBitmap.getWidth(), previewBitmap.getHeight(), matrix, true);
                    mainActivity.updateLocalPreview(rotatedPreview);
                }
            }

            // 同步向手錶發送流數據
            synchronized (mChannelLock) {
                if (mChannelOutputStream != null && isRunning) {
                    int length = jpegBytes.length;
                    byte[] lengthBuffer = new byte[4];
                    lengthBuffer[0] = (byte) ((length >> 24) & 0xFF);
                    lengthBuffer[1] = (byte) ((length >> 16) & 0xFF);
                    lengthBuffer[2] = (byte) ((length >> 8) & 0xFF);
                    lengthBuffer[3] = (byte) (length & 0xFF);

                    mChannelOutputStream.write(lengthBuffer);
                    mChannelOutputStream.write(jpegBytes);
                    mChannelOutputStream.flush();
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "傳輸影像幀失敗", e);
        }
    }

    private byte[] convertYuvToJpeg(ImageProxy image) {
        try {
            ImageProxy.PlaneProxy[] planes = image.getPlanes();
            ByteBuffer yBuffer = planes[0].getBuffer();
            ByteBuffer uBuffer = planes[1].getBuffer();
            ByteBuffer vBuffer = planes[2].getBuffer();

            int ySize = yBuffer.remaining();
            int uSize = uBuffer.remaining();
            int vSize = vBuffer.remaining();

            byte[] nv21 = new byte[ySize + uSize + vSize];

            yBuffer.get(nv21, 0, ySize);
            vBuffer.get(nv21, ySize, vSize);
            uBuffer.get(nv21, ySize + vSize, uSize);

            YuvImage yuvImage = new YuvImage(nv21, ImageFormat.NV21, image.getWidth(), image.getHeight(), null);
            ByteArrayOutputStream os = new ByteArrayOutputStream();
            // 用 35 畫質壓縮發送給手錶，極大緩解藍牙同步帶寬壓力
            yuvImage.compressToJpeg(new Rect(0, 0, image.getWidth(), image.getHeight()), 35, os);
            return os.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    private void performCaptureAction() {
        if (imageCapture == null) {
            Log.w(TAG, "⚠️ 拍照失敗: ImageCapture 尚未就緒");
            return;
        }

        File outputDir = getExternalFilesDir(null);
        File photoFile = new File(outputDir, "WearSync_" + System.currentTimeMillis() + ".jpg");

        ImageCapture.OutputFileOptions outputOptions = new ImageCapture.OutputFileOptions.Builder(photoFile).build();

        Log.d(TAG, "📸 正在使用 100 滿分純淨畫質寫入硬盤: " + photoFile.getAbsolutePath());
        imageCapture.takePicture(outputOptions, ContextCompat.getMainExecutor(this), new ImageCapture.OnImageSavedCallback() {
            @Override
            public void onImageSaved(@NonNull ImageCapture.OutputFileResults results) {
                Log.d(TAG, "🎉 照片保存成功: " + photoFile.getAbsolutePath());
                notifyWearCaptureDone();
            }

            @Override
            public void onError(@NonNull ImageCaptureException exception) {
                Log.e(TAG, "圖片落盤失敗", exception);
            }
        });
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
                    Wearable.getMessageClient(this).sendMessage(n.getId(), "/wear-universal-sync", data);
                }
            } catch (Exception ignored) {}
        }).start();
    }

    private void setupChannelPipeline() {
        new Thread(() -> {
            try {
                List<Node> nodes = Tasks.await(Wearable.getNodeClient(this).getConnectedNodes());
                if (nodes.isEmpty()) return;
                
                mActiveChannel = Tasks.await(Wearable.getChannelClient(this).openChannel(nodes.get(0).getId(), "/wear-camera-frame-stream"));
                synchronized (mChannelLock) {
                    mChannelOutputStream = Tasks.await(Wearable.getChannelClient(this).getOutputStream(mActiveChannel));
                }
                Log.d(TAG, "🚀 [Channel] 長連接管道已成功建立！");
            } catch (Exception e) {
                Log.e(TAG, "開啟長連接管道失敗", e);
            }
        }).start();
    }

    private void closeChannelSafely() {
        synchronized (mChannelLock) {
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
                Log.d(TAG, "🔒 Channel 長連接管道已安全釋放。");
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
                Log.d(TAG, "📸 [釋放] 已解綁 CameraX 的硬體繫結");
            } catch (Exception ignored) {}
        }

        // 🎯 隱藏本地主界面上的預覽小視窗
        PhoneSyncMainActivity mainActivity = PhoneSyncMainActivity.getInstance();
        if (mainActivity != null) {
            mainActivity.hideLocalPreview();
        }

        // 🎯 【反向鏈條補償】：通知手錶同步完全關閉
        new Thread(() -> {
            try {
                JSONObject json = new JSONObject();
                json.put("sender", "phone");
                json.put("type", "camera_control");
                json.put("action", "PHONE_CAMERA_CLOSED");
                byte[] data = json.toString().getBytes(StandardCharsets.UTF_8);

                List<Node> nodes = Tasks.await(Wearable.getNodeClient(this).getConnectedNodes());
                for (Node n : nodes) {
                    Wearable.getMessageClient(this).sendMessage(n.getId(), "/wear-universal-sync", data);
                }
                Log.d(TAG, "🚀 已反向投遞 PHONE_CAMERA_CLOSED 訊號給手錶");
            } catch (Exception ignored) {}
        }).start();

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
