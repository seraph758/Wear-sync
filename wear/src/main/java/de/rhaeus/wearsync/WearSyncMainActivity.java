package de.rhaeus.wearsync;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class WearMainActivity extends Activity {
    private static final String TAG = "WearSync_WearMain";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 确保绑定的是包含“唤醒手机相机”、“同步勿扰”等主按钮的手表主布局
        setContentView(R.layout.activity_main); 

        // 🎯 修复核心：找到你手表主界面上的那个“唤醒/打开相机”的按钮 ID
        Button btnLaunchCamera = findViewById(R.id.btn_launch_camera); 
        if (btnLaunchCamera == null) {
            // 兼容可能存在的不同 ID 命名
            btnLaunchCamera = findViewById(R.id.btnCamera);
        }

        if (btnLaunchCamera != null) {
            btnLaunchCamera.setOnClickListener(v -> {
                Log.d(TAG, "🚀 用户点击唤醒相机：执行双向联动...");
                
                // 1. 扔出信令通知手机在后台开机
                sendActionToPhone("camera_action", "START_CAMERA_UI");

                // 2. 强行切进预览界面！拒绝套娃！直接把 frameView 挂起来等待画面灌入
                Intent intent = new Intent(WearMainActivity.this, WearCameraActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                startActivity(intent);
            });
        }
    }

    private void sendActionToPhone(String typeValue, String actionValue) {
        new Thread(() -> {
            try {
                JSONObject json = new JSONObject();
                json.put("sender", "wear");
                json.put("type", typeValue);
                json.put("action", actionValue);
                json.put("timestamp", System.currentTimeMillis());

                byte[] data = json.toString().getBytes(StandardCharsets.UTF_8);
                List<Node> nodes = Tasks.await(Wearable.getNodeClient(this).getConnectedNodes());
                for (Node node : nodes) {
                    Wearable.getMessageClient(this).sendMessage(node.getId(), "/wear-universal-sync", data);
                }
                Log.d(TAG, "📤 手表指令成功发射: " + actionValue);
            } catch (Exception e) {
                Log.e(TAG, "向手机发射指令失败", e);
            }
        }).start();
    }
}
