package de.rhaeus.wearsync;

import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import androidx.annotation.NonNull;
import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.ChannelClient;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.Wearable;
import com.google.android.gms.wearable.WearableListenerService;
import org.json.JSONObject;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public class WearSyncListenerService extends WearableListenerService {
    private static final String TAG = "WearSync_WearListener";
    private static final String UNIVERSAL_SYNC_PATH = "/wear-universal-sync";
    private static final String CAMERA_PREVIEW_STREAM_PATH = "/camera-preview-stream";

    @Override
public void onMessageReceived(@NonNull MessageEvent messageEvent) {
    if (!UNIVERSAL_SYNC_PATH.equalsIgnoreCase(messageEvent.getPath())) {
        return;
    }
    byte[] data = messageEvent.getData();
    if (data == null) return;

    try {
        String jsonStr = new String(data, StandardCharsets.UTF_8);
        JSONObject json = new JSONObject(jsonStr);
        String sender = json.optString("sender", "");
        String type = json.optString("type", "");
        String action = json.optString("action", "");

        if ("wear".equalsIgnoreCase(sender)) return;

        WearLog.d(TAG, "📥 [手表信令到港] ➔ type=[" + type + "], action=[" + action + "]");

        // =========================================================================
        // 🎛️ 模組一：配置掩碼包到達（手機先發，負責把手機端的各個子開關意願存入暫存變量）
        // =========================================================================
        if ("status_mask".equalsIgnoreCase(type) || json.has("status_mask") || json.has("mask_value")) {
            // 🎯 只純淨更新配置開關，不執行任何實質動作，不觸發狀態變更
            WearSyncDndManager.updateConfigs(this, json);
            return;
        }

        // =========================================================================
        // 🌓 模組二：勿擾對齊與全自動化聯動模組（手機後發，攜帶系統原生 Filter 數值）
        // =========================================================================
        if ("dnd".equalsIgnoreCase(type)) {
            int dndStatePhone = json.optInt("dnd_state", -1);
            if (dndStatePhone == -1) return;

            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                // 🎯 100% 復刻舊代碼安全範圍校驗與狀態獲取
                int filterState = nm.getCurrentInterruptionFilter();
                if (filterState < 0 || filterState > 4) {
                    WearLog.d(TAG, "DNDSync weird current dnd state: " + filterState);
                }
                int currentDndState = filterState;

                WearLog.d(TAG, "🔍 [舊代碼對比精髓] 收到手機 dndStatePhone: " + dndStatePhone + " | 手錶當前 currentDndState: " + currentDndState);

                // 🔥 【重歸正統】唯有當手機原生Filter與手錶當前Filter「不相等」時，才觸發一連串連鎖聯動
                if (dndStatePhone != currentDndState) {
                    WearLog.d(TAG, "⚡ [狀態不相等] 確有變更，引爆全自動化同步流水線！");

                    // 🎯 物理防線：關上回傳大門，防止雙向回傳死循環大風暴
                    WearSyncNotificationService.isInternalUpdate = true;

                    // 🚀 執行集中聯動指揮官：內部搞定睡眠無障礙、省電強制同步、勿擾震動判斷
                    WearSyncDndManager.executeDndSync(this, dndStatePhone);

                    // 🌗 不論聯動腳本是否成功，手錶本體都要硬性寫入該原生勿擾狀態
                    if (nm.isNotificationPolicyAccessGranted()) {
                        nm.setInterruptionFilter(dndStatePhone);
                        WearLog.d(TAG, "🌓 手表端勿扰对齐更新成功 ➔ " + dndStatePhone);
                    } else {
                        WearLog.e(TAG, "attempting to set DND but access not granted");
                    }

                    // ⏳ 安全防線：延時拉長到 2000ms，給系統廣播留出足夠的消散時間
                    new Handler(getMainLooper()).postDelayed(() -> {
                        WearSyncNotificationService.isInternalUpdate = false;
                        WearLog.d(TAG, "🔓 [手錶正向鎖] 釋放，重新開啟逆向通道。");
                    }, 2000);

                } else {
                    WearLog.d(TAG, "✅ [對比吻合] 手機與手錶 Filter 數值完全一致，判定為重複信號，安全攔截。");
                }
            }
            return;
        }

          // =========================================================================
        // 🔋 模组三：闹钟拦截控制模组（极致精简・快递员模式）
        // =========================================================================
        if ("alarm".equalsIgnoreCase(type)) {
            WearLog.d(TAG, "⏰ 收到手机闹钟信令，正在将其无损打包并直发 WearAlarmActivity ➔ " + action);
                
                Intent alarmIntent = new Intent(this, WearAlarmActivity.class);
                // 🎯 核心精简：直接把原始 JSON 字符串塞进去，所有的解析和自毁都让 Activity 现场自己做！
                alarmIntent.putExtra("raw_alarm_json", json.toString());
                alarmIntent.putExtra("alarm_action", action);
                
                // 保证 Activity 不管是没启动还是已启动，都能被准确唤醒并投递新 Intent
                alarmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK 
                                   | Intent.FLAG_ACTIVITY_CLEAR_TOP 
                                   | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                startActivity(alarmIntent);
                return;
            }


            // 4. 相机穿透控制模组
            if ("camera_control".equalsIgnoreCase(type)) {
                if ("START_CAMERA".equalsIgnoreCase(action)) {
                    WearLog.d(TAG, "📸 远程相机开火指令送达！正在强制启动手表预览界面...");
                    Intent camIntent = new Intent(this, WearCameraActivity.class);
                    camIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                    startActivity(camIntent);
                } else if ("FORCE_QUIT_CAMERA".equalsIgnoreCase(action) || "STOP_CAMERA".equalsIgnoreCase(action)) {
                    WearLog.d(TAG, "🛑 远程相机被手机强制切断，向本地 Activity 发送被迫挂断中断广播...");
                    sendBroadcast(new Intent("de.rhaeus.wearsync.ACTION_FORCE_QUIT_WEAR_CAMERA"));
                }
                return;

            }

            // 5. 新增：手飙日志无线远程联控模组
            if ("wear_log_control".equalsIgnoreCase(type)) {
                boolean wearDebug = json.optBoolean("wear_log_debug", true);
                WearLog.DEBUG = wearDebug; // 🎯 远程改写手表本地日志全局静音开合状态
                WearLog.d(TAG, "🎛️ [远程同步] 接收到手机端远程控场，手表日志开闭状态同步修改为 ➔ " + wearDebug);
                return;
            }

        } catch (Exception e) {
            WearLog.e(TAG, "🔴 解析手机发往手表的指令崩溃: " + e.getMessage(), e);
        }
    }

    @Override
    public void onChannelOpened(@NonNull ChannelClient.Channel channel) {
        if (channel != null && CAMERA_PREVIEW_STREAM_PATH.equals(channel.getPath())) {
            WearLog.d(TAG, "🌊 发现专属流媒体图传高速公路 [Channel Opened]！开始抽取高频帧字节流...");
            readH264ChannelStream(channel);
        }
    }

    private void readH264ChannelStream(ChannelClient.Channel channel) {
        new Thread(() -> {
            try (InputStream is = Tasks.await(Wearable.getChannelClient(this).getInputStream(channel))) {
                byte[] buffer = new byte[40960];
                int length;
                while ((length = is.read(buffer)) != -1) {
                    WearCameraActivity activity = WearCameraActivity.sActivityRef.get();
                    if (activity != null) {
                        byte[] frame = new byte[length];
                        System.arraycopy(buffer, 0, frame, 0, length);
                        activity.feedH264Data(frame, length);
                    }
                }
            } catch (Exception e) {
                WearLog.e(TAG, "⚠️ 视频流高频泵送遭遇通道闭合熔断: " + e.getMessage());
            }
        }).start();
    }
}
