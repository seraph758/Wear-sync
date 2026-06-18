package de.rhaeus.wearsync;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceFragmentCompat;

import com.google.android.gms.wearable.CapabilityClient;
import com.google.android.gms.wearable.Wearable;

public class WearSyncMainFragment extends PreferenceFragmentCompat {
    private Preference connectivityPref;
    private Preference dndPref;
    private Preference accPref;
    private CapabilityClient.OnCapabilityChangedListener capabilityChangedListener;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.root_preferences, rootKey);

        connectivityPref = findPreference("connectivity_state_key");
        dndPref = findPreference("dnd_permission_key");
        accPref = findPreference("acc_permission_key");

        if (dndPref != null) {
            dndPref.setSelectable(false); // 仅做状态展示，不可点击
        }

        // 核心：无障碍跳转
        if (accPref != null) {
            accPref.setOnPreferenceClickListener(preference -> {
                try {
                    startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                } catch (Exception e) {
                    Toast.makeText(getContext(), "无法跳转，请在手表系统设置中手动开启无障碍", Toast.LENGTH_SHORT).show();
                }
                return true;
            });
        }

        // 📷 绑定相机快捷 Preference 条目
        Preference cameraPref = findPreference("camera_control_key");
        if (cameraPref != null) {
            cameraPref.setOnPreferenceClickListener(preference -> {
                Log.d("WearSync_UI", "👉 用户在 Preference 列表点击了【远端相机控制】");
                
                // 🚀 执行你原先给相机按键写的发射逻辑，例如向手机发包
                // PhoneSyncCameraService.invokeCamera(getContext());
                
                Toast.makeText(getContext(), "正在发送相机唤醒指令...", Toast.LENGTH_SHORT).show();
                return true;
            });
        }

        initConnectivityCheck();
    }

    @Override
    public void onResume() {
        super.onResume();
        updatePermissionStatus(); // 🎯 仅刷新核心权限状态
        registerConnectivityListener();
    }

    @Override
    public void onPause() {
        super.onPause();
        unregisterConnectivityListener();
    }

    /**
     * 🎯 仅核查并刷新必要的本地硬权限状态
     */
    private void updatePermissionStatus() {
        Context ctx = getContext();
        if (ctx == null) return;

        // 1. 通知监听权限核查
        String flat = Settings.Secure.getString(ctx.getContentResolver(), "enabled_notification_listeners");
        boolean notificationAllowed = flat != null && flat.contains(ctx.getPackageName());
        if (dndPref != null) {
            dndPref.setSummary(notificationAllowed ? "通知接听权限：已启用" : "通知接听权限：未启用 (请通过ADB授权)");
        }

        // 2. 无障碍自动点击核心核查
        boolean accAllowed = WearSyncAccessService.getSharedInstance() != null;
        if (accPref != null) {
            accPref.setSummary(accAllowed ? "辅助无障碍自动点击：已就绪" : "辅助无障碍自动点击：未开启，点击去授权");
        }
    }

    private void initConnectivityCheck() {
        if (getContext() == null) return;
        Wearable.getCapabilityClient(getContext())
                .getCapability("dnd_sync", CapabilityClient.FILTER_REACHABLE)
                .addOnSuccessListener(capabilityInfo -> updateConnectionUI(!capabilityInfo.getNodes().isEmpty()));
        capabilityChangedListener = capabilityInfo -> updateConnectionUI(!capabilityInfo.getNodes().isEmpty());
    }

    private void registerConnectivityListener() {
        if (getContext() != null && capabilityChangedListener != null) {
            Wearable.getCapabilityClient(getContext()).addListener(capabilityChangedListener, "dnd_sync");
        }
    }

    private void unregisterConnectivityListener() {
        if (getContext() != null && capabilityChangedListener != null) {
            Wearable.getCapabilityClient(getContext()).removeListener(capabilityChangedListener);
        }
    }

    private void updateConnectionUI(boolean isConnected) {
        if (connectivityPref != null) {
            connectivityPref.setSummary(isConnected ? "已成功连线至手机 (Wear Sync 万能互联)" : "未发现配对手机，请检查蓝牙");
        }
    }
}
