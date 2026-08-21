package cn.luke.wearsync;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.MediaCodec;
import android.media.MediaFormat;
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
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;

import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.ChannelClient;
import com.google.android.gms.wearable.Wearable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.DataInputStream;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.util.Locale;
import java.util.concurrent.LinkedBlockingQueue;

public class WearCameraActivity extends ComponentActivity implements SurfaceHolder.Callback {
    private static final String TAG = "WearSync_WearCameraUI";
    
    public static WeakReference<WearCameraActivity> sActivityRef = new WeakReference<>(null);

    private SurfaceView surfaceView;
    private FrameLayout surfaceContainer;
    private TextView tvStatusHint;
    private LinearLayout layoutCameraList;
    private LinearLayout layoutZoomList;
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
    private float mCurrentZoom = 1.0f;
    private float mMaxZoom = 1.0f;
    private ScaleGestureDetector mScaleDetector;
    private GestureDetector mGestureDetector;
    private ScaleGestureDetector mPreviewZoomDetector;

    private ChannelClient.ChannelCallback mChannelListener;
    private BroadcastReceiver mFileReceiver;
    private BroadcastReceiver mCameraListReceiver;

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
        surfaceContainer = findViewById(R.id.surface_container);
        tvStatusHint = findViewById(R.id.tv_status_hint);
        tvCountdown = findViewById(R.id.tv_countdown);
        focusMarker = findViewById(R.id.focus_marker);
        layoutCameraList = findViewById(R.id.layout_camera_list);
        layoutZoomList = findViewById(R.id.layout_zoom_list);

        setupGestures();

        if (surfaceContainer != null) {
            surfaceContainer.setOnTouchListener((v, event) -> {
                if (isFrozen) {
                    mScaleDetector.onTouchEvent(event);
                    mGestureDetector.onTouchEvent(event);
                    if (event.getAction() == MotionEvent.ACTION_UP) v.performClick();
                    return true;
                } else {
                    boolean handled = mPreviewZoomDetector.onTouchEvent(event);
                    if (event.getAction() == MotionEvent.ACTION_UP) v.performClick();
                    return handled;
                }
            });
        }

        if (surfaceView != null) {
            surfaceView.getHolder().addCallback(this);
            surfaceView.setOnTouchListener((v, event) -> {
                if (isFrozen) return false;
                if (event.getAction() == MotionEvent.ACTION_DOWN) {
                    float x = event.getX() / v.getWidth();
                    float y = event.getY() / v.getHeight();
                    showFocusMarker(event.getX(), event.getY());
                    WearSyncCommManager.getInstance(getApplicationContext()).sendBusinessCommand("camera_action", "FOCUS_CAMERA", "x", (double)x, "y", (double)y);
                }
                if (event.getAction() == MotionEvent.ACTION_UP) v.performClick();
                return true;
            });
        }

        findViewById(R.id.btn_shutter).setOnClickListener(v -> startCountdownAndCapture());
        
        mFileReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if ("cn.luke.wearsync.ACTION_FILE_RECEIVED".equals(intent.getAction())) {
                    WearLog.d(TAG, "收到同步文件广播");
                }
            }
        };
        registerReceiver(mFileReceiver, new IntentFilter("cn.luke.wearsync.ACTION_FILE_RECEIVED"), Context.RECEIVER_EXPORTED);

        mCameraListReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if ("cn.luke.wearsync.ACTION_CAMERA_LIST_RECEIVED".equals(intent.getAction())) {
                    updateCameraList(intent.getStringExtra("camera_list"));
                }
            }
        };
        registerReceiver(mCameraListReceiver, new IntentFilter("cn.luke.wearsync.ACTION_CAMERA_LIST_RECEIVED"), Context.RECEIVER_EXPORTED);

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (isFrozen) { unfreezePreview(); return; }
                WearSyncCommManager.getInstance(getApplicationContext()).sendBusinessCommand("camera_action", "STOP_CAMERA");
                cleanExit(false);
            }
        });

        startDecoderThread();

        mChannelListener = new ChannelClient.ChannelCallback() {
            @Override
            public void onChannelOpened(@NonNull ChannelClient.Channel channel) {
                if ("/wear_data_channel/camera".equals(channel.getPath())) {
                    readStreamFromChannel(channel);
                }
            }
        };
        Wearable.getChannelClient(this).registerChannelCallback(mChannelListener);
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
                resetTransform();
                return true;
            }
        });

        mPreviewZoomDetector = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(@NonNull ScaleGestureDetector detector) {
                float newZoom = mCurrentZoom * detector.getScaleFactor();
                newZoom = Math.max(1.0f, Math.min(newZoom, mMaxZoom));
                if (Math.abs(newZoom - mCurrentZoom) > 0.05f) {
                    mCurrentZoom = newZoom;
                    WearSyncCommManager.getInstance(getApplicationContext()).setZoom(mCurrentZoom);
                    showCaptureHint(String.format(Locale.getDefault(), "🔍 %.1fx", mCurrentZoom));
                }
                return true;
            }
        });
    }

    private void updateCameraList(String jsonStr) {
        if (jsonStr == null || layoutCameraList == null) return;
        runOnUiThread(() -> {
            try {
                layoutCameraList.removeAllViews();
                JSONArray array = new JSONArray(jsonStr);
                for (int i = 0; i < array.length(); i++) {
                    JSONObject obj = array.getJSONObject(i);
                    String id = obj.getString("id");
                    String name = obj.getString("name");
                    float maxZoom = (float) obj.optDouble("maxZoom", 1.0);
                    
                    Button btn = new Button(this);
                    btn.setText(name);
                    btn.setAllCaps(false);
                    btn.setTextSize(10);
                    btn.setTextColor(0xFFFFFFFF);
                    btn.setBackgroundResource(R.drawable.bg_action_btn);

                    btn.setOnClickListener(v -> {
                        mCurrentZoom = 1.0f;
                        mMaxZoom = maxZoom;
                        updateZoomUI();
                        WearSyncCommManager.getInstance(getApplicationContext()).selectCamera(id);
                        showCaptureHint("🔄 " + name);
                    });
                    
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        70 // 固定高度
                    );
                    lp.setMargins(8, 0, 8, 0);
                    layoutCameraList.addView(btn, lp);

                    if (i == 0 && mMaxZoom <= 1.0f) {
                        mMaxZoom = maxZoom;
                        updateZoomUI();
                    }
                }
            } catch (Exception ignored) {}
        });
    }

    private void updateZoomUI() {
        if (layoutZoomList == null) return;
        runOnUiThread(() -> {
            layoutZoomList.removeAllViews();
            float[] levels = {1.0f, 2.0f, 3.0f, 5.0f, 10.0f, 20.0f};
            for (float level : levels) {
                if (level <= mMaxZoom || level == 1.0f) {
                    Button btn = new Button(this);
                    btn.setText(String.format(Locale.getDefault(), "%.0fx", level));
                    btn.setTextSize(12);
                    btn.setTextColor(0xFFFFFFFF);
                    btn.setBackgroundResource(R.drawable.bg_action_btn);
                    
                    btn.setOnClickListener(v -> {
                        mCurrentZoom = level;
                        WearSyncCommManager.getInstance(getApplicationContext()).setZoom(mCurrentZoom);
                        showCaptureHint("🔍 " + level + "x");
                    });

                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    );
                    lp.setMargins(6, 0, 6, 0);
                    layoutZoomList.addView(btn, lp);
                }
            }
        });
    }

    private void applyTransform() {
        if (surfaceView != null && surfaceContainer != null) {
            surfaceView.setScaleX(mScaleFactor);
            surfaceView.setScaleY(mScaleFactor);
            surfaceView.setTranslationX(mPosX);
            surfaceView.setTranslationY(mPosY);
            surfaceContainer.invalidate();
        }
    }

    private void resetTransform() {
        mScaleFactor = 1.0f; mPosX = 0; mPosY = 0;
        applyTransform();
    }

    private void startCountdownAndCapture() {
        if (tvCountdown == null) return;
        tvCountdown.setVisibility(View.VISIBLE);
        new Thread(() -> {
            for (int i = 3; i > 0; i--) {
                final int count = i;
                runOnUiThread(() -> tvCountdown.setText(String.valueOf(count)));
                try { Thread.sleep(1000); } catch (InterruptedException e) { break; }
            }
            runOnUiThread(() -> {
                tvCountdown.setVisibility(View.GONE);
                freezePreview();
                WearSyncCommManager.getInstance(getApplicationContext()).sendBusinessCommand("camera_action", "TAKE_PHOTO");
                showCaptureHint("📸 拍摄中...");
            });
        }).start();
    }

    private void freezePreview() { isFrozen = true; }

    private void unfreezePreview() {
        isFrozen = false;
        resetTransform();
        frameQueue.clear();
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
            try {
                InputStream is = Tasks.await(Wearable.getChannelClient(this).getInputStream(channel));
                try (DataInputStream dis = new DataInputStream(is)) {
                    while (!Thread.currentThread().isInterrupted() && !isUserExiting) {
                        int length = dis.readInt();
                        dis.readLong(); dis.readInt();
                        if (length > 0 && length < 1000000) {
                            byte[] frameData = new byte[length];
                            dis.readFully(frameData);
                            feedH264Data(frameData);
                        }
                    }
                }
            } catch (Exception ignored) {}
        }).start();
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

    public void feedH264Data(byte[] h264Data) {
        if (isFrozen) return;
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
                } catch (Exception ignored) {}
            }
        });
        renderThread.start();
    }

    private void initDecoder() {
        try {
            MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 256, 256);
            format.setInteger(MediaFormat.KEY_ROTATION, 90);
            mDecoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            mDecoder.configure(format, surfaceView.getHolder().getSurface(), null, 0);
            mDecoder.start();
            isDecoderRunning = true;
        } catch (Exception ignored) {}
    }

    private void cleanExit(boolean fromPhone) {
        if (isUserExiting) return;
        isUserExiting = true;
        if (renderThread != null) renderThread.interrupt();
        if (mDecoder != null) {
            try {
                isDecoderRunning = false;
                mDecoder.stop();
                mDecoder.release();
            } catch (Exception ignored) {}
            mDecoder = null;
        }
        frameQueue.clear();
        finishAndRemoveTask();
    }

    @Override public void surfaceCreated(@NonNull SurfaceHolder holder) { isSurfaceReady = true; initDecoder(); }
    @Override public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {}
    @Override public void surfaceDestroyed(@NonNull SurfaceHolder holder) { isSurfaceReady = false; isDecoderRunning = false; }

    @Override
    protected void onDestroy() {
        if (mChannelListener != null) Wearable.getChannelClient(this).unregisterChannelCallback(mChannelListener);
        try { unregisterReceiver(mFileReceiver); } catch (Exception ignored) {}
        try { unregisterReceiver(mCameraListReceiver); } catch (Exception ignored) {}
        cleanExit(false);
        super.onDestroy();
    }
}
