package de.rhaeus.wearsync;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
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
 * 变更：全面替换为 PhoneLog 体系，实现高负载硬编码下的零日志省电开销，彻底消灭百行空行。
 */
public class PhoneSyncCameraService extends Service implements LifecycleOwner {

    private static final String TAG = "WearSync_PhoneCamera";
    private static final String UNIVERSAL_SYNC_PATH = "/wear-universal-sync";
    private static final String CAMERA_PREVIEW_STREAM_PATH = "/camera-preview-stream";

    public static final String ACTION_START_CAMERA = "de.rhaeus.wearsync.ACTION_START_CAMERA";
    public static final String ACTION_STOP_CAMERA_STREAM = "de.rhaeus.wearsync.ACTION_STOP_CAMERA_STREAM";
    public static final String ACTION_TRIGGER_SHUTTER = "de.rhaeus.wearsync.ACTION_TRIGGER_SHUTTER";

    private MediaCodec mEncoder;
    private ChannelClient.Channel mTargetChannel;
    private OutputStream mChannelOutputStream;
    private boolean isStreaming = false;
    private LifecycleRegistry lifecycleRegistry;

    @NonNull
    @Override
    public Lifecycle getLifecycle() {
        return lifecycleRegistry;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        lifecycleRegistry = new LifecycleRegistry(this);
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START);

        if (intent == null || intent.getAction() == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        String action = intent.getAction();
        PhoneLog.d(TAG, "📥 [服务收到 Action] ➔ " + action);

        if (ACTION_START_CAMERA.equals(action) || "START_CAMERA".equals(action)) {
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                String channelId = "camera_sync_channel";
                android.app.NotificationChannel channel = new android.app.NotificationChannel(
                        channelId, "相机远端同步", android.app.NotificationManager.IMPORTANCE_LOW);
                android.app.NotificationManager nm = (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                if (nm != null) nm.createNotificationChannel(channel);

                android.app.Notification notification = new android.app.Notification.Builder(this, channelId)
                        .setContentTitle("WearSync")
                        .setContentText("远端相机同步中...")
                        .setSmallIcon(android.R.drawable.ic_menu_camera)
                        .build();
                startForeground(8899, notification);
            }
            startCameraAndSetupPipeline();
        } else if (ACTION_STOP_CAMERA_STREAM.equals(action) || "de.rhaeus.wearsync.ACTION_STOP_CAMERA".equals(action)) {
            PhoneLog.d(TAG, "🛑 收到明确停止请求，开始释放相机管线资源...");
            releaseCameraAndPipeline();
            stopSelf();
        } else if (ACTION_TRIGGER_SHUTTER.equals(action)) {
            executePhoneShutter();
        }

        return START_NOT_STICKY;
    }

