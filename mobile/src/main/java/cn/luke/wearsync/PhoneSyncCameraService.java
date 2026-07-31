package cn.luke.wearsync;

import android.app.Notification;
import android.app.Service;
import android.app.Service;
import android.content.Intent;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Build;
import android.os.IBinder;
import android.view.OrientationEventListener;
import android.view.Surface;

import androidx.annotation.NonNull;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;

import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.ChannelClient;
import com.google.android.gms.wearable.Wearable;
import com.google.common.util.concurrent.ListenableFuture;

import org.json.JSONObject;

import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 📹 PhoneSyncCameraService - 重构稳定版
 * 修复了语法错误，清理了无用代码，并增强了日志。
 */
public class PhoneSyncCameraService extends Service implements LifecycleOwner {

    private static final String TAG = "WearSync_CameraService";
    private static final String UNIVERSAL_SYNC_PATH = "/wear-universal-sync";
    
    // --- 生命周期与状态管理 ---
    private final LifecycleRegistry lifecycleRegistry = new LifecycleRegistry(this);
    private final ExecutorService backgroundExecutor = Executors.newSingleThreadExecutor();
    private static PhoneSyncCameraService instance;

    // --- 业务逻辑变量 ---
    private MediaCodec mEncoder;
    private OutputStream mOutputStream;
    private Surface mInputSurface;
    private Preview mPreviewUseCase;
    private OrientationEventListener mOrientationListener;
    
    private byte[] spsData;
    private byte[] ppsData;
    private long totalFrames = 0;
    private boolean isFirstFrame = true;
    private final AtomicBoolean channelOpening = new AtomicBoolean(false);
    private String mPendingStreamingNodeId = null;

    // --- 状态机定义 ---
    private enum CameraState { IDLE, STARTING, CAMERA_READY, CHANNEL_OPENING, STREAMING, STOPPING, ERROR }
    private volatile CameraState currentState = CameraState.IDLE;

    // --- 状态机同步方法 ---
    private synchronized boolean transition(CameraState from, CameraState to) {
        if (currentState != from) {
            PhoneLog.d(TAG, "状态转换失败: 当前状态=" + currentState + ", 期望状态=" + from);
            return false;
        }
        currentState = to;
        PhoneLog.d(TAG, "状态转换: " + from + " -> " + to);
        return true;
    }

    private synchronized void setState(CameraState newState) {
        PhoneLog.d(TAG, "状态更新: " + currentState + " -> " + newState);
        currentState = newState;
    }

    private synchronized CameraState getState() {
        return currentState;
    }

