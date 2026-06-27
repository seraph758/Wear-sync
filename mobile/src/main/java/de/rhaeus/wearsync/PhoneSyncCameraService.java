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
import com.google.common.util.concurrent.ListenableFuture;
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
 * 极致动态日志全步进版：实时打通每一个异步回调与高频帧控网关。
 */
public class PhoneSyncCameraService extends Service implements LifecycleOwner {

    private static final String TAG = "WearSync_CameraService";
    
    public static final String ACTION_START_CAMERA = "de.rhaeus.wearsync.ACTION_START_CAMERA";
    public static final String ACTION_STOP_CAMERA = "de.rhaeus.wearsync.ACTION_STOP_CAMERA";
    private static final String UNIVERSAL_SYNC_PATH = "/wear-universal-sync";

    private final LifecycleRegistry lifecycleRegistry = new LifecycleRegistry(this);

    private MediaCodec mEncoder;
    private OutputStream mOutputStream;
    private boolean isPipelineReady = false;
    private OrientationEventListener mOrientationListener;
    private Surface mInputSurface;
    private Preview mPreviewUseCase;

    // 📊 用于防止 Logcat 撑爆的硬编码帧控计数器
    private long totalFramesEncoded = 0;
    private long lastLogTime = 0;
    private boolean isFirstFrameInjected = false;

    @NonNull
    @Override
    public Lifecycle getLifecycle() {
        return lifecycleRegistry;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        PhoneLog.d(TAG, "① [生命周期] onCreate ─── 远程相机图传核心服务启动初始化...");
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            PhoneLog.w(TAG, "② [生命周期] onStartCommand 收到空 Intent，忽略动作。");
            return START_NOT_STICKY;
        }

        String action = intent.getAction();
        PhoneLog.d(TAG, "② [生命周期] onStartCommand 接收到手錶端穿透信令 ➔ [" + action + "]");

        if (ACTION_START_CAMERA.equals(action)) {
            PhoneLog.d(TAG, "🚀 [初始化触发] 正在切回生命周期至 ON_START...");
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START);
            
            // 🤝 握手寻址补全
            String targetNodeId = WearSyncState.getNodeId(this);
            PhoneLog.d(TAG, "🤝 [寻址核对] 尝试拉取本地 WearSyncState 缓存节点...");
            if (targetNodeId == null || targetNodeId.isEmpty()) {
                PhoneLog.w(TAG, "⚠️ [寻址核对降级] 持久化车牌号缓存为空！可能连接断开，触发后备应急物理网关查找...");
                targetNodeId = "connected_nodes";
            } else {
                PhoneLog.d(TAG, "✅ [寻址核对命中] 捕获到目标手表节点 ID: [" + targetNodeId + "]");
            }

            // Reset 帧计数器
            totalFramesEncoded = 0;
            isFirstFrameInjected = false;

            // 1. 点火 CameraX 与 H264 编码管线
            PhoneLog.d(TAG, "🛠️ [步进 1/3] 正在点火 CameraX 空间投影与 H.264 硬件硬编码管线...");
            setupCameraAndPipeline();
            
            // 2. 启动重力方向传感器监听（手机自吃旋转）
            PhoneLog.d(TAG, "🛠️ [步进 2/3] 正在挂载重力方向传感器监听器（由手机端自适应扭转图像）...");
            setupOrientationListener();