    private void startCameraAndSetupPipeline() {
        if (isStreaming) {
            PhoneLog.w(TAG, "⚠️ 相机流已经在运行中，拒绝重复开启");
            return;
        }
        isStreaming = true;

        new Thread(() -> {
            try {
                PhoneLog.d(TAG, "⚙️ 开始配置 MediaCodec H.264 (AVC) 视频硬编码器 [640x480, 24fps]...");
                MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 640, 480);
                format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
                format.setInteger(MediaFormat.KEY_BIT_RATE, 1000000);
                format.setInteger(MediaFormat.KEY_FRAME_RATE, 24);
                format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);

                mEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
                mEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                Surface inputSurface = mEncoder.createInputSurface();
                mEncoder.start();

                int rotation = calculatePhoneRotation();
                PhoneLog.d(TAG, "⚙️ 手机当前屏幕旋转弧度 = " + rotation + "，正在向手表握手下发旋转角度基准...");
                sendControlMessageToWatch("START_CAMERA", rotation);

                String watchNodeId = WearSyncState.getNodeId(this);
                if (watchNodeId == null || watchNodeId.isEmpty()) {
                    PhoneLog.w(TAG, "⚠️ 内存中未命中手表 ID，开始拉起全网节点探针...");
                    List<Node> nodes = Tasks.await(Wearable.getNodeClient(this).getConnectedNodes());
                    if (nodes != null && !nodes.isEmpty()) {
                        watchNodeId = nodes.get(0).getId();
                        WearSyncState.setNodeId(this, watchNodeId);
                    }
                }

                if (watchNodeId != null) {
                    PhoneLog.d(TAG, "📡 正在通过谷歌微端创建高性能流媒体传输管道 (Channel Client)...");
                    mTargetChannel = Tasks.await(Wearable.getChannelClient(this).openChannel(watchNodeId, CAMERA_PREVIEW_STREAM_PATH));
                    mChannelOutputStream = Tasks.await(Wearable.getChannelClient(this).getOutputStream(mTargetChannel));

                    PhoneLog.d(TAG, "📸 异步抛回主线程：准备将 CameraX 预览层表面绑定到 MediaCodec Surface 上...");
                    new Handler(Looper.getMainLooper()).post(() -> bindCameraXToSurface(inputSurface));

                    PhoneLog.d(TAG, "🚀 [核心推流引擎] 成功点火！开始循环向通道灌入 H.264 原始帧字节数组...");
                    pumpEncodedStreamToWatch();
                } else {
                    PhoneLog.e(TAG, "❌ [推流中止] 未能发现任何处于在线连线状态的手表节点！");
                    releaseCameraAndPipeline();
                }
            } catch (Exception e) {
                PhoneLog.e(TAG, "🔴 [致命错误] 编码器硬管线搭建失败: " + e.getMessage(), e);
                releaseCameraAndPipeline();
            }
        }).start();
    }

    private void bindCameraXToSurface(Surface encoderInputSurface) {
        ProcessCameraProvider.getInstance(this).addListener(() -> {
            try {
                ProcessCameraProvider provider = ProcessCameraProvider.getInstance(this).get();
                provider.unbindAll();

                Preview preview = new Preview.Builder()
                        .setTargetResolution(new android.util.Size(640, 480))
                        .build();

                preview.setSurfaceProvider(request -> request.provideSurface(
                        encoderInputSurface,
                        ContextCompat.getMainExecutor(this),
                        result -> {}
                ));

                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview);
                PhoneLog.d(TAG, "✨ CameraX 预览管道与硬解编码器输入表面绑定大功告成！");
            } catch (Exception e) {
                PhoneLog.e(TAG, "🔴 CameraX 跨生命周期宿主绑定失败: " + e.getMessage(), e);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void pumpEncodedStreamToWatch() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        // 🌟 重点优化：在此高频循环中全面杜绝原生 Log。通过 PhoneLog 在开关闭合时将实现完全零调用，省电且不堵塞线程
        while (isStreaming && mEncoder != null && mChannelOutputStream != null) {
            try {
                int index = mEncoder.dequeueOutputBuffer(info, 10000);
                if (index >= 0) {
                    ByteBuffer buffer = mEncoder.getOutputBuffer(index);
                    if (buffer != null && info.size > 0) {
                        byte[] data = new byte[info.size];
                        buffer.position(info.offset);
                        buffer.limit(info.offset + info.size);
                        buffer.get(data);

                        mChannelOutputStream.write(data);
                        mChannelOutputStream.flush();
                    }
                    mEncoder.releaseOutputBuffer(index, false);
                }
            } catch (Exception e) {
                PhoneLog.e(TAG, "⚠️ [推流异常中断] 循环推流遭遇 IO 管道阻塞或主动熔断: " + e.getMessage());
                break;
            }
        }
    }

    private void executePhoneShutter() {
        PhoneLog.d(TAG, "📸 [快门动作] 收到手表下发的硬件快门拍照脉冲信号！");
    }

    private void releaseCameraAndPipeline() {
        if (!isStreaming) return;
        isStreaming = false;
        PhoneLog.w(TAG, "🛑 [管线开始熔断] 正在全力回收相机、编码器及数据流通道...");

        try {
            if (mEncoder != null) {
                mEncoder.stop();
                mEncoder.release();
                mEncoder = null;
            }
            new Handler(Looper.getMainLooper()).post(() -> {
                try {
                    ProcessCameraProvider.getInstance(this).get().unbindAll();
                    PhoneLog.d(TAG, "🛑 CameraX 组件解绑完成");
                } catch (Exception ignored) {}
            });
            if (mChannelOutputStream != null) {
                mChannelOutputStream.close();
                mChannelOutputStream = null;
            }
            if (mTargetChannel != null) {
                Wearable.getChannelClient(this).close(mTargetChannel);
                mTargetChannel = null;
            }
            sendControlMessageToWatch("FORCE_QUIT_CAMERA", 0);
            PhoneLog.d(TAG, "✨ 拍照所有硬管线资源解脱完毕");
        } catch (Exception e) {
            PhoneLog.e(TAG, "🔴 熔断管线抛出资源释放异常: " + e.getMessage());
        }
    }

    private void sendControlMessageToWatch(String action, int rotation) {
        new Thread(() -> {
            try {
                JSONObject json = new JSONObject();
                json.put("sender", "phone");
                json.put("type", "camera_control");
                json.put("action", action);
                json.put("rotation_degrees", rotation);

                byte[] data = json.toString().getBytes(StandardCharsets.UTF_8);
                String nodeId = WearSyncState.getNodeId(this);

                if (nodeId != null) {
                    Tasks.await(Wearable.getMessageClient(this).sendMessage(nodeId, UNIVERSAL_SYNC_PATH, data));
                }
            } catch (Exception e) {
                PhoneLog.e(TAG, "🔴 向手表同步相机控制反向信令失败", e);
            }
        }).start();
    }

    private int calculatePhoneRotation() {
        WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        if (wm == null) return 0;
        switch (wm.getDefaultDisplay().getRotation()) {
            case 1: return 90;
            case 2: return 180;
            case 3: return 270;
            default: return 0;
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        releaseCameraAndPipeline();
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY);
        super.onDestroy();
    }
}

