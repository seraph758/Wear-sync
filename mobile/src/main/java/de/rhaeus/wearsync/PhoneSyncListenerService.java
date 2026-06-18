package de.rhaeus.wearsync;

import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.PowerManager;
import android.util.Log;
import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;
import com.google.android.gms.wearable.WearableListenerService;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class PhoneSyncListenerService extends WearableListenerService {
    private static final String TAG = "WearSync_PhoneListener";
    private static final String UNIVERSAL_SYNC_PATH = "/wear-universal-sync";
    
    // 🔒 核心防护：全局内部更新锁，彻底避免双向状态回旋死循环
    public static boolean isInternalUpdate = false; 

    @Override
    public void onMessageReceived(MessageEvent messageEvent) {
        if (!UNIVERSAL_SYNC_PATH.equals(messageEvent.getPath())) {
            super.onMessageReceived(messageEvent);
            return;
        }

        try {
            String jsonStr = new String(messageEvent.getData(), StandardCharsets.UTF_8);
            JSONObject json = new JSONObject(jsonStr);
            String sender = json.optString("sender", "");
            String type = json.optString("type", "");
            String action = json.optString("action", "");

            // 过滤自己发出的包，防止回旋
            if ("phone".equalsIgnoreCase(sender)) return;

            Log.d(TAG, "📥 手機底層骨幹網收到信令 -> type: " + type + ", action: " + action);

            // =========================================================================
            // 🌗 模塊一：手機端勿擾模式接收塊（严格作为手表端旧代码机制的镜像参照物）
            // =========================================================================
            if ("status_mask".equalsIgnoreCase(type) || json.has("status_mask")) {
                int statusMask = json.optInt("status_mask", -1);
                if (statusMask != -1) {
                    Log.d(TAG, "📥 [手機端勿擾權威對齊] 收到手錶端反向傳回的 Mask 狀態包: " + statusMask);

                    isInternalUpdate = true; // 鎖定防回旋死循環

                    // 🎯 逆向提取 Bit 0（勿擾總開關）
                    boolean targetDndEnabled = (statusMask & 0x01) != 0; 

                    NotificationManager mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                    if (mNotificationManager != null) {
                        int currentInterruptionFilter = mNotificationManager.getCurrentInterruptionFilter();
                        boolean isPhoneDndOn = (currentInterruptionFilter > 1); // 2,3,4 均为开启勿扰
                        
                        if (targetDndEnabled != isPhoneDndOn) {
                            mNotificationManager.setInterruptionFilter(targetDndEnabled ? 3 : 1);
                            Log.i(TAG, "🌗 [手機勿擾實體同步] 手機端勿擾已成功跟隨手錶變更為: " + targetDndEnabled);
                        }
                    }
                    
                    // 延迟解锁，确保状态变更稳定
                    new Handler(getMainLooper()).postDelayed(() -> {
                        isInternalUpdate = false;
                        Log.d(TAG, "🔒 [解鎖] 手機內部勿擾狀態鎖已釋放。");
                    }, 1500);
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
            // 📸 模塊三：相機喚醒與釋放協議
            // =========================================================================
            else if ("camera".equalsIgnoreCase(type) || "camera_control".equalsIgnoreCase(type)) {
                if ("START_CAMERA_UI".equalsIgnoreCase(action) || "START_CAMERA".equalsIgnoreCase(action)) {
                    Log.d(TAG, "🚀 [相機模塊] 收到手錶端激活口令！直接安全喚醒手機端 PhoneSyncCameraService...");
            
                    try {
                        Intent cameraServiceIntent = new Intent(this, PhoneSyncCameraService.class);
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

    /**
     * 🛰️ 权威主动外发方法：当手机端检测到系统勿扰、睡眠、省电发生变更时，调用此方法组装 Mask 发送给手表
     * 完美复刻手表端解析规则：Bit 0=总勿扰, Bit 1=震动提示, Bit 2=睡眠联动, Bit 3=省电联动
     */
    public static void sendStatusMaskToWatch(Context context, boolean dndOn, boolean vibrateOn, boolean sleepLinkOn, boolean powerSaveLinkOn) {
        if (isInternalUpdate) {
            Log.d(TAG, "⚠️ 處於內部更新鎖定狀態，攔截本次外發，防止回旋死循環。");
            return;
        }

        new Thread(() -> {
            try {
                // 🎯 核心位運算：完美與手錶舊代碼解析器互為鏡像
                int mask = 0;
                if (dndOn) mask |= 0x01;             // Bit 0
                if (vibrateOn) mask |= 0x02;         // Bit 1
                if (sleepLinkOn) mask |= 0x04;       // Bit 2
                if (powerSaveLinkOn) mask |= 0x08;   // Bit 3

                JSONObject json = new JSONObject();
                json.put("sender", "phone");
                json.put("type", "status_mask");
                json.put("status_mask", mask);

                byte[] payload = json.toString().getBytes(StandardCharsets.UTF_8);
                List<Node> nodes = Tasks.await(Wearable.getNodeClient(context).getConnectedNodes());
                if (nodes != null) {
                    for (Node n : nodes) {
                        Tasks.await(Wearable.getMessageClient(context).sendMessage(n.getId(), UNIVERSAL_SYNC_PATH, payload));
                        Log.d(TAG, "🚀 [手機狀態外發] 成功向手錶投遞權威 Mask: " + mask + " (JSON: " + json.toString() + ")");
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "🔴 手機向手錶同步狀態掩碼失敗", e);
            }
        }).start();
    }
}
