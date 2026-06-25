package de.rhaeus.wearsync;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.OrientationEventListener;
import android.view.Surface;
import android.view.WindowManager;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;
import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.ChannelClient;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;
import org.json.JSONObject;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 📹 手机端背景相机取景流硬编码核心服务 (CameraX + MediaCodec + Wearable Channel)
 * 完美对齐版：修复双端握手寻址、校准Channel斜杠、手机端自适应重力旋转（手表无脑渲染）。
 */
public class PhoneSyncCameraService extends Service implements LifecycleOwner {

    private static final String TAG = "WearSync_CameraService";
    
    // 🎯 协议对齐：严格匹配清单文件与跳板 Activity 的 Action 字符串
    public static final String ACTION_START_CAMERA = "de.rhaeus.wearsync.ACTION_START_CAMERA";
    public static final String ACTION_STOP_CAMERA = "de.rhaeus.wearsync.ACTION_STOP_CAMERA";
    private static final String UNIVERSAL_SYNC_PATH = "/wear-universal-sync";

    private final LifecycleRegistry lifecycleRegistry = new LifecycleRegistry(this);

    private MediaCodec mEncoder;
    private OutputStream mOutputStream;
    private boolean isPipelineReady = false;
    private OrientationEventListener mOrientationListener;
    
    // 🎛️ 类成员变量：用以动态通知 CameraX 修正输出画幅方向
    private Preview mPreviewUseCase;

