package cn.luke.wearsync;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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
import androidx.constraintlayout.widget.ConstraintLayout;

import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.ChannelClient;
import com.google.android.gms.wearable.Wearable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.DataInputStream;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * 手表端相机 UI (Android 16/17 优先版)
 * 职责：极致低延迟预览、多焦段切换、手势缩放、高性能 H.264 解码
 */
public class WearCameraActivity extends ComponentActivity implements SurfaceHolder.Callback {
    private static final String TAG = "WearSync_WearCameraUI";
    public static WeakReference<WearCameraActivity> sActivityRef = new WeakReference<>(null);

    private SurfaceView surfaceView;
    private FrameLayout surfaceContainer;
    private TextView tvStatusHint, tvCountdown;
    private View focusMarker;
    private LinearLayout layoutCameraList, layoutZoomList;
    private MediaCodec mDecoder;
    private volatile boolean isUserExiting = false, isFrozen = false;
    private boolean isSurfaceReady = false, isRecording = false;
    
    private static class VideoFrame {
        byte[] data; long timestamp; int flags;
        VideoFrame(byte[] d, long t, int f) { data = d; timestamp = t; flags = f; }
    }
    private final LinkedBlockingQueue<VideoFrame> frameQueue = new LinkedBlockingQueue<>(15);
    private float mScaleFactor = 1.0f, mPosX = 0, mPosY = 0, mMaxZoom = 1.0f;
    private ScaleGestureDetector mScaleDetector;
    private GestureDetector mGestureDetector;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private BroadcastReceiver mFileReceiver, mCameraListReceiver, mVideoStatusReceiver;
    
    private final List<View> mDynamicButtons = new ArrayList<>();