    // --- Service 生命周期 ---
    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE);
        PhoneLog.d(TAG, "服务已创建 (onCreate)");

        // 创建并启动前台服务通知
        Notification notification = new NotificationCompat.Builder(this, "camera_service_channel")
                .setContentTitle("相机服务运行中")
                .setContentText("正在等待手表连接...")
                .setSmallIcon(R.drawable.ic_notification) // 确保你已创建此图标
                .build();
        startForeground(1, notification);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            PhoneLog.d(TAG, "收到空 Intent，服务保持运行 (START_STICKY)");
            return START_STICKY;
        }

        String action = intent.getAction();
        PhoneLog.d(TAG, "收到指令: " + action);

        if (PhoneSyncCameraService.ACTION_START_CAMERA.equals(action)) {
            startFlow();
        } else if (PhoneSyncCameraService.ACTION_STOP_CAMERA.equals(action)) {
            stopFlow();
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        PhoneLog.d(TAG, "服务即将销毁 (onDestroy)");
        releaseAll();
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY);
        instance = null;
        backgroundExecutor.shutdown();
        super.onDestroy();
    }

    // --- 核心流程控制 ---

    /**
     * 启动相机预览和编码器
     */
    private void startFlow() {
        PhoneLog.d(TAG, "=== 开始启动流程 (startFlow) ===");
        if (!transition(CameraState.IDLE, CameraState.STARTING)) {
            PhoneLog.d(TAG, "服务已在运行，忽略启动请求。当前状态: " + getState());
            return;
        }

        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START);
        totalFrames = 0;
        isFirstFrame = true;
        
        setupOrientation();
        setupEncoderAndCamera();
    }

    /**
     * 停止所有流程并关闭服务
     */
    private void stopFlow() {
        PhoneLog.d(TAG, "=== 开始停止流程 (stopFlow) ===");
        setState(CameraState.STOPPING);
        releaseAll();
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(Service.STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }
        stopSelf();
    }

    /**
     * 手表端请求开始推流
     */
    public void startStreaming(String nodeId) {
        PhoneLog.d(TAG, "收到推流请求 (startStreaming), NodeId: " + nodeId);
        if (nodeId == null || nodeId.isEmpty()) {
            PhoneLog.e(TAG, "推流请求失败: NodeId 为空");
            return;
        }

        if (getState() == CameraState.CAMERA_READY) {
            PhoneLog.d(TAG, "相机已就绪，开始打开通道");
            if (!transition(CameraState.CAMERA_READY, CameraState.CHANNEL_OPENING)) {
                PhoneLog.e(TAG, "状态转换失败，无法打开通道");
                return;
            }
            openChannel(nodeId);
        } else {
            PhoneLog.d(TAG, "相机未就绪，暂存 NodeId 等待后续处理");
            mPendingStreamingNodeId = nodeId;
        }
    }

    // --- 相机与编码器设置 ---

    /**
     * 初始化编码器和 CameraX
     */
    private void setupEncoderAndCamera() {
        PhoneLog.d(TAG, "正在设置编码器和相机 (setupEncoderAndCamera)");
        try {
            // 1. 配置 MediaCodec 编码器
            MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 640, 480);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            format.setInteger(MediaFormat.KEY_BIT_RATE, 500000);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, 15);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);

            mEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            mEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            mInputSurface = mEncoder.createInputSurface();
            
            // 2. 设置编码器回调，处理视频流
            mEncoder.setCallback(new MediaCodec.Callback() {
                @Override
                public void onOutputBufferAvailable(@NonNull MediaCodec codec, int index, @NonNull MediaCodec.BufferInfo info) {
                    if (getState() != CameraState.STREAMING || mOutputStream == null) {
                        codec.releaseOutputBuffer(index, false);
                        return;
                    }
                    if (info.size <= 0) {
                        codec.releaseOutputBuffer(index, false);
                        return;
                    }

                    try {
                        ByteBuffer buffer = codec.getOutputBuffer(index);
                        if (buffer == null) return;

                        byte[] data = new byte[info.size];
                        buffer.get(data);

                        // 写入 SPS/PPS 头信息（仅在第一个关键帧时）
                        if (totalFrames == 0) {
                            if (spsData != null) mOutputStream.write(spsData);
                            if (ppsData != null) mOutputStream.write(ppsData);
                        }
                        
                        mOutputStream.write(data);
                        mOutputStream.flush();
                        totalFrames++;

                        if (isFirstFrame) {
                            PhoneLog.d(TAG, "🎉 第一个视频帧已发送，大小: " + data.length);
                            isFirstFrame = false;
                        }
                    } catch (Exception e) {
                        PhoneLog.e(TAG, "视频流写入失败", e);
                    } finally {
                        codec.releaseOutputBuffer(index, false);
                    }
                }

                @Override
                public void onInputBufferAvailable(@NonNull MediaCodec codec, int index) {
                    // 编码器使用 Surface 作为输入，此回调不需要处理
                }

                @Override
                public void onError(@NonNull MediaCodec codec, @NonNull MediaCodec.CodecException e) {
                    PhoneLog.e(TAG, "编码器发生错误", e);
                    setState(CameraState.ERROR);
                }

                @Override
                public void onOutputFormatChanged(@NonNull MediaCodec codec, @NonNull MediaFormat format) {
                    PhoneLog.d(TAG, "编码器输出格式已改变");
                    try {
                        ByteBuffer csd0 = format.getByteBuffer("csd-0"); // SPS
                        ByteBuffer csd1 = format.getByteBuffer("csd-1"); // PPS
                        if (csd0 != null) {
                            spsData = new byte[csd0.limit()];
                            csd0.get(spsData);
                            csd0.rewind();
                        }
                        if (csd1 != null) {
                            ppsData = new byte[csd1.limit()];
                            csd1.get(ppsData);
                            csd1.rewind();
                        }
                        PhoneLog.d(TAG, "已获取 H.264 配置: SPS=" + (spsData != null) + ", PPS=" + (ppsData != null));
                    } catch (Exception e) {
                        PhoneLog.e(TAG, "解析 H.264 配置失败", e);
                    }
                }
            });
            mEncoder.start();
            PhoneLog.d(TAG, "编码器已启动");

            // 3. 初始化 CameraX
            ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);
            cameraProviderFuture.addListener(() -> {
                try {
                    ProcessCameraProvider cameraProvider = cameraProvider.get();
                    cameraProvider.unbindAll();

                    mPreviewUseCase = new Preview.Builder().build();
                    mPreviewUseCase.setSurfaceProvider(mInputSurface); // 将预览画面输出到编码器 Surface

                    CameraSelector cameraSelector = new CameraSelector.Builder()
                            .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                            .build();

                    cameraProvider.bindToLifecycle(this, cameraSelector, mPreviewUseCase);
                    PhoneLog.d(TAG, "相机已绑定到生命周期");
                    
                    setState(CameraState.CAMERA_READY);
                    PhoneLog.d(TAG, "相机准备就绪 (CAMERA_READY)");

                    // 如果有暂存的推流请求，现在可以处理了
                    if (mPendingStreamingNodeId != null) {
                        PhoneLog.d(TAG, "处理暂存的推流请求");
                        startStreaming(mPendingStreamingNodeId);
                        mPendingStreamingNodeId = null;
                    }

                } catch (Exception e) {
                    PhoneLog.e(TAG, "相机绑定失败", e);
                    setState(CameraState.ERROR);
                }
            }, ContextCompat.getMainExecutor(this));

        } catch (Exception e) {
            PhoneLog.e(TAG, "编码器或相机设置失败", e);
            setState(CameraState.ERROR);
        }
    }

    /**
     * 打开 Wear OS 数据通道
     */
    private void openChannel(String nodeId) {
        PhoneLog.d(TAG, "正在打开数据通道 (openChannel)");
        if (channelOpening.getAndSet(true)) {
            PhoneLog.d(TAG, "通道已在打开过程中，忽略重复请求");
            return;
        }

        backgroundExecutor.execute(() -> {
            try {
                ChannelClient.Channel channel = Tasks.await(
                        Wearable.getChannelClient(this).openChannel(nodeId, "/camera-preview-stream")
                );
                PhoneLog.d(TAG, "数据通道已打开: " + channel.getPath());

                mOutputStream = Tasks.await(Wearable.getChannelClient(this).getOutputStream(channel));
                PhoneLog.d(TAG, "输出流已准备就绪");

                // 通知手表端流已开始
                JSONObject json = new JSONObject();
                json.put("sender", "phone");
                json.put("type", "camera_control");
                json.put("action", "STREAM_START");
                Wearable.getMessageClient(this).sendMessage(
                        nodeId,
                        UNIVERSAL_SYNC_PATH,
                        json.toString().getBytes(StandardCharsets.UTF_8)
                );

                setState(CameraState.STREAMING);
                PhoneLog.d(TAG, "状态: 推流中 (STREAMING)");

            } catch (Exception e) {
                PhoneLog.e(TAG, "打开数据通道失败", e);
                channelOpening.set(false);
                setState(CameraState.CAMERA_READY); // 回退到准备就绪状态
            }
        });
    }

    /**
     * 监听手机方向变化
     */
    private void setupOrientation() {
        PhoneLog.d(TAG, "正在设置方向监听器 (setupOrientation)");
        mOrientationListener = new OrientationEventListener(this) {
            int lastRotation = -1;
            @Override
            public void onOrientationChanged(int orientation) {
                if (orientation == ORIENTATION_UNKNOWN || mPreviewUseCase == null) return;

                int rotation;
                if (orientation < 45 || orientation >= 315) {
                    rotation = Surface.ROTATION_0;
                } else if (orientation < 135) {
                    rotation = Surface.ROTATION_270;
                } else if (orientation < 225) {
                    rotation = Surface.ROTATION_180;
                } else {
                    rotation = Surface.ROTATION_90;
                }

                if (rotation != lastRotation) {
                    lastRotation = rotation;
                    mPreviewUseCase.setTargetRotation(rotation);
                    PhoneLog.d(TAG, "屏幕方向已更新: " + rotation);
                }
            }
        };
        mOrientationListener.enable();
    }

    /**
     * 释放所有资源
     */
    private void releaseAll() {
        PhoneLog.d(TAG, "正在释放所有资源 (releaseAll)");
        try {
            if (mEncoder != null) {
                mEncoder.stop();
                mEncoder.release();
                PhoneLog.d(TAG, "编码器已释放");
            }
        } catch (Exception e) {
            PhoneLog.e(TAG, "释放编码器时出错", e);
        } finally {
            mEncoder = null;
        }

        try {
            if (mOutputStream != null) {
                mOutputStream.close();
                PhoneLog.d(TAG, "输出流已关闭");
            }
        } catch (Exception e) {
            PhoneLog.e(TAG, "关闭输出流时出错", e);
        } finally {
            mOutputStream = null;
        }

        if (mInputSurface != null) {
            mInputSurface.release();
            mInputSurface = null;
            PhoneLog.d(TAG, "输入 Surface 已释放");
        }

        if (mOrientationListener != null) {
            mOrientationListener.disable();
            mOrientationListener = null;
            PhoneLog.d(TAG, "方向监听器已禁用");
        }

        // 解绑 CameraX 必须在后台线程执行
        backgroundExecutor.execute(() -> {
            try {
                ProcessCameraProvider provider = ProcessCameraProvider.getInstance(this).get();
                provider.unbindAll();
                PhoneLog.d(TAG, "CameraX 已解绑");
            } catch (Exception e) {
                PhoneLog.e(TAG, "解绑 CameraX 时出错", e);
            } finally {
                channelOpening.set(false);
                setState(CameraState.IDLE);
            }
        });
    }

    @Override
    public Lifecycle getLifecycle() {
        return lifecycleRegistry;
    }

    public static PhoneSyncCameraService getInstance() {
        return instance;
    }

    // --- 对外暴露的 Action ---
    public static final String ACTION_START_CAMERA = "cn.luke.wearsync.ACTION_START_CAMERA";
    public static final String ACTION_STOP_CAMERA = "cn.luke.wearsync.ACTION_STOP_CAMERA";
}