            // 3. 拧开高速公路闸门
            PhoneLog.d(TAG, "🛠️ [步进 3/3] 正在拧开底层高速公路物理流闸门，建立对等传输通道...");
            openChannelAndStream(targetNodeId);
        } 
        else if (ACTION_STOP_CAMERA.equals(action)) {
            PhoneLog.w(TAG, "🛑 [熔断触发] 接收到主动安全退出中断指令！准备执行自我终结...");
            stopSelf();
        } else {
            PhoneLog.w(TAG, "⚠️ [位置信令] 收到未知的异常 Action: [" + action + "]，丢弃不做处理。");
        }
        return START_NOT_STICKY;
    }

    private void setupCameraAndPipeline() {
        try {
            PhoneLog.d(TAG, "⚙️ [编码器配置] 开始配置 H.264 底层硬核编码参数 (画幅: 640x480, 帧率: 15fps)...");
            MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 640, 480);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            format.setInteger(MediaFormat.KEY_BIT_RATE, 500000); // 500kbps
            format.setInteger(MediaFormat.KEY_FRAME_RATE, 15);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);

            PhoneLog.d(TAG, "⚙️ [编码器配置] 正在呼叫系统硬件多媒体库创建 AVC 硬编码实例...");
            mEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            mEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            
            PhoneLog.d(TAG, "⚙️ [编码器配置] 正在从小卡座抽取 InputSurface 作为 CameraX 图像投影幕布...");
            mInputSurface = mEncoder.createInputSurface();

            PhoneLog.d(TAG, "⚙️ [编码器配置] 正在挂载 MediaCodec 异步双工数据环路回调監聽 (MediaCodec.Callback)...");
            mEncoder.setCallback(new MediaCodec.Callback() {
                @Override
                public void onInputBufferAvailable(@NonNull MediaCodec codec, int index) {
                    // Surface 模式下输入源自动托管，无需手动填塞数据
                }

                @Override
                public void onOutputBufferAvailable(@NonNull MediaCodec codec, int index, @NonNull MediaCodec.BufferInfo info) {
                    if (!isPipelineReady || mOutputStream == null) {
                        try { codec.releaseOutputBuffer(index, false); } catch (Exception ignored) {}
                        return;
                    }
                    try {
                        ByteBuffer outputBuffer = codec.getOutputBuffer(index);
                        if (outputBuffer != null) {
                            byte[] outData = new byte[info.size];
                            outputBuffer.get(outData);
                            
                            // 🚀 核心捕获：首帧锚点日誌印出
                            if (!isFirstFrameInjected) {
                                isFirstFrameInjected = true;
                                PhoneLog.d(TAG, "🔥 [图传首帧大捷] 第一帧 H.264 原生密文流已成功从编码器吐出！大小: " + info.size + " 字节，开始强势注流...");
                            }

                            // 將硬編碼出的 H.264 幀數據直接高頻噴射進 Wear Channel 管道
                            mOutputStream.write(outData, 0, outData.length);
                            mOutputStream.flush();
                            
                            totalFramesEncoded++;
                            long now = System.currentTimeMillis();
                            // 每隔 3 秒印出一條計數快照，避免卡死日誌鏈
                            if (now - lastLogTime > 3000) {
                                PhoneLog.d(TAG, "📊 [图传高频快照] 帧流稳定喷射中，当前累计已成功向手錶发送: " + totalFramesEncoded + " 帧。");
                                lastLogTime = now;
                            }
                        }
                    } catch (Exception e) {
                        PhoneLog.e(TAG, "⚠️ [图传拥堵] 帧流灌入高速传输网关遭遇短暂拥堵波动: " + e.getMessage());
                    } finally {
                        try { codec.releaseOutputBuffer(index, false); } catch (Exception ignored) {}
                    }
                }

                @Override
                public void onError(@NonNull MediaCodec codec, @NonNull MediaCodec.CodecException e) {
                    PhoneLog.e(TAG, "🔴 [编码器内部报错] MediaCodec 硬件层发生内部编码拥堵波动: " + e.getDiagnosticInfo(), e);
                }

                @Override
                public void onOutputFormatChanged(@NonNull MediaCodec codec, @NonNull MediaFormat format) {
                    PhoneLog.d(TAG, "✨ [编码器格式变更] 硬件编码器输出格式已重组更新: " + format.toString());
                }
            });

            PhoneLog.d(TAG, "🚀 [编码器点火] 正在呼叫 mEncoder.start()...");
            mEncoder.start();
            PhoneLog.d(TAG, "🚀 [编码器点火成功] H.264 硬件视频编码器已就绪。開始動態綁定 CameraX 視覺引擎...");

            // 🛠️ 獲取 CameraProvider
            PhoneLog.d(TAG, "⚙️ [CameraX绑定] 正在调用 ProcessCameraProvider.getInstance(this) 异步抓取相机驱动...");
            final ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);
            cameraProviderFuture.addListener(() -> {
                try {
                    PhoneLog.d(TAG, "⚙️ [CameraX绑定] 异步回调触发，正在调用 cameraProviderFuture.get() 提炼实例...");
                    ProcessCameraProvider cameraProvider = cameraProviderFuture.get();
                    
                    PhoneLog.d(TAG, "⚙️ [CameraX绑定] 正在构建 Preview 用例投影流...");
                    mPreviewUseCase = new Preview.Builder().build();
                    
                    mPreviewUseCase.setSurfaceProvider(ContextCompat.getMainExecutor(PhoneSyncCameraService.this), surfaceRequest -> {
                        PhoneLog.d(TAG, "🖥️ [Surface交接] CameraX 向服务索要承载画布，正在向其提供 MediaCodec 的 InputSurface...");
                        if (mInputSurface != null) {
                            surfaceRequest.provideSurface(mInputSurface, ContextCompat.getMainExecutor(PhoneSyncCameraService.this), result -> {
                                PhoneLog.d(TAG, "✨ [Surface交接成功] CameraX 图像承载 Surface 握手交接完毕，底层结果码: " + result.getResultCode());
                            });
                        } else {
                            PhoneLog.e(TAG, "❌ [Surface交接致命] 发现 mInputSurface 为 null，无法提供给 CameraX！");
                        }
                    });

                    // 获取当前手机屏幕的默认物理方向赋予初始值
                    WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
                    if (wm != null) {
                        int defaultRot = wm.getDefaultDisplay().getRotation();
                        PhoneLog.d(TAG, "⚙️ [CameraX绑定] 检测到当前手机系统物理象限方向码为: " + defaultRot + "，正在注入初始方向值...");
                        mPreviewUseCase.setTargetRotation(defaultRot);
                    }

                    CameraSelector cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;
                    PhoneLog.d(TAG, "⚙️ [CameraX绑定] 正在清空旧残余，准备执行 cameraProvider.bindToLifecycle()...");
                    cameraProvider.unbindAll();
                    cameraProvider.bindToLifecycle(PhoneSyncCameraService.this, cameraSelector, mPreviewUseCase);
                    PhoneLog.d(TAG, "✨ [CameraX大功告成] CameraX 后置相机核心预览用例已成功绑定至当前前台服务上下文！");
                } catch (Exception e) {
                    PhoneLog.e(TAG, "🔴 [CameraX绑定失败] 异步绑定 CameraX 空间投影进程发生严重溃败: " + e.getMessage(), e);
                }
            }, ContextCompat.getMainExecutor(this));

        } catch (Exception e) {
            PhoneLog.e(TAG, "🔴 [管线全盘崩溃] 相机流核心硬编码管线构建遭遇致命阻断: " + e.getMessage(), e);
        }
    }

    private void openChannelAndStream(String nodeId) {
        new Thread(() -> {
            PhoneLog.d(TAG, "🧵 [背景流线程] 网关线程已点火。正在向目标手錶节点 [" + nodeId + "] 申请打通高性能双轨流媒体物理通道...");
            try {
                // 🔥 核心對齊
                PhoneLog.d(TAG, "📡 [網關建立中] 正在呼叫 ChannelClient.openChannel ➔ 路径定位: [/camera-preview-stream]...");
                ChannelClient.Channel channel = Tasks.await(Wearable.getChannelClient(this)
                        .openChannel(nodeId, "/camera-preview-stream"));

                PhoneLog.d(TAG, "✨ [網關建立成功] 成功闭合物理网关！正在拧开高速流媒体字节输出流阀门 (getOutputStream)...");
                mOutputStream = Tasks.await(Wearable.getChannelClient(this).getOutputStream(channel));
                
                PhoneLog.d(TAG, "💾 [網關閉合完成] 正在拉起全数据链就绪旗帜 isPipelineReady = true");
                isPipelineReady = true;
                PhoneLog.d(TAG, "🚀 [管道彻底就绪] ━━━━ 手机图传大坝已开闸 ━━━━ 画面帧流开始向手表高频喷射！");
            } catch (Exception e) {
                PhoneLog.e(TAG, "🔴 [網關建立失敗] 无法与远端手表成功建立流媒体传输网络鏈路: " + e.getMessage(), e);
            }
        }).start();
    }

    private void setupOrientationListener() {
        PhoneLog.d(TAG, "📐 [陀螺仪配置] 正在例行初始化重力倾角 OrientationEventListener...");
        mOrientationListener = new OrientationEventListener(this) {
            private int mLastRotation = -1;

            @Override
            public void onOrientationChanged(int orientation) {
                if (orientation == ORIENTATION_UNKNOWN || mPreviewUseCase == null) return;

                int rotation;
                if (orientation >= 315 || orientation < 45) {
                    rotation = Surface.ROTATION_0;
                } else if (orientation >= 45 && orientation < 135) {
                    rotation = Surface.ROTATION_270; // 逆時針修正
                } else if (orientation >= 135 && orientation < 225) {
                    rotation = Surface.ROTATION_180;
                } else {
                    rotation = Surface.ROTATION_90;
                }

                // 防抖日誌：只有在方向真正發生轉變時才列印
                if (rotation != mLastRotation) {
                    PhoneLog.d(TAG, "📐 [重力扭转] 手机发生物理翻转！当前绝对角度: " + orientation + "° -> 映射 Android 标准象限码: " + rotation + "。正在动态修正 CameraX 发射源方向...");
                    mLastRotation = rotation;
                    try {
                        mPreviewUseCase.setTargetRotation(rotation);
                        PhoneLog.d(TAG, "📐 [重力扭转成功] CameraX 画面输出矩阵角已动态对齐。");
                    } catch (Exception e) {
                        PhoneLog.e(TAG, "⚠️ [重力扭转失败] 注入 CameraX 旋转矩阵时发生轻微抖动: " + e.getMessage());
                    }
                }
            }
        };
        mOrientationListener.enable();
        PhoneLog.d(TAG, "📐 [陀螺仪就绪] 传感器实时矩阵捕获已全量开启。");
    }

    private void releaseCameraAndPipeline() {
        PhoneLog.w(TAG, "🧹 [安全熔断] ━━━━━━━━ 触发底层系统资源大清洗 ━━━━━━━━");
        PhoneLog.w(TAG, "🧹 [安全熔断] 正在将全数据链旗帜降下 isPipelineReady = false");
        isPipelineReady = false;
        
        if (mOrientationListener != null) {
            PhoneLog.d(TAG, "🧹 [安全熔断] 正在摘除并关闭重力方向传感器监听器...");
            mOrientationListener.disable();
            mOrientationListener = null;
        }
        
        if (mEncoder != null) {
            PhoneLog.d(TAG, "🧹 [安全熔断] 正在释放关闭 mEncoder H.264 硬编码器...");
            try {
                mEncoder.stop();
                mEncoder.release();
                PhoneLog.d(TAG, "🧹 [安全熔断] 硬件编码器内存卡座已安全脱开。");
            } catch (Exception e) {
                PhoneLog.w(TAG, "🧹 [安全熔断] 关闭硬编码器发生不影响整体的内部波动: " + e.getMessage());
            }
            mEncoder = null;
        }
        
        if (mOutputStream != null) {
            PhoneLog.d(TAG, "🧹 [安全熔断] 正在关闭高速 Google Wear Channel 字节输出流阀门...");
            try {
                mOutputStream.close();
                PhoneLog.d(TAG, "🧹 [安全熔断] 物理管道输出流已彻底断开销毁。");
            } catch (Exception e) {
                PhoneLog.w(TAG, "🧹 [安全熔断] 关闭输出流管道遭遇短暂粘包阻断: " + e.getMessage());
            }
            mOutputStream = null;
        }
        
        if (mInputSurface != null) {
            PhoneLog.d(TAG, "🧹 [安全熔断] 正在释放 CameraX 的承载投影布幕 Surface...");
            mInputSurface.release();
            mInputSurface = null;
        }
        
        // 解綁 CameraX
        try {
            PhoneLog.d(TAG, "🧹 [安全熔断] 正在调用 ProcessCameraProvider 异步解绑清除全域 Preview 用例...");
            final ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);
            cameraProviderFuture.addListener(() -> {
                try {
                    ProcessCameraProvider cameraProvider = cameraProviderFuture.get();
                    cameraProvider.unbindAll();
                    PhoneLog.w(TAG, "🧹 [安全熔断结束] ━━━ 硬件流媒体所有底层依赖彻底清空解绑，服务降落到绝对安全地面 ━━━");
                } catch (Exception ignored) {}
            }, ContextCompat.getMainExecutor(this));
        } catch (Exception ignored) {}
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { 
        PhoneLog.d(TAG, "ℹ️ onBind 触发（当前服务设计为纯 StartService 模式，返回 null）");
        return null; 
    }

    @Override
    public void onDestroy() {
        PhoneLog.w(TAG, "🏳️ [生命周期] onDestroy ─── 服务彻底毁灭前夕，全盘清空资源...");
        releaseCameraAndPipeline();
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY);
        super.onDestroy();
        PhoneLog.w(TAG, "🏳️ [生命周期] onDestroy ─── 远程相机后台前台独立服务完整声明周期安全终结！");
    }
}
