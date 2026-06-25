package de.rhaeus.wearsync;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.widget.Toast;

/**
 * 🎬 远程拍照全透明/跳板 Activity（高版本 Android 兼容优化版）
 */
public class WearSyncRemoteCameraActivity extends Activity {

    private static final String TAG = "WearSync_RemoteActivity";
    private boolean isServiceBound = false;

    // 通过 BIND 机制辅助，确保 Service 拥有合法的 Activity 上下文豁免权，不易被系统在后台拦截
    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            PhoneLog.d(TAG, "🤝 [跳板握手成功] PhoneSyncCameraService 已成功与跳板 Activity 绑定");
            isServiceBound = true;
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            isServiceBound = false;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        PhoneLog.d(TAG, "① onCreate 开始执行... 收到手表反向穿透 URL");
        super.onCreate(savedInstanceState);

        Toast.makeText(this, "正在启动手表远程相机...", Toast.LENGTH_SHORT).show();

        // 1. 构建核心意图，请务必保证此处的 Action 字串与 PhoneSyncCameraService 内部完全对齐
        Intent serviceIntent = new Intent(this, PhoneSyncCameraService.class);
        // 💡 提示：如果常量对不上，可以直接强写成 Service 认的 Action 字符串，例如 "de.rhaeus.wearsync.ACTION_START_CAMERA"
        serviceIntent.setAction("de.rhaeus.wearsync.ACTION_START_CAMERA"); 

        try {
            // 2. 双管齐下：同时用 Start 和 Bind，给 Service 加上最高级别的存活和前台运行豁免权
            if (Build.VERSION.SDK_INT >= 26) {
                PhoneLog.d(TAG, "⑤ 高版本系统：调用 startForegroundService");
                startForegroundService(serviceIntent);
            } else {
                PhoneLog.d(TAG, "⑤ 低版本系统：调用 startService");
                startService(serviceIntent);
            }
            
            // 3. 显式绑定，协助规避 Android 14+ 的后台启动限制
            bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE);
            
        } catch (Exception e) {
            PhoneLog.e(TAG, "❌ [致命异常] 穿透拉起/绑定 PhoneSyncCameraService 失败: " + e.getMessage(), e);
        }

        // 4. 预留 3.5 秒让 Service 完成底层的 Camera 硬件初始化和 Channel 握手
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            PhoneLog.d(TAG, "⑧ 延时结束，解绑并安全退出跳板 Activity，使手机保持无感隐藏状态");
            if (isServiceBound) {
                unbindService(connection);
                isServiceBound = false;
            }
            finish();
        }, 3500);
    }

    @Override
    protected void onDestroy() {
        if (isServiceBound) {
            unbindService(connection);
            isServiceBound = false;
        }
        super.onDestroy();
        PhoneLog.d(TAG, "🏳️ onDestroy: 跳板 Activity 任务生命周期结束");
    }
}
