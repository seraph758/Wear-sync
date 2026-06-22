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


        Log.d(TAG, "准备通过 RemoteActivityHelper 拉起手机 Camera");


        Intent intent = new Intent(
                Intent.ACTION_VIEW
        );


        // 让手机端识别
        intent.setPackage(
                "de.rhaeus.wearsync"
        );


        // 手机端解析这个
        intent.setData(
                Uri.parse(
                        "wearsync://camera"
                )
        );


        RemoteActivityHelper helper =
                new RemoteActivityHelper(
                        context,
                        Executors.newSingleThreadExecutor()
                );


        helper.startRemoteActivity(intent)
                .addListener(() -> {


                    Log.d(
                            TAG,
                            "RemoteActivity 请求完成"
                    );


                },
                Executors.newSingleThreadExecutor());

    }

}