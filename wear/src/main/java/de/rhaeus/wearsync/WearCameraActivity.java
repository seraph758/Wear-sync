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

/**
 * 手表端预览画面流渲染主 Activity。
 * 完美修正：让相机 Activity 挂载原版的 activity_main 布局资源（原项目没有 activity_camera_preview），
 * 并且直接绑定原有的 bt_dismiss 或你原本界面上的关闭按钮。
 */
public class WearCameraActivity extends Activity {
    private static final String TAG = "WearSync_WearCamera";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // 🎯 核心修复：直接绑定手錶端原版的 activity_main 布局
        setContentView(R.layout.activity_main); 

        // 🎯 核心修复：直接绑定你原本界面上用来关闭相机的按钮（对齐你原版存在的 bt_dismiss 按钮）
        Button btnCloseCamera = findViewById(R.id.bt_dismiss);
        if (btnCloseCamera != null) {
            btnCloseCamera.setOnClickListener(v -> {
                Log.d(TAG, "🛎️ 用户在手表预览界面点击正常退出...");
                executeElegantExitFlow();
            });
        }
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
