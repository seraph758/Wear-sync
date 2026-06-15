package de.rhaeus.wearsync;

import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;
import androidx.annotation.Nullable;

/**
 * 独立解耦的勿扰处理服务。
 * 专门负责跟手机系统的 NotificationManager 进行交互，彻底洗清 PhoneListener 的耦合负担。
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
                    nm.setInterruptionFilter(dndProfileValue);
                    Log.d(TAG, "☯️ 成功应用目标勿扰状态值到手机系统: " + dndProfileValue);
                } else {
                    Log.w(TAG, "⚠️ 手机端未被授予勿扰读写访问权限，无法直接修改系统状态。");
                }
            } catch (Exception e) {
                Log.e(TAG, "独立修改系统勿扰状态产生异常", e);
            }
        }

        stopSelf();
        return START_NOT_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }
}