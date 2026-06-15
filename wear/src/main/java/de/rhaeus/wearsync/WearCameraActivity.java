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

public class WearCameraActivity extends Activity {
    private static final String TAG = "WearSync_WearCamera";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // 绑定相机主布局
        setContentView(R.layout.activity_main); 

        // 🎯 核心修正：彻底移除 btn_capture，只保留完美匹配你 XML 的驼峰命名 ID
        Button btnCapture = findViewById(R.id.btnCapture); 

        if (btnCapture != null) {
            btnCapture.setOnClickListener(v -> {
                Log.d(TAG, "📸 成功点击手表端 btnCapture 按钮，触发快门...");
                sendCameraCaptureSignal();
            });
        }

        // 盲操退出：点击预览画面任意地方优雅下线
        ImageView frameView = findViewById(R.id.frameView);
        if (frameView != null) {
            frameView.setOnClickListener(v -> {
                Log.d(TAG, "🛎️ 用户点击预览画面，执行协同退出...");
                executeElegantExitFlow();
            });
        }
    }

    private void sendCameraCaptureSignal() {
        new Thread(() -> {
            try {
                JSONObject json = new JSONObject();
                json.put("sender", "wear");
                json.put("type", "camera_action");
                json.put("action", "TAKE_PICTURE");
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
                
                Intent closeMainIntent = new Intent("DE_RHAEUS_WEARSYNC_FORCE_CLOSE_MAIN");
                closeMainIntent.setPackage(getPackageName());
                sendBroadcast(closeMainIntent);

                runOnUiThread(this::finish);
            } catch (Exception e) {
                Log.e(TAG, "退出通信流程失败", e);
                runOnUiThread(this::finish);
            }
        }).start();
    }

    @Override
    protected void onDestroy() {
        try {
            setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_NOSENSOR);
        } catch (Exception e) {
            Log.e(TAG, "清理残留异常", e);
        }
        super.onDestroy();
        android.os.Process.killProcess(android.os.Process.myPid());
    }
}
