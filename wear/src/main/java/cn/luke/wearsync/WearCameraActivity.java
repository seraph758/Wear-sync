package cn.luke.wearsync;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Bundle;
import android.os.PowerManager;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.WindowManager;
import android.widget.Button;
import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;
import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;

public class WearCameraActivity extends ComponentActivity implements SurfaceHolder.Callback {
    private static final String TAG = "WearSync_WearCameraUI";

    // 弱引用持有当前 Activity 实例，防止内存泄漏
    public static WeakReference<WearCameraActivity> sActivityRef = new WeakReference<>(null);

    private SurfaceView surfaceView;
    private MediaCodec mDecoder;
    private boolean isDecoderRunning = false;
    private boolean isUserExiting = false;
    private PowerManager.WakeLock wakeLock;
    private long activityCreateTime;
    private boolean isSurfaceReady = false;
    private final java.util.concurrent.ConcurrentLinkedQueue<byte[]> frameQueue = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private Thread renderThread;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        activityCreateTime = System.currentTimeMillis();
        WearLog.d(TAG, "🟢 [生命周期] onCreate 啟動時間戳: " + activityCreateTime);
        sActivityRef = new WeakReference<>(this);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.FULL_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP, "WearSync:CameraWakeLock");
            wakeLock.acquire(60 * 1000L);
            WearLog.d(TAG, "🔋 WakeLock acquired for 1 mins");
        }

        setContentView(R.layout.activity_wear_camera);

        surfaceView = findViewById(R.id.surfaceView);
        if (surfaceView != null) {
            surfaceView.getHolder().addCallback(this);
        } else {
            WearLog.e(TAG, "❌ 找不到 SurfaceView，请检查布局文件 ID 是否为 surfaceView");
        }

        Button btnClose = findViewById(R.id.btn_shutter);
        if (btnClose != null) {
            btnClose.setOnClickListener(_ -> {
                WearLog.d(TAG, "🔘 用户点击 [关闭相机]");
                WearSyncCommManager.getInstance(this).sendBusinessCommand("camera_action", "STOP_CAMERA");
                cleanExit(false);
            });
        }

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                WearLog.d(TAG, "🔙 用户按下返回键");
                WearSyncCommManager.getInstance(WearCameraActivity.this)
                        .sendBusinessCommand("camera_action", "STOP_CAMERA");
                cleanExit(false);
            }
        });

        startDecoderThread();

        // 🟢 [新增] 启动时向手机发送开启相机指令
        WearLog.d(TAG, "📤 发送开启手机相机指令");
        WearSyncCommManager.getInstance(this).sendBusinessCommand("camera_control", "open_phone_camera");
    }

    // ✅ 供 ListenerService 直接调用的静态强制关闭方法
    public static void forceClose() {
        WearCameraActivity activity = sActivityRef.get();
        if (activity != null && !activity.isUserExiting) {
            WearLog.w(TAG, "🚨 [外部指令] 触发 forceClose，准备干净退出");
            activity.runOnUiThread(() -> activity.cleanExit(true));
        }
    }

    public void feedH264Data(byte[] h264Data) {
        if (h264Data != null && h264Data.length > 0) {
            frameQueue.offer(h264Data);
        }
    }

    private void startDecoderThread() {
        renderThread = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                byte[] frameData = frameQueue.poll();
                if (frameData != null && isSurfaceReady) {
                    try {
                        int inputBufferId = mDecoder.dequeueInputBuffer(10000);
                        if (inputBufferId >= 0) {
                            ByteBuffer inputBuffer = mDecoder.getInputBuffer(inputBufferId);
                            if (inputBuffer != null) {
                                inputBuffer.clear();
                                inputBuffer.put(frameData);
                                mDecoder.queueInputBuffer(inputBufferId, 0, frameData.length, 0, 0);
                            }
                        }
                        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
                        int outputBufferId = mDecoder.dequeueOutputBuffer(bufferInfo, 10000);
                        if (outputBufferId >= 0) {
                            mDecoder.releaseOutputBuffer(outputBufferId, true);
                        }
                    } catch (Exception e) {
                        WearLog.e(TAG, "解码帧时出错", e);
                    }
                }
            }
        });
        renderThread.start();
    }

    private void initDecoder() {
        try {
            MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 640, 480);
            mDecoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            mDecoder.configure(format, surfaceView.getHolder().getSurface(), null, 0);
            mDecoder.start();
            isDecoderRunning = true;
            WearLog.d(TAG, "解码器初始化成功");
        } catch (Exception e) {
            WearLog.e(TAG, "初始化解码器失败", e);
        }
    }

    private void cleanExit(boolean fromPhone) {
        if (isUserExiting) return;
        isUserExiting = true;
        WearLog.d(TAG, "🚪 [干净退出] 开始执行，来源: " + (fromPhone ? "手机指令" : "用户操作"));

        if (renderThread != null) {
            renderThread.interrupt();
            try { renderThread.join(1000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            renderThread = null;
        }

        if (mDecoder != null) {
            try { mDecoder.stop(); mDecoder.release(); } catch (Exception e) { WearLog.e(TAG, "释放解码器异常", e); }
            mDecoder = null;
            isDecoderRunning = false;
        }

        frameQueue.clear();

        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
            wakeLock = null;
            WearLog.d(TAG, "🔋 WakeLock released");
        }

        if (sActivityRef != null) {
            sActivityRef.clear();
            sActivityRef = null;
        }

        finishAndRemoveTask();
        WearLog.d(TAG, "✅ [干净退出] 完成");
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        isSurfaceReady = true;
        initDecoder();
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {}

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        isSurfaceReady = false;
    }

    @Override
    protected void onDestroy() {
        cleanExit(false);
        super.onDestroy();
    }
}
