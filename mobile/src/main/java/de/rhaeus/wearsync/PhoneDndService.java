package de.rhaeus.wearsync;

import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.IBinder;
import android.util.Log;
import androidx.annotation.Nullable;

/**
 * 🌙 独立解耦的完全体勿扰/复合模式处理服务
 * 完美融合：支持手表传来的复合二进制 Mask 掩码值，
 * 动态拆解并对齐“总勿扰”、“震动睡眠模式”、“省电模式”三大物理开关状态。
 */
public class PhoneDndService extends Service {
    private static final String TAG = "WearSync_PhoneDnd";

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        // 🧠 核心：同时兼容基础的 dnd_profile_value 和我们大架构分发过来的 Mask 掩码值
        int dndProfileValue = intent.getIntExtra("dnd_profile_value", -1);
        
        Log.d(TAG, "☯️ 独立勿扰服务启动，收到转发的系统底层硬状态值: " + dndProfileValue);

        // ========================================================
        // 🎯 核心新增：Mask 二进制掩码状态值动态解析与本地状态对齐区
        // ========================================================
        // 假设手表端的二进制设计逻辑为：
        // 掩码第0位 (1): 总勿扰开关
        // 掩码第1位 (2): 震动睡眠模式
        // 掩码第2位 (4): 省电模式
        // 只要任何一个子开关是打开的，物理硬勿扰就需要联动开启。
        if (dndProfileValue != -1) {
            int mask = dndProfileValue; // 在你的底层协议中，dnd_profile_value 就是传过来的复合 Mask 数字
            
            boolean isDndOn = (mask & 1) != 0;       // 对应总勿扰
            boolean isSleepOn = (mask & 2) != 0;     // 对应震动睡眠
            boolean isPowerSaveOn = (mask & 4) != 0; // 对应省电模式

            Log.d(TAG, "🔢 [Mask 掩码深度拆解] -> 总勿扰: " + isDndOn 
                    + " | 震动睡眠: " + isSleepOn 
                    + " | 省电模式: " + isPowerSaveOn);

            // 1️⃣ 将这三个开关的状态，持久化保存到手机本地的存储中（保证手机打开 App 时 UI 状态能对齐）
            try {
                SharedPreferences prefs = getSharedPreferences("wear_sync_prefs", Context.MODE_PRIVATE);
                prefs.edit()
                     .putBoolean("key_dnd_switch", isDndOn)
                     .putBoolean("key_sleep_mode", isSleepOn)
                     .putBoolean("key_power_save", isPowerSaveOn)
                     .apply();
                Log.d(TAG, "💾 三大自定义开关状态已成功持久化至手机 SharedPreferences。");

                // 2️⃣ 【核心联动分发】：如果你手机主界面 (Activity) 此时正开着，发送一个本地广播让 UI 刷新开关图标
                Intent uiBroadcast = new Intent("DE_RHAEUS_WEARSYNC_UPDATE_UI_SWITCHES");
                uiBroadcast.putExtra("dnd_on", isDndOn);
                uiBroadcast.putExtra("sleep_on", isSleepOn);
                uiBroadcast.putExtra("power_on", isPowerSaveOn);
                sendBroadcast(uiBroadcast);

                // 3️⃣ 执行特定模式的特有业务（例如：如果省电模式开了，可以去触发手机本地的省电逻辑等）
                if (isPowerSaveOn) {
                    // 执行你原本项目里专门针对省电模式的后台操作...
                    Log.d(TAG, "🔋 联动触发：手机端同步响应 [省电模式] 业务分支");
                }

            } catch (Exception e) {
                Log.e(TAG, "持久化本地模式状态失败", e);
            }

            // ========================================================
            // 2. 原系统级勿扰硬开关控制区（严格保留防死锁闸门逻辑）
            // ========================================================
            try {
                NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                        
                if (nm != null && nm.isNotificationPolicyAccessGranted()) {
                    
                    // 修改前拉起内部更新拦截闸门（阻止 PhoneDndService 本身由于系统状态改变而触发二次回传）
                    PhoneSyncListenerService.isInternalUpdate = true;
                    
                    // 映射手机系统的勿扰过滤器级别
                    // 如果 Mask 解析出来任何一个开关开了（说明用户需要静音环境），就让系统进入优先事项勿扰；否则全开通知
                    int systemFilterValue = (mask > 0) ? NotificationManager.INTERRUPTION_FILTER_PRIORITY : NotificationManager.INTERRUPTION_FILTER_ALL;
                    
                    nm.setInterruptionFilter(systemFilterValue);
                    Log.d(TAG, "☯️ 成功应用系统过滤级别: " + systemFilterValue);
                    
                    // 修改完成后，延迟释放拦截闸门
                    new Thread(() -> {
                        try {
                            Thread.sleep(1000); // 扩充到1秒，给一加/OPPO系统回调留足反应时间
                        } catch (InterruptedException ignored) {}
                        PhoneSyncListenerService.isInternalUpdate = false;
                        Log.d(TAG, "🔓 独立勿扰防回环拦截闸门已安全释放。");
                    }).start();
                }
            } catch (Exception e) {
                Log.e(TAG, "独立修改系统勿扰状态产生异常", e);
                PhoneSyncListenerService.isInternalUpdate = false; // 异常兜底释放
            }
        }

        stopSelf();
        return START_NOT_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }
}
