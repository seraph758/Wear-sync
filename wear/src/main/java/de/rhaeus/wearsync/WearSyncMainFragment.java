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

        // 🎯 完美抹去被手機端托管的所有勿擾、小睡重複開關，保持手錶介面極度純淨
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

            // 強行隱藏空的 “遠端同步控制” 分組標題
            for (int i = 0; i < getPreferenceScreen().getPreferenceCount(); i++) {
                Preference pref = getPreferenceScreen().getPreference(i);
                if (pref instanceof PreferenceCategory) {
                    CharSequence title = pref.getTitle();
                    if (title != null && title.toString().contains("遠端同步控制")) {
                        pref.setVisible(false);
                    }
                }
            }
        } catch (Exception e) {
            Log.e("WearSync_WearUI", "清除託管組件時發生錯誤: " + e.getMessage());
        }

        if (dndPref != null) {
            dndPref.setSelectable(false);
        }

        if (accPref != null) {
            accPref.setOnPreferenceClickListener(preference -> {
                try {
                    startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                } catch (Exception e) {
                    Toast.makeText(getContext(), "無法跳轉，請在手錶系統設定中手動開啟無障礙", Toast.LENGTH_SHORT).show();
                }
                return true;
            });
        }

        initConnectivityCheck();
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

        String flat = Settings.Secure.getString(ctx.getContentResolver(), "enabled_notification_listeners");
        boolean notificationAllowed = flat != null && flat.contains(ctx.getPackageName());
        if (dndPref != null) {
            dndPref.setSummary(notificationAllowed ? "通知接聽權限：已啟用" : "通知接聽權限：未啟用 (請透過ADB授權)");
        }

        boolean accAllowed = WearSyncAccessService.getSharedInstance() != null;
        if (accPref != null) {
            accPref.setSummary(accAllowed ? "輔助無障礙自動點擊：已就緒" : "輔助無障礙自動點擊：未開啟，點擊去授權");
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
            connectivityPref.setSummary(isConnected ? "已成功連線至手機 (Wear Sync 萬能互聯)" : "未發現配對手機，請檢查藍牙");
        }
    }
}
