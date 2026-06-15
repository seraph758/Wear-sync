package de.rhaeus.wearsync;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;
import android.net.Uri;

import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceFragmentCompat;

import com.google.android.gms.wearable.CapabilityClient;
import com.google.android.gms.wearable.Wearable;

public class WearSyncMainFragment extends PreferenceFragmentCompat {
    private Preference connectivityPref;
    private Preference dndPref;
    private Preference accPref;
    private Preference wearWriteSettingPref; // 🎯 完美保留：手錶本地 Setting Write 權限項物件
    private CapabilityClient.OnCapabilityChangedListener capabilityChangedListener;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.root_preferences, rootKey);

        connectivityPref = findPreference("connectivity_state_key");
        dndPref = findPreference("dnd_permission_key");
        accPref = findPreference("acc_permission_key");
        wearWriteSettingPref = findPreference("wear_write_settings_key"); // 🎯 完美保留：繫結 XML 節點

        // 🎯 完美保留：完美抹去被手機端托管的所有勿擾、小睡重複開關，保持手錶介面極度純淨
        try {
            Preference dndSyncSwitch = findPreference("dnd_sync_key");
            Preference bedtimeSwitch = findPreference("bedtime_key");
            Preference vibrateSwitch = findPreference("vibrate_key");

            if (dndSyncSwitch != null && dndSyncSwitch.getParent() != null) {
                dndSyncSwitch.getParent().removePreference(dndSyncSwitch);
            }
            if (bedtimeSwitch != null && bedtimeSwitch.getParent() != null) {
                bedtimeSwitch.getParent().removePreference(bedtimeSwitch);
            }
            if (vibrateSwitch != null && vibrateSwitch.getParent() != null) {
                vibrateSwitch.getParent().removePreference(vibrateSwitch);
            }
        } catch (Exception e) {
            Log.e("WearSync_WearMain", "清理隱藏的遠端開關異常", e);
        }

        initPreferences();
    }

    private void initPreferences() {
        // 輔助無障礙核心點擊跳轉監聽
        if (accPref != null) {
            accPref.setOnPreferenceClickListener(preference -> {
                try {
                    Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(intent);
                } catch (Exception e) {
                    Toast.makeText(getContext(), "無法打開無障礙設置頁面", Toast.LENGTH_SHORT).show();
                }
                return true;
            });
        }

        // 🎯 完美保留：手錶本地對 Setting Write 點擊直接跳轉系統修改權限頁
        if (wearWriteSettingPref != null) {
            wearWriteSettingPref.setOnPreferenceClickListener(preference -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    try {
                        Intent intent = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS);
                        intent.setData(Uri.parse("package:" + requireContext().getPackageName()));
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(intent);
                    } catch (Exception e) {
                        try {
                            Intent intent = new Intent(Settings.ACTION_SETTINGS);
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            startActivity(intent);
                        } catch (Exception ignored) {}
                    }
                } else {
                    Toast.makeText(getContext(), "當前系統版本無需手動授予此權限", Toast.LENGTH_SHORT).show();
                }
                return true;
            });
        }

        initConnectivityCheck();
    }

    @Override
    public void onResume() {
        super.onResume();
        updatePermissionSummaries();
        registerConnectivityListener();
    }

    @Override
    public void onPause() {
        super.onPause();
        unregisterConnectivityListener();
    }

    private void updatePermissionSummaries() {
        Context context = getContext();
        if (context == null) return;

        // 🛠️ 已修正：將原本錯誤的 Kotlin 語法簡寫，改回正確的標準 Java 函數呼叫
        String flat = Settings.Secure.getString(context.getContentResolver(), "enabled_notification_listeners");
        boolean notificationAllowed = flat != null && flat.contains(context.getPackageName());

        if (dndPref != null) {
            dndPref.setSummary(notificationAllowed ? "通知接聽權限：已啟用" : "通知接聽權限：未啟用 (請透過ADB授權)");
        }

        boolean accAllowed = WearSyncAccessService.getSharedInstance() != null;
        if (accPref != null) {
            accPref.setSummary(accAllowed ? "輔助無障礙自動點擊：已就緒" : "輔助無障礙自動點擊：未開啟，點擊去授權");
        }

        // 🎯 完美保留：動態刷新手錶本地的 Setting Write 狀態摘要
        if (wearWriteSettingPref != null) {
            boolean canWrite = true;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                canWrite = Settings.System.canWrite(context);
            }
            wearWriteSettingPref.setSummary(canWrite ? "修改系統設置權限：已獲得" : "修改系統設置權限：未允許，點擊去啟用");
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
            connectivityPref.setSummary(isConnected ? "已成功連線至手機 (Wear Sync 萬能互聯)" : "等待手機端同步連線...");
        }
    }
}

