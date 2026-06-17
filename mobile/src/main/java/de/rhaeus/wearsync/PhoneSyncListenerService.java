package de.rhaeus.wearsync;

import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.provider.Settings;
import android.util.Log;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.WearableListenerService;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;

public class PhoneSyncListenerService extends WearableListenerService {
    private static final String TAG = "WearSync_PhoneListener";
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

            // ================= 🧠 終極重構：反向 Bitmask 狀態矩陣解耦分流器 =================
            if ("status_mask".equalsIgnoreCase(type) || json.has("status_mask")) {
                int statusMask = json.optInt("status_mask", -1);
                if (statusMask != -1) {
                    Log.d(TAG, "📥 [骨幹矩陣信令] 手機收到來自手錶的反向狀態 Mask: " + statusMask);
                    
                    isInternalUpdate = true;
                    
                    // 1. 【勿擾反向解耦】
                    boolean dndEnabled = (statusMask & 0x01) != 0;
                    // 將布林值轉回舊 DND 管理器需要的整型狀態（假設 1 為開啟，0 為關閉）
                    PhoneDndManager.handleIncomingAction(this, dndEnabled ? 1 : 0);
                    
                    // 2. 【睡眠模式反向解耦】
                    boolean bedtimeEnabled = (statusMask & 0x02) != 0;
                    Settings.Global.putInt(getContentResolver(), "bedtime_mode", bedtimeEnabled ? 1 : 0);
                    
                    // 3. 【震動模式反向解耦】
                    boolean vibrateEnabled = (statusMask & 0x04) != 0;
                    AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
                    if (am != null) {
                        am.setRingerMode(vibrateEnabled ? AudioManager.RINGER_MODE_VIBRATE : AudioManager.RINGER_MODE_NORMAL);
                    }

                    // 4. 【省電模式反向解耦】
                    boolean powerSaveEnabled = (statusMask & 0x08) != 0;
                    String powerValue = powerSaveEnabled ? "1" : "0";
                    Settings.Global.putString(getContentResolver(), "low_power", powerValue);
                    
                    // 延時重置內部更新鎖，防正反向廣播死循環
                    new Thread(() -> {
                        try { Thread.sleep(1000); } catch (Exception ignored) {}
                        isInternalUpdate = false;
                    }).start();
                }
                if ("status_mask".equalsIgnoreCase(type)) return;
            }

            // ================= 1️⃣ 舊版勿擾協議向下兼容 =================
            if ("dnd".equalsIgnoreCase(type)) {
                int state = json.has("dnd_state") ? json.optInt("dnd_state", -1) : json.optInt("dnd_profile_value", -1);
                if (state != -1) {
                    isInternalUpdate = true;
                    PhoneDndManager.handleIncomingAction(this, state);
                    
                    new Thread(() -> {
                        try { Thread.sleep(1000); } catch (Exception ignored) {}
                        isInternalUpdate = false;
                    }).start();
                }
                return;
            } 
            
            // ================= ⏰ 2️⃣ 鬧鐘反向代點 =================
            else if ("alarm".equalsIgnoreCase(type) || "alarm_action".equalsIgnoreCase(type)) {
                if ("DISMISS".equalsIgnoreCase(action) || "SNOOZE".equalsIgnoreCase(action)) {
                    PhoneAlarmManager.handleWatchCommand(this, action);
                }
                return;
            } 
            
            // ================= 📸 3️⃣ 相機喚醒與釋放協議 =================
            else if ("camera".equalsIgnoreCase(type) || "camera_control".equalsIgnoreCase(type)) {
                if ("START_CAMERA_UI".equalsIgnoreCase(action)) {
                    Log.d(TAG, "🚀 [核心重構] 收到手錶拍照口令！直接將手機主控 Activity 拽到前台...");
                    
                    Intent clIntent = new Intent();
                    clIntent.setClassName(getPackageName(), "de.rhaeus.wearsync.PhoneSyncMainActivity");
                    clIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK 
                                    | Intent.FLAG_ACTIVITY_CLEAR_TOP 
                                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    clIntent.putExtra("INTERNAL_CMD", "LAUNCH_CAMERA_SERVICE_FROM_FOREGROUND");
                    startActivity(clIntent);

                } else if ("STOP_CAMERA".equalsIgnoreCase(action) || "STOP_CAMERA_STREAM".equalsIgnoreCase(action)) {
                    Log.d(TAG, "🛑 收到手錶斷開要求，下發本地廣播釋放手機端相機服務");
                    sendBroadcast(new Intent("de.rhaeus.wearsync.ACTION_STOP_CAMERA_STREAM"));
                }
                return;
            }

        } catch (Exception e) {
            Log.e(TAG, "骨幹通道解析數據包災難性失敗", e);
        }
    }
}
