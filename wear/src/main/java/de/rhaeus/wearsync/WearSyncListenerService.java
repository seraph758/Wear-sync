package de.rhaeus.wearsync;

import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;
import androidx.annotation.NonNull;

import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.ChannelClient;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.Wearable;
import com.google.android.gms.wearable.WearableListenerService;
import org.json.JSONObject;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public class WearSyncListenerService extends WearableListenerService {
    private static final String TAG = "WearSync_WearListener";
    private static final String UNIVERSAL_SYNC_PATH = "/wear-universal-sync";
    private static final String CAMERA_PREVIEW_STREAM_PATH = "/camera-preview-stream";

    // 🎯 修复 2：删除了这里没用的本地变量，后续全局调用 WearSyncNotificationService 的锁

    @Override
    public void onMessageReceived(@NonNull MessageEvent messageEvent) {
        if (!UNIVERSAL_SYNC_PATH.equalsIgnoreCase(messageEvent.getPath())) return;
        byte[] data = messageEvent.getData();
        if (data == null) return;

        try {
            String jsonStr = new String(data, StandardCharsets.UTF_8);
            JSONObject json = new JSONObject(jsonStr);
            String sender = json.optString("sender", "");
            String type = json.optString("type", "");
            String action = json.optString("action", "");

            if ("wear".equalsIgnoreCase(sender)) return;

            // =========================================================================
            // 🌗 模块一：勿扰模式单向对齐块
            // =========================================================================
            if ("status_mask".equalsIgnoreCase(type) || json.has("status_mask")) {
                int statusMask = json.optInt("status_mask", -1);
                if (statusMask != -1) {
                    final int finalMask = statusMask;
                    Log.d(TAG, "📥 [手錶端勿擾解析] 收到手機權威 Mask: " + finalMask);

                    // 🎯 致命修复点：直接把锁下在发信人(NotificationService)身上，绝杀双向死循环大风暴！
                    WearSyncNotificationService.isInternalUpdate = true; 

                    boolean targetDndEnabled = (finalMask & 0x01) != 0;       
                    boolean isVibrateSwitchOn = (finalMask & 0x02) != 0;      
                    boolean isSleepLinkageOpen = (finalMask & 0x04) != 0;     
                    boolean isPowerSaveLinkageOpen = (finalMask & 0x08) != 0; 

                    NotificationManager mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                    boolean hasDndChanged = false; 
                    
                    if (mNotificationManager != null) {
                        int currentWatchDndFilter = mNotificationManager.getCurrentInterruptionFilter();
                        boolean isWatchDndOn = (currentWatchDndFilter > 1);
                        if (targetDndEnabled != isWatchDndOn) {
                            mNotificationManager.setInterruptionFilter(targetDndEnabled ? 3 : 1);
                            hasDndChanged = true;
                            Log.i(TAG, "🌗 [勿擾同步] 手錶本地勿擾已變更為: " + targetDndEnabled);
                        }
                    }

                    if (isVibrateSwitchOn) {
                        vibrate(); 
                    } else {
                        Log.w(TAG, "🔇 [勿擾震動子開關=關閉] 攔截並封鎖震動。");
                    }

                    if (isSleepLinkageOpen && hasDndChanged) {
                        toggleBedtimeMode(); 
                    }

                    if (isPowerSaveLinkageOpen) {
                        Settings.Global.putInt(getContentResolver(), "low_power", targetDndEnabled ? 1 : 0);
                        sendBroadcast(new Intent(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED));
                    }

                    // 延迟解锁，给手表系统广播彻底消散留出反应时间
                    new Handler(getMainLooper()).postDelayed(() -> {
                        WearSyncNotificationService.isInternalUpdate = false;
                        Log.d(TAG, "🔓 [解鎖] 手錶防回传死循环锁已安全釋放。");
                    }, 1500);
                }
                return;
            }

            // =========================================================================
            // ⏰ 模塊二：遠端鬧鐘控制鏈
            // =========================================================================
            if ("alarm".equalsIgnoreCase(type)) {
                if ("START_ALARM_UI".equalsIgnoreCase(action)) {
                    String alarmLabel = json.optString("alarm_label", "鬧鐘響鈴中");
                    String alarmTime = json.optString("alarm_time", "");

                    PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                    if (pm != null) {
                        PowerManager.WakeLock wakeLock = pm.newWakeLock(
                                PowerManager.FULL_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP | PowerManager.ON_AFTER_RELEASE, 
                                "wearsync:AlarmScreenWakeLock"
                        );
                        wakeLock.acquire(3000L);
                    }

                    Intent alarmIntent = new Intent(this, WearAlarmActivity.class);
                    alarmIntent.putExtra("EXTRA_ALARM_LABEL", alarmLabel);
                    alarmIntent.putExtra("EXTRA_ALARM_TIME", alarmTime);
                    alarmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    startActivity(alarmIntent);
                } 
                else if ("FORCE_STOP_WEAR_ALARM".equalsIgnoreCase(action)) {
                    sendBroadcast(new Intent(WearAlarmActivity.ACTION_INTERNAL_FORCE_STOP));
                }
                return; 
            }

            // =========================================================================
            // 📸 模塊三：相機主控協議鏈
            // =========================================================================
            if ("camera_control".equalsIgnoreCase(type) || "camera".equalsIgnoreCase(type)) {
                if ("START_CAMERA".equalsIgnoreCase(action)) {
                    int rotationDegrees = json.optInt("rotation_degrees", 0);
                    Intent camIntent = new Intent(this, WearCameraActivity.class);
                    camIntent.putExtra("rotation_degrees", rotationDegrees);
                    camIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                    startActivity(camIntent);
                } 
                else if ("FORCE_QUIT_CAMERA".equalsIgnoreCase(action) || "STOP_CAMERA_ACTIVE".equalsIgnoreCase(action)) {
                    sendBroadcast(new Intent("de.rhaeus.wearsync.ACTION_KILL_WEAR_CAMERA"));
                    WearCameraActivity.forceQuitInstance();
                }
                return; 
            }

        } catch (Exception e) {
            Log.e(TAG, "手錶骨幹路由分發異常", e);
        }
    }

    private void toggleBedtimeMode() {
        WearSyncAccessService serv = WearSyncAccessService.getSharedInstance();
        if (serv == null) {
            new Handler(getMainLooper()).post(() -> Toast.makeText(getApplicationContext(), "无障碍服务未连接", Toast.LENGTH_LONG).show());
            return;
        }

        PowerManager pm = (PowerManager) getApplicationContext().getSystemService(Context.POWER_SERVICE);
        PowerManager.WakeLock wakeLock = pm.newWakeLock(PowerManager.FULL_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP , "dndsync:MyWakeLock");
        wakeLock.acquire(2*60*1000L); // 2 分钟安全期

        new Handler(getMainLooper()).post(() -> Toast.makeText(getApplicationContext(), "正在同步睡眠模式...", Toast.LENGTH_SHORT).show());

        try {
            Thread.sleep(1000);
            serv.swipeDown();
            Thread.sleep(1000);
            serv.clickIcon1_2();
            Thread.sleep(1000);
            serv.goBack();
        } catch (Exception e) {
            Log.e(TAG, "无障碍操作中断", e);
        } finally {
            // 🎯 修复 3：补齐 finally 强制释放 Wakelock，避免手表长亮烧电
            if (wakeLock.isHeld()) wakeLock.release();
        }
    }

    private void vibrate() {
        Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        if (v != null && v.hasVibrator()) {
            v.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE));
        }
    }

   @Override
    public void onChannelOpened(@NonNull ChannelClient.Channel channel) {
        if (!CAMERA_PREVIEW_STREAM_PATH.equalsIgnoreCase(channel.getPath())) return;
    
        new Thread(() -> {
            try (InputStream inputStream = Tasks.await(Wearable.getChannelClient(this).getInputStream(channel))) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    WearCameraActivity activity = WearCameraActivity.sActivityRef.get();
                    if (activity != null) {
                        activity.feedH264Data(buffer, bytesRead);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "H.264 通道读取中断", e);
            }
        }).start();
    }
}
