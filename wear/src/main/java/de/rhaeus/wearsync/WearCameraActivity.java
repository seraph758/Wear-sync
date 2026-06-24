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

    public static WeakReference<WearCameraActivity> sActivityRef = new WeakReference<>(null);

    private final BroadcastReceiver phoneKillReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent != null && "de.rhaeus.wearsync.ACTION_FORCE_QUIT_WEAR_CAMERA".equals(intent.getAction())) {
                WearLog.d(TAG, "📥 收到手机端被迫挂断指令，正在无条件退出手表流媒体界面...");
                cleanExit(false);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WearCameraActivity.sActivityRef = new WeakReference<>(this);
        setContentView(R.layout.activity_wear_camera);

        surfaceView = findViewById(R.id.surfaceView);
        surfaceView.getHolder().addCallback(this);

        Button btnShutter = findViewById(R.id.btn_shutter);
        btnShutter.setOnClickListener(v -> {
            WearLog.d(TAG, "🔘 用户按下手表快门，下发触发拍照脉冲");
            sendControlSignalToPhone("ACTION_TRIGGER_SHUTTER");
        });

        Button btnClose = findViewById(R.id.btn_close);
        btnClose.setOnClickListener(v -> {
            WearLog.d(TAG, "🔘 用户点击手表关闭，主动断开链路");
            cleanExit(true);
        });

        IntentFilter filter = new IntentFilter("de.rhaeus.wearsync.ACTION_FORCE_QUIT_WEAR_CAMERA");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            registerReceiver(phoneKillReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(phoneKillReceiver, filter);
        }
        WearLog.d(TAG, "🎬 onCreate: 手表观景窗 Activity 完成初始化准备");
    }

    public void adjustRotation(int degrees) {
        runOnUiThread(() -> {
            if (surfaceView != null) {
                WearLog.d(TAG, "📐 收到手机端传感重力方向 ➔ 正在渲染动态旋转: " + degrees + "度");
                surfaceView.setRotation(degrees);
            }
        });
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        WearLog.d(TAG, "🖥️ Surface 硬件载体已成功构建，准备初始化 H264 视频解码器...");
        initDecoder(holder);
    }

    private void initDecoder(SurfaceHolder holder) {
        try {
            mDecoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 640, 480);
            mDecoder.configure(format, holder.getSurface(), null, 0);
            mDecoder.start();
            isDecoderRunning = true;
            WearLog.d(TAG, "✨ H.264 底层硬解引擎成功起飞！");
        } catch (Exception e) {
            WearLog.e(TAG, "❌ 解码器初始化严重受阻: " + e.getMessage(), e);
        }
    }

    public void feedH264Data(byte[] data, int length) {
        if (!isDecoderRunning || mDecoder == null) return;
        try {
            int inputBufferIndex = mDecoder.dequeueInputBuffer(10000);
            if (inputBufferIndex >= 0) {
                ByteBuffer inputBuffer = mDecoder.getInputBuffer(inputBufferIndex);
                if (inputBuffer != null) {
                    inputBuffer.clear();
                    inputBuffer.put(data, 0, length);
                    mDecoder.queueInputBuffer(inputBufferIndex, 0, length, System.currentTimeMillis(), 0);
                }
            }
            MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
            int outputBufferIndex = mDecoder.dequeueOutputBuffer(bufferInfo, 10000);
            while (outputBufferIndex >= 0) {
                mDecoder.releaseOutputBuffer(outputBufferIndex, true);
                outputBufferIndex = mDecoder.dequeueOutputBuffer(bufferInfo, 0);
            }
        } catch (Exception e) {
            WearLog.e(TAG, "⚠️ 帧流灌入硬解管线发生拥堵波动: " + e.getMessage());
        }
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {}

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        WearLog.w(TAG, "🖥️ Surface 被销毁，正在强制熔断解码管线...");
        releaseDecoder();
    }

    private void releaseDecoder() {
        isDecoderRunning = false;
        if (mDecoder != null) {
            try {
                mDecoder.stop();
                mDecoder.release();
            } catch (Exception ignored) {}
            mDecoder = null;
            WearLog.d(TAG, "🛑 解码器硬件资源已安全释放回收");
        }
    }

    private void sendControlSignalToPhone(String actionCommand) {
        new Thread(() -> {
            try {
                JSONObject json = new JSONObject();
                json.put("sender", "wear");
                json.put("type", "camera_control");
                json.put("action", actionCommand);
                json.put("timestamp", System.currentTimeMillis());

                byte[] payload = json.toString().getBytes(StandardCharsets.UTF_8);
                List<Node> nodes = Tasks.await(Wearable.getNodeClient(this).getConnectedNodes());
                if (nodes != null) {
                    for (Node n : nodes) {
                        Wearable.getMessageClient(this).sendMessage(n.getId(), UNIVERSAL_SYNC_PATH, payload);
                    }
                }
            } catch (Exception e) {
                WearLog.e(TAG, "❌ 向手机端投递相机控场指令失败", e);
            }
        }).start();
    }

    private void cleanExit(boolean notifyPhone) {
        if (isUserExiting) return;
        isUserExiting = true;
        WearLog.d(TAG, "🧹 正在启动手表相机退出机制...");

        if (notifyPhone) {
            sendControlSignalToPhone("STOP_CAMERA");
        }
        try {
            unregisterReceiver(phoneKillReceiver);
        } catch (Exception ignored) {}
        releaseDecoder();
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
        cleanExit(false);
        super.onDestroy();
    }
}
