package de.rhaeus.wearsync;

import android.app.Service;
import android.content.Intent;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.IBinder;
import android.view.OrientationEventListener;
import android.view.Surface;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;

import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.ChannelClient;
import com.google.android.gms.wearable.Wearable;
import com.google.common.util.concurrent.ListenableFuture;

import org.json.JSONObject;

import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 📹 PhoneSyncCameraService - StateMachine 终极稳定版
 * 单状态源 + 串行事件 + 可恢复流媒体管线
 */
public class PhoneSyncCameraService extends Service implements LifecycleOwner {

    private static final String TAG = "WearSync_CameraService";

    public static final String ACTION_START_CAMERA = "de.rhaeus.wearsync.ACTION_START_CAMERA";
    public static final String ACTION_STOP_CAMERA  = "de.rhaeus.wearsync.ACTION_STOP_CAMERA";
    private static final String UNIVERSAL_SYNC_PATH = "/wear-universal-sync";

    private final LifecycleRegistry lifecycleRegistry = new LifecycleRegistry(this);
    private static PhoneSyncCameraService instance;

    private MediaCodec mEncoder;
    private OutputStream mOutputStream;
    private Surface mInputSurface;
    private Preview mPreviewUseCase;
    private OrientationEventListener mOrientationListener;

    private long totalFrames = 0;
    private volatile boolean firstFrame = false;

    private final AtomicBoolean serviceStarted = new AtomicBoolean(false);
    private final AtomicBoolean channelOpening  = new AtomicBoolean(false);

    private enum CameraState {
        IDLE,
        STARTING,
        CAMERA_READY,
        CHANNEL_OPENING,
        STREAMING,
        STOPPING,
        ERROR
    }

    private volatile CameraState state = CameraState.IDLE;

    private synchronized boolean transition(CameraState from, CameraState to) {
        if (state != from) return false;
        state = to;
        PhoneLog.d(TAG, "STATE " + from + " -> " + to);
        return true;
    }

    private synchronized void setState(CameraState s) {
        state = s;
        PhoneLog.d(TAG, "STATE => " + s);
    }

    private synchronized CameraState getState() {
        return state;
    }

    @Override
    public Lifecycle getLifecycle() {
        return lifecycleRegistry;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE);
        PhoneLog.d(TAG, "SERVICE CREATED");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        if (intent == null) return START_NOT_STICKY;

        String action = intent.getAction();

        if (ACTION_START_CAMERA.equals(action)) {
            startFlow();
        } else if (ACTION_STOP_CAMERA.equals(action)) {
            stopFlow();
        }

