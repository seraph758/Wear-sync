package de.rhaeus.wearsync;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.RelativeLayout;

import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;

import org.json.JSONObject;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class WearCameraActivity extends Activity {
    private static final String TAG = "WearSync_WearCameraUI";
    private static final String UNIVERSAL_SYNC_PATH = "/wear-universal-sync";

    private ImageView imgPreview;
    private int mRotationDegrees = 0;
    private boolean isUserExiting = false;

    private static WeakReference<WearCameraActivity> sActivityRef = new WeakReference<>(null);

    // 🎯 核心重构：增加本地看门狗广播接收器，响应由 Service 转发来的手机关闭信令
    private final BroadcastReceiver phoneKillReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if ("de.rhaeus.wearsync.ACTION_KILL_WEAR_CAMERA".equals(intent.getAction())) {
                Log.d(TAG, "🏳️ 收到手机端关闭指令的本地广播，手錶端 Camera UI 无条件自杀退出...");
                cleanExit(false); // 手机端关的，不需要重复反向通知手机
            }
        }
    };

    public static void updateFrame(byte[] jpegData) {
        WearCameraActivity activity = sActivityRef.get();
        if (activity != null && jpegData != null) {
            activity.renderJpegFrame(jpegData);
        }
    }

    public static void forceQuitInstance() {
        WearCameraActivity activity = sActivityRef.get();
        if (activity != null) {
            Log.d(TAG, "🛑 触发兜底自杀退出...");
            activity.cleanExit(false);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_wear_camera);

        btnCapture.setOnClickListener(v -> startCaptureCountdown()); [cite: 325]
    
        sActivityRef = new WeakReference<>(this);
        mRotationDegrees = getIntent().getIntExtra("rotation_degrees", 0);
        imgPreview = findViewById(R.id.img_camera_preview);
    
        if (imgPreview != null && mRotationDegrees != 0) {
            imgPreview.setRotation(mRotationDegrees);
        }
    
        RelativeLayout rootLayout = findViewById(R.id.layout_camera_root);
        if (rootLayout != null) {
            rootLayout.setOnClickListener(v -> {
                Log.d(TAG, "📸 用户轻触全屏任意区域 ➔ 下发快门");
                sendControlSignalToPhone("CAPTURE_SHUTTER");
            });
        }
    
        Button btnCapture = findViewById(R.id.btnCapture); 
        if (btnCapture != null) {
            btnCapture.setOnClickListener(v -> {
                Log.d(TAG, "📸 用户点击了底部的专属相机按钮 ➔ 下发快门");
                sendControlSignalToPhone("CAPTURE_SHUTTER");
            });
        }

        // 🎯 注册看门狗
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(phoneKillReceiver, new IntentFilter("de.rhaeus.wearsync.ACTION_KILL_WEAR_CAMERA"), Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(phoneKillReceiver, new IntentFilter("de.rhaeus.wearsync.ACTION_KILL_WEAR_CAMERA"));
        }
    }

    private void renderJpegFrame(byte[] jpegData) {
        runOnUiThread(() -> {
            try {
                if (isUserExiting || imgPreview == null) return;
                Bitmap bitmap = BitmapFactory.decodeByteArray(jpegData, 0, jpegData.length);
                if (bitmap != null) {
                    imgPreview.setImageBitmap(bitmap);
                }
            } catch (Exception e) {
                Log.e(TAG, "渲染图片预览帧发生不可控异常", e);
            }
        });
    }

    private void sendControlSignalToPhone(String actionStr) {
        new Thread(() -> {
            try {
                JSONObject json = new JSONObject();
                json.put("sender", "wear");
                json.put("type", "camera_control");
                json.put("action", actionStr);
                json.put("timestamp", System.currentTimeMillis());

                byte[] payload = json.toString().getBytes(StandardCharsets.UTF_8);
                List<Node> nodes = Tasks.await(Wearable.getNodeClient(this).getConnectedNodes());
                if (nodes != null) {
                    for (Node n : nodes) {
                        Wearable.getMessageClient(this).sendMessage(n.getId(), UNIVERSAL_SYNC_PATH, payload);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "向手机端投递相机控场指令失败", e);
            }
        }).start();
    }

    private void startCaptureCountdown() {
    new android.os.CountDownTimer(3000, 1000) {
        public void onTick(long millisUntilFinished) {
            // UI 更新倒计时文字 (需在 XML 中添加一个 TextView tvCountdown)
            // tvCountdown.setText(String.valueOf(millisUntilFinished / 1000 + 1));
            Log.d(TAG, "📸 倒计时: " + (millisUntilFinished / 1000 + 1));
        }
        public void onFinish() {
            Log.d(TAG, "📸 倒计时结束 ➔ 下发快门");
            sendControlSignalToPhone("CAPTURE_SHUTTER"); [cite: 331]
        }
    }.start();
}

    private void cleanExit(boolean notifyPhone) {
        if (isUserExiting) return;
        isUserExiting = true;
        Log.d(TAG, "🧹 正在启动手表相机退出机制...");

        if (notifyPhone) {
            // 🎯 与手机端 PhoneSyncListenerService 完美拉齐的停止协议口令
            sendControlSignalToPhone("STOP_CAMERA");
        }

        try {
            unregisterReceiver(phoneKillReceiver);
        } catch (Exception ignored) {}

        if (sActivityRef.get() == this) {
            sActivityRef.clear();
        }

        if (imgPreview != null) {
            imgPreview.setImageBitmap(null);
        }

        finishAndRemoveTask(); // 彻底斩断后台残影
    }

    @Override
    public void onBackPressed() {
        cleanExit(true);
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        sendControlSignalToPhone("STOP_CAMERA");
        cleanExit(true);
        super.onDestroy();
    }
}
