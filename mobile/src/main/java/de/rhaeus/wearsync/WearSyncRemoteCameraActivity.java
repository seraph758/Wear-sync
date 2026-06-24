package de.rhaeus.wearsync;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.widget.Toast;

/**
 * 🎬 远程拍照全透明/跳板 Activity
 * 变更：全面重构 Log 级别规范，挤干换行空行，归化 PhoneLog 开关。
 */
public class WearSyncRemoteCameraActivity extends Activity {

    private static final String TAG = "WearSync_RemoteActivity";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        PhoneLog.d(TAG, "① onCreate 开始执行...");
        super.onCreate(savedInstanceState);

        Toast.makeText(this, "RemoteCameraActivity", Toast.LENGTH_LONG).show();
        PhoneLog.d(TAG, "② 跳板 Activity 组件与 Toast 已就绪. 当前线程: " + Thread.currentThread().getName());
        PhoneLog.d(TAG, "③ 携带的 Intent 载荷: " + getIntent());

        PhoneLog.d(TAG, "④ 准备通过跨进程通信启动 PhoneSyncCameraService...");
        Intent serviceIntent = new Intent(this, PhoneSyncCameraService.class);
        serviceIntent.setAction(PhoneSyncCameraService.ACTION_START_CAMERA);

        try {
            if (Build.VERSION.SDK_INT >= 26) {
                PhoneLog.d(TAG, "⑤ 系统版本 >= 26，调用 startForegroundService");
                startForegroundService(serviceIntent);
            } else {
                PhoneLog.d(TAG, "⑤ 系统版本 < 26，调用 startService");
                startService(serviceIntent);
            }
            PhoneLog.d(TAG, "⑥ 相机流前台后台绑定指令已成功下发");
        } catch (Exception e) {
            PhoneLog.e(TAG, "❌ [致命异常] 穿透拉起 PhoneSyncCameraService 失败: " + e.getMessage(), e);
        }

        PhoneLog.d(TAG, "⑦ 启动 3 秒防崩溃延时。预留空间给厂商 CameraManager 进行底层硬件握手...");
        new Handler().postDelayed(() -> {
            PhoneLog.d(TAG, "⑧ 防崩溃延时结束，主动调用 finish() 销毁跳板，实现后台无感隐藏");
            finish();
        }, 3000);
    }

    @Override
    protected void onStart() {
        super.onStart();
        PhoneLog.d(TAG, "生命周期回调 ➔ onStart");
    }

    @Override
    protected void onResume() {
        super.onResume();
        PhoneLog.d(TAG, "生命周期回调 ➔ onResume");
    }

    @Override
    protected void onPause() {
        super.onPause();
        PhoneLog.d(TAG, "生命周期回调 ➔ onPause");
    }

    @Override
    protected void onDestroy() {
        PhoneLog.d(TAG, "生命周期回调 ➔ onDestroy (Activity 销毁释放)");
        super.onDestroy();
    }
}
