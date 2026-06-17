package de.rhaeus.wearsync;

import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;

import androidx.fragment.app.FragmentActivity;

import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;

import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class WearSyncMainActivity extends FragmentActivity {
    private static final String TAG = "WearSync_WearMain";
    
    private TextView tvNotificationStatus;
    private TextView tvAccessibilityStatus;
    private TextView tvConnectionStatus;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 1. 初始化看板组件
        tvNotificationStatus = findViewById(R.id.tv_notification_status);
        tvAccessibilityStatus = findViewById(R.id.tv_accessibility_status);
        tvConnectionStatus = findViewById(R.id.tv_connection_status);

        // 2. 挂载点击跳转系统设置逻辑
        if (tvNotificationStatus != null) {
            tvNotificationStatus.setOnClickListener(v -> {
                try {
                    startActivity(new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS));
                } catch (Exception e) {
                    Log.e(TAG, "无法跳转到勿扰通知权限页", e);
                }
            });
        }

        if (tvAccessibilityStatus != null) {
            tvAccessibilityStatus.setOnClickListener(v -> {
                try {
                    startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                } catch (Exception e) {
                    Log.e(TAG, "无法跳转到无障碍设置页", e);
                }
            });
        }

        // 3. 使用安全的支持库管理器挂载原生的设置项 Fragment，完美解决类型转换报错
        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.content_frame, new WearSyncMainFragment())
                    .commit();
        }

        // 4. 绑定相机唤醒按钮
        Button btnLaunchCamera = findViewById(R.id.camera_button);
        if (btnLaunchCamera != null) {
            btnLaunchCamera.setOnClickListener(v -> {
                Log.d(TAG, "🚀 用户点击唤醒相机：执行双向协议连通...");
                sendActionToPhone("camera", "START_CAMERA_UI");

                Intent intent = new Intent(WearSyncMainActivity.this, WearCameraActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                startActivity(intent);
            });
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        checkAllPermissionsAndLinks();
    }

    private void checkAllPermissionsAndLinks() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (tvNotificationStatus != null) {
            if (nm != null && nm.isNotificationPolicyAccessGranted()) {
                tvNotificationStatus.setText("🟢 勿扰控制权限：已授权");
                tvNotificationStatus.setTextColor(Color.GREEN);
            } else {
                tvNotificationStatus.setText("🔴 勿扰控制权限：未授权 (点击前往)");
                tvNotificationStatus.setTextColor(Color.RED);
            }
        }

        if (tvAccessibilityStatus != null) {
            if (isAccessibilityServiceEnabled()) {
                tvAccessibilityStatus.setText("🟢 辅助无障碍核心：已激活");
                tvAccessibilityStatus.setTextColor(Color.GREEN);
            } else {
                tvAccessibilityStatus.setText("🔴 辅助无障碍核心：未激活 (点击前往)");
                tvAccessibilityStatus.setTextColor(Color.RED);
            }
        }

        if (tvConnectionStatus != null) {
            Wearable.getNodeClient(this).getConnectedNodes()
                .addOnSuccessListener(nodes -> {
                    if (nodes != null && !nodes.isEmpty()) {
                        tvConnectionStatus.setText("🟢 手机互联状态：已成功连线");
                        tvConnectionStatus.setTextColor(Color.GREEN);
                    } else {
                        tvConnectionStatus.setText("🔴 手机互联状态：未连通 (请开启蓝牙)");
                        tvConnectionStatus.setTextColor(Color.YELLOW);
                    }
                })
                .addOnFailureListener(e -> tvConnectionStatus.setText("🔴 手机互联：谷歌通信组件异常"));
        }
    }

    private boolean isAccessibilityServiceEnabled() {
        int accessibilityEnabled = 0;
        final String service = getPackageName() + "/" + WearSyncAccessService.class.getCanonicalName();
        try {
            accessibilityEnabled = Settings.Secure.getInt(getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED);
        } catch (Settings.SettingNotFoundException ignored) {}

        if (accessibilityEnabled == 1) {
            String settingValue = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (settingValue != null) {
                TextUtils.SimpleStringSplitter splitter = new TextUtils.SimpleStringSplitter(':');
                splitter.setString(settingValue);
                while (splitter.hasNext()) {
                    if (splitter.next().equalsIgnoreCase(service)) return true;
                }
            }
        }
        return false;
    }

    private void sendActionToPhone(String type, String action) {
        new Thread(() -> {
            try {
                JSONObject json = new JSONObject();
                json.put("sender", "wear");
                json.put("type", type);
                json.put("action", action);
                json.put("timestamp", System.currentTimeMillis());

                byte[] data = json.toString().getBytes(StandardCharsets.UTF_8);
                List<Node> nodes = Tasks.await(Wearable.getNodeClient(this).getConnectedNodes());
                for (Node node : nodes) {
                    Wearable.getMessageClient(this).sendMessage(node.getId(), "/wear-universal-sync", data);
                }
            } catch (Exception e) {
                Log.e(TAG, "主控发射指令到手机失败", e);
            }
        }).start();
    }
}
