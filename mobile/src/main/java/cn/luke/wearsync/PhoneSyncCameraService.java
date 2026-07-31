package cn.luke.wearsync;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.graphics.SurfaceTexture;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Build;
import android.os.IBinder;
import android.util.Size;
import android.view.Surface;

import androidx.annotation.NonNull;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.Preview;
import androidx.camera.core.SurfaceRequest;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;

import com.google.common.util.concurrent.ListenableFuture;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;

public class PhoneSyncCameraService extends Service implements LifecycleOwner {

    private static final String TAG = "WearSync_CameraService";
    
    // ✅ 修复点 1: 添加单例变量，解决 ListenerService 找不到 getInstance() 的问题
    private static PhoneSyncCameraService sInstance;

    // 原有的常量定义保持不变
    public static final String ACTION_START_CAMERA = "cn.luke.wearsync.action.START_CAMERA";
    public static final String ACTION_STOP_CAMERA = "cn.luke.wearsync.action.STOP_CAMERA";

    // 生命周期相关
    private final LifecycleRegistry lifecycleRegistry = new LifecycleRegistry(this);

    @Override
    public Lifecycle getLifecycle() {
        return lifecycleRegistry;
    }

    // ✅ 修复点 2: 实现 getInstance() 方法
    public static PhoneSyncCameraService getInstance() {
        return sInstance;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE);
    
        // 1. 定义渠道 ID
        String channelId = "camera_channel";
    
        // 2. 创建通知渠道 (Android 8.0+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    channelId,
                    "相机同步服务", // 渠道名称
                    NotificationManager.IMPORTANCE_LOW // 重要性：低（不会弹出横幅，但在状态栏可见）
            );
            channel.setDescription("用于保持相机推流服务在后台运行");
            
            NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    
        // 3. 构建通知
        Notification notification = new NotificationCompat.Builder(this, channelId) // 使用刚才定义的 channelId
                .setContentTitle("相机服务运行中")
                .setContentText("正在同步相机画面...") // 建议添加内容文本
                .setSmallIcon(android.R.drawable.ic_menu_camera) // 确保这个图标存在
                .build();
    
        // 4. 启动前台服务
        startForeground(1, notification);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_STICKY;
        
        String action = intent.getAction();
        if (ACTION_START_CAMERA.equals(action)) {
            String nodeId = intent.getStringExtra("node_id");
            startStreaming(nodeId);
        } else if (ACTION_STOP_CAMERA.equals(action)) {
            stopStreaming();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        sInstance = null; // 销毁时清空静态变量，防止内存泄漏
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY);
        stopStreaming();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // 模拟的推流启动逻辑
    public void startStreaming(String nodeId) {
        PhoneLog.d(TAG, "开始推流到节点: " + nodeId);
        // 这里是你原本的相机初始化和 MediaCodec 准备逻辑
        initCameraAndEncoder(nodeId);
    }

    public void stopStreaming() {
        PhoneLog.d(TAG, "停止推流");
        // 释放相机和编码器资源
    }

    private void initCameraAndEncoder(String nodeId) {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = 
                ProcessCameraProvider.getInstance(this);

        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();
                
                Preview preview = new Preview.Builder().build();
                CameraSelector cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;

                // ✅ 修复点 3: 设置 SurfaceProvider 时的正确写法
                preview.setSurfaceProvider(surfaceRequest -> {
                    Size resolution = surfaceRequest.getResolution();
                    
                    // 假设你已经初始化了 encoderInputSurface
                    Surface encoderInputSurface = createEncoderInputSurface(resolution); 
                    
                    if (encoderInputSurface != null) {
                        // 这里的第二个参数必须是 Executor，不能是 Context
                        Executor mainExecutor = ContextCompat.getMainExecutor(this);
                        
                        surfaceRequest.provideSurface(encoderInputSurface, mainExecutor, result -> {
                            // ✅ 修复点 4: 使用正确的 Result 常量
                            if (result.getResultCode() == SurfaceRequest.Result.RESULT_SURFACE_USED_SUCCESSFULLY) {
                                PhoneLog.d(TAG, "Surface 提供成功");
                            } else {
                                PhoneLog.e(TAG, "Surface 提供失败，代码: " + result.getResultCode());
                            }
                            // 无论成功与否，处理完后都需要释放 Surface
                            encoderInputSurface.release();
                        });
                    }
                });

                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(this, cameraSelector, preview);

            } catch (ExecutionException | InterruptedException e) {
                PhoneLog.e(TAG, "相机初始化失败", e);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    // 辅助方法：创建编码器输入 Surface (示例)
    private Surface createEncoderInputSurface(Size size) {
        try {
            MediaCodec codec = MediaCodec.createEncoderByType("video/avc");
            MediaFormat format = MediaFormat.createVideoFormat("video/avc", size.getWidth(), size.getHeight());
            format.setInteger(MediaFormat.KEY_BIT_RATE, 2000000);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
            
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            Surface surface = codec.createInputSurface();
            codec.start();
            return surface;
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }
}
