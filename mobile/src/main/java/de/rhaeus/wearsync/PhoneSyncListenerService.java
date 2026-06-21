package de.rhaeus.wearsync;

import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
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

        if ("phone".equalsIgnoreCase(sender)) return;

        Log.d(TAG, "📥 手機底層骨幹網收到信令 -> type: " + type + ", action: " + action);

        // =========================================================================
        // 🌗 模塊一：手機端勿擾模式接收塊
        // =========================================================================
        if ("dnd".equalsIgnoreCase(type)) {
            int wearDndVal = json.has("dnd_profile_value") ? json.optInt("dnd_profile_value", -1) : json.optInt("dnd_state", -1);
            if (wearDndVal == -1) return;

            isInternalUpdate = true;
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            
            // 💡 物理规律：改变勿扰状态必须有该权限，否则会 Crash。
            if (nm != null && nm.isNotificationPolicyAccessGranted()) {
                nm.setInterruptionFilter(wearDndVal); 
                Log.i(TAG, "🌗 [指令執行成功] 手機系統勿擾已變更為: " + wearDndVal);
            } else {
                Log.w(TAG, "⚠️ 手機端缺少【勿扰模式权限】，无法执行同步！请在手机设置中授予。");
            }

            new Handler(getMainLooper()).postDelayed(() -> {
                isInternalUpdate = false;
            }, 1500);
            return;
        }    
        // =========================================================================
        // ⏰ 模塊二：遠端鬧鐘反向代點
        // =========================================================================
        else if ("alarm".equalsIgnoreCase(type) || "alarm_action".equalsIgnoreCase(type)) {
            if ("DISMISS".equalsIgnoreCase(action) || "SNOOZE".equalsIgnoreCase(action)) {
                Log.d(TAG, "⏰ 收到手錶控制口令: " + action + "，直调执行！");
                PhoneAlarmManager.handleWatchCommand(this, action.toUpperCase());
            }
            return;
        }
        // =========================================================================
        // 📸 模塊三：相機前台服務直接拉起（修复后台拦截死结）
        // =========================================================================
        else if ("camera".equalsIgnoreCase(type) || "camera_control".equalsIgnoreCase(type)) {
            if ("START_CAMERA_UI".equalsIgnoreCase(action) || "START_CAMERA".equalsIgnoreCase(action)) {
                Log.d(TAG, "🚀 [相機模塊] 收到手錶端激活口令！突破後台限制，直接喚醒前台相機服務...");

                // 🎯 修复：不再使用 startActivity 跳板，直接拉起已配置好 Foreground 的 Service
                Intent cameraIntent = new Intent(this, PhoneSyncCameraService.class);
                cameraIntent.setAction(PhoneSyncCameraService.ACTION_START_CAMERA);
                androidx.core.content.ContextCompat.startForegroundService(this, cameraIntent);

            } else if ("STOP_CAMERA".equalsIgnoreCase(action) || "STOP_CAMERA_STREAM".equalsIgnoreCase(action)) {
                Log.d(TAG, "🛑 [相機模塊] 銷毀手機端相機管道");
                Intent stopIntent = new Intent(this, PhoneSyncCameraService.class);
                stopIntent.setAction(PhoneSyncCameraService.ACTION_STOP_CAMERA_STREAM);
                startService(stopIntent);
            } else if ("CAPTURE_SHUTTER".equalsIgnoreCase(action) || "TRIGGER_SHUTTER".equalsIgnoreCase(action)) {
                Log.d(TAG, "📸 [相機模塊] 觸發快門");
                Intent shutterIntent = new Intent(this, PhoneSyncCameraService.class);
                shutterIntent.setAction(PhoneSyncCameraService.ACTION_TRIGGER_SHUTTER);
                startService(shutterIntent);
            }
            return;
        }

    } catch (Exception e) {
        Log.e(TAG, "骨幹通道解析數據包失敗", e);
    }
}


    /**
     * 🛰️ 權威主動外發方法：當手機端自身的勿擾模式觸發變更時，必須由手機主動調用此方法，把 Mask 射給手錶！
     * 這樣手錶的 WearSyncListenerService 才能收到包並觸發 toggleBedtimeMode() 睡眠執行線程！
     */
    public static void sendStatusMaskToWatch(Context context, boolean dndOn, boolean vibrateOn, boolean sleepLinkOn, boolean powerSaveLinkOn) {
        if (isInternalUpdate) {
            Log.d(TAG, "⚠️ 處於內部更新鎖定中，攔截本次外發防止死循環。");
            return;
        }

        new Thread(() -> {
            try {
                // 依照手錶端舊代碼位運算規則組裝
                int mask = 0;
                if (dndOn) mask |= 0x01;             // Bit 0
                if (vibrateOn) mask |= 0x02;         // Bit 1
                if (sleepLinkOn) mask |= 0x04;       // Bit 2
                if (powerSaveLinkOn) mask |= 0x08;   // Bit 3

                JSONObject json = new JSONObject();
                json.put("sender", "phone"); // 告訴手錶這不是手錶自己發的包
                json.put("type", "status_mask");
                json.put("status_mask", mask);

                byte[] payload = json.toString().getBytes(StandardCharsets.UTF_8);
                List<Node> nodes = Tasks.await(Wearable.getNodeClient(context).getConnectedNodes());
                if (nodes != null) {
                    for (Node n : nodes) {
                        Tasks.await(Wearable.getMessageClient(context).sendMessage(n.getId(), UNIVERSAL_SYNC_PATH, payload));
                        Log.d(TAG, "🚀 [手機勿擾主動外發] 成功向手錶投遞 Mask: " + mask);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "🔴 手機向手錶同步狀態掩碼失敗", e);
            }
        }).start();
    }
}
