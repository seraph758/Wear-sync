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

public class WearSyncMainActivity extends Activity {
    private static final String TAG = "WearSync_WearMain";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 确保绑定的是包含“唤醒手机相机”主按钮的手表主布局
        setContentView(R.layout.activity_main); 

        // 🎯 核心修复二：直接绑定按钮，去掉无意义的自循环 if 判断
        Button btnLaunchCamera = findViewById(R.id.btn_trigger_camera); 

        if (btnLaunchCamera != null) {
            btnLaunchCamera.setOnClickListener(v -> {
                Log.d(TAG, "🚀 用户点击唤醒相机：执行双向联动...");
                
                // 1. 发送信令通知手机在后台开启相机服务
                // 注意：请确保你的基类里有 sendActionToPhone 方法，或者改用与 WearSyncNotificationService 类似的通用投递
                // 如果编译提示找不到 sendActionToPhone，可以参考你 Listener 里的通用路径投递
                sendActionToPhone("camera_action", "START_CAMERA_UI");

                // 2. 强行切进手表预览界面！
                // 🎯 核心修复三：上下文必须是 WearSyncMainActivity.this
                Intent intent = new Intent(WearSyncMainActivity.this, WearCameraActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                startActivity(intent);
            });
        }
    }

    private void sendActionToPhone(String type, String action) {
            new Thread(() -> {
                try {
                    JSONObject json = new JSONObject();
                    json.put("sender", "wear");
                    json.put("type", type);
                    json.put("action", action);
                    json.put("timestamp", System.currentTimeMillis()); // 🌟 保留时间戳，便于时序排查
        
                    byte[] data = json.toString().getBytes(StandardCharsets.UTF_8);
                    List<Node> nodes = Tasks.await(Wearable.getNodeClient(this).getConnectedNodes());
                    for (Node node : nodes) {
                        Wearable.getMessageClient(this).sendMessage(node.getId(), "/wear-universal-sync", data);
                    }
                    Log.d(TAG, "📤 手表指令成功发射: " + action);
                } catch (Exception e) {
                    Log.e(TAG, "向手机发射指令失败", e);
                }
            }).start();
        }
}
