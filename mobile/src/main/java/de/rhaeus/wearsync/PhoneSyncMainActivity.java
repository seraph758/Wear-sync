package de.rhaeus.wearsync;

import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.FragmentTransaction;

public class PhoneSyncMainActivity extends AppCompatActivity {
    private static final String TAG = "WearSync_PhoneMain";
    private static PhoneSyncMainActivity instance;

    public static PhoneSyncMainActivity getInstance() {
        return instance;
    }

    public static void closeAndReleaseScreenLock() {
        if (instance != null) {
            instance.runOnUiThread(() -> {
                try {
                    Log.d(TAG, "🧹 收到外部清理清算信号，正在安全卸载屏幕常亮标志，并自杀释放...");
                    if (instance.getWindow() != null) {
                        instance.getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    }
                    instance.finish();
                } catch (Exception e) {
                    Log.e(TAG, "卸载屏幕锁发生异常", e);
                }
            });
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        com.google.android.material.color.DynamicColors.applyToActivityIfAvailable(this);
        super.onCreate(savedInstanceState);
        instance = this;
        Log.d(
        "WearSync_Main",
        "onCreate"
    );
        
        if (getSupportActionBar() != null) {
            getSupportActionBar().hide();
        }

        setContentView(R.layout.activity_main);

        if (savedInstanceState == null) {
            FragmentTransaction ft = getSupportFragmentManager().beginTransaction();
            ft.replace(R.id.settings, new PhoneSyncMainFragment()); 
            ft.commit();
        }

        // 🎯 规范化对齐：统一调用 handleIncomingCommand 
        handleIncomingCommand(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        // 🎯 确保热启动状态下收到手表的跳板指令也能响应
        handleIncomingCommand(intent);
    }

    private void handleIncomingCommand(Intent intent) {
        if (intent != null && "LAUNCH_CAMERA_SERVICE_FROM_FOREGROUND".equals(intent.getStringExtra("INTERNAL_CMD"))) {
            Log.d(TAG, "🟢 应用已安全立足于前台！现在名正言顺启动拍照服务与混合协议传输。");
            
            // 🎯 这里去调起负责开启相机和建立 Channel Client 通道的服务
     /*     Intent cameraServiceIntent = new Intent(this, PhoneSyncCameraService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(cameraServiceIntent);
            } else {
                startService(cameraServiceIntent);
            } */
            Intent cameraServiceIntent =
                        new Intent(this, PhoneSyncCameraService.class);
                
                cameraServiceIntent.setAction(
                        PhoneSyncCameraService.ACTION_START_CAMERA
                );
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(cameraServiceIntent);
                } else {
                    startService(cameraServiceIntent);
                }
                        }
    }
    @Override
    protected void onResume() {
        super.onResume();
    
        Log.d(
            "WearSync_Main",
            "onResume"
    );
    }
    @Override
    protected void onDestroy() {
        if (instance == this) {
            instance = null;
        }
        super.onDestroy();
    }
}
