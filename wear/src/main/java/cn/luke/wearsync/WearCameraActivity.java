package cn.luke.wearsync;

import android.app.Activity;
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
import cn.luke.wearsync.wear.R;  // 🎯 指向你最新的手表端 namespace 


import androidx.core.content.ContextCompat;

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

    // 🚀 彻底删除了原有的 static instance 泄露源，全部由下方的 sActivityRef 弱引用全权接管
    public static WeakReference<WearCameraActivity> sActivityRef = new WeakReference<>(null);

    private SurfaceView surfaceView;
    private MediaCodec mDecoder;
    private boolean isDecoderRunning = false;
    private boolean isUserExiting = false;
    private PowerManager.WakeLock wakeLock;
    private long activityCreateTime;

    private long totalFramesDecoded = 0;
    private long lastLogTime = 0;
    private boolean isFirstFrameDecoded = false;
    private volatile boolean isSurfaceReady = false;
    private volatile boolean isChannelReady = false;
    private volatile boolean decoderReady = false;

    // 🚀 提供给外部调用的标准静态获取单例方法，安全可靠不泄漏
    public static WearCameraActivity getInstance() {
        return (sActivityRef != null) ? sActivityRef.get() : null;
    }

    private final BroadcastReceiver phoneKillReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            String action = intent.getAction();
            WearLog.w(TAG, "📥 [逆向熔断广播] 接收到全局通知 Action: [" + action + "]");
            if ("cn.luke.wearsync.ACTION_FORCE_QUIT_WEAR_CAMERA".equals(action)) {
                WearLog.w(TAG, "📥 [逆向熔断广播] 🎯 确认命中手机端挂断指令！正在启动无条件退出机制...");
                cleanExit(false);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        WearLog.d(TAG, "① [生命周期] onCreate 点火 ─── 手表观景窗 Activity 开始加载初始化...");
        activityCreateTime = System.currentTimeMillis();
        WearLog.d(TAG, "CAM-001 Activity onCreate");
        super.onCreate(savedInstanceState);
        setShowWhenLocked(true);
        setTurnScreenOn(true);

        // 🚀 核心修改：移除 instance=this，只保留安全的弱引用初始化
        WearCameraActivity.sActivityRef = new WeakReference<>(this);

        WearLog.w(TAG, "💡 [电源管理] 正在向当前 Window 注入 FLAG_KEEP_SCREEN_ON 旗帜，强制压制手表的休眠机制...");
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(
                        PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                                | PowerManager.ACQUIRE_CAUSES_WAKEUP,
                        TAG + ":CameraWake");
                wakeLock.acquire(10 * 60 * 1000L);
                WearLog.d(TAG, "💡 WakeLock 已获取，强制点亮屏幕并保持唤醒");
            }
        } catch (Exception e) {
            WearLog.e(TAG, "获取 WakeLock 失败: " + e.getMessage());
        }
        WearLog.d(TAG, "✨ [电源管理] 窗口高亮锁成功挂载，观景窗前台常亮已生效。");

        setContentView(R.layout.activity_wear_camera);

        surfaceView = findViewById(R.id.surfaceView);
        WearLog.d(TAG, "⚙️ [UI挂载] 正在将 SurfaceHolder 渲染回调绑定至 Activity...");
        surfaceView.getHolder().addCallback(this);

        Button btnShutter = findViewById(R.id.btn_shutter);
        btnShutter.setOnClickListener(v -> {
            WearLog.d(TAG, "🔘 [交互触发] ━━━ 用户按下手表物理/虚拟快门 ━━━ 准备向手机发射脉冲...");
            sendControlSignalToPhone("ACTION_TRIGGER_SHUTTER");
        });

        WearLog.d(TAG, "⚙️ [信令挂载] 正在构建逆向断开拦截器 IntentFilter...");
        IntentFilter filter = new IntentFilter("cn.luke.wearsync.ACTION_FORCE_QUIT_WEAR_CAMERA");

        ContextCompat.registerReceiver(this, phoneKillReceiver, filter, ContextCompat.RECEIVER_EXPORTED);
        WearLog.d(TAG, "⚙️ [信令挂载] 动态广播无缝兼容注册成功");

        totalFramesDecoded = 0;
        isFirstFrameDecoded = false;
        WearLog.d(TAG, "🎬 [生命周期] onCreate 结束 ─── 手表端图传观景窗 Activity 全功能就绪。");
    }

    @Override
    protected void onResume() {
        super.onResume();
        WearLog.d(TAG, "📺 Activity 已进入前台，Surface=" + (surfaceView != null));
    }

    @Override
    protected void onPause() {
        super.onPause();
        WearLog.d(TAG, "📺 Activity onPause()");
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        WearLog.d(TAG, "🖥️ [Surface状态] ─── 物理 Surface 硬件画布载体已成功构建完成 ───");
        if (holder != null && holder.getSurface() != null) {
            WearLog.d(TAG, "🖥️ [Surface状态] 检查 Surface 活体正常。正在移交底层初始化 H.264 解码器...");
            initDecoder(holder);
            isSurfaceReady = true;
            WearLog.d(TAG,"CAM-W001 DecoderReady=" + decoderReady);
            if (decoderReady) {
                sendControlSignalToPhone("CAMERA_READY");
            }
        } else {
            releaseDecoder();
            WearLog.e(TAG, "❌ [Surface状态致命] 检测到 SurfaceHolder 或其内部 Surface 为 null！无法点火解调引擎。");
        }
    }

    private void initDecoder(SurfaceHolder holder) {
        try {
            WearLog.d(TAG, "⚙️ [解码器配置] 开始配置 H.264 底层硬解引擎参数 (画幅规格: 640x480)...");
            mDecoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 640, 480);
            mDecoder.configure(format, holder.getSurface(), null, 0);
            mDecoder.start();
            isDecoderRunning = true;
            decoderReady = true;
            WearLog.d(TAG, "✨ [解码器点火成功] H.264 底层硬解引擎成功起飞！等待接收来自手机的帧数据流...");
        } catch (Exception e) {
            WearLog.e(TAG, "❌ [解码器致命异常] 初始化硬解管线严重受阻: " + e.getMessage(), e);
        }
    }

    public void feedH264Data(byte[] data, int length) {
        if (!decoderReady || !isSurfaceReady || mDecoder == null) return;
        try {
            int inputBufferIndex = mDecoder.dequeueInputBuffer(10000);
            if (inputBufferIndex >= 0) {
                ByteBuffer inputBuffer = mDecoder.getInputBuffer(inputBufferIndex);
                if (inputBuffer != null) {
                    inputBuffer.clear();
                    inputBuffer.put(data, 0, length);
                    mDecoder.queueInputBuffer(inputBufferIndex, 0, length, System.nanoTime()/1000, 0);
                }
            }

            MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
            int outputBufferIndex = mDecoder.dequeueOutputBuffer(bufferInfo, 10000);
            while (outputBufferIndex >= 0) {
                if (!isFirstFrameDecoded) {
                    isFirstFrameDecoded = true;
                    WearLog.w(TAG, "🔥 [硬解首帧大捷] 手表端成功完成第一帧 H.264 物理图传的渲染突破！");
                }
                mDecoder.releaseOutputBuffer(outputBufferIndex, true);
                totalFramesDecoded++;
                long now = System.currentTimeMillis();
                if (now - lastLogTime > 4000) {
                    WearLog.d(TAG, "📊 [硬解高频快照] 画面正在丝滑渲染，已成功解码: " + totalFramesDecoded + " 帧。");
                    lastLogTime = now;
                }
                outputBufferIndex = mDecoder.dequeueOutputBuffer(bufferInfo, 0);
            }
        } catch (Exception e) {
            WearLog.e(TAG, "⚠️ [图传硬解异常] 帧流灌入硬解管线发生拥堵异常波动: " + e.getMessage());
        }
    }

    public void onChannelReady() {
        if (isChannelReady) return;
        isChannelReady = true;
        WearLog.d(TAG, "CAM-W002 Channel Ready");
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {}

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        releaseDecoder();
    }

    private void releaseDecoder() {
        isDecoderRunning = false;
        decoderReady = false;
        if (mDecoder != null) {
            try {
                mDecoder.stop();
                mDecoder.release();
            } catch (Exception ignored) {}
            mDecoder = null;
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
                if (nodes != null && !nodes.isEmpty()) {
                    for (Node n : nodes) {
                        Wearable.getMessageClient(this).sendMessage(n.getId(), UNIVERSAL_SYNC_PATH, payload);
                    }
                }
            } catch (Exception e) {
                WearLog.e(TAG, "🔴 [信令发送致命] " + e.getMessage());
            }
        }).start();
    }

    private void cleanExit(boolean notifyPhone) {
        if (isUserExiting) return;
        isUserExiting = true;
        isSurfaceReady = false;

        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (wakeLock != null) {
            try {
                if (wakeLock.isHeld()) wakeLock.release();
            } catch (Exception ignored) {}
            wakeLock = null;
        }

        if (notifyPhone) {
            sendControlSignalToPhone("STOP_CAMERA");
        }

        try {
            unregisterReceiver(phoneKillReceiver);
        } catch (Exception ignored) {}

        releaseDecoder();

        // 🚀 核心优化：干净清空弱引用卡槽
        if (sActivityRef != null) {
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
        WearLog.w(TAG, "🏳️ [生命周期] onDestroy 触发...");
        // 🚀 核心修改：在销毁时再次防线清空弱引用，确保不留死角
        if (sActivityRef != null) {
            sActivityRef.clear();
        }
        cleanExit(true);
        super.onDestroy();
    }
}