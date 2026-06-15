package de.rhaeus.wearsync;

import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.FragmentTransaction;

/**
 * 核心功能：充当一加实机后台唤醒的“前台防御盾牌”。
 * 完美支持 getInstance() 单例供相机后台服务调取感知生命周期，
 * 完美支持智能意图识别分流，防止用户点击桌面图标时误触发相机流采集。
 */
public class PhoneSyncMainActivity extends AppCompatActivity {
    private static final String TAG = "WearSync_PhoneMain";
    
    // 建立安全的静态实例持有者
    private static PhoneSyncMainActivity instance;

    public static PhoneSyncMainActivity getInstance() {
        return instance;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        com.google.android.material.color.DynamicColors.applyToActivityIfAvailable(this);
        super.onCreate(savedInstanceState);
        
        instance = this;

        if (getSupportActionBar() != null) {
            getSupportActionBar().hide();
        }

        // 绑定原本载入 ComposeView 容器的布局
        setContentView(R.layout.activity_main);

        if (savedInstanceState == null) {
            FragmentTransaction ft = getSupportFragmentManager().beginTransaction();
            ft.replace(R.id.settings, new PhoneSyncMainFragment()); 
            ft.commit();
        }

        // 第一次创建时，对传入的意图进行智能路由分流判断
        handleIncomingIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        // 复用旧实例时，重新对新意图进行智能路由分流判断
        handleIncomingIntent(intent);
    }

    /**
     * 核心智能分流逻辑：判断是手表远程反向拉起的，还是用户自己点击桌面进入设置的
     */
    private void handleIncomingIntent(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        Log.d(TAG, "📥 手机端主页面收到检测意图 Action: " + action);

        // 场景：只有捕获到专门的手表反向穿透拉起暗号，才开启屏幕常亮并拉起拍照服务
        if ("ACTION_START_CAMERA_FLOW".equalsIgnoreCase(action)) {
            Log.d(TAG, "🎬 [手表唤醒判定成功]：强制开启一加屏幕常亮防冻结机制，并接力相机后台采集服务...");
            
            // 一加熄屏防御核心：强制保持屏幕常亮，防止无触控导致的后台相机硬件冻结
            getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            
            Intent cameraService = new Intent(this, PhoneSyncCameraService.class);
            cameraService.setAction("START_CAMERA");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(cameraService);
            } else {
                startService(cameraService);
            }
        } else {
            Log.d(TAG, "🏡 [普通日常点击设置判定]：仅加载 Compose 设置主界面，不干预相机后台硬件。");
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