        return START_NOT_STICKY;
    }

    /* =========================
       FLOW CONTROL
       ========================= */

    private void startFlow() {

        if (!transition(CameraState.IDLE, CameraState.STARTING)) return;

        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START);

        totalFrames = 0;
        firstFrame = false;

        setupOrientation();
        setupEncoderAndCamera();
    }

    public void startStreaming(String nodeId) {

        if (nodeId == null || nodeId.isEmpty()) return;
        if (getState() != CameraState.CAMERA_READY) return;

        if (!transition(CameraState.CAMERA_READY, CameraState.CHANNEL_OPENING)) return;

        openChannel(nodeId);
    }

    private void stopFlow() {

        setState(CameraState.STOPPING);

        releaseAll();

        stopForeground(true);
        stopSelf();
    }

    /* =========================
       CAMERA + ENCODER
       ========================= */

    private void setupEncoderAndCamera() {

        try {

            MediaFormat format = MediaFormat.createVideoFormat(
                    MediaFormat.MIMETYPE_VIDEO_AVC, 640, 480);

            format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);

            format.setInteger(MediaFormat.KEY_BIT_RATE, 500000);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, 15);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);

            mEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            mEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            mInputSurface = mEncoder.createInputSurface();

            mEncoder.setCallback(new MediaCodec.Callback() {

                @Override
                public void onOutputBufferAvailable(@NonNull MediaCodec codec, int index,
                                                    @NonNull MediaCodec.BufferInfo info) {

                    if (getState() != CameraState.STREAMING || mOutputStream == null) {
                        codec.releaseOutputBuffer(index, false);
                        return;
                    }

                    if (info.size <= 0) {
                        codec.releaseOutputBuffer(index, false);
                        return;
                    }

                    try {

                        ByteBuffer buffer = codec.getOutputBuffer(index);
                        if (buffer == null) return;

                        byte[] data = new byte[info.size];
                        buffer.get(data);

                        mOutputStream.write(data);
                        mOutputStream.flush();

                        totalFrames++;

                        if (!firstFrame) {
                            firstFrame = true;
                            PhoneLog.d(TAG, "FIRST FRAME SENT");
                        }

                    } catch (Exception e) {
                        PhoneLog.e(TAG, "ENCODE STREAM ERROR", e);
                    } finally {
                        codec.releaseOutputBuffer(index, false);
                    }
                }

                @Override public void onInputBufferAvailable(@NonNull MediaCodec codec, int index) {}
                @Override public void onError(@NonNull MediaCodec codec, @NonNull MediaCodec.CodecException e) {}
                @Override public void onOutputFormatChanged(@NonNull MediaCodec codec, @NonNull MediaFormat format) {}
            });

            mEncoder.start();

            ListenableFuture<ProcessCameraProvider> future =
                    ProcessCameraProvider.getInstance(this);

            future.addListener(() -> {

                try {

                    ProcessCameraProvider provider = future.get();

                    mPreviewUseCase = new Preview.Builder().build();

                    mPreviewUseCase.setSurfaceProvider(
                            ContextCompat.getMainExecutor(this),
                            request -> request.provideSurface(
                                    mInputSurface,
                                    ContextCompat.getMainExecutor(this),
                                    result -> {}
                            )
                    );

                    provider.unbindAll();
                    provider.bindToLifecycle(
                            PhoneSyncCameraService.this,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            mPreviewUseCase
                    );

                    setState(CameraState.CAMERA_READY);

                } catch (Exception e) {
                    setState(CameraState.ERROR);
                }

            }, ContextCompat.getMainExecutor(this));

        } catch (Exception e) {
            setState(CameraState.ERROR);
        }
    }

    /* =========================
       CHANNEL
       ========================= */

    private void openChannel(String nodeId) {

        new Thread(() -> {

            try {

                ChannelClient.Channel channel =
                        Tasks.await(Wearable.getChannelClient(this)
                                .openChannel(nodeId, "/camera-preview-stream"));

                mOutputStream =
                        Tasks.await(Wearable.getChannelClient(this)
                                .getOutputStream(channel));

                JSONObject json = new JSONObject();
                json.put("sender", "phone");
                json.put("type", "camera");
                json.put("action", "STREAM_START");

                Wearable.getMessageClient(this).sendMessage(
                        nodeId,
                        UNIVERSAL_SYNC_PATH,
                        json.toString().getBytes(StandardCharsets.UTF_8)
                );

                setState(CameraState.STREAMING);

            } catch (Exception e) {
                PhoneLog.e(TAG, "CHANNEL ERROR", e);
                setState(CameraState.CAMERA_READY);
            }

        }).start();
    }

    /* =========================
       ORIENTATION
       ========================= */

    private void setupOrientation() {

        mOrientationListener = new OrientationEventListener(this) {

            int last = -1;

            @Override
            public void onOrientationChanged(int o) {

                if (o == ORIENTATION_UNKNOWN || mPreviewUseCase == null) return;

                int r;

                if (o < 45 || o >= 315) r = Surface.ROTATION_0;
                else if (o < 135) r = Surface.ROTATION_270;
                else if (o < 225) r = Surface.ROTATION_180;
                else r = Surface.ROTATION_90;

                if (r != last) {
                    last = r;
                    mPreviewUseCase.setTargetRotation(r);
                }
            }
        };

        mOrientationListener.enable();
    }

    /* =========================
       RELEASE
       ========================= */

    private void releaseAll() {

        try { if (mEncoder != null) { mEncoder.stop(); mEncoder.release(); } } catch (Exception ignored) {}
        mEncoder = null;

        try { if (mOutputStream != null) mOutputStream.close(); } catch (Exception ignored) {}
        mOutputStream = null;

        try { if (mInputSurface != null) mInputSurface.release(); } catch (Exception ignored) {}
        mInputSurface = null;

        if (mOrientationListener != null) {
            mOrientationListener.disable();
            mOrientationListener = null;
        }

        try {
            ProcessCameraProvider provider =
                    ProcessCameraProvider.getInstance(this).get();
            provider.unbindAll();
        } catch (Exception ignored) {}

        setState(CameraState.IDLE);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        releaseAll();
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY);
        instance = null;
        super.onDestroy();
    }
}