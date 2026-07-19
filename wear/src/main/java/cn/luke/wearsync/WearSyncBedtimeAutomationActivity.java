package cn.luke.wearsync; // ← 替换为你实际的包名

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

// ✅ 你项目中已有的自定义类（确保路径正确）
import cn.luke.wearsync.WearSyncScreenManager;
import cn.luke.wearsync.WearSyncAccessService;
import cn.luke.wearsync.WearLog;

public class WearSyncBedtimeAutomationActivity extends androidx.activity.ComponentActivity {

    private static final String TAG = "BedtimeAuto";
    private WearSyncScreenManager screenManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // ⚠️ 关键：不设置任何 ContentView，保持完全透明
        
        screenManager = new WearSyncScreenManager(this);
        screenManager.bind(this);
        
        // 立即唤醒屏幕进入 Interactive Mode
        screenManager.wakeScreen();
        screenManager.acquireCpu(8000);
        
        executeBedtimeToggle();
    }

    private void executeBedtimeToggle() {
        Handler h = new Handler(Looper.getMainLooper());
        
        // 等待屏幕从 Ambient 切换到 Interactive（Wear OS 6 通常需要 600-800ms）
        h.postDelayed(() -> {
            WearSyncAccessService serv = WearSyncAccessService.getSharedInstance();
            if (serv == null) {
                finishAndCleanup();
                return;
            }
            
            WearLog.d(TAG, "🖥️ 屏幕已亮起，执行下拉");
            serv.swipeDown();
            
            h.postDelayed(() -> {
                WearLog.d(TAG, "👆 点击三星睡眠模式");
                serv.clickIcon1_1();
                
                h.postDelayed(() -> {
                    WearLog.d(TAG, "↩️ 返回表盘");
                    serv.goBack();
                    
                    // 手势完成后立即释放资源并关闭自身
                    h.postDelayed(this::finishAndCleanup, 500);
                }, 1000);
            }, 1000);
        }, 800);
    }

    private void finishAndCleanup() {
        screenManager.releaseScreen();
        screenManager.releaseCpu();
        finish();
        // 移除任务栈中的痕迹，避免按最近任务看到空白页
        finishAndRemoveTask(); 
    }
}

