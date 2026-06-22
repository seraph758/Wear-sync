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


        if (dndPref != null) {
            dndPref.setSelectable(false);
        }


        if (accPref != null) {

            accPref.setOnPreferenceClickListener(preference -> {

                try {

                    startActivity(
                            new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    );

                } catch (Exception e) {

                    Toast.makeText(
                            getContext(),
                            "无法打开辅助功能设置",
                            Toast.LENGTH_SHORT
                    ).show();

                }

                return true;
            });
        }



        // ==========================
        // RemoteActivityHelper 测试入口
        // ==========================

Preference cameraPref =
        findPreference("camera_control_key");


if(cameraPref != null){

    cameraPref.setOnPreferenceClickListener(preference -> {


        Log.d(
                "WearSync_UI",
                "点击远程相机"
        );


        WearSyncRemoteCameraHandler.openPhoneCamera(
                requireContext()
        );


        return true;

    });

}

        initConnectivityCheck();

    }





    @Override
    public void onResume(){

        super.onResume();

        updatePermissionStatus();

        registerConnectivityListener();

    }




    @Override
    public void onPause(){

        super.onPause();

        unregisterConnectivityListener();

    }





    private void updatePermissionStatus(){


        Context ctx=getContext();

        if(ctx==null)return;



        String enabledListeners =
                Settings.Secure.getString(
                        ctx.getContentResolver(),
                        "enabled_notification_listeners"
                );


        boolean notificationAllowed =
                enabledListeners != null &&
                enabledListeners.contains(
                        ctx.getPackageName()
                );



        if(dndPref!=null){

            dndPref.setSummary(
                    notificationAllowed ?
                            "🟢通知监听已启用" :
                            "🔴未启用"
            );

        }



        boolean accAllowed =
                WearSyncAccessService.getSharedInstance()!=null;



        if(accPref!=null){

            accPref.setSummary(
                    accAllowed ?
                            "🟢辅助功能已激活" :
                            "🔴未激活"
            );

        }


    }





    private void initConnectivityCheck(){


        if(getContext()==null)return;



        Wearable
                .getCapabilityClient(getContext())
                .getCapability(
                        "dnd_sync",
                        CapabilityClient.FILTER_REACHABLE
                )
                .addOnSuccessListener(
                        capabilityInfo -> {

                            updateConnectionUI(
                                    !capabilityInfo.getNodes().isEmpty()
                            );

                        }
                );



        capabilityChangedListener =
                capabilityInfo -> updateConnectionUI(
                        !capabilityInfo.getNodes().isEmpty()
                );

    }





    private void registerConnectivityListener(){

        if(getContext()!=null &&
                capabilityChangedListener!=null){

            Wearable
                    .getCapabilityClient(getContext())
                    .addListener(
                            capabilityChangedListener,
                            "dnd_sync"
                    );

        }

    }





    private void unregisterConnectivityListener(){


        if(getContext()!=null &&
                capabilityChangedListener!=null){


            Wearable
                    .getCapabilityClient(getContext())
                    .removeListener(
                            capabilityChangedListener
                    );

        }

    }





    private void updateConnectionUI(boolean connected){


        if(connectivityPref!=null){

            connectivityPref.setSummary(
                    connected ?
                            "🟢已连接手机" :
                            "🔴未连接"
            );

        }

    }


}