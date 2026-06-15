package de.rhaeus.wearsync;

import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
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
import com.google.android.gms.wearable.Wearable;
import com.google.android.gms.wearable.WearableListenerService;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * ⌚ 手表端中央接收服务（全新方案对齐版）
 * 核心逻辑：
 * 1. 严格对齐 1-睡眠、2-震动、4-省电 协议。
 * 2. 无论开关数值是否为 0，优先无条件对齐双端系统硬勿扰状态（全局校对）。
 * 3. 根据数值独立控制：省电、单向短震动、就寝手势宏。
 */
public class WearSyncListenerService extends WearableListenerService {
    private static final String TAG = "WearSync_WearListener";
    private static final String UNIVERSAL_SYNC_PATH = "/wear-universal-sync";

    // 手勢內部宏防併發鎖
    private static boolean isGestureMacroRunning = false;

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

            // 过滤本地，确保只处理手机端发来的主控命令
            if ("wear".equalsIgnoreCase(sender)) return;

            // ================= 🌙 1️⃣ 勿擾/就寢/省電 全局校對與精細分撥區 =================
            if ("dnd".equalsIgnoreCase(type)) {
                // 手机端真实的硬勿扰系统状态值 (INTERRUPTION_FILTER_PRIORITY=2, INTERRUPTION_FILTER_ALL=1)
                int dndStatePhone = json.optInt("dnd_profile_value", -1);
                // 纯净的开关组合结果掩码
                int score = json.optInt("switches_mask", 0); 

                if (dndStatePhone == -1) return;

                NotificationManager mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                if (mNotificationManager == null) return;

                Log.d(TAG, "🌙 [手表接收] 收到手机同步：开关掩码=" + score + " | 手机硬勿扰状态=" + dndStatePhone);

                // ---------------------------------------------------------------------
                // 🎯 核心一：全局硬勿扰状态校对环节（哪怕数值 score 是 0，手表的勿扰也要强制跟变）
                // ---------------------------------------------------------------------
                boolean phoneExpectsDndOn = (dndStatePhone == NotificationManager.INTERRUPTION_FILTER_PRIORITY || 
                                             dndStatePhone == NotificationManager.INTERRUPTION_FILTER_NONE ||
                                             dndStatePhone == NotificationManager.INTERRUPTION_FILTER_ALARMS);

                // 强制对齐手表底层系统的硬勿扰 Filter
                if (mNotificationManager.isNotificationPolicyAccessGranted()) {
                    mNotificationManager.setInterruptionFilter(dndStatePhone);
                    Log.d(TAG, "☯️ [硬勿扰对齐] 手表底层系统勿扰已无条件同步为: " + dndStatePhone);
                }

                // ---------------------------------------------------------------------
                // 🎯 核心二：严格遵照 [1-睡眠, 2-震动, 4-省电] 二进制协议精准拆解
                // ---------------------------------------------------------------------
                boolean isSleepSyncEnabled   = (score & 1) != 0; // 第一位：睡眠模式手势宏
                boolean isVibrateEnabled     = (score & 2) != 0; // 第二位：手机向手表单向同步状态时候的震动开关
                boolean isPowerSyncEnabled   = (score & 4) != 0; // 第三位：省电模式同步

                // ---------------------------------------------------------------------
                // 📳 动作 A：单向短震动控制（手机控制手表专享，且只有开启勿扰时提醒）
                // ---------------------------------------------------------------------
                if (isVibrateEnabled && phoneExpectsDndOn) {
                    Log.d(TAG, "📳 [单向震动激活] 触发手表短震动提醒用户");
                    vibrateShort();
                }

                // ---------------------------------------------------------------------
                // 🔋 动作 B：省电同步选项的真实动作
                // ---------------------------------------------------------------------
                if (isPowerSyncEnabled) {
                    try {
                        if (phoneExpectsDndOn) {
                            Log.d(TAG, "🔋 [省电同步] 强开手表底层省电模式");
                            Settings.Global.putInt(getContentResolver(), "low_power", 1);
                        } else {
                            Log.d(TAG, "🔌 [省电同步] 强关手表底层省电模式");
                            Settings.Global.putInt(getContentResolver(), "low_power", 0);
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "🚨 手表省电模式底层写入失败", e);
                    }
                } else {
                    Log.d(TAG, "🛡️ [省电同步关闭] 保持手表本地省电模式现状，不做任何操作。");
                }

                // ---------------------------------------------------------------------
                // 🛌 动作 C：物理就寝/睡眠手势宏区域（配合状态感知，防止二次翻转）
                // ---------------------------------------------------------------------
                if (isSleepSyncEnabled) {
                    // 获取设置前的手表本地状态，用于状态感知
                    int currentDndState = mNotificationManager.getCurrentInterruptionFilter();
                    boolean wearLocalDndIsOn = (currentDndState == NotificationManager.INTERRUPTION_FILTER_PRIORITY || 
                                                currentDndState == NotificationManager.INTERRUPTION_FILTER_NONE ||
                                                currentDndState == NotificationManager.INTERRUPTION_FILTER_ALARMS);

                    // 只有当期望的就寝状态与手表当前物理开关不一致时，才触发手势宏校准，防止无限循环
                    if (phoneExpectsDndOn != wearLocalDndIsOn) {
                        Log.d(TAG, "🔄 [状态错位] 触发无障碍下拉手势宏去物理点亮/关闭就寝模式。");
                        executePhysicalBedtimeMacro();
                    } else {
                        Log.d(TAG, "🛡️ [物理手势拦截] 手表物理就寝状态与手机一致，拦截手势宏。");
                    }
                } else {
                    Log.d(TAG, "🛡️ [睡眠宏同步关闭] 手机未选择同步睡眠动作，略过手势宏。");
                }

                return; // 乾净返回
            } 
            
