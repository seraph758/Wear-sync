package de.rhaeus.wearsync;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Toast;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import com.google.android.gms.wearable.CapabilityClient;
import com.google.android.gms.wearable.Wearable;

import android.app.NotificationManager;
import android.content.Context; // 確保有這個

public class WearSyncMainFragment extends PreferenceFragmentCompat {
    private static final String TAG = "WearSync_MainFragment";
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
            dndPref.setSelectable(false);
        }

        if (accPref != null) {
            accPref.setOnPreferenceClickListener(preference -> {
                try {
                    startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                } catch (Exception e) {
                    Toast.makeText(getContext(), "无法跳转无障碍设置", Toast.LENGTH_SHORT).show();
                }
                return true;
            });
        }
        setupConnectionCheck();
    }

    @Override
    public void onResume() {
        super.onResume();
        WearLog.d(TAG, "🔄 onResume: 刷新手表本地系统特权审批状态...");
        
        NotificationManager nm = (NotificationManager) requireContext().getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null && dndPref != null) {
            boolean hasDnd = nm.isNotificationPolicyAccessGranted();
            dndPref.setSummary(hasDnd ? "🟢 已获得系统勿扰读写权" : "🔴 未授权高级勿扰 (请长按给予读写权限)");
        }
        registerConnectivityListener();
    }

    @Override
    public void onPause() {
        super.onPause();
        unregisterConnectivityListener();
    }

    private void setupConnectionCheck() {
        Wearable.getCapabilityClient(requireContext())
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

    private void updateConnectionUI(boolean connected) {
        if (connectivityPref != null) {
            connectivityPref.setSummary(connected ? "🟢 已连接到宿主手机" : "🔴 与手机断开联络");
            WearLog.d(TAG, "📡 联络链路状态突变探查 ➔ 在线=[" + connected + "]");
        }
    }
}
