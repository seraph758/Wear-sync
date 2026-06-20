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

import java.io.EOFException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public class WearSyncListenerService extends WearableListenerService {
    private static final String TAG = "WearSync_WearListener";
    private static final String UNIVERSAL_SYNC_PATH = "/wear-universal-sync";
    private static final String CAMERA_PREVIEW_STREAM_PATH = "/camera-preview-stream";

    // 🔒 核心防護：全局內部更新鎖，徹底避免雙向狀態回旋死循環
    public static boolean isInternalUpdate = false;

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
            // 🌗 模块一：勿扰模式单向对齐块（手机发 MASK -> 手表对齐本地勿扰）
            // =========================================================================
            if ("status_mask".equalsIgnoreCase(type) || json.has("status_mask")) {
                int statusMask = json.optInt("status_mask", -1);
                if (statusMask != -1) {
                    final int finalMask = statusMask;
                    Log.d(TAG, "📥 [手錶端勿擾解析] 收到手機權威 Mask: " + finalMask);

                    isInternalUpdate = true; // 鎖定防回傳死循環

                    // 解析出 1 個總開關 + 3 個手錶專屬勿擾子開關
                    boolean targetDndEnabled = (finalMask & 0x01) != 0;       // Bit 0: 勿擾總開關
                    boolean isVibrateSwitchOn = (finalMask & 0x02) != 0;      // Bit 1: 勿擾傳輸震動子開關
                    boolean isSleepLinkageOpen = (finalMask & 0x04) != 0;     // Bit 2: 睡眠模式(無障礙)子開關
                    boolean isPowerSaveLinkageOpen = (finalMask & 0x08) != 0; // Bit 3: 省電模式子開關

                    // 🌗 【總開關執行】手錶本地勿擾狀態跟隨手機（一起開、一起關）
                    NotificationManager mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                    boolean hasDndChanged = false; // 嚴格標記勿擾狀態是否有實質變更
                    
                    if (mNotificationManager != null) {
                        int currentWatchDndFilter = mNotificationManager.getCurrentInterruptionFilter();
                        boolean isWatchDndOn = (currentWatchDndFilter > 1);
                        if (targetDndEnabled != isWatchDndOn) {
                            mNotificationManager.setInterruptionFilter(targetDndEnabled ? 3 : 1);
                            hasDndChanged = true;
                            Log.i(TAG, "🌗 [勿擾同步] 手錶本地勿擾已變更為: " + targetDndEnabled);
                        }
                    }

                    // 📳 【子開關 1 執行】震動開關邏輯（採用舊代碼 50ms 物理震動方式）
                    if (isVibrateSwitchOn) {
                        Log.d(TAG, "📳 [勿擾震動子開關=開啟] 觸發 50ms 物理震動提示");
                        vibrate(); 
                    } else {
                        Log.w(TAG, "🔇 [勿擾震動子開關=關閉] 攔截並封鎖震動，保持絕對靜默。");
                    }

                    // 🛌 【子開關 2 執行】睡眠模式邏輯（完全重用舊代碼安全經驗的線程挂起機制）
                    if (isSleepLinkageOpen && hasDndChanged) {
                        Log.d(TAG, "🛌 [睡眠模式連動激活] 狀態變更，調用本地舊代碼無障礙處理流程...");
                        toggleBedtimeMode(); // 🎯 內部會透過 WearSyncAccessService 執行動作
                    }

                    // 🔋 【子開關 3 執行】省電模式邏輯
                    if (isPowerSaveLinkageOpen) {
                        Log.d(TAG, "🔋 [省電模式連動激活] 手錶省電與勿擾捆綁變更 -> 實際省電狀態=" + targetDndEnabled);
                        Settings.Global.putInt(getContentResolver(), "low_power", targetDndEnabled ? 1 : 0);
                        sendBroadcast(new Intent(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED));
                    }

                    // 釋放全局鎖
                    new Handler(getMainLooper()).postDelayed(() -> {
                        isInternalUpdate = false;
                        Log.d(TAG, "🔒 [解鎖] 手錶內部勿擾狀態鎖已釋放。");
                    }, 1500);
                }
                return;
            }

            // =========================================================================
            // ⏰ 模塊二：遠端鬧鐘控制鏈（完美支持全屏提示注入與核查再次拉起機制）
            // =========================================================================
            if ("alarm".equalsIgnoreCase(type)) {
                Log.d(TAG, "📥 [鬧鐘模塊] 收到控制動作: " + action);
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

                    alarmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK 
                                        | Intent.FLAG_ACTIVITY_CLEAR_TOP 
                                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    startActivity(alarmIntent);
                } 
                else if ("FORCE_STOP_WEAR_ALARM".equalsIgnoreCase(action)) {
                    Intent stopBroadcast = new Intent(WearAlarmActivity.ACTION_INTERNAL_FORCE_STOP);
                    sendBroadcast(stopBroadcast);
                }
                return; 
            }

            // =========================================================================
            // 📸 模塊三：相機主控協議鏈（完全獨立）
            // =========================================================================
            if ("camera_control".equalsIgnoreCase(type) || "camera".equalsIgnoreCase(type)) {
                Log.d(TAG, "📥 [相機模塊] 收到主控動作: " + action);
                if ("START_CAMERA".equalsIgnoreCase(action)) {
                    int rotationDegrees = json.optInt("rotation_degrees", 0);
                    Intent camIntent = new Intent(this, WearCameraActivity.class);
                    camIntent.putExtra("rotation_degrees", rotationDegrees);
                    camIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                    startActivity(camIntent);
                } 
                else if ("FORCE_QUIT_CAMERA".equalsIgnoreCase(action) || "STOP_CAMERA_ACTIVE".equalsIgnoreCase(action)) {
                    Intent killIntent = new Intent("de.rhaeus.wearsync.ACTION_KILL_WEAR_CAMERA");
                    sendBroadcast(killIntent);
                    WearCameraActivity.forceQuitInstance();
                }
                return; 
            }

        } catch (Exception e) {
            Log.e(TAG, "手錶骨幹路由分發異常", e);
        }
    }

    // =========================================================================
    // 🛠️ 專屬旧代码挂起核心线程（无障碍睡眠模拟物理点击，绝不可修改的黄金逻辑）
    // =========================================================================
    
    private void toggleBedtimeMode() {
        // 🎯 這裡精確調用了 WearSyncAccessService.java 暴露出來的單例
        WearSyncAccessService serv = WearSyncAccessService.getSharedInstance();
        if (serv == null) {
            Log.d(TAG, "accessibility not connected");
            Handler mHandler = new Handler(getMainLooper());
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(getApplicationContext(), "无障碍服务未连接", Toast.LENGTH_LONG).show();
                }
            });
            return;
        }

        Log.d(TAG, "accessibility connected. Perform toggle.");
        // 点亮屏幕并获取唤醒锁
        PowerManager pm = (PowerManager) getApplicationContext().getSystemService(Context.POWER_SERVICE);
        PowerManager.WakeLock wakeLock = pm.newWakeLock(PowerManager.FULL_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP , "dndsync:MyWakeLock");
        wakeLock.acquire(2*60*1000L /*2 minutes*/);

        Handler mHandler = new Handler(getMainLooper());
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(getApplicationContext(), "正在同步睡眠模式...", Toast.LENGTH_SHORT).show();
            }
        });

        // 🔒 延迟：确保屏幕彻底唤醒并就绪
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        // 下拉快捷面板
        serv.swipeDown();

        // 🔒 延迟：等待下拉动画播放完毕
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        // 核心动作：点击第一排中间（第 2 个）的图标
        serv.clickIcon1_2();

        // 🔒 延迟：等待点击动作生效
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        // 关闭快捷面板并返回
        serv.goBack();

        wakeLock.release();
    }

    private void vibrate() {
        Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        if (v != null && v.hasVibrator()) {
            v.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE));
        }
    }

    // =========================================================================
    // 📸 相机大数据底层 JPEG 高频接收与缓冲管道
    // =========================================================================
   @Override
    public void onChannelOpened(@NonNull ChannelClient.Channel channel) {
        if (!CAMERA_PREVIEW_STREAM_PATH.equalsIgnoreCase(channel.getPath())) return;
    
        new Thread(() -> {
            try (InputStream inputStream = Tasks.await(Wearable.getChannelClient(this).getInputStream(channel))) {
                byte[] buffer = new byte[8192]; // 一次读取一小块
                int bytesRead;
                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    WearCameraActivity activity = WearCameraActivity.sActivityRef.get();
                    if (activity != null) {
                        // 直接把拿到的二进制块塞给解码器，解码器会自动寻找帧边界
                        activity.feedH264Data(buffer, bytesRead);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "H.264 通道读取中断", e);
            }
        }).start();
    }

}
