package de.rhaeus.wearsync;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import androidx.fragment.app.FragmentActivity;

/**
 * 手表端主入口 Activity。
 * 完美对齐：100% 保留反向 JSON 唤醒协议与拉起 WearCameraActivity 逻辑。
 * 升级补齐本地广播接收器，当接收到 WearCameraActivity 发出的协调退出暗号时，强制关掉自身，实现无感退干净。
 */
public class WearSyncMainActivity extends FragmentActivity {
    private static final String TAG = "WearSync_WearMain";
    private BroadcastReceiver forceCloseReceiver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 1. 将原本的 MainFragment 塞入 FrameLayout 容器中，继续保持通知、无障碍就绪
        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.settings, new WearSyncMainFragment())
                    .commit();
        }

        // 2. 完美保留你原有的触发反向相机按键事件监听
        Button btnTriggerCamera = findViewById(R.id.btn_trigger_camera);
        if (btnTriggerCamera != null) {
            btnTriggerCamera.setOnClickListener(v -> triggerRemotePhoneCamera());
            Log.d(TAG, "⚡ 手表端反向相机唤醒按钮监听成功绑定");
        }

        // 3. 注册协调退出广播监听器：当接收到关闭通知时，强制关掉手表 MainActivity 自身
        registerForceCloseReceiver();
    }

    private void triggerRemotePhoneCamera() {
        new Thread(() -> {
            try {
                // 完美保留：向手机中央路由器投递穿透前台豁免暗号
                org.json.JSONObject json = new org.json.JSONObject();
                json.put("sender", "wear");
                json.put("type", "camera_action");
                json.put("action", "START_CAMERA_UI");
                json.put("timestamp", System.currentTimeMillis());
                
                byte[] data = json.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                java.util.List<com.google.android.gms.wearable.Node> nodes = 
                        com.google.android.gms.tasks.Tasks.await(com.google.android.gms.wearable.Wearable.getNodeClient(this).getConnectedNodes());
                for (com.google.android.gms.wearable.Node n : nodes) {
                    com.google.android.gms.wearable.Wearable.getMessageClient(this).sendMessage(n.getId(), "/wear-universal-sync", data);
                }

                // 完美保留：本地立即拉起手表的预览 Activity 开始承接画面渲染
                Intent intent = new Intent(this, WearCameraActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
                Log.d(TAG, "📤 手表成功触发反向唤醒逻辑并接力拉起本地渲染 UI");
            } catch (Exception e) {
                Log.e(TAG, "手表端主动唤醒相机动作失败", e);
            }
        }).start();
    }

    private void registerForceCloseReceiver() {
        forceCloseReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if ("DE_RHAEUS_WEARSYNC_FORCE_CLOSE_MAIN".equals(intent.getAction())) {
                    Log.d(TAG, "🛑 收到协同正常退出广播，正在强行关闭手表 MainActivity 自身...");
                    finish();
                }
            }
        };
        IntentFilter filter = new IntentFilter("DE_RHAEUS_WEARSYNC_FORCE_CLOSE_MAIN");
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(forceCloseReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(forceCloseReceiver, filter);
        }
    }

    @Override
    protected void onDestroy() {
        if (forceCloseReceiver != null) {
            unregisterReceiver(forceCloseReceiver);
        }
        super.onDestroy();
    }
}