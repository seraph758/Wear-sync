package cn.luke.wearsync;

import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.PowerManager;
import android.util.Log;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;

import java.lang.ref.WeakReference;

/**
 * Wear OS 6+ 专用屏幕与CPU管理器
 * 仅支持 Android 15 (API 35) 及以上
 */
public class WearSyncScreenManager implements DefaultLifecycleObserver {

    private static final String TAG = "WearSyncScreenMgr";
    private static final long MAX_CPU_WAKE_MS = 5 * 60 * 1000L; // 安全上限5分钟

    private final Context appContext;
    private WeakReference<Activity> activityRef;
    private PowerManager.WakeLock cpuWakeLock;
    private boolean isBound = false;

    public WearSyncScreenManager(@NonNull Context context) {
        this.appContext = context.getApplicationContext();
    }

    /**
     * 绑定到 Activity 生命周期，自动清理资源
     * ⚠️ 必须在 onCreate 中调用
     */
    public void bind(@NonNull Activity activity) {
        this.activityRef = new WeakReference<>(activity);
        activity.getLifecycle().addObserver(this);
        isBound = true;
        Log.i(TAG, "Bound to lifecycle");
    }

    /**
     * 【Wear OS 6+ 推荐】唤醒屏幕并保持亮屏
     * 替代了旧的 FLAG_TURN_SCREEN_ON
     */
    public void wakeScreen() {
        Activity activity = getSafeActivity();
        if (activity == null) return;

        // Wear OS 6+ 标准 API
        activity.setTurnScreenOn(true);
        activity.setShowWhenLocked(true);
        activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        Log.i(TAG, "Screen wake requested (WearOS6+ API)");
    }

    /**
     * 仅保持屏幕常亮（不主动唤醒）
     */
    public void keepScreenOn() {
        Activity activity = getSafeActivity();
        if (activity != null) {
            activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            Log.i(TAG, "Keep screen on set");
        }
    }

    /**
     * 释放屏幕控制，允许系统进入 Ambient Mode
     */
    public void releaseScreen() {
        Activity activity = getSafeActivity();
        if (activity == null) return;

        activity.setTurnScreenOn(false);
        activity.setShowWhenLocked(false);
        activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        Log.i(TAG, "Screen control released");
    }

    /**
     * 获取 CPU WakeLock（带安全上限）
     */
    public void acquireCpu(long timeoutMs) {
        if (appContext.checkSelfPermission("android.permission.WAKE_LOCK")
                != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "WAKE_LOCK permission missing!");
            return;
        }

        releaseCpu();

        long safeTimeout = Math.min(timeoutMs, MAX_CPU_WAKE_MS);
        if (safeTimeout != timeoutMs) {
            Log.w(TAG, "CPU timeout clamped: " + timeoutMs + " → " + safeTimeout + "ms");
        }

        PowerManager pm = (PowerManager) appContext.getSystemService(Context.POWER_SERVICE);
        if (pm == null) {
            Log.e(TAG, "PowerManager unavailable");
            return;
        }

        String tag = appContext.getPackageName() + ":WearSyncCPU";
        cpuWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, tag);
        cpuWakeLock.acquire(safeTimeout);

        Log.i(TAG, "CPU WakeLock acquired for " + safeTimeout + "ms");
    }

    public void releaseCpu() {
        if (cpuWakeLock != null && cpuWakeLock.isHeld()) {
            cpuWakeLock.release();
            Log.i(TAG, "CPU WakeLock released");
        }
        cpuWakeLock = null;
    }

    /**
     * 一键同步唤醒（DND/消息同步推荐入口）
     */
    public void wakeForSync(long cpuTimeMs) {
        wakeScreen();
        acquireCpu(cpuTimeMs);
        Log.i(TAG, "Wake for sync triggered");
    }

    // --- Lifecycle 自动清理 ---

    @Override
    public void onDestroy(@NonNull LifecycleOwner owner) {
        Log.i(TAG, "Lifecycle destroyed, auto-cleanup");
        releaseScreen();
        releaseCpu();
        isBound = false;
    }

    private Activity getSafeActivity() {
        if (!isBound || activityRef == null) {
            Log.w(TAG, "Manager not bound to any Activity!");
            return null;
        }
        Activity activity = activityRef.get();
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
            Log.w(TAG, "Activity reference invalid");
            return null;
        }
        return activity;
    }
}
