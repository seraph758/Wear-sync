package cn.luke.wearsync;

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
import android.view.WindowManager;
import android.widget.Button;
import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;
import org.json.JSONObject;
import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import android.os.PowerManager;

/**
 * 🎬 远程拍照手表观景窗/流媒体渲染 UI（MediaCodec 硬件解码 + 实时反向发信控制）
 * 极致动态日志全步进版：微秒级动态监控高频帧解码、输入输出网关、窗口物理常亮锁定及异步信令生命周期。
 */
public class WearCameraActivity extends Activity implements SurfaceHolder.Callback {
    private static final String TAG = "WearSync_WearCameraUI";
    private static final String UNIVERSAL_SYNC_PATH = "/wear-universal-sync";
    private static WearCameraActivity instance;
    private SurfaceView surfaceView;
    private MediaCodec mDecoder;
    private boolean isDecoderRunning = false;
    private boolean isUserExiting = false;
    private PowerManager.WakeLock wakeLock;
    private long activityCreateTime;
    
    // 📊 用于防止高频解码日志淹没系统的统计计数器
    private long totalFramesDecoded = 0;
    private long lastLogTime = 0;
    private boolean isFirstFrameDecoded = false;
    private volatile boolean ischanReady = false;
    private volatile boolean decoderReady = false;
    private byte[] pendingSps;
    private byte[] pendingPps;
    private boolean codecConfigured = false;

    public static WeakReference<WearCameraActivity> sActivityRef = new WeakReference<>(null);

    // 🛰️ 手机端强制挂断哨兵：接收来自手机因某种意外被迫熔断相机的全局广播
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
        instance=this;
        WearCameraActivity.sActivityRef = new WeakReference<>(this);

        // 💡 核心注入：在载入布局前，对当前 Window 强制灌入 FLAG_KEEP_SCREEN_ON 旗帜，锁死屏幕高亮
        WearLog.w(TAG, "💡 [电源管理] 正在向当前 Window 注入 FLAG_KEEP_SCREEN_ON 常亮旗帜，强制压制手表的休眠机制...");
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            
            try {
                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            
                if (pm != null) {
            
                    wakeLock = pm.newWakeLock(
                            PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                                    | PowerManager.ACQUIRE_CAUSES_WAKEUP,
                            TAG + ":CameraWake");
            
                    wakeLock.acquire(10 * 60 * 1000L);
            
                    WearLog.d(TAG,
                            "💡 WakeLock 已获取，强制点亮屏幕并保持唤醒");
                }
            
            } catch (Exception e) {
            
                WearLog.e(TAG,
                        "获取 WakeLock 失败: "
                                + e.getMessage());
            
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

        // 🛠️ 注册手机断开物理拦截广播
        WearLog.d(TAG, "⚙️ [信令挂载] 正在构建逆向断开拦截器 IntentFilter...");
        IntentFilter filter = new IntentFilter("cn.luke.wearsync.ACTION_FORCE_QUIT_WEAR_CAMERA");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WearLog.d(TAG, "⚙️ [信令挂载] 当前系统 SDK: " + Build.VERSION.SDK_INT + " (>=26)，注入 RECEIVER_EXPORTED 旗帜注册动态广播...");
            registerReceiver(phoneKillReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            WearLog.d(TAG, "⚙️ [信令挂载] 当前旧版系统 SDK: " + Build.VERSION.SDK_INT + "，采用常规方式动态注册...");
            registerReceiver(phoneKillReceiver, filter);
        }
        
        // 重置解调计数
        totalFramesDecoded = 0;
        isFirstFrameDecoded = false;
        WearLog.d(TAG, "🎬 [生命周期] onCreate 结束 ─── 手表端图传观景窗 Activity 全功能就绪。");
    }
    @Override
    protected void onResume() {
        super.onResume();
    
        WearLog.d(TAG,
                "📺 Activity 已进入前台，Surface="
                        + (surfaceView != null));
    }
    @Override
    protected void onPause() {
        super.onPause();
    
        WearLog.d(TAG,
                "📺 Activity onPause()");
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
    
        WearLog.d(TAG, "🖥️ [Surface状态] ─── 物理 Surface 硬件画布载体已成功构建完成 ───");
    
        if (holder != null && holder.getSurface() != null) {
    
            WearLog.d(TAG, "🖥️ [Surface状态] 检查 Surface 活体正常。正在移交底层初始化 H.264 解码器...");
    
            initDecoder(holder);
    
            isSurfaceReady = true;
    
                WearLog.d(TAG,"CAM-W001 DecoderReady="+decoderReady);    
            if (decoderReady) {
                sendControlSignalToPhone("CAMERA_READY");
            }
    
        } else {
    
            releaseDecoder();
    
            WearLog.e(TAG,
                    "❌ [Surface状态致命] 检测到 SurfaceHolder 或其内部 Surface 为 null！无法点火解调引擎。");
    
        }
    }

