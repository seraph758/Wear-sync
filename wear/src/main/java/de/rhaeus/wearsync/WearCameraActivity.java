package de.rhaeus.wearsync;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;          // 🎯 核心修复一：必须加上 Button 的导包！
import android.widget.RelativeLayout;
import android.widget.ImageView;
import android.util.Log;
import java.lang.ref.WeakReference;

public class WearCameraActivity extends Activity {
    private static final String TAG = "WearSync_WearCamera";
    public static WeakReference<WearCameraActivity> sActivityRef;
    private int mRotationDegrees = 0;
    private ImageView imgPreview;

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
    
        // 🎯 核心修复二：删掉之前错误的 btncapture 行！直接精准匹配 XML 里的驼峰 ID btnCapture
        Button btnCapture = findViewById(R.id.btnCapture); 
        if (btnCapture != null) {
            btnCapture.setOnClickListener(v -> {
                Log.d(TAG, "📸 用户点击了底部的专属相机按钮 ➔ 下发快门");
                sendControlSignalToPhone("CAPTURE_SHUTTER");
            });
        }
    }

    private void sendControlSignalToPhone(String action) {
        new Thread(() -> {
            try {
                org.json.JSONObject json = new org.json.JSONObject();
                json.put("sender", "wear");
                json.put("type", "camera_control");
                json.put("action", action);
                byte[] data = json.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                
                java.util.List<com.google.android.gms.wearable.Node> nodes = 
                    com.google.android.gms.tasks.Tasks.await(com.google.android.gms.wearable.Wearable.getNodeClient(this).getConnectedNodes());
                for (com.google.android.gms.wearable.Node node : nodes) {
                    com.google.android.gms.wearable.Wearable.getMessageClient(this)
                        .sendMessage(node.getId(), "/wear-universal-sync", data);
                }
            } catch (Exception e) {
                Log.e(TAG, "发送相机控制指令失败", e);
            }
        }).start();
    }
}
