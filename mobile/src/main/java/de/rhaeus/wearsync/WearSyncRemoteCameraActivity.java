package de.rhaeus.wearsync;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

public class WearSyncRemoteCameraActivity extends Activity {

    private static final String TAG =
            "WearSync_RemoteActivity";

    @Override
    protected void onCreate(Bundle savedInstanceState) {

        Log.e(
                TAG,
                "① onCreate开始执行"
        );

        super.onCreate(savedInstanceState);

        Log.e(
                TAG,
                "② super.onCreate执行完成"
        );

        Toast.makeText(
                this,
                "RemoteCameraActivity",
                Toast.LENGTH_LONG
        ).show();

        Log.e(
                TAG,
                "③ Toast已显示"
        );

        Log.e(
                TAG,
                "④ 当前线程="
                        + Thread.currentThread().getName()
        );

        Log.e(
                TAG,
                "⑤ 当前Intent="
                        + getIntent()
        );

        Log.e(
                TAG,
                "⑥ 准备启动 PhoneSyncCameraService"
        );

        Intent serviceIntent =
                new Intent(
                        this,
                        PhoneSyncCameraService.class
                );

        Log.e(
                TAG,
                "⑦ Service Intent创建完成"
        );

        serviceIntent.setAction(
                PhoneSyncCameraService.ACTION_START_CAMERA
        );

        Log.e(
                TAG,
                "⑧ Action="
                        + PhoneSyncCameraService.ACTION_START_CAMERA
        );

        try {

            if (Build.VERSION.SDK_INT >= 26) {

                Log.e(
                        TAG,
                        "⑨ 调用 startForegroundService"
                );

                startForegroundService(
                        serviceIntent
                );

            } else {

                Log.e(
                        TAG,
                        "⑨ 调用 startService"
                );

                startService(
                        serviceIntent
                );

            }

            Log.e(
                    TAG,
                    "⑩ Service启动调用完成"
            );

        } catch (Exception e) {

            Log.e(
                    TAG,
                    "❌ Service启动异常",
                    e
            );
        }

        Log.e(
                TAG,
                "⑪ 即将finish"
        );

        finish();

        Log.e(
                TAG,
                "⑫ finish已调用"
        );
    }

    @Override
    protected void onStart() {

        super.onStart();

        Log.e(
                TAG,
                "onStart"
        );
    }

    @Override
    protected void onResume() {

        super.onResume();

        Log.e(
                TAG,
                "onResume"
        );
    }

    @Override
    protected void onPause() {

        super.onPause();

        Log.e(
                TAG,
                "onPause"
        );
    }

    @Override
    protected void onDestroy() {

        Log.e(
                TAG,
                "onDestroy"
        );

        super.onDestroy();
    }
}