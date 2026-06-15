package de.rhaeus.wearsync;

import android.content.Intent;
import android.util.Log;
import androidx.annotation.NonNull;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.WearableListenerService;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;

/**
 * 🚀 极致纯净版中央信令路由器
 * 职责：只负责接收手表原生信令，并通过标准的 Android 组件通信（Intent/Broadcast）分发给独立的业务文件。
 * 本文件内不包含任何具体的勿扰控制、闹钟点击、相机核心逻辑，达成完美大解耦！
 */
public class PhoneSyncListenerService extends WearableListenerService {
    private static final String TAG = "WearSync_PhoneListener";
    private static final String UNIVERSAL_SYNC_PATH = "/wear-universal-sync";
    
    /**
     * 🎯 核心全局拦截锁：专门留给独立的 PhoneDndService 和 PhoneSyncNotificationService 远程控制读写，
     * 维持原项目的防死循环拦截机制，确保编译 100% 通过。
     */
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

            // ================= 1️⃣ 勿擾同步模塊（完美解耦） =================
            // 职责剥离：不再本文件中调用 NotificationManager，而是直接打包信令发送给独立的后台 PhoneDndService
            if ("dnd".equalsIgnoreCase(type)) {
                int dndVal = json.optInt("dnd_profile_value", -1);
                if (dndVal != -1) {
                    Log.d(TAG, "🌙 [信令路由] 收到手錶勿擾同步请求，掩碼值: " + dndVal + " -> 轉發至獨立 PhoneDndService");
                    
                    Intent dndIntent = new Intent(this, PhoneDndService.class);
                    dndIntent.setAction("UPDATE_FROM_WEAR_MASK"); // 对应你原 Service 里的接收动作
                    dndIntent.putExtra("dnd_profile_value", dndVal);
                    startService(dndIntent);
                }
                return;
            }

            // ================= 2️⃣ 鬧鐘延後點擊模塊（完美解耦） =================
            // 职责剥离：不在此处点击 PendingIntent，而是直接通过自定义广播或显式 Intent 发送给独立的闹钟处理模块
            if ("alarm_action".equalsIgnoreCase(type)) {
                Log.d(TAG, "⏰ [信令路由] 收到手錶觸發延後鬧鐘動作 -> " + action + " -> 轉發至獨立鬧鐘廣播/服務");
                
                Intent alarmBroadcast = new Intent("DE_RHAEUS_WEARSYNC_ALARM_TRIGGER");
                alarmBroadcast.putExtra("action", action);
                sendBroadcast(alarmBroadcast);
                return;
            }

            // ================= 3️⃣ 相機模塊（穿透啟動与自动熄屏联动） =================
            // 职责剥离：只负责提权拉起 Activity 和启动相机独立的 FGS 服务 PhoneSyncCameraService
            if ("camera_control".equalsIgnoreCase(type)) {
                Log.d(TAG, "📸 [信令路由] 收到相機動作 Action: " + action);

                if ("START_CAMERA".equalsIgnoreCase(action)) {
                    Log.d(TAG, "🚀 [穿透啟動] 正在喚醒手機前台 Activity 以獲取 OxygenOS 前台啟動豁免權...");

                    // 1. 先把手機端 Activity 提權到最前台，幫相機前台服務拿到系統豁免准入資格
                    Intent intent = new Intent(this, PhoneSyncMainActivity.class);
                    intent.setAction("ACTION_START_CAMERA_FLOW");
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK 
                                  | Intent.FLAG_ACTIVITY_CLEAR_TOP 
                                  | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    startActivity(intent);

                    // 2. 隨後緊接著調用 startForegroundService 啟動相機背景 FGS 服務
                    Intent svc = new Intent(this, PhoneSyncCameraService.class);
                    svc.setAction("START_CAMERA");
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        startForegroundService(svc);
                    } else {
                        startService(svc);
                    }
                } else {
                    // 投递拍照（TAKE_PICTURE）或停止（STOP_CAMERA）控制指令给相机独立服务
                    Intent svc = new Intent(this, PhoneSyncCameraService.class);
                    svc.setAction(action);
                    startService(svc); 

                    // 🎯 【核心联动防不熄屏 Bug】：如果手表下达的是停止相机动作，强制清算并自杀 Activity，解除常亮屏幕锁
                    if ("STOP_CAMERA".equalsIgnoreCase(action)) {
                        Log.d(TAG, "🧼 手錶主動關閉相機，通知 Activity 釋放屏幕鎖並退出...");
                        PhoneSyncMainActivity.closeAndReleaseScreenLock();
                    }
                }
            }

        } catch (Exception e) {
            Log.e(TAG, "解析手錶訊息失敗", e);
        }
    }
}
