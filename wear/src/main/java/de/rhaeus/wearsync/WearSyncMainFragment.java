package de.rhaeus.wearsync;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
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
    
    // 🎯 新增：手錶本地 Setting Write 權限項顯示
    private Preference wearWriteSettingPref;
    
    private CapabilityClient.OnCapabilityChangedListener capabilityChangedListener;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.root_preferences, rootKey);

        connectivityPref = findPreference("connectivity_state_key");
        dndPref = findPreference("dnd_permission_key");
        accPref = findPreference("acc_permission_key");
        
        // 🎯 繫結本地 XML 中的鍵值（如果 R.xml.root_preferences 裡沒有，我們會動態創建或借用 summary）
        wearWriteSettingPref = findPreference("wear_write_settings_key");

        // 🎯 完美抹去被手機端託管的所有勿擾、小睡重複開關，保持手錶介面極度純淨 (100% 保留)
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
            Log.e("WearSync_MainFragment", "動態清理重複開關異常", e);
        }

        initPreferences();
        initConnectivityCheck();
    }

    private void initPreferences() {
        // 1. 通知監聽權限引導 (保留)
        if (dndPref != null) {
            dndPref.setOnPreferenceClickListener(preference -> {
                Intent intent = new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS");
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    startActivity(intent);
                } catch (Exception e) {
                    Toast.makeText(getContext(), "無法開啟通知授權頁，請手動設定", Toast.LENGTH_SHORT).show();
                }
                return true;
            });
        }

        // 2. 無障礙服務點擊引導 (保留)
        if (accPref != null) {
            accPref.setOnPreferenceClickListener(preference -> {
                Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    startActivity(intent);
                } catch (Exception e) {
                    Toast.makeText(getContext(), "無法開啟無障礙頁面", Toast.LENGTH_SHORT).show();
                }
                return true;
            });
        }

        // 3. 🎯 [全新追加]：手錶本地對 Setting Write 點擊直接跳轉系統修改權限頁
        if (wearWriteSettingPref != null) {
            wearWriteSettingPref.setOnPreferenceClickListener(preference -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    try {
                        Intent intent = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS);
                        intent.setData(Uri.parse("package:" + requireContext().getPackageName()));
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(intent);
                    } catch (Exception e) {
                        Intent intent = new Intent(Settings.ACTION_SETTINGS);
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(intent);
                    }
                } else {
                    Toast.makeText(getContext(), "當前系統版本無需手動授予此權限", Toast.LENGTH_SHORT).show();
                }
                return true;
            });
        }
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
        if (getContext() == null) return;

        // 1. 刷新通知監聽狀態
        String flat = Settings.Secure.getString(getContext().contentResolver(), "enabled_notification_listeners");
        boolean notificationAllowed = flat != null && flat.contains(getContext().packageName);
        if (dndPref != null) {
            dndPref.setSummary(notificationAllowed ? "通知接聽權限：已啟用" : "通知接聽權限：未啟用 (請透過ADB授權)");
        }

        // 2. 刷新無障礙狀態
        boolean accAllowed = WearSyncAccessService.getSharedInstance() != null;
        if (accPref != null) {
            accPref.setSummary(accAllowed ? "輔助無障礙自動點擊：已就緒" : "輔助無障礙自動點擊：未開啟，點擊去授權");
        }

        // 3. 🎯 [全新追加]：動態刷新手錶本地的 Setting Write 狀態摘要
        if (wearWriteSettingPref != null) {
            boolean canWrite = true;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                canWrite = Settings.System.canWrite(getContext());
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
            connectivityPref.setSummary(isConnected ? "已成功連線至手機 (Wear Sync 萬能互聯)" : "未檢測到可連線的手機節點，請檢查藍牙");
        }
    }
}
