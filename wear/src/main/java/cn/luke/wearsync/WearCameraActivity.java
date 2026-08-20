package cn.luke.wearsync;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Bundle;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;

import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.ChannelClient;
import com.google.android.gms.wearable.Wearable;

import java.io.DataInputStream;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.util.concurrent.LinkedBlockingQueue;

public class WearCameraActivity extends ComponentActivity implements SurfaceHolder.Callback {
    private static final String TAG = "WearSync_WearCameraUI";
    
    public static WeakReference<WearCameraActivity> sActivityRef = new WeakReference<>(null);

    private SurfaceView surfaceView;
    private FrameLayout surfaceContainer;
    private TextView tvStatusHint;
    private MediaCodec mDecoder;
    private volatile boolean isDecoderRunning = false;
    private volatile boolean isUserExiting = false;
    private volatile boolean isFrozen = false; // 🎯 冻结预览标志位
    private boolean isSurfaceReady = false;
    private final LinkedBlockingQueue<byte[]> frameQueue = new LinkedBlockingQueue<>(15);
    private Thread renderThread;

    // 🎯 缩放和平移相关
    private float mScaleFactor = 1.0f;
    private float mPosX = 0;
    private float mPosY = 0;
    private ScaleGestureDetector mScaleDetector;
    private GestureDetector mGestureDetector;

    private ChannelClient.ChannelCallback mChannelListener;
    private BroadcastReceiver mFileReceiver;

    private TextView tvCountdown;
    private View focusMarker;

    @SuppressLint("ClickableViewAccessibility")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WearLog.d(TAG, "🟢 [生命周期] WearCameraActivity onCreate 启动");
        
