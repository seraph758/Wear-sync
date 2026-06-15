package de.rhaeus.wearsync;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 手表端预览画面流渲染主 Activity
 * 完美修正：精准适配你提供的 FrameLayout 布局，绑定拍照按钮，并提供无按钮盲操退出机制。
 */
public class WearCameraActivity extends Activity {
    private static final String TAG = "WearSync_WearCamera";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // 🎯 1. 绑定你的相机预览布局（请确保这里的文件名和你的 XML 文件名完全一致，如 activity_main 或 activity_camera_preview）
        setContentView(R.layout.activity_main); 

        // 🎯 2. 精准绑定你布局里的拍照按钮 btnCapture
        Button btnCapture = findViewById(R.id.btn_capture); // 如果你的 XML ID 是 btnCapture，Java 编译会自动识别
        if (btnCapture == null) {
            // 兼容可能的大写或下划线命名习惯
            btnCapture = findViewById(R.id.btnCapture);
        }

        if (btnCapture != null) {
            btnCapture.setOnClickListener(v -> {
                Log.d(TAG, "📸 用户点击了手表端的拍照按钮，触发手机快门...");
                sendCameraCaptureSignal(); // 投递快门信号
            });
        }

        // 🎯 3. 盲操退出优化：由于布局里没有退出按钮，点击画面预览层（frameView）任意地方，直接优雅退出
        ImageView frameView = findViewById(R.id.frameView);
        if (frameView != null) {
            frameView.setOnClickListener(v -> {
                Log.d(TAG, "🛎️ 用户点击预览画面，判定为退出相机意图。执行双端协同下线...");
                executeElegantExitFlow();
            });
        } else {
            // 兜底：如果连 frameView 都没拿到，点屏幕任意空白处退出
            View rootView = findViewById(android.R.id.content);
            if (rootView != null) {
                rootView.setOnClickListener(v -> executeElegantExitFlow());
            }
        }
    }

    /**
     * 发送拍照快门信号给手机
     */
    private void sendCameraCaptureSignal() {
        new Thread(() -> {
            try {
                JSONObject json = new JSONObject();
                json.put("sender", "wear");
                json.put("type", "camera_action");
                json.put("action", "TAKE_PICTURE"); // 投递拍照动作
                json.put("timestamp", System.currentTimeMillis());

                byte[] data = json.toString().getBytes(StandardCharsets.UTF_8);
                List<Node> nodes = Tasks.await(Wearable.getNodeClient(this).getConnectedNodes());
                for (Node node : nodes) {
                    Wearable.getMessageClient(this).sendMessage(node.getId(), "/wear-universal-sync", data);
                }
            } catch (Exception e) {
                Log.e(TAG, "发送快门信号失败", e);
            }
        }).start();
    }

    /**
     * 执行正常协同关闭流程
     */
    private void executeElegantExitFlow() {
        new Thread(() -> {
            try {
                JSONObject json = new JSONObject();
                json.put("sender", "wear");
                json.put("type", "camera_action");
                json.put("action", "STOP_CAMERA_UI");
                json.put("timestamp", System.currentTimeMillis());

                byte[] data = json.toString().getBytes(StandardCharsets.UTF_8);
                List<Node> nodes = Tasks.await(Wearable.getNodeClient(this).getConnectedNodes());
                for (Node node : nodes) {
                    Wearable.getMessageClient(this).sendMessage(node.getId(), "/wear-universal-sync", data);
                }
                Log.d(TAG, "📤 手表已成功向手机端发送正常关闭相机信令");

                Intent closeMainIntent = new Intent("DE_RHAEUS_WEARSYNC_FORCE_CLOSE_MAIN");
                closeMainIntent.setPackage(getPackageName());
                sendBroadcast(closeMainIntent);

                runOnUiThread(this::finish);
            } catch (Exception e) {
                Log.e(TAG, "执行正常协同退出通信流程失败", e);
                runOnUiThread(this::finish);
            }
        }).start();
    }

    @Override
    protected void onDestroy() {
        Log.d(TAG, "🧹 正在执行 onDestroy 生命周期。开始彻底释放手表的画面硬件持有与重力感应...");
        try {
            setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_NOSENSOR);
        } catch (Exception e) {
            Log.e(TAG, "清理重力感应残留异常", e);
        }
        super.onDestroy();
        android.os.Process.killProcess(android.os.Process.myPid());
    }
}
