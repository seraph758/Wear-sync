package cn.luke.wearsync;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import com.google.android.gms.wearable.CapabilityClient;
import com.google.android.gms.wearable.Wearable;

/**
 * 🎬 WearOS 手表端主控制与权限状态 Fragment (ADB授权引导版)
 * <p>
 * 权限模型说明：
 * - 无障碍服务：可通过系统设置 UI 授权 → 点击跳转 ACTION_ACCESSIBILITY_SETTINGS
 * - 通知使用权：WearOS 不支持 UI 授权 → 点击弹窗展示 ADB 命令
 */
public class WearSyncMainFragment extends PreferenceFragmentCompat {

    private static final String TAG = "WearSync_MainFragment";
    private static final String CAPABILITY_NAME = "wear_sync";

    private Preference connectivityPref;
    private Preference notificationPref; 
    private Preference accPref;
    private Preference cameraPref;

    private CapabilityClient.OnCapabilityChangedListener capabilityChangedListener;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        WearLog.d(TAG, "① [生命周期] onCreatePreferences ─── 构建 Preference 树阵 ───");
        setPreferencesFromResource(R.xml.root_preferences, rootKey);

        connectivityPref = findPreference("connectivity_state_key");
        notificationPref = findPreference("dnd_permission_key"); 
        accPref = findPreference("acc_permission_key");
        cameraPref = findPreference("camera_control_key");

        updatePermissionStatus();
        setupConnectionCheck();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        WearLog.d(TAG, "② [生命周期] onViewCreated ─── 注入交互逻辑 ───");

        // 🛡️ 1. 无障碍权限：跳转系统设置（UI 可授权）
        if (accPref != null) {
            accPref.setOnPreferenceClickListener(preference -> {
                WearLog.d(TAG, "🔘 [交互] 点击无障碍，跳转系统设置");
                try {
                    Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(intent);
                } catch (Exception e) {
                    WearLog.e(TAG, "❌ 无障碍设置跳转失败", e);
                }
                return true;
            });
        }

        // 🔔 2. 通知使用权：弹窗展示 ADB 授权指令
        if (notificationPref != null) {
            notificationPref.setOnPreferenceClickListener(preference -> {
                WearLog.d(TAG, "🔔 [交互] 点击通知权限，展示 ADB 授权指引");
                String packageName = requireContext().getPackageName();
                String componentName = packageName + "/cn.luke.wearsync.WearSyncNotificationService";
                String adbCommand = "adb shell cmd notification allow_listener " + componentName;
        
                new AlertDialog.Builder(requireContext())
                        .setTitle("🔔 通知权限需手动授予")
                        .setMessage("WearOS 不支持通过界面授权此权限。\n\n"
                                + adbCommand + "\n\n"
                                + "授权后重启本应用即可生效。")
                        .setPositiveButton("知道了", null)
                        .show();
                return true;
            });
        }

        // 📸 3. 远端相机控制
        if (cameraPref != null) {
            cameraPref.setOnPreferenceClickListener(preference -> {
                WearLog.w(TAG, "📸 [交互] 用户点击【远端相机控制】");
                Context ctx = requireContext();
                try {
                    Intent localIntent = new Intent(ctx, WearCameraActivity.class);
                    localIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(localIntent);
                    WearSyncCommManager.getInstance(ctx.getApplicationContext()).openPhoneCamera();
                } catch (Exception e) {
                    WearLog.e(TAG, "❌ [远端相机] 启动异常: " + e.getMessage(), e);
                }
                return true;
            });
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        updatePermissionStatus();
        registerConnectivityListener();
    }

    @Override
    public void onPause() {
        super.onPause();
        unregisterConnectivityListener();
    }

    private void updatePermissionStatus() {
        Context ctx = getContext();
        if (ctx == null) return;

        if (notificationPref != null) {
            boolean hasAccess = false;
            try {
                String enabledListeners = Settings.Secure.getString(ctx.getContentResolver(), "enabled_notification_listeners");
                if (enabledListeners != null) {
                    hasAccess = enabledListeners.contains(ctx.getPackageName());
                }
            } catch (Exception e) {
                WearLog.e(TAG, "检查通知权限失败", e);
            }
            notificationPref.setSummary(hasAccess ? "已授权" : "未授权 · 点击获取ADB命令");
        }

        if (accPref != null) {
            boolean isServiceAlive = WearSyncAccessService.getSharedInstance() != null;
            boolean isSettingEnabled = false;
            try {
                String enabledServices = Settings.Secure.getString(ctx.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
                if (enabledServices != null) {
                    isSettingEnabled = enabledServices.contains(ctx.getPackageName());
                }
            } catch (Exception ignored) {}
            accPref.setSummary((isServiceAlive && isSettingEnabled) ? "已激活" : "未激活 · 点击前往开启");
        }
    }

    private void setupConnectionCheck() {
        Context ctx = getContext();
        if (ctx == null) return;
        Wearable.getCapabilityClient(ctx).getCapability(CAPABILITY_NAME, CapabilityClient.FILTER_REACHABLE)
                .addOnSuccessListener(capabilityInfo -> updateConnectionUI(capabilityInfo.getNodes().size() > 0));
        capabilityChangedListener = capabilityInfo -> updateConnectionUI(capabilityInfo.getNodes().size() > 0);
    }

    private void registerConnectivityListener() {
        Context ctx = getContext();
        if (ctx != null && capabilityChangedListener != null) {
            Wearable.getCapabilityClient(ctx).addListener(capabilityChangedListener, CAPABILITY_NAME);
        }
    }

    private void unregisterConnectivityListener() {
        Context ctx = getContext();
        if (ctx != null && capabilityChangedListener != null) {
            Wearable.getCapabilityClient(ctx).removeListener(capabilityChangedListener);
        }
    }

    private void updateConnectionUI(boolean connected) {
        if (connectivityPref != null) {
            connectivityPref.setSummary(connected ? "已连接" : "未连接");
        }
    }
}
