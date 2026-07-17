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

import androidx.core.content.ContextCompat;
import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;

import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;

import org.json.JSONObject;

import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;

// 🟢 完美優化：變更繼承為 ComponentActivity 以原生支持現代返回調度器
public class WearCameraActivity extends ComponentActivity implements SurfaceHolder.Callback {
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
            wakeLock.acquire(10 * 60 * 1000L);
            WearLog.d(TAG, "🔋 WakeLock acquired for 10 mins");
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
                WearLog.d(TAG, "👆 用戶點擊了介面上的【關閉】按鈕");
                cleanExit(true);
            });
        }

        IntentFilter filter = new IntentFilter("cn.luke.wearsync.ACTION_PHONE_KILL_CAMERA");
        ContextCompat.registerReceiver(this, phoneKillReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);

        // 🚀 核心優化移動：將預測性返回監聽器安全註冊在 onCreate 內部，杜絕編譯錯位
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                WearLog.d(TAG, "🔄 [手勢側滑返回] 觸發預測性返回，執行乾淨退出...");
                cleanExit(true); 
            }
        });
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        WearLog.d(TAG, "📺 surfaceCreated");
        isSurfaceReady = true;
        startRenderThread(holder);
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        WearLog.d(TAG, "📺 surfaceChanged W=" + width + " H=" + height);
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        WearLog.d(TAG, "📺 surfaceDestroyed");
        isSurfaceReady = false;
        stopRenderThread();
    }

    public void feedH264Data(byte[] data,int length){
    
        if(isUserExiting){
    
            WearLog.d(TAG,
                    "CAM-W020 ignore exiting");
    
            return;
        }
    
    
        byte[] frame =
                new byte[length];
    
    
        System.arraycopy(
                data,
                0,
                frame,
                0,
                length
        );
    
    
        frameQueue.offer(frame);
    
    
        if(frameQueue.size()==1){
    
            WearLog.d(TAG,
                    "CAM-W021 first frame queued");
    
        }
    
    }

    private void initDecoder(SurfaceHolder holder) {
        try {
            WearLog.d(TAG, "🎬 開始初始化 MediaCodec H.264 解碼器...");
            mDecoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 640, 480);
            format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
            
            mDecoder.configure(format, holder.getSurface(), null, 0);
            mDecoder.start();
            isDecoderRunning = true;
            WearLog.i(TAG, "🟢 解碼器初始化完成並成功開啟！");
        } catch (Exception e) {
            WearLog.e(TAG, "❌ 解碼器初始化失敗: " + e.getMessage(), e);
        }
    }

    private void releaseDecoder() {
        isDecoderRunning = false;
        if (mDecoder != null) {
            try {
                WearLog.d(TAG, "🎬 正在釋放解碼器管線...");
                mDecoder.stop();
                mDecoder.release();
                WearLog.i(TAG, "🟢 解碼器管線釋放完畢");
            } catch (Exception ignored) {}
            mDecoder = null;
        }
        frameQueue.clear();
    }

    private void startRenderThread(SurfaceHolder holder) {
        stopRenderThread();
        initDecoder(holder);
        WearLog.d(TAG,
        "CAM-W030 decoder started");

        renderThread = new Thread(() -> {
            MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
            while (isSurfaceReady && isDecoderRunning && !Thread.currentThread().isInterrupted()) {
                try {
                    byte[] sampleData = frameQueue.poll();
                    if(sampleData != null){

                WearLog.d(TAG,
                        "CAM-W031 decode input size="
                                +sampleData.length);
            
            }
                 if (sampleData == null) {
                        Thread.sleep(5);
                        continue;
                    }

                    int inputBufferIndex = mDecoder.dequeueInputBuffer(10000);
                    if (inputBufferIndex >= 0) {
                        ByteBuffer inputBuffer = mDecoder.getInputBuffer(inputBufferIndex);
                        if (inputBuffer != null) {
                            inputBuffer.clear();
                            inputBuffer.put(sampleData);
                            mDecoder.queueInputBuffer(inputBufferIndex, 0, sampleData.length, System.currentTimeMillis() * 1000, 0);
                        }
                    }

                    int outputBufferIndex = mDecoder.dequeueOutputBuffer(bufferInfo, 10000);
                    while (outputBufferIndex >= 0) {
                        mDecoder.releaseOutputBuffer(outputBufferIndex, true);
                        outputBufferIndex = mDecoder.dequeueOutputBuffer(bufferInfo, 0);
                    }
                } catch (InterruptedException e) {
                    break;
                } catch (Exception e) {
                    WearLog.e(TAG, "⚠️ 渲染線程循環中遭遇異常: " + e.getMessage());
                }
            }
        }, "WearCameraRenderThread");
        renderThread.start();
    }
public void onChannelReady() {

    WearLog.d(TAG,
            "CAM-W011 Channel ready callback");

}
    private void stopRenderThread() {
        if (renderThread != null) {
            renderThread.interrupt();
            try { renderThread.join(500); } catch (Exception ignored) {}
            renderThread = null;
        }
        releaseDecoder();
    }

    private void sendControlSignalToPhone(String actionCommand) {
        new Thread(() -> {
            try {
                JSONObject json = new JSONObject();
                json.put("sender", "wear");
                json.put("type", "camera_action");
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
                WearLog.e(TAG, "🔴 [信令發送致命] " + e.getMessage());
            }
        }).start();
    }

    private void cleanExit(boolean notifyPhone) {
        if (isUserExiting) return; // 👈 完美防線：攔截任何二次釋放
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

        // 🚀 核心優化：乾淨清空弱引用卡槽
        if (sActivityRef != null) {
            sActivityRef.clear();
        }

        finishAndRemoveTask();
    }

    @Override
    protected void onDestroy() {
        WearLog.w(TAG, "🏳️ [生命周期] onDestroy 觸發...");
        
        // 🚀 安全防線：在此調用無通知的 cleanExit，防止任何系統強制殺死時的資源殘留
        cleanExit(false); 

        if (sActivityRef != null) {
            sActivityRef.clear();
        }
        super.onDestroy();
    }
}
