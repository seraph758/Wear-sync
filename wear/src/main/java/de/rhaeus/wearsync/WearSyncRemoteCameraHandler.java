package de.rhaeus.wearsync;


import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;

import androidx.wear.remote.interactions.RemoteActivityHelper;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;


public class WearSyncRemoteCameraHandler {


    private static final String TAG =
            "WearSync_RemoteCamera";


    private final Context context;


    private final Executor executor =
            Executors.newSingleThreadExecutor();



    public WearSyncRemoteCameraHandler(Context context){

        this.context = context.getApplicationContext();

    }





    public void openPhoneCamera(){


        try {


            RemoteActivityHelper helper =
                    new RemoteActivityHelper(
                            context,
                            executor
                    );



            Intent intent =
                    new Intent(Intent.ACTION_VIEW);



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
                    "发送RemoteActivity请求"
            );



            helper.startRemoteActivity(
                    intent
            ).addListener(
                    () -> {

                        Log.d(
                                TAG,
                                "RemoteActivity执行完成"
                        );

                    },
                    executor
            );


        }catch(Exception e){


            Log.e(
                    TAG,
                    "RemoteActivity失败",
                    e
            );


        }


    }

}
