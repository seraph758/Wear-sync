package de.rhaeus.wearsync;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;

import androidx.wear.remote.interactions.RemoteActivityHelper;

import java.util.concurrent.Executors;

public class WearSyncRemoteCameraHandler {

    private static final String TAG = "WearSync_RemoteCamera";


    public static void openPhoneCamera(Context context) {


    Log.d(TAG,"开始调用 RemoteActivityHelper");


    Intent intent = new Intent(
            Intent.ACTION_VIEW
    );


    intent.setPackage(
            "de.rhaeus.wearsync"
    );


    intent.setData(
            Uri.parse(
                    "wearsync://camera"
            )
    );


    Log.d(
            TAG,
            "Intent="
            + intent.toUri(0)
    );


    RemoteActivityHelper helper =
            new RemoteActivityHelper(
                    context
            );


    helper.startRemoteActivity(intent)
            .addListener(() -> {

                Log.d(
                        TAG,
                        "RemoteActivity finished"
                );

            },
            Executors.newSingleThreadExecutor());


}
}