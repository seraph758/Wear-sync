package de.rhaeus.wearsync;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import androidx.fragment.app.FragmentActivity; // 🎯 必須繼承 FragmentActivity，否則原本的 MainFragment 無法加載
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;
import java.util.List;

public class WearSyncMainActivity extends FragmentActivity {
    private static final String TAG = "WearSync_WearMain";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 1️⃣ 核心恢復：將原本的 MainFragment 塞回 id 為 settings 的 FrameLayout 容器中
        // 這樣你的通知權限、輔助功能跳轉、震動開關、鬧鐘廣播全都會重新生效！
        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.settings, new WearSyncMainFragment())
                    .commit();
        }

        // 2️⃣ 綁定新增加的按鈕（從 XML 讀取）
        Button btnTriggerCamera = findViewById(R.id.btn_trigger_camera);
        if (btnTriggerCamera != null) {
            btnTriggerCamera.setOnClickListener(v -> triggerRemotePhoneCamera());
            Log.d(TAG, "⚡ 手錶端相機喚醒按鈕監聽成功綁定");
        }
    }

    private void triggerRemotePhoneCamera() {
        new Thread(() -> {
            try {
                // 1. 發送給手機，讓手機拉起前台相機採集服務
                JSONObject json = new JSONObject();
                json.put("sender", "wear");
                json.put("type", "camera_action");
                json.put("action", "START_CAMERA_UI");
                json.put("timestamp", System.currentTimeMillis());
                
                byte[] data = json.toString().getBytes(StandardCharsets.UTF_8);
                List<Node> nodes = Tasks.await(Wearable.getNodeClient(this).getConnectedNodes());
                for (Node n : nodes) {
                    Wearable.getMessageClient(this).sendMessage(n.getId(), "/wear-universal-sync", data);
                }

                // 2. 本地立即打開手錶端自己的 WearCameraActivity（渲染手機傳過來的畫面流）
                Intent intent = new Intent(this, WearCameraActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
                Log.d(TAG, "📤 手錶端成功觸發反向喚醒相機邏輯並拉起本地UI");
            } catch (Exception e) {
                Log.e(TAG, "手錶端主動喚醒相機失敗", e);
            }
        }).start();
    }
}
