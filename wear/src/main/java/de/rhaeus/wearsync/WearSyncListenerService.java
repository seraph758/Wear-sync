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

            // 1. 勿扰同步模组
            if ("dnd".equalsIgnoreCase(type)) {
                int value = json.optInt("dnd_state", -1);
                if (value == -1) return;

                WearSyncNotificationService.isInternalUpdate = true;
                NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                if (nm != null) {
                    nm.setInterruptionFilter(value);
                    WearLog.d(TAG, "🌓 手表端勿扰对齐更新成功 ➔ " + value);
                }
                new Handler(getMainLooper()).postDelayed(() -> WearSyncNotificationService.isInternalUpdate = false, 1500);
                return;
            }

            // 2. 状态掩码处理模组
            if ("status_mask".equalsIgnoreCase(type)) {
                int mask = json.optInt("mask_value", 0);
                WearLog.d(TAG, "📊 收到手机同步的全新全局掩码 = " + mask);
                return;
            }

            // 3. 闹钟拦截控制模组
            if ("alarm".equalsIgnoreCase(type)) {
                if ("START_WEAR_ALARM".equalsIgnoreCase(action)) {
                    WearLog.d(TAG, "⏰ 手机闹钟高能预警！正在强制唤醒全屏强拉全画幅接管 activity...");
                    Intent alarmIntent = new Intent(this, WearAlarmActivity.class);
                    alarmIntent.putExtra("alarm_time", json.optString("time", "00:00"));
                    alarmIntent.putExtra("alarm_label", json.optString("label", "闹钟"));
                    alarmIntent.putExtra("alarm_day", json.optString("day_tips", ""));
                    alarmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                    startActivity(alarmIntent);
                } else if ("FORCE_STOP_WEAR_ALARM".equalsIgnoreCase(action)) {
                    WearLog.d(TAG, "⏰ 手机端下发强退信号，正在通过本地内核广播自毁灭活手表全屏闹钟...");
                    sendBroadcast(new Intent(WearAlarmActivity.ACTION_INTERNAL_FORCE_STOP));
                }
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
