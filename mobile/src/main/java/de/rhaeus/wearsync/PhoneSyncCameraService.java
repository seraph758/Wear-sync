package de.rhaeus.wearsync;

import android.content.Context;
import android.content.Intent;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleService;

import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.ChannelClient;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;

import org.json.JSONObject;

import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class PhoneSyncCameraService extends LifecycleService {
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

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        super.onStartCommand(intent, flags, startId); // LifecycleService 必须保留
        Log.d(TAG, "🚀 PhoneSyncCameraService 收到触发信令...");

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            String channelId = "camera_sync_channel";
            android.app.NotificationChannel channel = new android.app.NotificationChannel(
                    channelId, "相机远端同步", android.app.NotificationManager.IMPORTANCE_LOW);
            android.app.NotificationManager nm = (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(channel);

            android.app.Notification notification = new android.app.Notification.Builder(this, channelId)
                    .setContentTitle("WearSync")
                    .setContentText("远端相机流同步交互中...")
                    .setSmallIcon(android.R.drawable.ic_menu_camera)
                    .build();
            startForeground(8899, notification);
        }

        if (intent == null || intent.getAction() == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        String action = intent.getAction();
        Log.d(TAG, "⚙️ 手机相机服务收到动作: " + action);

        if (ACTION_START_CAMERA.equals(action)) {
            startCameraAndSetupPipeline();
        } else if (ACTION_STOP_CAMERA_STREAM.equals(action)) {
            releaseCameraAndPipeline();
        } else if (ACTION_TRIGGER_SHUTTER.equals(action)) {
            executePhoneShutter();
        }

        return START_NOT_STICKY;
    }

    private void startCameraAndSetupPipeline() {
        if (isStreaming) return;
        isStreaming = true;

        new Thread(() -> {
            try {
                // 1. 初始化 H.264 硬件编码器
                MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 640, 480);
                format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
                format.setInteger(MediaFormat.KEY_BIT_RATE, 1000000); // 1Mbps 足够手表清晰预览
                format.setInteger(MediaFormat.KEY_FRAME_RATE, 24);
                format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);

                mEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
                mEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                
                // 获取编码器的零拷贝输入表面
                Surface inputSurface = mEncoder.createInputSurface();
                mEncoder.start();

                int rotationDegrees = calculatePhoneRotation();
                Log.d(TAG, "📐 手机检测到当前画面旋转角度: " + rotationDegrees);
                sendControlMessageToWatch("START_CAMERA", rotationDegrees);

                // 2. 建立与手表的 Channel 连接
                List<Node> nodes = Tasks.await(Wearable.getNodeClient(this).getConnectedNodes());
                if (nodes != null && !nodes.isEmpty()) {
                    String watchNodeId = nodes.get(0).getId();
                    mTargetChannel = Tasks.await(Wearable.getChannelClient(this)
                            .openChannel(watchNodeId, CAMERA_PREVIEW_STREAM_PATH));
                    mChannelOutputStream = Tasks.await(Wearable.getChannelClient(this)
                            .getOutputStream(mTargetChannel));
                    Log.d(TAG, "🚀 [管道打通] 开始向手表泵入 H.264 实时帧。");

                    // 3. 在主线程启动 CameraX，并将画面直通给编码器
                    new Handler(Looper.getMainLooper()).post(() -> bindCameraXToSurface(inputSurface));

                    // 4. 开启推流死循环
                    pumpEncodedStreamToWatch();
                } else {
                    Log.w(TAG, "⚠️ 找不到可用的手表节点");
                    releaseCameraAndPipeline();
                }

            } catch (Exception e) {
                Log.e(TAG, "相机管道建立失败", e);
                releaseCameraAndPipeline();
            }
        }).start();
    }

    private void bindCameraXToSurface(Surface encoderInputSurface) {
        ProcessCameraProvider.getInstance(this).addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = ProcessCameraProvider.getInstance(this).get();
                cameraProvider.unbindAll();

                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(request -> {
                    request.provideSurface(encoderInputSurface, ContextCompat.getMainExecutor(PhoneSyncCameraService.this), result -> {
                        // Surface 释放时的回调处理
                    });
                });

                CameraSelector cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;
                // 将 CameraX 生命周期绑定到当前 LifecycleService
                cameraProvider.bindToLifecycle(this, cameraSelector, preview);

            } catch (Exception e) {
                Log.e(TAG, "CameraX 绑定 Surface 失败", e);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void pumpEncodedStreamToWatch() {
        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
        while (isStreaming && mEncoder != null && mChannelOutputStream != null) {
            try {
                int outputBufferIndex = mEncoder.dequeueOutputBuffer(bufferInfo, 10000);
                if (outputBufferIndex >= 0) {
                    ByteBuffer outputBuffer = mEncoder.getOutputBuffer(outputBufferIndex);
                    byte[] outData = new byte[bufferInfo.size];
                    outputBuffer.get(outData);

                    // 纯裸流直写蓝牙管道
                    mChannelOutputStream.write(outData);
                    mChannelOutputStream.flush();

                    mEncoder.releaseOutputBuffer(outputBufferIndex, false);
                }
            } catch (Exception e) {
                Log.e(TAG, "推流循环中断", e);
                break;
            }
        }
    }

    private void executePhoneShutter() {
        Log.d(TAG, "📸 [快门联动] 手机收到快门指令！此处可扩展实际 CameraX 拍照逻辑。");
    }

    private void releaseCameraAndPipeline() {
        if (!isStreaming) return;
        isStreaming = false;
        Log.d(TAG, "🧹 销毁释放手机端相机与传输管道...");

        try {
            if (mEncoder != null) {
                mEncoder.stop();
                mEncoder.release();
                mEncoder = null;
            }
            
            // 安全解绑 CameraX
            new Handler(Looper.getMainLooper()).post(() -> {
                try {
                    ProcessCameraProvider cameraProvider = ProcessCameraProvider.getInstance(this).get();
                    cameraProvider.unbindAll();
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
        } catch (Exception e) {
            Log.e(TAG, "释放相机管道资源时发生异常", e);
        } finally {
            stopSelf();
        }
    }

    private void sendControlMessageToWatch(String actionStr, int rotation) {
        try {
            JSONObject json = new JSONObject();
            json.put("sender", "phone");
            json.put("type", "camera_control");
            json.put("action", actionStr);
            json.put("rotation_degrees", rotation);

            byte[] payload = json.toString().getBytes(StandardCharsets.UTF_8);
            List<Node> nodes = Tasks.await(Wearable.getNodeClient(this).getConnectedNodes());
            if (nodes != null) {
                for (Node n : nodes) {
                    Wearable.getMessageClient(this).sendMessage(n.getId(), UNIVERSAL_SYNC_PATH, payload);
                }
            }
        } catch (Exception ignored) {}
    }

    private int calculatePhoneRotation() {
        WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        if (wm == null) return 0;
        int rotation = wm.getDefaultDisplay().getRotation();
        switch (rotation) {
            case 1: return 90;
            case 2: return 180;
            case 3: return 270;
            default: return 0;
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        super.onBind(intent); // LifecycleService 必须调用父类
        return null; 
    }
    
    @Override
    public void onDestroy() {
        releaseCameraAndPipeline();
        super.onDestroy();
    }
}