        sActivityRef = new WeakReference<>(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        WearSyncScreenManager screenManager = new WearSyncScreenManager(this);
        screenManager.bind(this);
        
        // 🚀 唤醒屏幕并保持常亮
        screenManager.wakeScreen();
        screenManager.acquireCpu(3 * 60 * 1000L); // 保持 CPU 运转
        
        setContentView(R.layout.activity_wear_camera);
        surfaceView = findViewById(R.id.surfaceView);
        surfaceContainer = findViewById(R.id.surface_container); // 🎯 需要在布局中增加容器
        tvStatusHint = findViewById(R.id.tv_status_hint);
        tvCountdown = findViewById(R.id.tv_countdown);
        focusMarker = findViewById(R.id.focus_marker);
        Button btnSwitchCamera = findViewById(R.id.btn_switch_camera);

        setupGestures();

        if (surfaceContainer != null) {
            surfaceContainer.setOnTouchListener((v, event) -> {
                if (isFrozen) {
                    mScaleDetector.onTouchEvent(event);
                    mGestureDetector.onTouchEvent(event);
                    if (event.getAction() == MotionEvent.ACTION_UP) v.performClick();
                    return true;
                }
                return false;
            });
        }

        if (surfaceView != null) {
            surfaceView.getHolder().addCallback(this);
            // 🎯 仅在非冻结状态下处理对焦
            surfaceView.setOnTouchListener((v, event) -> {
                if (isFrozen) return false;
                
                if (event.getAction() == MotionEvent.ACTION_DOWN) {
                    float x = event.getX() / v.getWidth();
                    float y = event.getY() / v.getHeight();
                    showFocusMarker(event.getX(), event.getY());
                    WearSyncCommManager.getInstance(getApplicationContext()).sendBusinessCommand("camera_action", "FOCUS_CAMERA", "x", (double)x, "y", (double)y);
                }
                
                if (event.getAction() == MotionEvent.ACTION_UP) {
                    v.performClick();
                }
                return true;
            });
        }

        // 🎯 拍照快门按钮：倒计时 3 秒拍照
        Button btnShutter = findViewById(R.id.btn_shutter);
        if (btnShutter != null) {
            btnShutter.setOnClickListener(v -> startCountdownAndCapture());
        }

        // 🎯 切换摄像头按钮
        if (btnSwitchCamera != null) {
            btnSwitchCamera.setOnClickListener(v -> {
                WearLog.d(TAG, "🔄 用户点击 [切换摄像头]");
                WearSyncCommManager.getInstance(getApplicationContext()).sendBusinessCommand("camera_control", "SWITCH_CAMERA");
                showCaptureHint("🔄 正在切换摄像头...");
            });
        }
        
        // 注册文件接收广播
        mFileReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if ("cn.luke.wearsync.ACTION_FILE_RECEIVED".equals(intent.getAction())) {
                    String uriStr = intent.getStringExtra("file_uri");
                    if (uriStr != null) {
                        showPhotoPreview(Uri.parse(uriStr));
                    }
                }
            }
        };
        IntentFilter filter = new IntentFilter("cn.luke.wearsync.ACTION_FILE_RECEIVED");
        registerReceiver(mFileReceiver, filter, Context.RECEIVER_EXPORTED);

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                // 🎯 如果处于冻结状态，先退出冻结
                if (isFrozen) {
                    unfreezePreview();
                    return;
                }
                WearLog.d(TAG, "🔙 用户按下返回键，准备关闭远端相机");
                WearSyncCommManager.getInstance(getApplicationContext()).sendBusinessCommand("camera_action", "STOP_CAMERA");
                cleanExit(false);
            }
        });

        startDecoderThread();

        // 1. 注册 Channel 回调监听视频流
        mChannelListener = new ChannelClient.ChannelCallback() {
            @Override
            public void onChannelOpened(@NonNull ChannelClient.Channel channel) {
                if ("/wear_data_channel/camera".equals(channel.getPath())) {
                    WearLog.d(TAG, "🔗 [Activity] 相机通道已打开，开始接收推流");
                    readStreamFromChannel(channel);
                }
            }

            @Override
            public void onChannelClosed(@NonNull ChannelClient.Channel channel, int closeReason, int appSpecificErrorCode) {
                if ("/wear_data_channel/camera".equals(channel.getPath())) {
                    WearLog.d(TAG, "🔌 [Activity] 相机通道已关闭");
                }
            }
        };
        Wearable.getChannelClient(this).registerChannelCallback(mChannelListener);

        // 2. 向手机发送开启相机指令
        WearLog.d(TAG, "📤 发送开启手机相机指令");
        WearSyncCommManager.getInstance(getApplicationContext()).sendBusinessCommand("camera_control", "open_phone_camera");
    }

    private void setupGestures() {
        mScaleDetector = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(@NonNull ScaleGestureDetector detector) {
                mScaleFactor *= detector.getScaleFactor();
                mScaleFactor = Math.max(1.0f, Math.min(mScaleFactor, 5.0f));
                applyTransform();
                return true;
            }
        });

        mGestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onScroll(MotionEvent e1, @NonNull MotionEvent e2, float distanceX, float distanceY) {
                if (mScaleFactor > 1.0f) {
                    mPosX -= distanceX;
                    mPosY -= distanceY;
                    applyTransform();
                    return true;
                }
                return false;
            }

            @Override
            public boolean onDoubleTap(@NonNull MotionEvent e) {
                if (mScaleFactor > 1.0f) {
                    resetTransform();
                } else {
                    mScaleFactor = 2.0f;
                    applyTransform();
                }
                return true;
            }
        });
    }

    private void applyTransform() {
        if (surfaceView != null) {
            surfaceView.setScaleX(mScaleFactor);
            surfaceView.setScaleY(mScaleFactor);
            surfaceView.setTranslationX(mPosX);
            surfaceView.setTranslationY(mPosY);
            
            // 🎯 通知容器失效以刷新绘制
            if (surfaceContainer != null) surfaceContainer.invalidate();
        }
    }

    private void resetTransform() {
        mScaleFactor = 1.0f;
        mPosX = 0;
        mPosY = 0;
        applyTransform();
    }

    private void startCountdownAndCapture() {
        if (tvCountdown == null) return;
        tvCountdown.setVisibility(View.VISIBLE);
        new Thread(() -> {
            for (int i = 3; i > 0; i--) {
                final int count = i;
                runOnUiThread(() -> tvCountdown.setText(String.valueOf(count)));
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    break;
                }
            }
            runOnUiThread(() -> {
                tvCountdown.setVisibility(View.GONE);
                WearLog.d(TAG, "📸 倒计时结束：冻结预览并请求手机拍照");
                
                // 🎯 1. 立即冻结预览
                freezePreview();
                
                // 🎯 2. 发送拍照指令
                WearSyncCommManager.getInstance(getApplicationContext()).sendBusinessCommand("camera_action", "TAKE_PHOTO");
                showCaptureHint("📸 拍摄中...");
            });
        }).start();
    }

    private void freezePreview() {
        isFrozen = true;
        WearLog.d(TAG, "❄️ 预览已冻结，支持缩放查看");
        showCaptureHint("❄️ 已冻结（双击或缩放）");
    }

    private void unfreezePreview() {
        if (!isFrozen) return;
        isFrozen = false;
        resetTransform();
        frameQueue.clear(); // 清空积压的帧
        WearLog.d(TAG, "🔥 预览已恢复实时流");
    }

    private void showFocusMarker(float x, float y) {
        if (focusMarker == null) return;
        runOnUiThread(() -> {
            focusMarker.setX(x - focusMarker.getWidth() / 2f);
            focusMarker.setY(y - focusMarker.getHeight() / 2f);
            focusMarker.setVisibility(View.VISIBLE);
            focusMarker.setAlpha(1.0f);
            focusMarker.animate().alpha(0.0f).setDuration(1000).withEndAction(() -> focusMarker.setVisibility(View.GONE)).start();
        });
    }

    private void readStreamFromChannel(ChannelClient.Channel channel) {
        new Thread(() -> {
            WearLog.d(TAG, "🚀 启动 Channel-Reader 线程准备读取 H.264 视频流");
            try {
                InputStream is = Tasks.await(Wearable.getChannelClient(this).getInputStream(channel));
                try (DataInputStream dis = new DataInputStream(is)) {
                    while (!Thread.currentThread().isInterrupted() && !isUserExiting) {
                        // 🎯 完美读取手机端 DataOutputStream 写入的完整包头
                        int length = dis.readInt();
                        dis.readLong(); // 时间戳
                        dis.readInt();  // 标志位

                        if (length > 0 && length < 1000000) {
                            byte[] frameData = new byte[length];
                            dis.readFully(frameData);
                            feedH264Data(frameData);
                        }
                    }
                }
            } catch (Exception e) {
                WearLog.e(TAG, "❌ 读取相机 Channel 流发生异常: " + e.getMessage());
            }
        }, "Channel-Reader-Thread").start();
    }

    public static void forceClose() {
        WearCameraActivity activity = sActivityRef.get();
        if (activity != null && !activity.isUserExiting) {
            activity.runOnUiThread(() -> activity.cleanExit(true));
        }
    }

    private void showCaptureHint(String text) {
        if (tvStatusHint == null) return;
        runOnUiThread(() -> {
            tvStatusHint.setText(text);
            tvStatusHint.setVisibility(View.VISIBLE);
            tvStatusHint.setAlpha(1.0f);
            tvStatusHint.animate().alpha(0.0f).setDuration(1500).withEndAction(() -> tvStatusHint.setVisibility(View.GONE)).start();
        });
    }

    private void showPhotoPreview(Uri uri) {
        // 🎯 手表端逻辑已改为实时预览冻结，不再接收 WebP 缩略图文件
        WearLog.d(TAG, "ℹ️ 收到同步文件(忽略): " + uri);
    }

    public void feedH264Data(byte[] h264Data) {
        if (isFrozen) return; // 🎯 冻结时不接收新帧
        if (h264Data != null && h264Data.length > 0) {
            if (!frameQueue.offer(h264Data)) {
                frameQueue.poll();
                frameQueue.offer(h264Data);
            }
        }
    }

    private void startDecoderThread() {
        renderThread = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted() && !isUserExiting) {
                try {
                    byte[] frameData = frameQueue.take();
                    if (frameData != null && isSurfaceReady && mDecoder != null && isDecoderRunning) {
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
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    WearLog.e(TAG, "❌ 解码视频帧异常", e);
                }
            }
        }, "RenderThread");
        renderThread.start();
    }

    private void initDecoder() {
        try {
            // 🎯 对齐手机端的 256 宽度画面尺寸
            MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 256, 256);
            // 🔄 关键：告诉硬件解码器将流旋转 90 度以匹配手机传感器方向
            format.setInteger(MediaFormat.KEY_ROTATION, 90);
            
            mDecoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            mDecoder.configure(format, surfaceView.getHolder().getSurface(), null, 0);
            mDecoder.start();
            isDecoderRunning = true;
            WearLog.d(TAG, "🎉 H.264 解码器初始化成功 (256 width target)");
        } catch (Exception e) {
            WearLog.e(TAG, "❌ 初始化解码器失败: " + e.getMessage(), e);
        }
    }

    private void cleanExit(boolean fromPhone) {
        if (isUserExiting) return;
        isUserExiting = true;

        if (renderThread != null) {
            renderThread.interrupt();
            renderThread = null;
        }

        if (mDecoder != null) {
            try {
                isDecoderRunning = false;
                mDecoder.stop();
                mDecoder.release();
            } catch (Exception e) {
                WearLog.e(TAG, "⚠️ 释放解码器异常", e);
            }
            mDecoder = null;
        }

        frameQueue.clear();

     

        finishAndRemoveTask();
    }

    @Override
    public void surfaceCreated(@NonNull SurfaceHolder holder) {
        isSurfaceReady = true;
        initDecoder();
    }

    @Override
    public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {}

    @Override
    public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
        isSurfaceReady = false;
        isDecoderRunning = false;
    }

    @Override
    protected void onDestroy() {
        if (mChannelListener != null) {
            Wearable.getChannelClient(this).unregisterChannelCallback(mChannelListener);
        }
        if (mFileReceiver != null) {
            unregisterReceiver(mFileReceiver);
        }
        cleanExit(false);
        super.onDestroy();
    }
}
