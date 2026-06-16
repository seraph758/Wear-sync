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
 * 核心职责：
 * 1. 建立弱引用安全访问通路，供 Service 高频零延时直接泵入图片。
 * 2. 接收手机端物理姿态角度，采用 Hardware-Accelerated View 矩阵旋转纠偏（杜绝GC内存抖动）。
 * 3. 屏幕点击触发快门下发，退出时触发双端对齐释放，并执行 finishAndRemoveTask() 核级自杀。
 */
public class WearCameraActivity extends Activity {
    private static final String TAG = "WearSync_WearCameraUI";
    private static final String UNIVERSAL_SYNC_PATH = "/wear-universal-sync";

    private ImageView imgPreview;
    private int mRotationDegrees = 0;
    private boolean isUserExiting = false;

    // 🌟 核心高能内存设计：利用弱引用持有当前活动实例，既保证了高频帧数据的零拷贝极速分发，又绝对防止了 Activity 内存泄漏
    private static WeakReference<WearCameraActivity> sActivityRef = new WeakReference<>(null);

    /**
     * 🛰️ 高速外部泵入接口：由 Service 线程直接高频调用 (修复找不到符号报错)
     */
    public static void updateFrame(byte[] jpegData) {
        WearCameraActivity activity = sActivityRef.get();
        if (activity != null && jpegData != null) {
            activity.renderJpegFrame(jpegData);
        }
    }

    /**
     * 🛰️ 强退接口：由 Service 收到手机彻底退出消息时直接调用 (修复找不到符号报错)
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
    
        // 🎯 优化二：精准点击底部的相机小按钮 ➔ 同样触发拍照
        Button btnCapture = findViewById(R.id.btn_capture);
        if (btnCapture == null) {
            // 兼容你 XML 里的驼峰命名 id：btnCapture
            btnCapture = findViewById(R.id.btnCapture);
        }
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
                
                // 将干净的压缩包解码为原生 Bitmap
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
                json.put("action", actionStr); // "CAPTURE_SHUTTER" 或 "STOP_CAMERA"
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
     * 🧹 撤退清空协议：斩草除根式退出，绝不留任何 Recent Tasks 后台污染
     */
    private void cleanExit(boolean notifyPhone) {
        if (isUserExiting) return;
        isUserExiting = true;
        Log.d(TAG, "🧹 正在启动手表相机退出机制，全面清理资源通道...");

        if (notifyPhone) {
            // 如果是用户自己在手表上滑走或者按返回退出的，必须通知手机端关闭相机硬件并摧毁 Channel 管道
            sendControlSignalToPhone("STOP_CAMERA");
        }

        // 解除弱引用绑定
        if (sActivityRef.get() == this) {
            sActivityRef.clear();
        }

        if (imgPreview != null) {
            imgPreview.setImageBitmap(null); // 解除图片资产绑定，供虚拟机全力回收内存
        }

        // 🏁 核心：执行彻底的销毁并从最近任务列表中彻底抹去残影（核级自杀）
        finishAndRemoveTask();
    }

    @Override
    public void onBackPressed() {
        cleanExit(true); // 拦截返回键，优雅走完清空流程
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        cleanExit(true); // 拦截左滑退出，进行终极安全防御
        super.onDestroy();
    }
}
