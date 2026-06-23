package de.rhaeus.wearsync;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.Button;
import android.widget.RelativeLayout;

import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;

import org.json.JSONObject;

import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class WearCameraActivity extends Activity implements SurfaceHolder.Callback {
    private static final String TAG = "WearSync_WearCameraUI";
    private static final String UNIVERSAL_SYNC_PATH = "/wear-universal-sync";

    private SurfaceView surfaceView;
    private MediaCodec mDecoder;
    private boolean isDecoderRunning = false;
    private boolean isUserExiting = false;

    // 暴露引用给 ListenerService 使用
    public static WeakReference<WearCameraActivity> sActivityRef = new WeakReference<>(null);

    private final BroadcastReceiver phoneKillReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if ("de.rhaeus.wearsync.ACTION_KILL_WEAR_CAMERA".equals(intent.getAction())) {
                Log.d(TAG, "🏳️ 收到手机端关闭指令的本地广播，手表端 Camera UI 无条件自杀退出...");
                cleanExit(false);
            }
        }
    };

    public static void forceQuitInstance() {
        WearCameraActivity activity = sActivityRef.get();
        if (activity != null) {
            Log.d(TAG, "🛑 触发兜底自杀退出...");
            activity.cleanExit(false);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_wear_camera);

        sActivityRef = new WeakReference<>(this);

        surfaceView = findViewById(R.id.surface_camera_preview);
        surfaceView.getHolder().addCallback(this);

        RelativeLayout rootLayout = findViewById(R.id.layout_camera_root);
        if (rootLayout != null) {
            rootLayout.setOnClickListener(v -> {
                Log.d(TAG, "📸 用户轻触全屏任意区域 ➔ 触发倒计时");
                startCaptureCountdown();
            });
        }

        Button btnCapture = findViewById(R.id.btnCapture);
        if (btnCapture != null) {
            btnCapture.setOnClickListener(v -> {
                Log.d(TAG, "📸 用户点击了底部的专属相机按钮 ➔ 触发倒计时");
                startCaptureCountdown();
            });
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(phoneKillReceiver, new IntentFilter("de.rhaeus.wearsync.ACTION_KILL_WEAR_CAMERA"), Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(phoneKillReceiver, new IntentFilter("de.rhaeus.wearsync.ACTION_KILL_WEAR_CAMERA"));
        }
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        try {
            MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 640, 480);
            mDecoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            mDecoder.configure(format, holder.getSurface(), null, 0);
            mDecoder.start();
            isDecoderRunning = true;
            
            // 🎯 核心补充：当画布准备好后，立刻向手机发射唤醒指令！
            Log.d(TAG, "📺 画布就绪，正在呼叫手机端...");
            sendControlSignalToPhone("START_CAMERA");

        } catch (Exception e) {
            Log.e(TAG, "解码器初始化失败", e);
        }
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        // Surface 大小改变，暂无需处理
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        if (mDecoder != null) {
            try {
                mDecoder.stop();
                mDecoder.release();
            } catch (Exception ignored) {}
            mDecoder = null;
            isDecoderRunning = false;
        }
    }

    public void feedH264Data(byte[] data, int length) {
        if (!isDecoderRunning || mDecoder == null || isUserExiting) return;

        try {
            int inputBufferIndex = mDecoder.dequeueInputBuffer(10000);
            if (inputBufferIndex >= 0) {
                ByteBuffer inputBuffer = mDecoder.getInputBuffer(inputBufferIndex);
                inputBuffer.clear();
                inputBuffer.put(data, 0, length);
                mDecoder.queueInputBuffer(inputBufferIndex, 0, length, System.currentTimeMillis() * 1000, 0);
            }

            MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
            int outputBufferIndex = mDecoder.dequeueOutputBuffer(bufferInfo, 0);
            while (outputBufferIndex >= 0) {
                mDecoder.releaseOutputBuffer(outputBufferIndex, true); 
                outputBufferIndex = mDecoder.dequeueOutputBuffer(bufferInfo, 0);
            }
        } catch (Exception e) {
            Log.e(TAG, "解码馈入失败", e);
        }
    }

    private void sendControlSignalToPhone(String actionStr) {
        new Thread(() -> {
            try {
                JSONObject json = new JSONObject();
                json.put("sender", "wear");
                json.put("type", "camera_control");
                json.put("action", actionStr);
                json.put("timestamp", System.currentTimeMillis());

                byte[] payload = json.toString().getBytes(StandardCharsets.UTF_8);
                List<Node> nodes = Tasks.await(Wearable.getNodeClient(this).getConnectedNodes());
                if (nodes != null) {
                    for (Node n : nodes) {
                        Wearable.getMessageClient(this).sendMessage(n.getId(), UNIVERSAL_SYNC_PATH, payload);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "向手机端投递相机控场指令失败", e);
            }
        }).start();
    }

    private void startCaptureCountdown() {
        new android.os.CountDownTimer(3000, 1000) {
            public void onTick(long millisUntilFinished) {
                Log.d(TAG, "📸 倒计时: " + (millisUntilFinished / 1000 + 1));
            }
            public void onFinish() {
                Log.d(TAG, "📸 倒计时结束 ➔ 下发快门");
                sendControlSignalToPhone("CAPTURE_SHUTTER");
            }
        }.start();
    }

    private void cleanExit(boolean notifyPhone) {
        if (isUserExiting) return;
        isUserExiting = true;
        Log.d(TAG, "🧹 正在启动手表相机退出机制...");

        if (notifyPhone) {
            sendControlSignalToPhone("STOP_CAMERA");
        }

        try {
            unregisterReceiver(phoneKillReceiver);
        } catch (Exception ignored) {}

        if (sActivityRef.get() == this) {
            sActivityRef.clear();
        }

        finishAndRemoveTask(); 
    }

    @Override
    public void onBackPressed() {
        cleanExit(true);
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        cleanExit(true);
        super.onDestroy();
    }
}
