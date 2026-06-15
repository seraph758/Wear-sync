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

    /**
     * 🎯 核心新增：专门给外部分流或 Service 销毁时调用的“闭幕式”清理函数
     */
    public static void closeAndReleaseScreenLock() {
        if (instance != null) {
            instance.runOnUiThread(() -> {
                try {
                    Log.d(TAG, "🧹 收到外部清理清算信号，正在安全卸载屏幕常亮标志，并自杀释放...");
                    if (instance.getWindow() != null) {
                        instance.getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    }
                    // 彻底关闭自己，让一加系统可以顺利将屏幕熄屏睡眠
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

        if (getSupportActionBar() != null) {
            getSupportActionBar().hide();
        }

        setContentView(R.layout.activity_main);

        if (savedInstanceState == null) {
            FragmentTransaction ft = getSupportFragmentManager().beginTransaction();
            ft.replace(R.id.settings, new PhoneSyncMainFragment()); 
            ft.commit();
        }

        handleIncomingIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIncomingIntent(intent);
    }

    private void handleIncomingIntent(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        Log.d(TAG, "📥 手机端主页面收到检测意图 Action: " + action);

        if ("ACTION_START_CAMERA_FLOW".equalsIgnoreCase(action)) {
            Log.d(TAG, "🎬 [手表唤醒判定成功]：强制开启一加屏幕常亮防冻结机制...");
            
            // 锁定屏幕常亮
            getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            
            Intent cameraService = new Intent(this, PhoneSyncCameraService.class);
            cameraService.setAction("START_CAMERA");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(cameraService);
            } else {
                startService(cameraService);
            }
        } else {
            Log.d(TAG, "🏡 [普通日常点击设置判定]：仅加载 Compose 设置主界面。");
        }
    }

    @Override
    protected void onDestroy() {
        if (instance == this) {
            instance = null;
        }
        super.onDestroy();
    }
}
