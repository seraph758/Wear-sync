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

            // 🌟 核心对齐：手表发过来的时候自称 sender="wear"，如果是手机自己回旋的包则直接过滤
            if ("phone".equalsIgnoreCase(sender)) return;

            Log.d(TAG, "📥 手機底層骨幹網收到信令 -> type: " + type + ", action: " + action + ", sender: " + sender);

            // =========================================================================
            // 🌗 模塊一：手機端勿擾模式接收塊（兼容 Mask 包與手錶反向發送的 dnd 協議）
            // =========================================================================
            // 🎯 请替换 PhoneSyncListenerService.java 中接收 "dnd" 类型协议的代码块：
            if ("dnd".equalsIgnoreCase(type)) {
            int wearDndVal = json.has("dnd_profile_value") ? json.optInt("dnd_profile_value", -1) : json.optInt("dnd_state", -1);
            Log.d(TAG, "📥 [骨干网接收] 收到手表反向勿扰指令，解析值: " + wearDndVal);
            if (wearDndVal == -1) return;
        
            isInternalUpdate = true;
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null && nm.isNotificationPolicyAccessGranted()) {
                // 🎯 修正变量名，直接同步手表的硬过滤器值
                nm.setInterruptionFilter(wearDndVal); 
                Log.i(TAG, "🌗 [指令执行成功] 手机系统勿扰已变更为: " + wearDndVal);
            }
        
            new Handler(getMainLooper()).postDelayed(() -> {
                isInternalUpdate = false;
            }, 1500);
            return;
        }    
             // =========================================================================
            // ⏰ 模塊二：遠端鬧鐘反向代點控制鏈（直调 Manager 斩断空放广播）
            // =========================================================================
            else if ("alarm".equalsIgnoreCase(type) || "alarm_action".equalsIgnoreCase(type)) {
                if ("DISMISS".equalsIgnoreCase(action) || "SNOOZE".equalsIgnoreCase(action)) {
                    Log.d(TAG, "⏰ [鬧鐘模塊] 收到手錶端反向控制口令: " + action + "，直调大脑执行物理代点！");
            
                    // 🎯 彻底修复：干掉无用的 sendBroadcast 广播，直接调用处理函数，把上下文和口令传进去
                    PhoneAlarmManager.handleWatchCommand(this, action.toUpperCase());
                }
                return;
            }
            // =========================================================================
            // 📸 模塊三：相機無障礙前台拉起協議（解決後台拉起被 Android 封殺的死結）
            // =========================================================================
            else if ("camera".equalsIgnoreCase(type) || "camera_control".equalsIgnoreCase(type)) {
                if ("START_CAMERA_UI".equalsIgnoreCase(action) || "START_CAMERA".equalsIgnoreCase(action)) {
                    Log.d(TAG, "🚀 [相機模塊] 收到手錶端激活口令！安全繞過 Android 後台限制，引導至前台拉起...");
            
                    // 🎯 核心修復：高版本 Android 嚴禁後台啟動 Service，必須利用 MainActivity 作為跳板安全喚醒前台相機
                    Intent launchIntent = new Intent();
                    launchIntent.setClassName(getPackageName(), "de.rhaeus.wearsync.PhoneSyncMainActivity");
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK 
                                        | Intent.FLAG_ACTIVITY_CLEAR_TOP 
                                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    launchIntent.putExtra("INTERNAL_CMD", "FORCE_LAUNCH_CAMERA_SERVICE");
                    startActivity(launchIntent);
            
                } else if ("STOP_CAMERA".equalsIgnoreCase(action) || "STOP_CAMERA_STREAM".equalsIgnoreCase(action)) {
                    Log.d(TAG, "🛑 [相機模塊] 下發本地廣播，全數銷毀手機端相機物理管道");
                    sendBroadcast(new Intent("de.rhaeus.wearsync.ACTION_STOP_CAMERA_STREAM"));
                }
                return;
            }

        } catch (Exception e) {
            Log.e(TAG, "骨幹通道解析數據包災難性失敗", e);
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
