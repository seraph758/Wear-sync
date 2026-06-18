package de.rhaeus.wearsync;

import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
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

            // =========================================================================
            // 🌗 模塊一：手機端勿擾模式獨立接收塊（只改勿擾，別的不動，動態校準防回旋）
            // =========================================================================
            if ("status_mask".equalsIgnoreCase(type) || json.has("status_mask")) {
                int statusMask = json.optInt("status_mask", -1);
                if (statusMask != -1) {
                    Log.d(TAG, "📥 [勿擾模塊] 手機收到來自手錶的反向狀態 Mask: " + statusMask + " (二進制: " + Integer.toBinaryString(statusMask) + ")");

                    // 🎯 解析 Bit 1 (0x01) -> 手錶端送過來的「勿擾預期狀態」
                    boolean targetDndEnabled = (statusMask & 0x01) != 0;

                    try {
                        // 🚀 1. 獲取手機當前物理勿擾狀態
                        NotificationManager mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                        if (mNotificationManager != null) {
                            int currentPhoneDndFilter = mNotificationManager.getCurrentInterruptionFilter();
                            boolean isPhoneDndOnNow = (currentPhoneDndFilter > 1);

                            Log.d(TAG, "🔍 勿擾動態校準: 手錶預期=" + targetDndEnabled + ", 手機當前=" + isPhoneDndOnNow);

                            // 🔥【核心閉環規則 1】：不一致則修改，一致則不動
                            if (targetDndEnabled != isPhoneDndOnNow) {
                                
                                // 🔥【核心閉環規則 2】：立刻激活內部更新鎖，破除雙向同步引發的死循環回旋
                                isInternalUpdate = true;
                                Log.d(TAG, "🔒 [上鎖] 手機內部更新鎖激活，防止狀態回旋。");

                                // 執行手機底層勿擾物理切換 (3=全面勿擾, 1=關閉勿擾)
                                if (mNotificationManager.isNotificationPolicyAccessGranted()) {
                                    mNotificationManager.setInterruptionFilter(targetDndEnabled ? 3 : 1);
                                    Log.d(TAG, " 🌗 [物理執行] 手機勿擾狀態修改成功 ➔ " + targetDndEnabled);
                                } else {
                                    Log.e(TAG, "🔴 失敗：手機缺少修改勿擾模式(Do Not Disturb Access)的權限！");
                                }
                            } else {
                                Log.d(TAG, "🤝 狀態完全一致，手機端不作任何多餘動作。");
                            }
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "🔴 手機端勿擾狀態校準或執行變更時崩潰", e);
                    } finally {
                        // 🔥【核心閉環規則 3】：延時釋放更新鎖，確保手機系統渲染完畢後，再恢復主動控手錶能力
                        new Thread(() -> {
                            try { Thread.sleep(1200); } catch (Exception ignored) {}
                            isInternalUpdate = false;
                            Log.d(TAG, "🔓 [解鎖] 手機內部更新鎖解除，恢復主動權。");
                        }).start();
                    }
                }
                
                // 嚴格截斷：只要是 status_mask 類型的信令，到此全部處理完畢，直接返回，絕不向下串擾！
                if ("status_mask".equalsIgnoreCase(type)) return;
            }

            // ================= 1️⃣ 舊版勿擾協議向下兼容 =================
            if ("dnd".equalsIgnoreCase(type)) {
                int state = json.has("dnd_state") ? json.optInt("dnd_state", -1) : json.optInt("dnd_profile_value", -1);
                if (state != -1) {
                    try {
                        NotificationManager mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                        if (mNotificationManager != null) {
                            boolean targetDndEnabled = (state > 0);
                            boolean isPhoneDndOnNow = (mNotificationManager.getCurrentInterruptionFilter() > 1);
                            
                            if (targetDndEnabled != isPhoneDndOnNow) {
                                isInternalUpdate = true;
                                Log.d(TAG, "🔒 [上鎖] 舊版向下兼容勿擾鎖激活。");
                                if (mNotificationManager.isNotificationPolicyAccessGranted()) {
                                    mNotificationManager.setInterruptionFilter(targetDndEnabled ? 3 : 1);
                                }
                                new Thread(() -> {
                                    try { Thread.sleep(1200); } catch (Exception ignored) {}
                                    isInternalUpdate = false;
                                    Log.d(TAG, "🔓 [解鎖] 舊版向下兼容勿擾鎖解除。");
                                }).start();
                            }
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "舊版勿擾兼容處理失敗", e);
                    }
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

            // =========================================================================
// 📸 3️⃣ 相机唤醒与释放协议（突破 Android 高版本后台拉起限制）
// =========================================================================
            else if ("camera".equalsIgnoreCase(type) || "camera_control".equalsIgnoreCase(type)) {
                if ("START_CAMERA_UI".equalsIgnoreCase(action) || "START_CAMERA".equalsIgnoreCase(action)) {
                    Log.d(TAG, "🚀 [相机模块] 收到手表端拍照激活口令！直接安全唤醒手机端 PhoneSyncCameraService...");
            
                    try {
                        Intent cameraServiceIntent = new Intent(this, PhoneSyncCameraService.class);
                        // 🎯 核心安全加固：高版本 Android 如果在后台启动服务，必须用 startForegroundService
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                            startForegroundService(cameraServiceIntent);
                        } else {
                            startService(cameraServiceIntent);
                        }
                        Log.i(TAG, "🟢 [网关物理执行] PhoneSyncCameraService 后台服务已成功发出拉起指令");
                    } catch (Exception e) {
                        Log.e(TAG, "🔴 跨进程后台拉起手机相机服务遭遇强力封杀", e);
                    }
            
                } else if ("STOP_CAMERA".equalsIgnoreCase(action) || "STOP_CAMERA_STREAM".equalsIgnoreCase(action)) {
                    Log.d(TAG, "🛑 收到手表断开要求，下发本地广播释放手机端相机服务");
                    sendBroadcast(new Intent("de.rhaeus.wearsync.ACTION_STOP_CAMERA_STREAM"));
                }
                return;
            }

        } catch (Exception e) {
            Log.e(TAG, "骨幹通道解析數據包災難性失敗", e);
        }
    }
}
