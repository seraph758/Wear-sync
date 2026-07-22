package cn.luke.wearsync; // ← 替换为你实际的包名

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

// ✅ 你项目中已有的自定义类（确保路径正确）

public class WearSyncBedtimeAutomationActivity extends androidx.activity.ComponentActivity {

    private static final String TAG = "BedtimeAuto";
    private static final String EXTRA_PULL_DOWN_DELAY = "extra_pull_down_delay";
    private WearSyncScreenManager screenManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        screenManager = new WearSyncScreenManager(this);
        screenManager.bind(this);
        screenManager.wakeScreen();
        screenManager.acquireCpu(8000);

        // ✅ 直接从 Intent 读取本次下发的新鲜数值，不读本地 SP
        int pullDownDelay = getIntent().getIntExtra(EXTRA_PULL_DOWN_DELAY, 500);

        WearLog.d(TAG, "🌙 [BedtimeActivity] 收到延迟值=" + pullDownDelay + "ms");

        executeBedtimeToggle(pullDownDelay);
    }


    private void executeBedtimeToggle(int pullDownDelay) {
        Handler h = new Handler(Looper.getMainLooper());

        // ✅ 使用传入的最新延迟值控制亮屏与下拉的间隔
        h.postDelayed(() -> {
            WearSyncAccessService serv = WearSyncAccessService.getSharedInstance();
            if (serv == null) { finishAndCleanup(); return; }

            WearLog.d(TAG, "🖥️ 屏幕已亮起，等待 " + pullDownDelay + "ms 后执行下拉");
            serv.swipeDown();

            h.postDelayed(() -> {
                WearLog.d(TAG, "👆 点击三星睡眠模式");
                serv.clickIcon1_1();

                h.postDelayed(() -> {
                    WearLog.d(TAG, "↩️ 返回表盘");
                    serv.goBack();
                    h.postDelayed(this::finishAndCleanup, 500);
                }, 1000);
            }, 1000);
        }, pullDownDelay); // ✅ 即用即弃，Activity 销毁后数值自然释放
    }

    private void finishAndCleanup() {
        screenManager.releaseScreen();
        screenManager.releaseCpu();
        finish();
        // 移除任务栈中的痕迹，避免按最近任务看到空白页
        finishAndRemoveTask(); 
    }
}

