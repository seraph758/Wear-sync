package de.rhaeus.wearsync;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
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

/**
 * 📸 手表端独立解耦相机全屏交互舱
 */
public class WearCameraActivity extends Activity {
    private static final String TAG = "WearSync_WearCameraUI";
    private static final String UNIVERSAL_SYNC_PATH = "/wear-universal-sync";

    private ImageView imgPreview;
    private int mRotationDegrees = 0;
    private boolean isUserExiting = false;

    // 弱引用持有当前活动实例，防止 Activity 内存泄漏
    private static WeakReference<WearCameraActivity> sActivityRef = new WeakReference<>(null);

    /**
     * 🛰️ 高速外部泵入接口：由 Service 线程直接高频调用
     */
    public static void updateFrame(byte[] jpegData) {
        WearCameraActivity activity = sActivityRef.get();
        if (activity != null && jpegData != null) {
            activity.renderJpegFrame(jpegData);
        }
    }

    /**
     * 🛰️ 强退接口：由 Service 收到手机彻底退出消息时直接调用
     */
    public static void forceQuitInstance() {
        WearCameraActivity activity = sActivityRef.get();
        if (activity != null) {
            Log.d(TAG, "🛑 收到手机端强迫退出指令，触发核级自杀退出...");
            activity.cleanExit(false);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_wear_camera);
    
        sActivityRef = new WeakReference<>(this);
        mRotationDegrees = getIntent().getIntExtra("rotation_degrees", 0);
        imgPreview = findViewById(R.id.img_camera_preview);
    
        if (imgPreview != null && mRotationDegrees != 0) {
            imgPreview.setRotation(mRotationDegrees); // 硬件加速旋转
        }
    
        // 🎯 优化一：点击全屏空白处 ➔ 触发拍照
        RelativeLayout rootLayout = findViewById(R.id.layout_camera_root);
        if (rootLayout != null) {
            rootLayout.setOnClickListener(v -> {
                Log.d(TAG, "📸 用户轻触全屏任意区域 ➔ 下发快门");
                sendControlSignalToPhone("CAPTURE_SHUTTER");
            });
        }
    
        // 🎯 核心修复：彻底删掉不存在的 btn_capture 变量行，直接精准、唯一匹配 XML 里的驼峰 ID btnCapture
        Button btnCapture = findViewById(R.id.btnCapture); 
        if (btnCapture != null) {
            btnCapture.setOnClickListener(v -> {
                Log.d(TAG, "📸 用户点击了底部的专属相机按钮 ➔ 下发快门");
                sendControlSignalToPhone("CAPTURE_SHUTTER");
            });
        }
    }

    /**
     * 🖼️ 高速位图渲染器：在 UI 线程高速还原 JPEG 帧
     */
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

    /**
     * 🚀 跨端发射器：向手机发送快门或退出要求
     */
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

    /**
     * 🧹 撤退清空协议
     */
    private void cleanExit(boolean notifyPhone) {
        if (isUserExiting) return;
        isUserExiting = true;
        Log.d(TAG, "🧹 正在启动手表相机退出机制，全面清理资源通道...");

        if (notifyPhone) {
            sendControlSignalToPhone("STOP_CAMERA");
        }

        if (sActivityRef.get() == this) {
            sActivityRef.clear();
        }

        if (imgPreview != null) {
            imgPreview.setImageBitmap(null);
        }

        finish();
    }

    @Override
    public void onBackPressed() {
        cleanExit(true);
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        cleanExit(true);
        super.onDestroy();
    }
}
