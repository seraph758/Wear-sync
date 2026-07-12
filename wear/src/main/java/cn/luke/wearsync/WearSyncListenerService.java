package cn.luke.wearsync;

import android.content.Intent;

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

    @Override
public void onCreate() {
    super.onCreate();
    WearLog.e(TAG, "★★★★★ WearSyncListenerService CREATED ★★★★★");
    WearLog.d(TAG, "CAM-W010 Listener Created");
}

    private static final String TAG = "WearSync_WearListener";
    private static final String UNIVERSAL_SYNC_PATH = "/wear-universal-sync";
    private static final String CAMERA_PREVIEW_STREAM_PATH = "/camera-preview-stream";

    @Override
public void onMessageReceived(@NonNull MessageEvent messageEvent) {

    WearLog.e(TAG, "========== MESSAGE RECEIVED ==========");
    WearLog.e(TAG, "path = " + messageEvent.getPath());
    WearLog.e(TAG, "sourceNode = " + messageEvent.getSourceNodeId());

    if (!UNIVERSAL_SYNC_PATH.equalsIgnoreCase(messageEvent.getPath())) {
        WearLog.e(TAG, "❌ Path not match. expected=" + UNIVERSAL_SYNC_PATH);
        return;
    }
        byte[] data = messageEvent.getData();
        // 【优化1】去掉了原本多余的 data == null 恒假判断，直接进行安全性拦截
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
            
                // 交給DndManager
                WearSyncDndManager.updateConfigs(json);
                WearSyncDndManager.executeDndSync(this, dndStatePhone);
            
                return;
            }

            // =========================================================================
            // 🔋 模组三：闹钟拦截控制模组（极致精简・快递员模式）
            // =========================================================================
            if ("alarm".equalsIgnoreCase(type)) {
                WearLog.d(TAG, "⏰ 收到手机闹钟信令，正在将其无损打包并直发 WearAlarmActivity ➔ " + action);
                
                Intent alarmIntent = new Intent(this, WearAlarmActivity.class);
                
                // 【优化2】去掉了重复编写两次的 Intent Flags 赋值与两次 startActivity 冗余
                // 保证 Activity 不管是没启动还是已启动，都能被准确唤醒并投递新 Intent，且移除了官方不推荐同时用的 CLEAR_TOP
                // 🚀 删除了 NEW_TASK，只保留正常的流转标记，警告立刻消失
                alarmIntent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
                alarmIntent.putExtra("raw_alarm_json", json.toString());
                alarmIntent.putExtra("alarm_action", action);

                startActivity(alarmIntent);
                return;
            }

            // =========================================================================
            // 4. 相机穿透控制模组
            // =========================================================================
            if ("camera_control".equalsIgnoreCase(type)) {
                if ("CAMERA_HANDSHAKE".equalsIgnoreCase(action)) {
                    WearLog.d(TAG, "CAM-W001 收到 CAMERA_HANDSHAKE");
                    return;
                }
            
                if ("STREAM_START".equalsIgnoreCase(action)) {
                    WearLog.d(TAG, "CAM-W003 STREAM_START");
                    WearCameraActivity activity = WearCameraActivity.sActivityRef.get();
                    WearLog.d(TAG, "CAM-W003 activity=" + activity);

                    if (activity != null) {
                        activity.onChannelReady();
                    }
                    return;
                }
            
                if ("STOP_CAMERA".equalsIgnoreCase(action)
                        || "FORCE_QUIT_CAMERA".equalsIgnoreCase(action)) {
                    sendBroadcast(new Intent("cn.luke.wearsync.ACTION_FORCE_QUIT_WEAR_CAMERA"));
                    return;
                }
                
                // 【优化3】移除了 void 方法函数末尾多余的 return; 语句
            }

            // =========================================================================
            // 5. 新增：手飙日志无线远程联控模组
            // =========================================================================
            if ("wear_log_control".equalsIgnoreCase(type)) {
                boolean wearDebug = json.optBoolean("wear_log_debug", true);
                WearLog.DEBUG = wearDebug; 
                WearLog.d(TAG, "🎛️ [远程同步] 接收到手机端远程控场，手表日志开闭状态同步修改为 ➔ " + wearDebug);
                return;
            }

        } catch (Exception e) {
            WearLog.e(TAG, "🔴 解析手机发往手表的指令崩溃: " + e.getMessage(), e);
        }
    }

    @Override
    public void onChannelOpened(
            @NonNull ChannelClient.Channel channel) {
    
        WearLog.d(
                TAG,
                "CAM-W004 path="
                        + channel.getPath());
    
        super.onChannelOpened(channel);
    
        if (CAMERA_PREVIEW_STREAM_PATH.equals(channel.getPath())) {
    
            WearLog.d(TAG, "CAM-W005");
    
            readH264ChannelStream(channel);
    
        }
    }
   
    @Override
    public void onChannelClosed(
            @NonNull ChannelClient.Channel channel,
            int closeReason,
            int appSpecificErrorCode) {

        WearLog.d(
                TAG,
                "CAM-W004C closed path="
                        + channel.getPath()
                        + " reason="
                        + closeReason);
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
    
                    if (WearCameraActivity.sActivityRef == null) {
                        continue;
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
