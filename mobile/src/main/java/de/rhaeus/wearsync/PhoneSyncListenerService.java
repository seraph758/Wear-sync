package de.rhaeus.wearsync;

import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import androidx.annotation.NonNull;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.WearableListenerService;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;

public class PhoneSyncListenerService extends WearableListenerService {
    private static final String TAG = "WearSync_PhoneListener";
    private static final String UNIVERSAL_SYNC_PATH = "/wear-universal-sync";
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

            if ("phone".equalsIgnoreCase(sender)) return; // 過濾本地回環

            // ================= 1️⃣ 勿擾同步模塊 =================
            if ("dnd".equalsIgnoreCase(type)) {
                int dndVal = json.optInt("dnd_profile_value", -1);
                if (dndVal != -1) {
                    NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                    if (nm != null && nm.isNotificationPolicyAccessGranted()) {
                        isInternalUpdate = true;
                        nm.setInterruptionFilter(dndVal);
                        Log.d(TAG, "🌙 [同步成功] 已依手錶同步變更手機勿擾狀態: " + dndVal);
                        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> isInternalUpdate = false, 1000);
                    }
                }
                return;
            }

            // ================= 2️⃣ 鬧鐘延後點擊模塊 =================
            if ("alarm_action".equalsIgnoreCase(type)) {
                if ("SNOOZE_ALARM".equalsIgnoreCase(action)) {
                    Log.d(TAG, "⏰ [收到指令] 手錶觸發了「延後手機鬧鐘」");
                    if (PhoneSyncNotificationService.snoozePendingIntent != null) {
                        PhoneSyncNotificationService.snoozePendingIntent.send();
                        Log.d(TAG, "🎯 [自動化成功] 已代用戶點擊手機通知欄延後按鈕");
                    } else {
                        Log.w(TAG, "⚠️ 觸發點击失敗：手機端暫未捕獲到合法的延後 PendingIntent");
                    }
                }
                return;
            }

            // ================= 🎯 3️⃣ 核心新增：精準對接手錶端 WearAlarmActivity 的按鈕事件 =================
            if ("alarm_control".equalsIgnoreCase(type)) {
                Log.d(TAG, "⏰ [鬧鐘控制] 收到手錶端 WearAlarmActivity 點擊事件 Action: " + action);
                
                if ("DISMISS".equalsIgnoreCase(action)) {
                    Log.d(TAG, "⏰ 檢測到手錶端點擊了【關閉鬧鐘】，正在向手機本地發送關閉廣播...");
                    try {
                        // 投遞結束廣播給手機本地鬧鐘
                        Intent stopPhoneAlarmIntent = new Intent("de.rhaeus.wearsync.FORCE_STOP_ALARM_UI");
                        sendBroadcast(stopPhoneAlarmIntent);
                        Log.d(TAG, "✅ 已成功在手機本地投遞 FORCE_STOP_ALARM_UI 廣播");
                    } catch (Exception e) {
                        Log.e(TAG, "手機本地發送鬧鐘終止廣播失敗", e);
                    }
                } 
                else if ("SNOOZE".equalsIgnoreCase(action)) {
                    Log.d(TAG, "⏰ 檢測到手錶端點擊了【延後鬧鐘】，正在調用通知欄 PendingIntent...");
                    try {
                        if (PhoneSyncNotificationService.snoozePendingIntent != null) {
                            PhoneSyncNotificationService.snoozePendingIntent.send();
                            Log.d(TAG, "🎯 [自動化成功] 已成功代點手機端的延後 PendingIntent");
                        } else {
                            Log.w(TAG, "⚠️ 延後失敗：手機端未捕獲到合法的 PendingIntent，發射保底廣播");
                            sendBroadcast(new Intent("de.rhaeus.wearsync.FORCE_SNOOZE_ALARM"));
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "執行延後動作失敗", e);
                    }
                }
                return;
            }

            // ================= 4️⃣ 相機模塊 =================
            if ("camera_control".equalsIgnoreCase(type)) {
                Log.d(TAG, "📸 [中轉接收] 收到相機動作 Action: " + action);

                if ("START_CAMERA".equalsIgnoreCase(action)) {
                    Log.d(TAG, "🚀 [穿透啟動] 正在喚醒手機前台 Activity 以獲取前台啟動豁免權...");

                    Intent intent = new Intent(this, PhoneSyncMainActivity.class);
                    intent.setAction("ACTION_START_CAMERA_FLOW");
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK 
                                  | Intent.FLAG_ACTIVITY_CLEAR_TOP 
                                  | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    startActivity(intent);

                    Intent svc = new Intent(this, PhoneSyncCameraService.class);
                    svc.setAction("START_CAMERA");
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        startForegroundService(svc);
                    } else {
                        startService(svc);
                    }
                } else {
                    Intent svc = new Intent(this, PhoneSyncCameraService.class);
                    svc.setAction(action);
                    startService(svc); 
                }
            }

        } catch (Exception e) {
            Log.e(TAG, "解析手錶訊息失敗", e);
        }
    }
}