    private void initDecoder(SurfaceHolder holder) {
        try {
            WearLog.d(TAG, "⚙️ [解码器配置] 开始配置 H.264 底层硬解引擎参数 (画幅规格: 640x480)...");
            mDecoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 640, 480);
            
            WearLog.d(TAG, "⚙️ [解码器配置] 正在呼叫系统硬件多媒体库执行 mDecoder.configure()...");
            mDecoder.configure(format, holder.getSurface(), null, 0);
            
            WearLog.d(TAG, "🚀 [解码器点火] 正在呼叫 mDecoder.start()...");
            mDecoder.start();

            isDecoderRunning = true;
            
            decoderReady = true;
            
            codecConfigured = true;
            WearLog.d(TAG, "✨ [解码器点火成功] H.264 底层硬解引擎成功起飞！等待接收来自手机的帧数据流...");
        } catch (Exception e) {
            WearLog.e(TAG, "❌ [解码器致命异常] 初始化硬解管线严重受阻，可能底层硬件能力不足或 Surface 被提前抢占: " + e.getMessage(), e);
        }
    }

    /**
     * 🚀 接收来自高速公路网关通道传输过来的 H.264 原生帧，注入解码器
     */
    public void feedH264Data(byte[] data, int length) {
        WearLog.d(TAG, "CAM-W010 feed " + length);
        if (!decoderReady
                || !isSurfaceReady
                || mDecoder == null) {
        
            return;
        }
        try {
            // 1. 抽取输入端空闲小卡座 (Timeout: 10毫秒)
            int inputBufferIndex = mDecoder.dequeueInputBuffer(10000);
            if (inputBufferIndex >= 0) {
                ByteBuffer inputBuffer = mDecoder.getInputBuffer(inputBufferIndex);
                if (inputBuffer != null) {
                    inputBuffer.clear();
                    inputBuffer.put(data, 0, length);
                    
                    // 填塞原始密文帧推进 H.264 异步解调队列
                    mDecoder.queueInputBuffer(
                            inputBufferIndex,
                            0,
                            length,
                            System.nanoTime()/1000,
                            0
                    );                
                }
            } else {
                long now = System.currentTimeMillis();
                if (now - lastLogTime > 4000) {
                    WearLog.w(TAG, "⚠️ [图传卡顿警报] 解码器输入端卡座爆满 (dequeueInputBuffer == -1)，说明手表芯片解调速度跟不上手机喷射速度。");
                }
            }
            

            // 2. 抽取输出端已解调完成的像素裸流画面并渲染
            MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
            int outputBufferIndex = mDecoder.dequeueOutputBuffer(bufferInfo, 10000);
            
            while (outputBufferIndex >= 0) {
                if (!isFirstFrameDecoded) {
                    isFirstFrameDecoded = true;
                    WearLog.w(TAG, "🔥 [硬解首帧大捷] 手表端成功完成第一帧 H.264 物理图传的渲染突破！");
                }

                // 传入 true：命令解调器立刻将此缓冲区数据刷新推向 Surface 进行屏幕物理渲染
                mDecoder.releaseOutputBuffer(outputBufferIndex, true);
                
                totalFramesDecoded++;
                long now = System.currentTimeMillis();
                if (now - lastLogTime > 4000) {
                    WearLog.d(TAG, "📊 [硬解高频快照] 画面正在丝滑渲染，手表端累计已成功解码并刷新: " + totalFramesDecoded + " 帧画面。");
                    lastLogTime = now;
                }

                outputBufferIndex = mDecoder.dequeueOutputBuffer(bufferInfo, 0);
            }
        } catch (Exception e) {
            WearLog.e(TAG, "⚠️ [图传硬解异常] 帧流灌入硬解管线发生拥堵异常波动: " + e.getMessage());
        }
    }
        public void onChannelReady() {
    
        if (isChannelReady) {
            return;
        }
    
        isChannelReady = true;
    
        WearLog.d(TAG, "CAM-W002 Channel Ready");
    
        if (decoderReady && isSurfaceReady) {
            WearLog.d(TAG, "CAM-W002 Decoder Ready");
        }
    }
    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        WearLog.d(TAG, "🖥️ [Surface状态] surfaceChanged 触发 ➔ 画布几何尺寸或格式发生变更: w=" + width + ", h=" + height);
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        WearLog.w(TAG, "🖥️ [Surface状态] ─── Surface 硬件载体被系统强行销毁 ───");
        WearLog.w(TAG, "🖥️ [Surface状态] 正在强力执行系统级熔断清理，彻底拆卸解码管线...");
        releaseDecoder();
    }

    private void releaseDecoder() {
        WearLog.w(TAG, "🧹 [安全熔断] 正在关闭并释放 mDecoder 硬件解码资源...");
        isDecoderRunning = false;
        decoderReady = false;
        if (mDecoder != null) {
            try {
                mDecoder.stop();
                mDecoder.release();
                WearLog.w(TAG, "🧹 [安全熔断] 硬件解码器内存物理卡座已完全断开脱离。");
            } catch (Exception e) {
                WearLog.w(TAG, "🧹 [安全熔断] 关闭解码器发生了不影响全局的边缘异常: " + e.getMessage());
            }
            mDecoder = null;
            WearLog.d(TAG, "🛑 [安全熔断结束] 终解完成 ─── 解码器硬件资源已安全释放回收。");
        }
    }

    /**
     * 📡 反向控场：向手机端反向穿透发送拍照控制信令
     */
    private void sendControlSignalToPhone(String actionCommand) {
        new Thread(() -> {
            WearLog.d(TAG, "🧵 [背景信令线程] 异步控制发送线程启动，当前准备投递指令: [" + actionCommand + "]");
            try {
                JSONObject json = new JSONObject();
                json.put("sender", "wear");
                json.put("type", "camera_control");
                json.put("action", actionCommand);
                json.put("timestamp", System.currentTimeMillis());

                byte[] payload = json.toString().getBytes(StandardCharsets.UTF_8);
                
                WearLog.d(TAG, "📡 [信令发送中] 正在呼叫 getNodeClient().getConnectedNodes() 寻找配对的手机节点...");
                List<Node> nodes = Tasks.await(Wearable.getNodeClient(this).getConnectedNodes());
                
                if (nodes != null && !nodes.isEmpty()) {
                    WearLog.d(TAG, "📊 [信令发送中] 发现处于在线激活状态的手表配对手机节点数量: " + nodes.size());
                    for (Node n : nodes) {
                        WearLog.d(TAG, "📡 [信令发送中] 正在通过高速 Google Message 物理网关注入字节包 ➔ 目标手机 ID: [" + n.getId() + "] ➔ 路径: [" + UNIVERSAL_SYNC_PATH + "]");
                        Wearable.getMessageClient(this).sendMessage(n.getId(), UNIVERSAL_SYNC_PATH, payload);
                    }
                    WearLog.d(TAG, "🚀 [信令发送大捷] 控制信号 [" + actionCommand + "] 已成功扔进系统底层穿透发射通道。");
                } else {
                    WearLog.e(TAG, "❌ [信令发送失败] 物理链路扫描结束，未发现任何处于配对连线状态的手机节点！信令丢弃。");
                }
            } catch (Exception e) {
                WearLog.e(TAG, "🔴 [信令发送致命] 向手机端投递相机全局控场指令遭遇崩溃阻断: " + e.getMessage(), e);
            }
        }).start();
    }

    /**
     * 🧹 全局纯净安全退出回收器
     */
    private void cleanExit(boolean notifyPhone) {
        if (isUserExiting) {
            WearLog.d(TAG, "🧹 [清场熔断] 触发保护：当前正处于退出进程中，拒绝二次重入。");
            return;
        }
        isUserExiting = true;
        isSurfaceReady = false;
        WearLog.w(TAG, "🧹 [清场熔断] ━━━━━━━━ 开启手表相机 UI 完整退出销毁机制 ━━━━━━━━");
        
        // 💡 核心回收：清空常亮旗帜，把屏幕控制权安全还给系统省电策略
        WearLog.w(TAG, "💡 [电源管理] 正在从 Window 摘除 FLAG_KEEP_SCREEN_ON 常亮旗帜，恢复系统原生省电睡眠策略...");
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (wakeLock != null) {
        
            try {
        
                if (wakeLock.isHeld()) {
                    wakeLock.release();
        
                    WearLog.d(TAG,
                            "💡 WakeLock 已释放");
                }
        
            } catch (Exception ignored) {
            }
        
            wakeLock = null;
        }

        WearLog.w(TAG, "  └─ ⚙️ 参数设定 -> 是否反向通知手机同步关闭相机: " + (notifyPhone ? "【是/TRUE】" : "【否/FALSE】"));

        if (notifyPhone) {
            WearLog.d(TAG, "🧹 [清场熔断] 正在呼叫反向信令，命令手机端立即同步熔断 PhoneSyncCameraService...");
            sendControlSignalToPhone("STOP_CAMERA");
        }
        
        try {
            WearLog.d(TAG, "🧹 [清场熔断] 正在摘除动态挂载的逆向 phoneKillReceiver 广播接收器...");
            unregisterReceiver(phoneKillReceiver);
            WearLog.d(TAG, "🧹 [清场熔断] 广播接收器注销成功。");
        } catch (Exception e) {
            WearLog.w(TAG, "🧹 [清场熔断] 注销广播时触发了边缘报错 (可能之前已被解绑): " + e.getMessage());
        }
        
        // 彻底释放解调器
        releaseDecoder();
        
        if (sActivityRef.get() == this) {
            WearLog.d(TAG, "🧹 [清场熔断] 正在擦除全域弱引用 Activity 句柄卡槽 (sActivityRef)...");
            sActivityRef.clear();
        }
        
        WearLog.w(TAG, "🏳️ [清场熔断完毕] 正在调用 finishAndRemoveTask() 将任务彻底从手表堆栈和最近任务栏连根拔除。");
        finishAndRemoveTask();
    }

    @Override
    public void onBackPressed() {
        WearLog.w(TAG, "🔙 [交互触发] 用户主动在手表屏幕按下【返回键/滑动返回】，判定为主动中止退出...");
        cleanExit(true);
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        WearLog.w(TAG, "🏳️ [生命周期] onDestroy 触发：监测到手表 UI 堆栈准备销毁...");
        if(instance==this){
                instance=null;
            }
       cleanExit(true);
        super.onDestroy();
        WearLog.w(TAG, "🏳️ [生命周期] onDestroy ─── 手表端图传观景窗 Activity 全生命周期安全终结 ───");
    }
}
