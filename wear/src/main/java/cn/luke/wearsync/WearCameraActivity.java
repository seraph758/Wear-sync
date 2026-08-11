package cn.luke.wearsync;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Bundle;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
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
    private ImageView imgPreview;
    private TextView tvStatusHint;
    private MediaCodec mDecoder;
    private volatile boolean isDecoderRunning = false;
    private volatile boolean isUserExiting = false;
    private boolean isSurfaceReady = false;
    private final LinkedBlockingQueue<byte[]> frameQueue = new LinkedBlockingQueue<>(15);
    private Thread renderThread;

    private ChannelClient.ChannelCallback mChannelListener;
    private BroadcastReceiver mFileReceiver;

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
        imgPreview = findViewById(R.id.img_preview);
        tvStatusHint = findViewById(R.id.tv_status_hint);

        if (surfaceView != null) {
            surfaceView.getHolder().addCallback(this);
            // 🔄 移除手表端旋转，由手机端推流时完成旋转
        }

        // 🎯 拍照快门按钮：向手机发送触发高清拍照指令
        Button btnShutter = findViewById(R.id.btn_shutter);
        if (btnShutter != null) {
            btnShutter.setOnClickListener(_ -> {
                WearLog.d(TAG, "📸 用户点击 [快门按钮]，发送最高画质拍照请求");
                WearSyncCommManager.getInstance(getApplicationContext()).sendBusinessCommand("camera_action", "TAKE_PHOTO");
                showCaptureHint("📸 正在拍照...");
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
            tvStatusHint.animate().alpha(0.0f).setDuration(1500).withEndAction(() -> {
                tvStatusHint.setVisibility(View.GONE);
            }).start();
        });
    }

    private void showPhotoPreview(Uri uri) {
        if (imgPreview == null) return;
        runOnUiThread(() -> {
            try {
                showCaptureHint("✨ 照片已保存并同步");
                imgPreview.setImageURI(uri);
                imgPreview.setVisibility(View.VISIBLE);
                imgPreview.setAlpha(0.0f);
                imgPreview.animate().alpha(1.0f).setDuration(500).start();
                
                // 3秒后自动隐藏预览返回取景
                imgPreview.postDelayed(() -> {
                    if (!isUserExiting) {
                        imgPreview.animate().alpha(0.0f).setDuration(500).withEndAction(() -> {
                            imgPreview.setVisibility(View.GONE);
                        }).start();
                    }
                }, 3000);
            } catch (Exception e) {
                WearLog.e(TAG, "❌ 显示照片预览失败", e);
            }
        });
    }

    public void feedH264Data(byte[] h264Data) {
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
            // 🎯 对齐手机端的 320x320 画面尺寸
            MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 320, 320);
            // 🔄 关键：告诉硬件解码器将流旋转 90 度以匹配手机传感器方向
            format.setInteger(MediaFormat.KEY_ROTATION, 90);
            
            mDecoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            mDecoder.configure(format, surfaceView.getHolder().getSurface(), null, 0);
            mDecoder.start();
            isDecoderRunning = true;
            WearLog.d(TAG, "🎉 H.264 解码器初始化成功 (320x320)");
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