    @SuppressLint("ClickableViewAccessibility")
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        sActivityRef = new WeakReference<>(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        new WearSyncScreenManager(this).bind(this);
        setContentView(R.layout.activity_wear_camera);
        
        surfaceView = findViewById(R.id.surfaceView);
        surfaceContainer = findViewById(R.id.surface_container);
        tvStatusHint = findViewById(R.id.tv_status_hint);
        tvCountdown = findViewById(R.id.tv_countdown);
        focusMarker = findViewById(R.id.focus_marker);
        layoutCameraList = findViewById(R.id.layout_camera_list);
        layoutZoomList = findViewById(R.id.layout_zoom_list);
        
        setupGestures();
        initUI();
        registerReceivers();

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (isFrozen) { unfreezePreview(); return; }
                WearSyncCommManager.getInstance(getApplicationContext()).sendBusinessCommand("camera_action", "STOP_CAMERA");
                cleanExit();
            }
        });

        startDecoderThread();
        Wearable.getChannelClient(this).registerChannelCallback(mChannelListener);
        WearSyncCommManager.getInstance(getApplicationContext()).sendBusinessCommand("camera_control", "open_phone_camera");
        WearLog.d(TAG, "🟢 UI Started");
    }

    @SuppressLint("ClickableViewAccessibility")
    private void initUI() {
        findViewById(R.id.btn_shutter).setOnClickListener(v -> startCountdownAndCapture());
        findViewById(R.id.btn_record).setOnClickListener(v -> WearSyncCommManager.getInstance(getApplicationContext()).toggleVideoRecording());
        
        if (surfaceContainer != null) {
            surfaceContainer.setOnTouchListener((v, event) -> {
                if (isFrozen) { mScaleDetector.onTouchEvent(event); mGestureDetector.onTouchEvent(event); }
                if (event.getAction() == MotionEvent.ACTION_UP) v.performClick();
                return isFrozen;
            });
        }
        if (surfaceView != null) {
            surfaceView.getHolder().addCallback(this);
            surfaceView.setOnTouchListener((v, event) -> {
                if (isFrozen) return false;
                if (event.getAction() == MotionEvent.ACTION_DOWN) {
                    showFocusMarker(event.getX(), event.getY());
                    WearSyncCommManager.getInstance(getApplicationContext()).sendBusinessCommand("camera_action", "FOCUS_CAMERA", "x", (double)(event.getX()/v.getWidth()), "y", (double)(event.getY()/v.getHeight()));
                }
                if (event.getAction() == MotionEvent.ACTION_UP) v.performClick();
                return true;
            });
        }
        mHandler.postDelayed(() -> {
            if (mDynamicButtons.isEmpty()) WearSyncCommManager.getInstance(this).sendBusinessCommand("camera_control", "REQUEST_CAMERA_LIST");
        }, 2000);
    }

    private void setupGestures() {
        mScaleDetector = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(@NonNull ScaleGestureDetector d) { mScaleFactor *= d.getScaleFactor(); mScaleFactor = Math.max(1f, Math.min(mScaleFactor, 5f)); applyTransform(); return true; }
        });
        mGestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onScroll(MotionEvent e1, @NonNull MotionEvent e2, float dx, float dy) { if (mScaleFactor > 1f) { mPosX -= dx; mPosY -= dy; applyTransform(); return true; } return false; }
            @Override public boolean onDoubleTap(@NonNull MotionEvent e) { resetTransform(); return true; }
        });
    }

    private void updateCameraList(String json) {
        if (json == null) return;
        runOnUiThread(() -> {
            try {
                clearDynamicButtons();
                if (layoutCameraList != null) layoutCameraList.removeAllViews();
                JSONArray arr = new JSONArray(json);
                boolean isRound = getResources().getConfiguration().isScreenRound();
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject obj = arr.getJSONObject(i);
                    String id = obj.getString("id"), name = obj.getString("name");
                    float maxZ = (float) obj.optDouble("maxZoom", 1.0);
                    Button btn = createSmallButton(name.substring(0, 1));
                    btn.setOnClickListener(v -> { mMaxZoom = maxZ; updateZoomUI(); WearSyncCommManager.getInstance(this).selectCamera(id); });
                    // 🎯 顶部角度 225° 开始，间隔 30°
                    if (isRound) positionCircular(btn, 225 + i * 30); else if (layoutCameraList != null) { layoutCameraList.setVisibility(View.VISIBLE); layoutCameraList.addView(btn); }
                    if (i == 0) { mMaxZoom = maxZ; updateZoomUI(); }
                }
            } catch (Exception e) { WearLog.e(TAG, "Update Camera List Error", e); }
        });
    }

    private void updateZoomUI() {
        runOnUiThread(() -> {
            if (layoutZoomList != null) layoutZoomList.removeAllViews();
            float[] lvls = {1f, 2f, 5f, 10f};
            boolean isRound = getResources().getConfiguration().isScreenRound();
            int count = 0;
            for (float l : lvls) {
                if (l <= mMaxZoom || l == 1f) {
                    Button btn = createSmallButton(String.format(Locale.US, "%.0f", l));
                    btn.setOnClickListener(v -> { WearSyncCommManager.getInstance(this).setZoom(l); showCaptureHint(l + "X"); });
                    // 🎯 右侧角度 45° 开始
                    if (isRound) positionCircular(btn, 45 + count * 30); else if (layoutZoomList != null) { layoutZoomList.setVisibility(View.VISIBLE); layoutZoomList.addView(btn); }
                    count++;
                }
            }
        });
    }

    private void clearDynamicButtons() {
        ConstraintLayout root = findViewById(R.id.layout_camera_root);
        if (root == null) return;
        for (View v : mDynamicButtons) root.removeView(v);
        mDynamicButtons.clear();
    }

    private Button createSmallButton(String text) {
        Button b = new Button(this); b.setText(text); b.setTextSize(10); b.setTextColor(0xFFFFFFFF); b.setPadding(0,0,0,0);
        b.setBackgroundResource(R.drawable.bg_action_btn);
        float d = getResources().getDisplayMetrics().density;
        b.setLayoutParams(new ConstraintLayout.LayoutParams((int)(30*d), (int)(30*d)));
        return b;
    }

    private void positionCircular(View v, int angle) {
        ConstraintLayout root = findViewById(R.id.layout_camera_root);
        if (root == null) return;
        if (v.getParent() == null) { root.addView(v); mDynamicButtons.add(v); }
        ConstraintLayout.LayoutParams lp = (ConstraintLayout.LayoutParams) v.getLayoutParams();
        lp.circleConstraint = R.id.anchor_center;
        lp.circleAngle = angle;
        lp.circleRadius = (int) (85 * getResources().getDisplayMetrics().density); 
        v.setLayoutParams(lp);
    }

    private void registerReceivers() {
        mFileReceiver = new BroadcastReceiver() { @Override public void onReceive(Context c, Intent i) { showCaptureHint("已拍"); unfreezePreview(); } };
        registerReceiver(mFileReceiver, new IntentFilter("cn.luke.wearsync.ACTION_FILE_RECEIVED"), Context.RECEIVER_EXPORTED);
        mCameraListReceiver = new BroadcastReceiver() { @Override public void onReceive(Context c, Intent i) { updateCameraList(i.getStringExtra("camera_list")); } };
        registerReceiver(mCameraListReceiver, new IntentFilter("cn.luke.wearsync.ACTION_CAMERA_LIST_RECEIVED"), Context.RECEIVER_EXPORTED);
        mVideoStatusReceiver = new BroadcastReceiver() { 
            @Override public void onReceive(Context c, Intent i) {
                String status = i.getStringExtra("status");
                if (status == null) return;
                try {
                    JSONObject j = new JSONObject(status);
                    isRecording = j.optBoolean("isRecording");
                    runOnUiThread(() -> {
                        Button rb = findViewById(R.id.btn_record);
                        if (rb != null) { rb.setText(isRecording ? "停" : "录"); rb.setTextColor(isRecording ? 0xFFFF0000 : 0xFFFFFFFF); }
                        showCaptureHint(isRecording ? "录像中" : "录像停止");
                    });
                } catch (Exception e) {
                    WearLog.e(TAG, "解析视频状态失败", e);
                }
            }
        };
        registerReceiver(mVideoStatusReceiver, new IntentFilter("cn.luke.wearsync.ACTION_VIDEO_STATUS"), Context.RECEIVER_EXPORTED);
    }

    private final ChannelClient.ChannelCallback mChannelListener = new ChannelClient.ChannelCallback() {
        @Override public void onChannelOpened(@NonNull ChannelClient.Channel c) { if ("/wear_data_channel/camera".equals(c.getPath())) readStreamFromChannel(c); }
    };

    private void applyTransform() { if (surfaceView != null) { surfaceView.setScaleX(mScaleFactor); surfaceView.setScaleY(mScaleFactor); surfaceView.setTranslationX(mPosX); surfaceView.setTranslationY(mPosY); } }
    private void resetTransform() { mScaleFactor = 1f; mPosX = 0; mPosY = 0; applyTransform(); }
    private void startCountdownAndCapture() { if (isRecording) return; if (tvCountdown != null) tvCountdown.setVisibility(View.VISIBLE); new Thread(() -> { for (int i=3; i>0; i--) { int c=i; runOnUiThread(() -> { if (tvCountdown != null) tvCountdown.setText(String.valueOf(c)); }); try { Thread.sleep(1000); } catch (Exception e) { break; } } runOnUiThread(() -> { if (tvCountdown != null) tvCountdown.setVisibility(View.GONE); freezePreview(); WearSyncCommManager.getInstance(this).sendBusinessCommand("camera_action", "TAKE_PHOTO"); }); }).start(); }
    private void freezePreview() { isFrozen = true; }
    private void unfreezePreview() { isFrozen = false; resetTransform(); frameQueue.clear(); }
    private void showFocusMarker(float x, float y) { if (focusMarker != null) { focusMarker.setX(x-20); focusMarker.setY(y-20); focusMarker.setVisibility(View.VISIBLE); focusMarker.setAlpha(1f); focusMarker.animate().alpha(0f).setDuration(800).withEndAction(() -> focusMarker.setVisibility(View.GONE)).start(); } }
    
    private void readStreamFromChannel(ChannelClient.Channel c) {
        new Thread(() -> {
            try (InputStream is = Tasks.await(Wearable.getChannelClient(this).getInputStream(c)); 
                 DataInputStream dis = new DataInputStream(is)) {
                
                while (!isUserExiting) {
                    // 🎯 修复：增加非阻塞检查。DataInputStream.readInt() 在流切换时可能无限期阻塞
                    if (is.available() < 4) {
                        Thread.sleep(10); 
                        continue;
                    }
                    
                    int len = dis.readInt(); 
                    long time = dis.readLong(); 
                    int flags = dis.readInt();
                    
                    if (len > 0 && len < 1000000) {
                        byte[] d = new byte[len]; 
                        dis.readFully(d);
                        if (!isFrozen) frameQueue.offer(new VideoFrame(d, time, flags));
                    }
                }
            } catch (Exception e) { 
                WearLog.e(TAG, "读取流异常 (可能是流重建中断)", e); 
            }
        }).start();
    }
    
    private void startDecoderThread() {
        new Thread(() -> {
            while (!isUserExiting) {
                try {
                    VideoFrame f = frameQueue.take();
                    if (isSurfaceReady && mDecoder != null) {
                        int id = mDecoder.dequeueInputBuffer(10000);
                        if (id >= 0) {
                            ByteBuffer b = mDecoder.getInputBuffer(id);
                            if (b != null) { b.clear(); b.put(f.data); mDecoder.queueInputBuffer(id, 0, f.data.length, f.timestamp, f.flags); }
                        }
                        MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
                        int outId = mDecoder.dequeueOutputBuffer(bi, 10000);
                        if (outId >= 0) mDecoder.releaseOutputBuffer(outId, true);
                    }
                } catch (Exception e) {
                    if (e instanceof IllegalStateException) {
                        WearLog.w(TAG, "解码器状态异常，尝试重置");
                        initDecoder();
                    } else {
                        WearLog.e(TAG, "解码线程意外错误", e);
                    }
                }
            }
        }).start();
    }

    private void initDecoder() {
        try {
            // 🎯 将解码器默认尺寸设为 256x256，匹配手机端的流畅优先策略
            MediaFormat f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 256, 256);
            f.setInteger(MediaFormat.KEY_ROTATION, 90);
            mDecoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            mDecoder.configure(f, surfaceView.getHolder().getSurface(), null, 0);
            mDecoder.start();
            WearLog.i(TAG, "Decoder Synchronized (Low-Res Fluid Mode)");
        } catch (Exception e) { WearLog.e(TAG, "Init Decoder err", e); }
    }

    public static void forceClose() { WearCameraActivity activity = sActivityRef.get(); if (activity != null && !activity.isUserExiting) { activity.runOnUiThread(activity::cleanExit); } }
    private void cleanExit() {
        isUserExiting = true;
        if (mDecoder != null) {
            try {
                mDecoder.stop();
                mDecoder.release();
            } catch (Exception e) {
                WearLog.w(TAG, "清理解码器异常: " + e.getMessage());
            }
            mDecoder = null;
        }
        finishAndRemoveTask();
    }
    @Override public void surfaceCreated(@NonNull SurfaceHolder h) { isSurfaceReady = true; initDecoder(); }
    @Override public void surfaceChanged(@NonNull SurfaceHolder h, int f, int w, int h1) {}
    @Override public void surfaceDestroyed(@NonNull SurfaceHolder h) { isSurfaceReady = false; }
    @Override protected void onDestroy() {
        Wearable.getChannelClient(this).unregisterChannelCallback(mChannelListener);
        try {
            unregisterReceiver(mFileReceiver);
            unregisterReceiver(mCameraListReceiver);
            unregisterReceiver(mVideoStatusReceiver);
        } catch (Exception e) {
            WearLog.w(TAG, "解注册接收器失败: " + e.getMessage());
        }
        cleanExit();
        super.onDestroy();
    }
    private void showCaptureHint(String t) { if (tvStatusHint != null) { tvStatusHint.setText(t); tvStatusHint.setVisibility(View.VISIBLE); tvStatusHint.animate().alpha(0f).setDuration(1500).setStartDelay(500).withEndAction(()->tvStatusHint.setVisibility(View.GONE)).start(); } }
}