            // 2️⃣ 相機模組控制分支
            else if ("camera_control".equalsIgnoreCase(type)) {
                if ("START_CAMERA".equalsIgnoreCase(action)) {
                    Log.d(TAG, "📸 [手錶監聽] 收到手機端拉起相機指令，執行強行物理亮屏保護...");
                    try {
                        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                        if (pm != null) {
                            PowerManager.WakeLock wl = pm.newWakeLock(
                                PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP, 
                                "WearSync:CameraWakeLock"
                            );
                            wl.acquire(3000L); 
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "🚨 后台物理亮屏唤醒失败", e);
                    }

                    Intent startCamIntent = new Intent(this, WearCameraActivity.class);
                    startCamIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                    try {
                        startActivity(startCamIntent);
                    } catch (Exception e) {
                        Log.e(TAG, "🚨 手錶端拉起 WearCameraActivity 失敗", e);
                    }
                }
                return; 
            }

            // ===================================================================================
            // === [🔥 LOCKED_FIREWALL: ALARM_MODULE_WEAR_UI_LAUNCH_FIREWALL - START] ===
            if ("alarm".equalsIgnoreCase(type)) {
                if ("START_ALARM_UI".equalsIgnoreCase(action)) {
                    Intent uiIntent = new Intent(this, WearAlarmActivity.class);
                    uiIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    startActivity(uiIntent);
                } else if ("FORCE_STOP_WEAR_ALARM".equalsIgnoreCase(action)) {
                    sendBroadcast(new Intent("de.rhaeus.wearsync.FORCE_STOP_ALARM_UI"));
                }
                return;
            }
            // === [🔥 LOCKED_FIREWALL: ALARM_MODULE_WEAR_UI_LAUNCH_FIREWALL - END] ===
            // ===================================================================================

        } catch (Exception e) { 
            Log.e(TAG, "流解析异常", e); 
        }
    }

    /**
     * 獨立非阻塞線程：嚴格依照舊代碼的時序執行「喚醒 -> 下滑 -> 點擊 -> 返回」
     */
    private void executePhysicalBedtimeMacro() {
        if (isGestureMacroRunning) {
            Log.w(TAG, "⚠️ 手勢宏正在執行中，拒絕併發干擾");
            return;
        }

        new Thread(() -> {
            WearSyncAccessService serv = WearSyncAccessService.getSharedInstance();
            if (serv == null) {
                Log.e(TAG, "❌ 無障礙服務未連通，放棄執行手勢");
                return;
            }

            PowerManager.WakeLock wakeLock = null;
            try {
                isGestureMacroRunning = true;

                PowerManager pm = (PowerManager) getApplicationContext().getSystemService(Context.POWER_SERVICE);
                if (pm != null) {
                    wakeLock = pm.newWakeLock(PowerManager.FULL_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP | PowerManager.ON_AFTER_RELEASE, "wearsync:WakeLock");
                    wakeLock.acquire(8000L); 
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
                isGestureMacroRunning = false;
            }
        }).start();
    }

    private void vibrateShort() {
        Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        if (v != null && v.hasVibrator()) {
            v.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE));
        }
    }
}
