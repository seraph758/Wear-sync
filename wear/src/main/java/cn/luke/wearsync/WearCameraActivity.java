package cn.luke.wearsync;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Bundle;
import android.os.PowerManager;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.WindowManager;
import android.widget.Button;
import android.os.VibrationEffect;
import androidx.core.content.ContextCompat;
import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;
import androidx.localbroadcastmanager.content.LocalBroadcastManager; // ✅ 新增导入
import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;
import org.json.JSONObject;
import org.json.JSONException; // ✅ 新增导入
import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;

// 🟢 完美優化：變更繼承為 ComponentActivity 以原生支持現代返回調度器
public class WearCameraActivity extends ComponentActivity implements SurfaceHolder.Callback {
    private static final String TAG = "WearSync_WearCameraUI";
    // 🚀 彻底删除了原有的 static instance 泄露源，全部由下方的 sActivityRef 弱引用全权接管
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

    private final BroadcastReceiver phoneKillReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if ("cn.luke.wearsync.ACTION_PHONE_KILL_CAMERA".equals(intent.getAction())) {
                WearLog.w(TAG, "🚨 [手機端熔斷信號] 收到手機端要求強制關閉相機命令，執行乾淨退出...");
                cleanExit(false);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        activityCreateTime = System.currentTimeMillis();
        // 修改点1: 将 WearLog.i 改为 WearLog.d (假设你的类里只有 d 方法)
        WearLog.d(TAG, "🟢 [生命周期] onCreate 啟動時間戳: " + activityCreateTime);
        sActivityRef = new WeakReference<>(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            // 注意：FULL_WAKE_LOCK 已过时，但目前为了编译通过先保留，后续建议改为 FLAG_KEEP_SCREEN_ON
            wakeLock = pm.newWakeLock(PowerManager.FULL_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP, "WearSync:CameraWakeLock");
            wakeLock.acquire(1 * 60 * 1000L);
            WearLog.d(TAG, "🔋 WakeLock acquired for 1 mins");
        }
        setContentView(R.layout.activity_wear_camera);
        // 修改点2: 修正 findViewById 的逻辑，使用 XML 中实际存在的 ID 'surfaceView'
        surfaceView = findViewById(R.id.surfaceView);
        if (surfaceView != null) {
            surfaceView.getHolder().addCallback(this);
        } else {
            WearLog.e(TAG, "❌ 找不到 SurfaceView，请检查布局文件 ID 是否为 surfaceView");
        }
        // 修改点3: 修正按钮 ID，XML 中是 btn_shutter，代码里写成了 btn_close_camera
        Button btnClose = findViewById(R.id.btn_shutter);
                if (btnClose != null) {
            btnClose.setOnClickListener(v -> {
                WearLog.d(TAG, "🔘 用户点击 [关闭相机]");
                // 发送关闭指令给手机
                WearSyncCommManager.getInstance(this).sendBusinessCommand("camera_action", "STOP");
                cleanExit(false);
            });
        }

        // 注册返回键回调，实现干净退出
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                WearLog.d(TAG, "🔙 用户按下返回键");
                cleanExit(false);
            }
        });

        // 注册广播接收器，监听来自手机的关闭指令
        IntentFilter filter = new IntentFilter("cn.luke.wearsync.ACTION_PHONE_KILL_CAMERA");
        ContextCompat.registerReceiver(this, phoneKillReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);

        // 启动解码线程
        startDecoderThread();
    }

    // ✅ 新增：处理来自 CommManager 的指令
    public static void handleIncomingCommand(Context context, JSONObject json) {
        WearLog.d(TAG, "收到相机控制指令: " + json.toString());
        String action = json.optString("action");
        if ("STOP".equals(action)) {
            // 可以在这里执行一些逻辑，然后关闭界面
            if (sActivityRef.get() != null) {
                sActivityRef.get().cleanExit(false);
            }
        }
    }
    public void feedH264Data(byte[] frame, int length) {
            // 创建一个精确长度的新数组，避免传递 buffer 中的无效数据
            byte[] frameCopy = new byte[length];
            System.arraycopy(frame, 0, frameCopy, 0, length);
            // 将数据帧放入队列，等待解码线程处理
            frameQueue.offer(frameCopy);
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
            // 假设视频是 H.264 编码，分辨率 640x480
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

    /**
     * 🚀 核心修复：无条件停止解码 + 释放屏幕资源
     * @param fromPhone 是否由手机端指令触发
     */
    private void cleanExit(boolean fromPhone) {
        if (isUserExiting) return;
        isUserExiting = true;
        WearLog.d(TAG, "🚪 [干净退出] 开始执行，来源: " + (fromPhone ? "手机指令" : "用户操作"));

        // 1. 停止解码线程
        if (renderThread != null) {
            renderThread.interrupt();
            try {
                renderThread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            renderThread = null;
        }

        // 2. 释放解码器
        if (mDecoder != null) {
            try {
                mDecoder.stop();
                mDecoder.release();
            } catch (Exception e) {
                WearLog.e(TAG, "释放解码器异常", e);
            }
            mDecoder = null;
            isDecoderRunning = false;
        }

        // 3. 清空帧队列
        frameQueue.clear();

        // 4. 释放 WakeLock
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
            wakeLock = null;
            WearLog.d(TAG, "🔋 WakeLock released");
        }

        // 5. 移除广播接收器
        try {
            unregisterReceiver(phoneKillReceiver);
        } catch (Exception e) {
            // 可能未注册，忽略
        }

        // 6. 清除静态引用
        if (sActivityRef != null) {
            sActivityRef.clear();
            sActivityRef = null;
        }

        // 7. 结束 Activity
        finishAndRemoveTask();
        WearLog.d(TAG, "✅ [干净退出] 完成");
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        isSurfaceReady = true;
        initDecoder();
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        isSurfaceReady = false;
    }

    @Override
    protected void onDestroy() {
        // 双重保险，确保资源被释放
        cleanExit(false);
        super.onDestroy();
    }
}
