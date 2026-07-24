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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        PhoneLog.d(TAG, "① [生命周期] onCreate 点火 ━━━ 接收到手表远端击穿流 ━━━");
        super.onCreate(savedInstanceState);

        // 捕获可能携带的 Intent 信息
        Intent incomingIntent = getIntent();
        if (incomingIntent != null) {
            PhoneLog.d(TAG, "🔍 [入站信令核对] Intent Action: [" + incomingIntent.getAction() + "]");
        }

        // 🚀【Android 6.0+ 核心适配】检查相机权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                PhoneLog.w(TAG, "🔒 [权限拦截] 监测到手机端未被授予 CAMERA 权限，正在强制唤起系统弹窗...");
                requestPermissions(new String[]{android.Manifest.permission.CAMERA}, 102);
                Toast.makeText(this, "请授予相机权限以允许手表控制拍照", Toast.LENGTH_LONG).show();
                return; // 等待用户授权
            }
        }

        // 🟢 如果已经有权限，直接执行核心点火逻辑
        proceedToStartCameraService();
    }

    /**
     * 🚀 封装的核心点火业务：只负责启动前台服务
     */
    private void proceedToStartCameraService() {
        Toast.makeText(this, "正在启动远程相机...", Toast.LENGTH_SHORT).show();
        PhoneLog.d(TAG, "开始拉起手机端相机服务...");

        Intent serviceIntent = new Intent(this, PhoneSyncCameraService.class);
        serviceIntent.setAction("cn.luke.wearsync.ACTION_START_CAMERA");

        try {
            // 1. 启动前台服务。服务内部会自己处理所有初始化逻辑。
            // 因为此 Activity 是可见的，所以可以成功启动前台服务。
            startForegroundService(serviceIntent);
            PhoneLog.d(TAG, "✅ 前台服务启动指令已发送");

        } catch (Exception e) {
            PhoneLog.e(TAG, "拉起 PhoneSyncCameraService 失败: " + e.getMessage());
            Toast.makeText(this, "启动服务失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        // 2. 服务启动后，跳板 Activity 立即退出
        // 不需要 bindService，因为服务本身就有完整的 Context 来操作摄像头
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            PhoneLog.d(TAG, "跳板 Activity 任务完成，退出前台。");
            finish();
        }, 1500); // 1.5秒足够服务启动了
    }

    // 🚀【权限接力棒】
    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 102) {
            if (grantResults.length > 0 && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                PhoneLog.d(TAG, "✅ [权限接力成功] 用户已点击允许相机权限，立刻无缝拉起前台相机服务！");
                proceedToStartCameraService();
            } else {
                PhoneLog.e(TAG, "❌ [权限接力失败] 用户拒绝了相机权限，跳板退出。");
                Toast.makeText(this, "未获得相机权限，无法完成远程拍照", Toast.LENGTH_SHORT).show();
                finish();
            }
        }
    }

    @Override
    protected void onDestroy() {
        PhoneLog.w(TAG, "🏳️ [生命周期] onDestroy ─── 远程相机全透明跳板 Activity 任务生命周期完美安全终结 ───");
        super.onDestroy();
    }
}
