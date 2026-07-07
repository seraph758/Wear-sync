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
import com.google.android.gms.wearable.Node;

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
        // 🌓 勿擾同步包（手機發送 dnd_state + mask 一體包）
        // =========================================================================
        if ("dnd".equalsIgnoreCase(type)) {
        
            int dndStatePhone = json.optInt("dnd_state", -1);
        
            if (dndStatePhone == -1) {
                WearLog.w(TAG, "⚠️ [DND同步] 收到勿擾包但缺少 dnd_state");
                return;
            }
        
        
            WearLog.d(TAG,
                    "📥 [DND同步] 收到手機勿擾狀態="
                            + dndStatePhone
                            + " mask="
                            + json.optInt("mask",-1));
        
        
            // 交給DndManager：
            // 1.解析mask
            // 2.判斷總開關
            // 3.同步震動/睡眠/省電
            // 4.寫入手錶DND
            WearSyncDndManager.updateConfigs(json);
        
            WearSyncDndManager.executeDndSync(             this,
                    dndStatePhone
            );
        
        
            return;
        }
          // =========================================================================
        // 🔋 模组三：闹钟拦截控制模组（极致精简・快递员模式）
        // =========================================================================
        if ("alarm".equalsIgnoreCase(type)) {
            WearLog.d(TAG, "⏰ 收到手机闹钟信令，正在将其无损打包并直发 WearAlarmActivity ➔ " + action);
                
                Intent alarmIntent = new Intent(this, WearAlarmActivity.class);
                alarmIntent.addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK |
                        Intent.FLAG_ACTIVITY_CLEAR_TOP |
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                );
                startActivity(alarmIntent);
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
            
                if ("CAMERA_HANDSHAKE".equalsIgnoreCase(action)) {
            
                    WearLog.d(TAG, "CAM-W001 收到 CAMERA_HANDSHAKE");
            
                    return;
                }
            
                if ("START_CAMERA".equalsIgnoreCase(action)) {
            
                    WearLog.d(TAG, "启动 WearCameraActivity");
            
                    Intent intent = new Intent(this, WearCameraActivity.class);
            
                    intent.addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK
                                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            
                    startActivity(intent);
            
                    return;
                }
            
                if ("STREAM_START".equalsIgnoreCase(action)) {
            
                    WearLog.d(TAG, "CAM-W003 STREAM_START");
            
                    WearCameraActivity activity =
                            WearCameraActivity.sActivityRef.get();
            
                    if (activity != null) {
                        activity.onChannelReady();
                    }
            
                    return;
                }
            
                if ("STOP_CAMERA".equalsIgnoreCase(action)
                        || "FORCE_QUIT_CAMERA".equalsIgnoreCase(action)) {
            
                    sendBroadcast(
                            new Intent("de.rhaeus.wearsync.ACTION_FORCE_QUIT_WEAR_CAMERA"));
            
                    return;
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
        WearLog.d(TAG, "CAM-W004 onChannelOpened");

        if (channel != null && CAMERA_PREVIEW_STREAM_PATH.equals(channel.getPath())) {
            WearLog.d(TAG, "🌊 发现专属流媒体图传高速公路 [Channel Opened]！开始抽取高频帧字节流...");
            WearLog.d(TAG, "CAM-W005 path=" + channel.getPath());
            readH264ChannelStream(channel);
        }
    }
    private void sendStreamStart() {
        new Thread(() -> {
            try {
                JSONObject json = new JSONObject();
                json.put("sender", "wear");
                json.put("type", "camera");
                json.put("action", "STREAM_START");
    
                byte[] payload = json.toString().getBytes(StandardCharsets.UTF_8);
    
                for (Node node : Tasks.await(Wearable.getNodeClient(this).getConnectedNodes())) {
                    Wearable.getMessageClient(this).sendMessage(
                            node.getId(),
                            UNIVERSAL_SYNC_PATH,
                            payload
                    );
                }
    
                WearLog.d(TAG, "P-020 STREAM_START 已发送");
    
            } catch (Exception e) {
                WearLog.e(TAG, "发送 STREAM_START 失败", e);
            }
        }).start();
    }
    private void readH264ChannelStream(ChannelClient.Channel channel) {
    
        new Thread(() -> {
    
            WearLog.d(TAG, "CAM-W007 start read thread");
    
            try (InputStream is = Tasks.await(
                    Wearable.getChannelClient(this).getInputStream(channel))) {
    
                WearLog.d(TAG, "CAM-W006 InputStream ready");
                WearLog.d(TAG, "CAM-W008 reading...");
    
                byte[] buffer = new byte[40960];
                int length;
    
                boolean firstFrame = true;
    
                while ((length = is.read(buffer)) != -1) {
    
                    if (firstFrame) {
                        firstFrame = false;
                        WearLog.d(TAG, "CAM-W009 len=" + length);
                    }
    
                    WearCameraActivity activity = WearCameraActivity.sActivityRef.get();
    
                    if (activity != null) {
                        byte[] frame = new byte[length];
                        System.arraycopy(buffer, 0, frame, 0, length);
                        activity.feedH264Data(frame, length);
                    }
                }
    
            } catch (Exception e) {
                WearLog.e(TAG, "⚠️ 视频流高频泵送遭遇通道闭合熔断: " + e.getMessage(), e);
            }
    
        }).start();
    }
}
