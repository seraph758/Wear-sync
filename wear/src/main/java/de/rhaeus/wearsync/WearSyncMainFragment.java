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
import androidx.preference.SwitchPreferenceCompat;

import com.google.android.gms.wearable.CapabilityClient;
import com.google.android.gms.wearable.Wearable;

public class WearSyncMainFragment extends PreferenceFragmentCompat {
    private Preference connectivityPref;
    private Preference dndPref;
    private Preference accPref;
    
    // 🎯 新增省電模式開關
    private SwitchPreferenceCompat batterySaverPref; 
    private CapabilityClient.OnCapabilityChangedListener capabilityChangedListener;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.root_preferences, rootKey);

        connectivityPref = findPreference("connectivity_state_key");
        dndPref = findPreference("dnd_permission_key");
        accPref = findPreference("acc_permission_key");
        
        // 🎯 綁定省電模式開關
        batterySaverPref = findPreference("battery_saver_sync_key"); 

        // 完美抹去被手機端托管的所有勿擾、小睡重複開關，保持手錶介面極度純淨
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

        // 🎯 監聽用戶手動點擊省電模式開關的動作
        if (batterySaverPref != null) {
            batterySaverPref.setOnPreferenceChangeListener((preference, newValue) -> {
                boolean isChecked = (Boolean) newValue;
                togglePowerSaveMode(getContext(), isChecked);
                return true; // 允許 UI 狀態改變
            });
        }

        initConnectivityCheck();
    }

    @Override
    public void onResume() {
        super.onResume();
        updatePermissionAndSettingsStatus(); // 🎯 整合刷新狀態
        registerConnectivityListener();
    }

    @Override
    public void onPause() {
        super.onPause();
        unregisterConnectivityListener();
    }

    /**
     * 🎯 整合權限檢測與系統省電模式回顯
     */
    private void updatePermissionAndSettingsStatus() {
        Context ctx = getContext();
        if (ctx == null) return;

        // 1. 通知權限狀態刷新
        String flat = Settings.Secure.getString(ctx.getContentResolver(), "enabled_notification_listeners");
        boolean notificationAllowed = flat != null && flat.contains(ctx.getPackageName());
        if (dndPref != null) {
            dndPref.setSummary(notificationAllowed ? "通知接聽權限：已啟用" : "通知接聽權限：未啟用 (請透過ADB授權)");
        }

        // 2. 無障礙權限狀態刷新
        boolean accAllowed = WearSyncAccessService.getSharedInstance() != null;
        if (accPref != null) {
            accPref.setSummary(accAllowed ? "輔助無障礙自動點擊：已就緒" : "輔助無障礙自動點擊：未開啟，點擊去授權");
        }

        // 3. 🎯 省電模式系統狀態回顯：每次回前台時，去讀取底層真正的 low_power 值
        if (batterySaverPref != null) {
            try {
                int lowPowerMode = Settings.Global.getInt(ctx.getContentResolver(), "low_power", 0);
                batterySaverPref.setChecked(lowPowerMode == 1);
                Log.d("WearSync_Power", "🔋 同步回顯系統最新省電模式狀態: " + (lowPowerMode == 1));
            } catch (Exception e) {
                // 如果 Global 讀取失敗，降級嘗試讀取 Secure 域
                try {
                    int lowPowerModeSecure = Settings.Secure.getInt(ctx.getContentResolver(), "low_power", 0);
                    batterySaverPref.setChecked(lowPowerModeSecure == 1);
                } catch (Exception ignored) {}
            }
        }
    }

    /**
     * ⚡ 物理修改省電模式核心執行函數
     */
    private void togglePowerSaveMode(Context context, boolean enable) {
        if (context == null) return;
        
        // 1代表開啟省電，0代表關閉省電
        String value = enable ? "1" : "0";
        
        new Thread(() -> {
            try {
                // 🎯 核心利用 ADB 授予的 WRITE_SECURE_SETTINGS 權限寫入底層
                Settings.Global.putString(
                        context.getContentResolver(), 
                        "low_power", 
                        value
                );
                Log.d("WearSync_Power", "🟢 [安全設置寫入成功] Global 域低功耗模式變更為: " + value);
                
                // 雙域同步映射，確保所有廠商的 Wear OS 都能捕捉到事件
                Settings.Secure.putString(
                        context.getContentResolver(), 
                        "low_power", 
                        value
                );
            } catch (SecurityException e) {
                Log.e("WearSync_Power", "🔴 物理變更省電模式失敗！請確認 ADB 授權是否失效。", e);
                // 異步在主線程彈出提示
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> 
                        Toast.makeText(context, "變更失敗！請檢查是否授予 WRITE_SECURE_SETTINGS 權限", Toast.LENGTH_LONG).show()
                    );
                }
            } catch (Exception e) {
                Log.e("WearSync_Power", "🔴 寫入省電模式遭遇未知錯誤", e);
            }
        }).start();
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
