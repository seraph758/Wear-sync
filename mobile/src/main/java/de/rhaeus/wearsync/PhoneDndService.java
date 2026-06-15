package de.rhaeus.wearsync;

import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;
import androidx.annotation.Nullable;

/**
 * 独立解耦的勿扰处理服务
 * 完美补齐：在真正去修改手机系统勿扰值的一瞬间，将 PhoneSyncListenerService.isInternalUpdate 设为 true，
 * 修改完毕后通过轻微延时设为 false，确保一加手机绝不会发生双端勿扰死循环同步卡死。
 */
public class PhoneDndService extends Service {
    private static final String TAG = "WearSync_PhoneDnd";

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        int dndProfileValue = intent.getIntExtra("dnd_profile_value", -1);
        Log.d(TAG, "☯️ 独立勿扰服务启动，收到目标勿扰状态值: " + dndProfileValue);

        if (dndProfileValue != -1) {
            try {
                NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                        
                if (nm != null && nm.isNotificationPolicyAccessGranted()) {
                    
                    // 🎯 防止自循环核心：修改前拉起内部更新拦截闸门
                    PhoneSyncListenerService.isInternalUpdate = true;
                    
                    nm.setInterruptionFilter(dndProfileValue);
                    Log.d(TAG, "☯️ 成功应用目标勿扰状态值到手机系统: " + dndProfileValue);
                    
                    // 修改完成后，通过轻微延时放开闸门，给手机系统反应回调的时间，彻底防止自锁
                    new Thread(() -> {
                        try {
                            Thread.sleep(800);
                        } catch (InterruptedException ignored) {}
                        PhoneSyncListenerService.isInternalUpdate = false;
                        Log.d(TAG, "🔓 内部更新拦截闸门已安全释放。");
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