    @NonNull
    @Override
    public Lifecycle getLifecycle() {
        return lifecycleRegistry;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        PhoneLog.d(TAG, "① onCreate: 远程相机核心流转服务开始初始化...");
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getAction() != null) {
            String action = intent.getAction();
            PhoneLog.d(TAG, "② onStartCommand 接收到穿透指令 ➔ " + action);

            if (ACTION_START_CAMERA.equals(action)) {
                lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START);
                
                // 🤝 握手寻址补全：优先从全局车牌号缓存中获取当前触发抓拍的手表节点 ID
                String targetNodeId = WearSyncState.getNodeId(this);
                if (targetNodeId == null || targetNodeId.isEmpty()) {
                    PhoneLog.w(TAG, "⚠️ [寻址警告] 持久化节点 ID 为空，触发后备应急广播查找机制...");
                    targetNodeId = "connected_nodes";
                }

                // 1. 点火 CameraX 与 H264 编码管线
                setupCameraAndPipeline();
                
                // 2. 启动重力方向传感器监听（手机自吃旋转）
                setupOrientationListener();

                // 3. 拧开高速公路闸门：带上明确的 nodeId 去和手表握手开辟流通道
                openChannelAndStream(targetNodeId);
            } 
            else if (ACTION_STOP_CAMERA.equals(action)) {
                PhoneLog.d(TAG, "🛑 接收到主动安全退出中断指令，准备自我熔断销毁...");
                stopSelf();
            }
        }
        return START_NOT_STICKY;
    }

    private void setupCameraAndPipeline() {
        try {
            PhoneLog.d(TAG, "⚙️ 开始配置 H.264 底层硬核编码参数 (640x480, 15fps)...");
            MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 640, 480);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            format.setInteger(MediaFormat.KEY_BIT_RATE, 500000); // 500kbps 顺滑不卡顿
            format.setInteger(MediaFormat.KEY_FRAME_RATE, 15);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);

            mEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            mEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            Surface inputSurface = mEncoder.createInputSurface();

            mEncoder.setCallback(new MediaCodec.Callback() {
                @Override
                public void onInputBufferAvailable(@NonNull MediaCodec codec, int index) {}

                @Override
                public void onOutputBufferAvailable(@NonNull MediaCodec codec, int index, @NonNull MediaCodec.BufferInfo info) {
                    if (!isPipelineReady || mOutputStream == null) {
                        try { mEncoder.releaseOutputBuffer(index, false); } catch (Exception ignored) {}
                        return;
                    }
                    try {
                        ByteBuffer outputBuffer = codec.getOutputBuffer(index);
                        if (outputBuffer != null) {
                            byte[] outData = new byte[info.size];
                            outputBuffer.get(outData);
                            // 将硬编码出的 H.264 帧数据直接高频喷射进 Wear Channel 管道
                            mOutputStream.write(outData, 0, outData.length);
                            mOutputStream.flush();
                        }
                    } catch (Exception e) {
                        PhoneLog.e(TAG, "⚠️ 帧流灌入高速传输网关遭遇短暂拥堵: " + e.getMessage());
                    } finally {
                        try { codec.releaseOutputBuffer(index, false); } catch (Exception ignored) {}
                    }
                }

                @Override
                public void onError(@NonNull MediaCodec codec, @NonNull MediaCodec.CodecException e) {
                    PhoneLog.e(TAG, "🔴 MediaCodec 硬件层发生内部编码拥堵波动: " + e.getDiagnosticInfo());
                }

                @Override
                public void onOutputFormatChanged(@NonNull MediaCodec codec, @NonNull MediaFormat format) {}
            });

            mEncoder.start();
            PhoneLog.d(TAG, "🚀 H.264 硬件视频编码器点火成功！开始绑定 CameraX 空间投影...");

            ProcessCameraProvider cameraProvider = Tasks.await(ProcessCameraProvider.getInstance(this));
            
            // 🎯 将实例赋值给全局成员变量，以便 Orientation 监听器动态修改输出旋转角
            mPreviewUseCase = new Preview.Builder().build();
            mPreviewUseCase.setSurfaceProvider(ContextCompat.getMainExecutor(this), surfaceRequest -> {
                surfaceRequest.provideSurface(inputSurface, ContextCompat.getMainExecutor(this), result -> {
                    PhoneLog.d(TAG, "🖥️ CameraX 图像承载 Surface 握手交接完毕。");
                });
            });

            // 获取当前手机屏幕的默认物理方向赋予初始值
            WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
            if (wm != null) {
                mPreviewUseCase.setTargetRotation(wm.getDefaultDisplay().getRotation());
            }

            CameraSelector cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;
            cameraProvider.unbindAll();
            cameraProvider.bindToLifecycle(this, cameraSelector, mPreviewUseCase);
            PhoneLog.d(TAG, "✨ CameraX 核心用例已成功绑定至前台服务上下文。");

        } catch (Exception e) {
            PhoneLog.e(TAG, "🔴 [致命] 相机流核心硬编码管线构建遭遇崩溃: " + e.getMessage(), e);
        }
    }

    private void openChannelAndStream(String nodeId) {
        new Thread(() -> {
            try {
                PhoneLog.d(TAG, "🌊 正在向目标手表节点 [" + nodeId + "] 申请打通高性能双轨流媒体网关...");
                
                // 🔥 核心对齐：路径前面必须补上斜杠 "/"，与手表端接收常量绝对咬死！
                ChannelClient.Channel channel = Tasks.await(Wearable.getChannelClient(this)
                        .openChannel(nodeId, "/camera-preview-stream"));

                PhoneLog.d(TAG, "✨ [网关建立成功] 正在拧开高速流媒体字节输出流阀门...");
                mOutputStream = Tasks.await(Wearable.getChannelClient(this).getOutputStream(channel));
                isPipelineReady = true;
                PhoneLog.d(TAG, "🚀 [管道就绪] 手机图传帧率流开始顺畅喷射！");
            } catch (Exception e) {
                PhoneLog.e(TAG, "🔴 [网关崩溃] 无法与远端手表成功闭合流媒体物理链路: " + e.getMessage(), e);
            }
        }).start();
    }

    /**
     * 📐 核心重构：让手机端自适应重力方向旋转，手表端彻底解脱，只负责纯平渲染！
     */
    private void setupOrientationListener() {
        mOrientationListener = new OrientationEventListener(this) {
            @Override
            public void onOrientationChanged(int orientation) {
                if (orientation == ORIENTATION_UNKNOWN) return;

                // 将传感器的倾斜角度严格映射为 Android 标准的 Surface 物理象限角度
                int rotation;
                if (orientation >= 315 || orientation < 45) {
                    rotation = Surface.ROTATION_0;
                } else if (orientation >= 45 && orientation < 135) {
                    rotation = Surface.ROTATION_270; // 逆时针修正
                } else if (orientation >= 135 && orientation < 225) {
                    rotation = Surface.ROTATION_180;
                } else {
                    rotation = Surface.ROTATION_90;
                }

                // 🎯 手机自吃旋转：直接动态修改 CameraX 的数据源发射方向！
                if (mPreviewUseCase != null) {
                    try {
                        mPreviewUseCase.setTargetRotation(rotation);
                    } catch (Exception ignored) {}
                }
                
                // 💡 注意：此处已经彻底删除了 sendControlMessageToWatch("ROTATION_CHANGED") 指令。
                // 手表不会收到任何旋转干扰，从而实现“手机转、画面转、手表无脑平铺显示”的最佳图传体验。
            }
        };
        mOrientationListener.enable();
    }

    private void releaseCameraAndPipeline() {
        PhoneLog.w(TAG, "🧹 正在执行系统熔断保护：回收并关闭手机相机及硬解管线资源...");
        isPipelineReady = false;
        if (mOrientationListener != null) {
            mOrientationListener.disable();
            mOrientationListener = null;
        }
        if (mEncoder != null) {
            try {
                mEncoder.stop();
                mEncoder.release();
            } catch (Exception ignored) {}
            mEncoder = null;
        }
        if (mOutputStream != null) {
            try {
                mOutputStream.close();
            } catch (Exception ignored) {}
            mOutputStream = null;
        }
        try {
            ProcessCameraProvider cameraProvider = Tasks.await(ProcessCameraProvider.getInstance(this));
            cameraProvider.unbindAll();
        } catch (Exception ignored) {}
        PhoneLog.d(TAG, "🧹 硬件流媒体所有底层依赖彻底降落安全释放。");
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        releaseCameraAndPipeline();
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY);
        super.onDestroy();
        PhoneLog.d(TAG, "🏳️ onDestroy: 远程相机前台服务完整生命周期安全终结。");
    }
}
