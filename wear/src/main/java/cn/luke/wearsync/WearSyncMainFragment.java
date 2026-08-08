package cn.luke.wearsync;

import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import com.google.android.gms.wearable.CapabilityClient;
import com.google.android.gms.wearable.Wearable;

public class WearSyncMainFragment extends PreferenceFragmentCompat {

    private static final String TAG = "WearSync_MainFragment";
    private static final String CAPABILITY_NAME = "wear_sync";

    private Preference connectivityPref;
    private Preference dndPref;
    private Preference accPref;
    private Preference cameraPref;
    
    private CapabilityClient.OnCapabilityChangedListener capabilityChangedListener;
    private boolean isNavigatingToCamera = false;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        WearLog.d(TAG, "① [生命周期] onCreatePreferences 启动 ─── 解析 XML ───");
        setPreferencesFromResource(R.xml.root_preferences, rootKey);

        connectivityPref = findPreference("connectivity_status");
        dndPref = findPreference("dnd_permission");
        accPref = findPreference("accessibility_service");
        cameraPref = findPreference("remote_camera");
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        WearLog.d(TAG, "② [生命周期] onViewCreated 触发 ─── UI 视图构建完成，绑定点击事件 ───");

        // 🎯 在 View 构建完成后绑定相机点击事件，解决点击无效问题
        if (cameraPref != null) {
            cameraPref.setOnPreferenceClickListener(preference -> {
                WearLog.w(TAG, "📸 [远端相机] 用户点击【远端相机控制】");

                Context appContext = requireContext().getApplicationContext();
                isNavigatingToCamera = true;

                try {
                    // 1. 启动本地相机 Activity
                    Intent intent = new Intent(appContext, WearCameraActivity.class);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(intent);
                    WearLog.d(TAG, "✅ [远端相机] 本地 WearCameraActivity 启动完毕");

                    // 2. 发送信令唤醒手机
                    WearSyncCommManager.getInstance(appContext).openPhoneCamera();
                    WearLog.d(TAG, "✅ [远端相机] 唤醒手机信令下发完成");

                } catch (Exception e) {
                    WearLog.e(TAG, "❌ [远端相机] 启动异常: " + e.getMessage(), e);
                    isNavigatingToCamera = false;
                    Toast.makeText(appContext, "启动失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                }
                return true;
            });
        }

        if (dndPref != null) {
            dndPref.setOnPreferenceClickListener(p -> {
                if (!hasDndPermission()) {
                    try {
                        startActivity(new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS));
                    } catch (Exception e) {
                        WearLog.e(TAG, "跳转勿扰失败: " + e.getMessage());
                    }
                } else {
                    Toast.makeText(getContext(), "勿扰权限已授予", Toast.LENGTH_SHORT).show();
                }
                return true;
            });
        }

        if (accPref != null) {
            accPref.setOnPreferenceClickListener(p -> {
                if (!isAccessibilityServiceEnabled()) {
                    try {
                        startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                    } catch (Exception e) {
                        WearLog.e(TAG, "跳转无障碍失败: " + e.getMessage());
                    }
                } else {
                    Toast.makeText(getContext(), "无障碍服务已开启", Toast.LENGTH_SHORT).show();
                }
                return true;
            });
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        WearLog.d(TAG, "🔄 [生命周期] onResume 触发");

        if (isNavigatingToCamera) {
            isNavigatingToCamera = false;
        }

        updateDndUI();
        updateAccessibilityUI();
        checkAndCheckCapability();
        registerConnectivityListener();
    }

    @Override
    public void onPause() {
        super.onPause();
        WearLog.w(TAG, "⏸️ [生命周期] onPause 触发");

        if (isNavigatingToCamera) {
            WearLog.d(TAG, "🛡️ [防抖保护] 正准备进入 WearCameraActivity，保留监听器");
            return;
        }

        unregisterConnectivityListener();
    }

    private boolean hasDndPermission() {
        Context ctx = getContext();
        if (ctx == null) return false;
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        return nm != null && nm.isNotificationPolicyAccessGranted();
    }

    private boolean isAccessibilityServiceEnabled() {
        Context ctx = getContext();
        if (ctx == null) return false;
        String prefString = Settings.Secure.getString(
                ctx.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        );
        return prefString != null && prefString.contains(ctx.getPackageName());
    }

    private void updateDndUI() {
        if (dndPref != null) {
            dndPref.setSummary(hasDndPermission() ? "已授权 (点击可重新校验)" : "未授权，点击前往系统设置开启");
        }
    }

    private void updateAccessibilityUI() {
        if (accPref != null) {
            accPref.setSummary(isAccessibilityServiceEnabled() ? "已开启 (点击可重新校验)" : "未开启，点击前往系统设置开启");
        }
    }

    private void checkAndCheckCapability() {
        Context ctx = getContext();
        if (ctx == null) return;

        Wearable.getCapabilityClient(ctx.getApplicationContext())
                .getCapability(CAPABILITY_NAME, CapabilityClient.FILTER_REACHABLE)
                .addOnSuccessListener(capabilityInfo -> {
                    boolean connected = !capabilityInfo.getNodes().isEmpty();
                    if (isAdded() && getActivity() != null) {
                        getActivity().runOnUiThread(() -> updateConnectionUI(connected));
                    }
                })
                .addOnFailureListener(e -> {
                    if (isAdded() && getActivity() != null) {
                        getActivity().runOnUiThread(() -> updateConnectionUI(false));
                    }
                });
    }

    private void registerConnectivityListener() {
        Context ctx = getContext();
        if (ctx != null) {
            Context appContext = ctx.getApplicationContext();
            if (capabilityChangedListener == null) {
                capabilityChangedListener = capabilityInfo -> {
                    boolean connected = !capabilityInfo.getNodes().isEmpty();
                    if (isAdded() && getActivity() != null) {
                        getActivity().runOnUiThread(() -> updateConnectionUI(connected));
                    }
                };
            }
            try {
                Wearable.getCapabilityClient(appContext).addListener(capabilityChangedListener, CAPABILITY_NAME);
            } catch (Exception e) {
                WearLog.e(TAG, "❌ [监听器] 挂载异常: " + e.getMessage());
            }
        }
    }

    private void unregisterConnectivityListener() {
        Context ctx = getContext();
        if (ctx != null && capabilityChangedListener != null) {
            try {
                Wearable.getCapabilityClient(ctx.getApplicationContext()).removeListener(capabilityChangedListener);
            } catch (Exception e) {
                WearLog.w(TAG, "⚠️ [监听器] 注销异常: " + e.getMessage());
            }
        }
    }

    private void updateConnectionUI(boolean connected) {
        if (connectivityPref != null) {
            connectivityPref.setSummary(connected ? "已连接到手机" : "未连接到手机");
        }
    }
}
