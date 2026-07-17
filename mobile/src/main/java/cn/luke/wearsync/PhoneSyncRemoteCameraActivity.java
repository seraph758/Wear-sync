package cn.luke.wearsync;

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
 * 极致动态日志全步进版：微秒级追踪跳板点火、双轨绑定与延时解绑自我熔断流。
 */
public class PhoneSyncRemoteCameraActivity extends Activity {

    private static final String TAG = "PhoneSync_RemoteActivity";
    private boolean isServiceBound = false;

    // 🤝 异步连接接线员：通过 BIND 机制辅助，确保 Service 拥有合法的 Activity 上下文豁免权
    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            PhoneLog.d(TAG, "🤝 [跳板握手成功] ─── 异部回调触发 ───");
            PhoneLog.d(TAG, "  └─ 🎯 目标服务类: [" + (name != null ? name.getShortClassName() : "未知") + "]");
            PhoneLog.d(TAG, "  └─ 🚀 状态更新: PhoneSyncCameraService 已成功与当前跳板 Activity 建立物理绑定 (Context 豁免权生效)！");
            isServiceBound = true;
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            PhoneLog.w(TAG, "⚠️ [跳板断开连接] ─── 异步断开触发 ───");
            PhoneLog.w(TAG, "  └─ 🎯 目标服务类: [" + (name != null ? name.getShortClassName() : "未知") + "] 发生内部崩溃或被系统意外回收。");
            isServiceBound = false;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        PhoneLog.d(TAG, "① [生命周期] onCreate 点火 ━━━ 接收到手表远端击穿流 ━━━");
        super.onCreate(savedInstanceState);

        // 捕获可能携带的 Intent 信息
        Intent incomingIntent = getIntent();
        if (incomingIntent != null) {
            PhoneLog.d(TAG, "🔍 [入站信令核对] Intent Action: [" + incomingIntent.getAction() + "]");
        }

        // 🚀【Android 14+ 核心适配】在拉起前台相机服务前，必须确保手机端已有硬件相机权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) { // Android 6.0+
            if (checkSelfPermission(android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                PhoneLog.w(TAG, "🔒 [权限拦截] 监测到手机端未被授予 CAMERA 权限，正在强制唤起系统弹窗让用户授权...");

                // 弹出系统权限请求（注意：这会使 Activity 暂停等待用户点击）
                requestPermissions(new String[]{android.Manifest.permission.CAMERA}, 102);

                // 提示用户
                Toast.makeText(this, "请授予相机权限以允许手表控制拍照", Toast.LENGTH_LONG).show();

                // ⚠️ 注意：未授权时不能立刻往下走拉起服务，否则直接崩溃！
                // 我们在下方的 onRequestPermissionsResult 回调中接力拉起服务。
                return;
            }
        }

        // 🟢 如果已经有权限，直接执行核心点火逻辑
        proceedToStartCameraService();
    }

    /**
     * 🚀 封装的核心点火业务：启动并双轨绑定前台相机服务
     */
    /**
     * 🚀 启动并绑定手机端前台相机同步服务
     */
    private void proceedToStartCameraService() {
        Toast.makeText(this, "正在启动远程相机...", Toast.LENGTH_SHORT).show();
        PhoneLog.d(TAG, "开始拉起手机端相机服务...");

        Intent serviceIntent = new Intent(this, PhoneSyncCameraService.class);
        serviceIntent.setAction("cn.luke.wearsync.ACTION_START_CAMERA");

        try {
            // 1. 因为 minSdk >= 26，直接一行代码走前台服务启动
            startForegroundService(serviceIntent);

            // 2. 绑定服务以获取 Android 14+ 后台运行豁免权
            isServiceBound = bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE);
            PhoneLog.d(TAG, "服务双轨绑定状态: " + isServiceBound);

        } catch (Exception e) {
            PhoneLog.e(TAG, "拉起或绑定 PhoneSyncCameraService 失败: " + e.getMessage());
        }

        // 3. 预留 3.5 秒供相机硬件初始化，随后跳板 Activity 自我销毁
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (isServiceBound) {
                try {
                    unbindService(connection);
                } catch (Exception ignored) {}
                isServiceBound = false;
            }
            PhoneLog.d(TAG, "跳板 Activity 任务完成，退出前台。");
            finish();
        }, 3500);
    }

    // 🚀【权限接力棒】当用户点击了系统“允许相机权限”后，在这里立刻接力点火拉起服务
    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 102) {
            if (grantResults.length > 0 && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                PhoneLog.d(TAG, "✅ [权限接力成功] 用户已点击允许相机权限，立刻无缝拉起前台相机服务！");
                proceedToStartCameraService();
            } else {
                PhoneLog.e(TAG, "❌ [权限接力失败] 用户拒绝了相机权限，跳板退出，无法拉起前台相机。");
                Toast.makeText(this, "未获得相机权限，无法完成远程拍照", Toast.LENGTH_SHORT).show();
                finish();
            }
        }
    }

    @Override
    protected void onDestroy() {
        PhoneLog.w(TAG, "🏳️ [生命周期] onDestroy 触发：监测到 Activity 任务堆栈准备销毁...");
        if (isServiceBound) {
            PhoneLog.w(TAG, "🧹 [生命周期保底释放] 发现 onDestroy 触发时绑定仍未断开，触发二次保底 unbindService()...");
            try {
                unbindService(connection);
            } catch (Exception ignored) {}
            isServiceBound = false;
        }
        super.onDestroy();
        PhoneLog.w(TAG, "🏳️ [生命周期] onDestroy ─── 远程相机全透明跳板 Activity 任务生命周期完美安全终结 ───");
    }
}
