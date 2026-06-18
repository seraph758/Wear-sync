package de.rhaeus.wearsync;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import androidx.preference.Preference;
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

        // 🎯 遵照核心規則：手錶無法自開通知監聽，此條目僅做 ADB 狀態展示，徹底鎖死不可點擊
        if (dndPref != null) {
            dndPref.setSelectable(false); 
        }

        // 🟢 遵照核心規則：只有無障礙可以引導用戶去手錶系統頁面手動授權
        if (accPref != null) {
            accPref.setOnPreferenceClickListener(preference -> {
                try {
                    startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                } catch (Exception e) {
                    Toast.makeText(getContext(), "無法跳轉，請在手錶系統設置中手動開啟無障礙", Toast.LENGTH_SHORT).show();
                }
                return true;
            });
        }

        // 📸 綁定相機快捷 Preference 條目
        Preference cameraPref = findPreference("camera_control_key");
        if (cameraPref != null) {
            cameraPref.setOnPreferenceClickListener(preference -> {
                Log.d("WearSync_UI", "👉 用戶在 Preference 列表點擊了【遠端相機控制】");
                Toast.makeText(getContext(), "正在發送相機喚醒指令...", Toast.LENGTH_SHORT).show();
                return true;
            });
        }

        initConnectivityCheck();
    }

    @Override
    public void onResume() {
        super.onResume();
        updatePermissionStatus(); // 🎯 精準動態核查本地底層權限
        registerConnectivityListener();
    }

    @Override
    public void onPause() {
        super.onPause();
        unregisterConnectivityListener();
    }

    /**
     * 🎯 精準權限核查矩陣（徹底剔除 NotificationManager 誤區）
     */
    private void updatePermissionStatus() {
        Context ctx = getContext();
        if (ctx == null) return;

        // 1. 🔍 通過安全設置數據庫，精準核查手錶端是否已獲取 [通知監聽權限 (ADB授權)]
        String enabledListeners = Settings.Secure.getString(ctx.getContentResolver(), "enabled_notification_listeners");
        boolean notificationAllowed = enabledListeners != null && enabledListeners.contains(ctx.getPackageName());
        
        if (dndPref != null) {
            dndPref.setSummary(notificationAllowed 
                    ? "🟢 通知監聽權限：已獲取 (ADB授權成功)" 
                    : "🔴 通知監聽權限：未啟用 (請通過 ADB 命令授權)");
        }

        // 2. 🔍 核查 [輔助無障礙核心開關] 是否激活
        boolean accAllowed = WearSyncAccessService.getSharedInstance() != null;
        if (accPref != null) {
            accPref.setSummary(accAllowed 
                    ? "🟢 輔助無障礙核心：已激活" 
                    : "🔴 輔助無障礙核心：未激活 (點擊前往授權)");
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
            connectivityPref.setSummary(isConnected ? "🟢 離線狀態：已成功連線至手機" : "🔴 離線狀態：未連通 (請檢查手錶藍牙)");
        }
    }
}
