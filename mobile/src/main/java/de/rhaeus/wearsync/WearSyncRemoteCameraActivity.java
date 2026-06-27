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
 * 极致动态日志全步进版：微秒级追踪跳板点火、双轨绑定与延时解绑自我熔断流。
 */
public class WearSyncRemoteCameraActivity extends Activity {

    private static final String TAG = "WearSync_RemoteActivity";
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

        // 捕获可能携带的 Intent 信息，方便白天联调核对
        Intent incomingIntent = getIntent();
        if (incomingIntent != null) {
            PhoneLog.d(TAG, "🔍 [入站信令核对] Intent Action: [" + incomingIntent.getAction() + "]");
            PhoneLog.d(TAG, "🔍 [入站信令核对] Intent Data URI: [" + (incomingIntent.getData() != null ? incomingIntent.getData().toString() : "null") + "]");
        }

        PhoneLog.d(TAG, "💬 [UI弹出] 正在向手机屏幕弹窗提示: [正在启动手表远程相机...]");
        Toast.makeText(this, "正在启动手表远程相机...", Toast.LENGTH_SHORT).show();

        // 1. 构建核心意图，严格对齐 Action 
        PhoneLog.d(TAG, "⚙️ [核心意图封装] 正在组装发射弹药 ➔ 目标指向: PhoneSyncCameraService.class");
        Intent serviceIntent = new Intent(this, PhoneSyncCameraService.class);
        
        PhoneLog.d(TAG, "⚙️ [核心意图封装] 注入高优先级行动暗号: [de.rhaeus.wearsync.ACTION_START_CAMERA]");
        serviceIntent.setAction("de.rhaeus.wearsync.ACTION_START_CAMERA"); 

        try {
            // 2. 双管齐下：同时用 Start 和 Bind，给 Service 加上最高级别的存活和前台运行豁免权
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) { // SDK 26 (Android 8.0+)
                PhoneLog.d(TAG, "⑤ [版本分支命中] 检测到当前手机系统 SDK: " + Build.VERSION.SDK_INT + " (>=26)，调用 [startForegroundService] 强行破壳...");
                startForegroundService(serviceIntent);
            } else {
                PhoneLog.d(TAG, "⑤ [版本分支命中] 检测到当前手机系统 SDK: " + Build.VERSION.SDK_INT + " (<26)，调用旧版 [startService] 破壳...");
                startService(serviceIntent);
            }
            
            // 3. 显式绑定，协助规避 Android 14+ 的后台启动限制
            PhoneLog.d(TAG, "📡 [物理绑定挂载] 正在调用 bindService() 并注入 [Context.BIND_AUTO_CREATE] 旗帜，拉高服务优先级系数...");
            boolean bindResult = bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE);
            PhoneLog.d(TAG, "📡 [物理绑定挂载] 底层系统反馈 bindService 投递申请状态碼: " + (bindResult ? "【成功/TRUE】" : "【失败/FALSE】"));
            
        } catch (Exception e) {
            PhoneLog.e(TAG, "🔴 [点火致命异常] 穿透拉起或双轨绑定 PhoneSyncCameraService 遭遇系统底层拦截崩溃: " + e.getMessage(), e);
        }

        // 4. 预留 3.5 秒让 Service 完成底层的 Camera 硬件初始化和 Channel 握手
        PhoneLog.d(TAG, "⏳ [沙盒倒计时挂起] 成功挂载 3500 毫秒延时防抖 Handler，预留充足频宽给相机初始化与 Channel 网关闭合...");
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            PhoneLog.w(TAG, "⑧ [沙盒倒计时触发] ━━━ 3.5秒大坝蓄水结束 ━━━ 准备引导跳板安全退场...");
            
            if (isServiceBound) {
                PhoneLog.d(TAG, "🧹 [沙盒清场] 检测到当前正处于 Context 绑定存续状态，正在安全执行 unbindService()...");
                try {
                    unbindService(connection);
                    PhoneLog.d(TAG, "🧹 [沙盒清场] unbindService 逻辑断开顺利完成。");
                } catch (Exception e) {
                    PhoneLog.e(TAG, "⚠️ [沙盒清场轻微波动] 释放 unbindService 触发了边缘报错 (可能已被系统自动回收): " + e.getMessage());
                }
                isServiceBound = false;
            } else {
                PhoneLog.d(TAG, "🧹 [沙盒清场] 检测到当前未处于绑定存续状态，跳过 unbindService。");
            }
            
            PhoneLog.w(TAG, "🏳️ [跳板功成身退] 正在调用 finish() 销毁本 Activity，使手机彻底回归纯净无感背景隐蔽状态。");
            finish();
        }, 3500);
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
