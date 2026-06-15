package de.rhaeus.wearsync;

import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.PowerManager;
import android.os.Vibrator;
import android.os.VibrationEffect;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;
import androidx.annotation.NonNull;
import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.ChannelClient;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;
import com.google.android.gms.wearable.WearableListenerService;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class WearSyncListenerService extends WearableListenerService {
    private static final String TAG = "WearSync_WearListener";
    private static final String UNIVERSAL_SYNC_PATH = "/wear-universal-sync";

    // 手勢內部宏防併發鎖 (原封不動保留)
    private static boolean isGestureMacroRunning = false;

    @Override
    public void onMessageReceived(@NonNull MessageEvent messageEvent) {
        if (!UNIVERSAL_SYNC_PATH.equalsIgnoreCase(messageEvent.getPath())) return;
        byte[] data = messageEvent.getData();
        if (data == null) return;

        try {
            String jsonStr = new String(data, StandardCharsets.UTF_8);
            JSONObject json = new JSONObject(jsonStr);
            String type = json.optString("type", "");
            String action = json.optString("action", "");

            // 🎯 [全新追加]：處理手機端發送過來的 Setting Write 權限控制
            if ("setting_control".equalsIgnoreCase(type)) {
                if ("QUERY_WEAR_WRITE_SETTINGS_PERMISSION".equalsIgnoreCase(action)) {
                    Log.d(TAG, "📥 收到手機端查詢手錶 Setting Write 權限請求");
                    respondWearWriteSettingsStatus();
                } else if ("REQUEST_WEAR_WRITE_SETTINGS_UI".equalsIgnoreCase(action)) {
                    Log.d(TAG, "📥 收到手機端遠端引導開關請求，準備為用戶拉起手錶系統授權頁面");
                    launchWearWriteSettingsActivity();
                }
                return; // 處理完畢直接返回，不干涉下方既有邏輯
            }

            // ------------------ 以下為你原本所有的既有邏輯，100% 完整保留 ------------------
            if ("camera_control".equalsIgnoreCase(type)) {
                if ("START_CAMERA".equalsIgnoreCase(action)) {
                    Log.d(TAG, "📸 接收到 START_CAMERA 指令，正在手錶端拉起 WearCameraActivity...");
                    Intent intent = new Intent(this, WearCameraActivity.class);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                    startActivity(intent);
                }
                return;
            }

            if (json.has("vibrate")) {
                int vibrateDuration = json.getInt("vibrate");
                Vibrator vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
                if (vibrator != null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        vibrator.vibrate(VibrationEffect.createOneShot(vibrateDuration, VibrationEffect.DEFAULT_AMPLITUDE));
                    } else {
                        vibrator.vibrate(vibrateDuration);
                    }
                }
            }

            if (json.has("gesture_macro")) {
                String macroType = json.getString("gesture_macro");
                if ("BEDTIME_TOGGLE".equalsIgnoreCase(macroType)) {
                    synchronized (WearSyncListenerService.class) {
                        if (isGestureMacroRunning) {
                            Log.w(TAG, "⚠️ 手勢宏正在運行中，拒絕重複觸發，防止併發死鎖");
                            return;
                        }
                        isGestureMacroRunning = true;
                    }
                    
                    new Thread(() -> {
                        PowerManager.WakeLock wakeLock = null;
                        try {
                            Log.d(TAG, "🎬 [手勢宏] 收到手機就寢同步訊號，開始啟動實體螢幕喚醒與下滑控制鏈條...");
                            WearSyncAccessService serv = WearSyncAccessService.getSharedInstance();
                            if (serv == null) {
                                Log.e(TAG, "❌ 輔助自動化無障礙服務未開啟，無法模擬下滑點擊！");
                                return;
                            }

                            PowerManager pm = (PowerManager) getApplicationContext().getSystemService(Context.POWER_SERVICE);
                            if (pm != null) {
                                wakeLock = pm.newWakeLock(PowerManager.FULL_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP | PowerManager.ON_AFTER_RELEASE, "wearsync:WakeLock");
                                wakeLock.acquire(8000L); // 鎖定 8 秒完成整套動作
                            }

                            Thread.sleep(2000);
                            serv.swipeDown();
                            Thread.sleep(1000);
                            serv.clickIcon1_2();
                            Thread.sleep(800);
                            serv.goBack();
                            Log.d(TAG, "🏁 [手勢宏] 物理控制校準鏈條圓滿結束");

                        } catch (InterruptedException e) {
                            Log.e(TAG, "手勢宏線程中斷", e);
                        } finally {
                            if (wakeLock != null && wakeLock.isHeld()) {
                                wakeLock.release();
                            }
                            synchronized (WearSyncListenerService.class) {
                                isGestureMacroRunning = false;
                            }
                        }
                    }).start();
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "解析通訊訊息失敗", e);
        }
    }

    /**
     * 🎯 核心補償方法 A：檢測手錶本地 Setting Write 權限，並異步發送回手機
     */
    private void respondWearWriteSettingsStatus() {
        new Thread(() -> {
            try {
                boolean hasPermission = true;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    hasPermission = Settings.System.canWrite(this);
                }
                
                JSONObject response = new JSONObject();
                response.put("sender", "wear");
                response.put("type", "wear_status_response");
                response.put("action", "RESPONSE_WEAR_WRITE_SETTINGS_STATUS");
                response.put("has_permission", hasPermission);
                
                byte[] data = response.toString().getBytes(StandardCharsets.UTF_8);
                List<Node> nodes = Tasks.await(Wearable.getNodeClient(this).getConnectedNodes());
                for (Node n : nodes) {
                    Wearable.getMessageClient(this).sendMessage(n.getId(), UNIVERSAL_SYNC_PATH, data);
                }
                Log.d(TAG, "🚀 已成功向手機端反饋手錶 Setting Write 權限狀態: " + hasPermission);
            } catch (Exception e) {
                Log.e(TAG, "向手機反饋權限狀態失敗", e);
            }
        }).start();
    }

    /**
     * 🎯 核心補償方法 B：為手錶拉起系統層級的「修改系統設置」權限開啟頁面
     */
    private void launchWearWriteSettingsActivity() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                Intent intent = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS);
                intent.setData(Uri.parse("package:" + getPackageName()));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
                Log.d(TAG, "⚙️ 成功拉起手錶系統 WRITE_SETTINGS 配置頁面");
            } catch (Exception e) {
                Log.e(TAG, "無法直接跳轉，嘗試拉起通用設置頁", e);
                Intent intent = new Intent(Settings.ACTION_SETTINGS);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
            }
        }
    }
}
