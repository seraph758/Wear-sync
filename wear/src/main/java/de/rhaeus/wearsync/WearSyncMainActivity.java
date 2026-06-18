package de.rhaeus.wearsync;

import android.os.Bundle;
import android.util.Log; // 🎯 迎回我们的日志核心
import androidx.appcompat.app.AppCompatActivity;

public class WearSyncMainActivity extends AppCompatActivity {
    // 🎯 规范定义全局 TAG
    private static final String TAG = "WearSync_MainActivity";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(Bundle savedInstanceState);
        Log.d(TAG, "🚀 onCreate: 手表主 Activity 启动...");
        
        // 1. 加载已经精简过的全屏 FrameLayout 布局
        setContentView(R.layout.activity_main);
        Log.d(TAG, "📦 setContentView: activity_main 容器布局加载完成");

        // 2. 纯粹、安全地将 PreferenceFragment 注入到全局容器中
        if (savedInstanceState == null) {
            Log.d(TAG, "🔄 savedInstanceState 为空，开始首次冷启动动态注入 WearSyncMainFragment...");
            getSupportFragmentManager()
                    .beginTransaction()
                    .replace(R.id.content_frame, new WearSyncMainFragment())
                    .commit();
            Log.i(TAG, "🟢 WearSyncMainFragment 事务已成功 commit 提交");
        } else {
            // 规避屏幕旋转、内存回收重启时的重复注入
            Log.w(TAG, "⚠️ 检测到系统实例恢复 (savedInstanceState != null)，由系统自动接管 Fragment 恢复，跳过重复注入");
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.d(TAG, "⏱️ onResume: 主界面进入前台");
    }

    @Override
    protected void onPause() {
        super.onPause();
        Log.d(TAG, "⏸️ onPause: 主界面离开前台");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "🛑 onDestroy: 主界面被系统销毁");
    }
}
