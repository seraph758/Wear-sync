package de.rhaeus.wearsync;

import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.WearableListenerService;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;

public class PhoneSyncListenerService extends WearableListenerService {
    private static final String TAG = "WearSync_PhoneListener";
    
    // 🔒 核心防护：全局内部更新锁，彻底避免双向状态回旋死循环
    public static boolean isInternalUpdate = false; 

    @Override
    public void onMessageReceived(MessageEvent messageEvent) {
        if (!"/wear-universal-sync".equals(messageEvent.getPath())) {
            super.onMessageReceived(messageEvent);
            return;
        }

        try {
            String jsonStr = new String(messageEvent.getData(), StandardCharsets.UTF_8);
            JSONObject json = new JSONObject(jsonStr);
            String type = json.optString("type", "");
            String action = json.optString("action", "");

            Log.d(TAG, "📥 手機底層骨幹網收到信令 -> type: " + type + ", action: " + action);

            // =========================================================================
            // 🌗 模塊一：手機端勿擾模式接收塊（严格作为手表端旧代码机制的镜像参照物）
            // =========================================================================
            if ("status_mask".equalsIgnoreCase(type) || json.has("status_mask")) {
                int statusMask = json.optInt("status_mask", -1);
                if (statusMask != -1) {
                    Log.d(TAG, "📥 [手機端勿擾權威對齊] 收到 Mask 狀態包: " + statusMask);

                    isInternalUpdate = true; // 鎖定防回旋死循環

                    // 🎯 核心映射：依照手錶端舊代碼位運算規則，逆向提取 Bit 0（勿擾總開關）
                    // 協議約定：Bit 0=總勿擾，Bit 1=震動，Bit 2=睡眠無障礙，Bit 3=省電模式
                    boolean targetDndEnabled = (statusMask & 0x01) != 0; 

                    NotificationManager mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                    if (mNotificationManager != null) {
                        int currentInterruptionFilter = mNotificationManager.getCurrentInterruptionFilter();
                        boolean isPhoneDndOn = (currentInterruptionFilter > 1); // 2,3,4 均为开启勿扰
                        
                        // 只有當手機本地與目標狀態不一致時才觸發實體變更
                        if (targetDndEnabled != isPhoneDndOn) {
                            // 3 代表 INTERRUPTION_FILTER_NONE/PRIORITY，1 代表 INTERRUPTION_FILTER_ALL(不攔截)
                            mNotificationManager.setInterruptionFilter(targetDndEnabled ? 3 : 1);
                            Log.i(TAG, "🌗 [手機勿擾實體同步] 手機端勿擾已成功對齊變更為: " + targetDndEnabled);
                        }
                    }
                    
                    isInternalUpdate = false; // 解鎖
                }
                return;
            }

            // =========================================================================
            // ⏰ 模塊二：遠端鬧鐘控制鏈（完美對齊雙端控制閉環與反向代點）
            // =========================================================================
            else if ("alarm".equalsIgnoreCase(type) || "alarm_action".equalsIgnoreCase(type)) {
                if ("DISMISS".equalsIgnoreCase(action) || "SNOOZE".equalsIgnoreCase(action)) {
                    Log.d(TAG, "⏰ [鬧鐘模塊] 收到手錶端反向控制口令: " + action + "，交由 PhoneAlarmManager 物理執行...");
                    PhoneAlarmManager.handleWatchCommand(this, action);
                }
                return;
            } 

            // =========================================================================
            // 📸 模塊三：相機喚醒與釋放協議（突破 Android 后台拉起限制，完美支持当前命名）
            // =========================================================================
            else if ("camera".equalsIgnoreCase(type) || "camera_control".equalsIgnoreCase(type)) {
                if ("START_CAMERA_UI".equalsIgnoreCase(action) || "START_CAMERA".equalsIgnoreCase(action)) {
                    Log.d(TAG, "🚀 [相機模塊] 收到手錶端激活口令！直接安全喚醒手機端 PhoneSyncCameraService...");
            
                    try {
                        Intent cameraServiceIntent = new Intent(this, PhoneSyncCameraService.class);
                        // 🎯 核心安全加固：高版本 Android 如果在後台啟動服務，必須用 startForegroundService
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            startForegroundService(cameraServiceIntent);
                        } else {
                            startService(cameraServiceIntent);
                        }
                        Log.i(TAG, "🟢 [網關物理執行] PhoneSyncCameraService 後台服務已成功發出拉起指令");
                    } catch (Exception e) {
                        Log.e(TAG, "🔴 跨進程後台拉起手機相機服務遭遇強力封殺", e);
                    }
            
                } else if ("STOP_CAMERA".equalsIgnoreCase(action) || "STOP_CAMERA_STREAM".equalsIgnoreCase(action)) {
                    Log.d(TAG, "🛑 [相機模塊] 收到手錶斷開要求，下發本地廣播釋放手機端相機服務");
                    sendBroadcast(new Intent("de.rhaeus.wearsync.ACTION_STOP_CAMERA_STREAM"));
                }
                return;
            }

        } catch (Exception e) {
            Log.e(TAG, "骨幹通道解析數據包災難性失敗", e);
        }
    }
}
