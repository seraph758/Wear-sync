package cn.luke.wearsync;

import android.content.Context;
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
import android.util.Log;



public class WearSyncListenerService extends WearableListenerService {

    @Override
    public void onCreate() {
        super.onCreate();
        WearLog.e(TAG, "★★★★★ WearSyncListenerService CREATED ★★★★★");
        WearLog.d(TAG, "CAM-W010 Listener Created");
    }

    private static final String TAG = "WearSync_WearListener";
    private static final String UNIVERSAL_SYNC_PATH = "/wear-universal-sync";
    private static final String DATA_CHANNEL_BASE_PATH = "/wear_data_channel";

    private static final String CAMERA_PREVIEW_STREAM_PATH = DATA_CHANNEL_BASE_PATH + "/camera";
    private ChannelClient.Channel mLogChannel;
    @Override
    public void onMessageReceived(@NonNull MessageEvent messageEvent) {
        WearLog.e(TAG, "========== MESSAGE RECEIVED ==========");
        WearLog.e(TAG, "path = " + messageEvent.getPath());
        WearLog.e(TAG, "sourceNode = " + messageEvent.getSourceNodeId());

        // 1. 统一入口：只处理通用同步路径
        if (!UNIVERSAL_SYNC_PATH.equalsIgnoreCase(messageEvent.getPath())) {
            WearLog.e(TAG, "❌ Path not match. expected=" + UNIVERSAL_SYNC_PATH);
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

                // 防止手表自己发的消息循环处理
                if ("wear".equalsIgnoreCase(sender)) return;

                WearLog.d(TAG, "📥 [手表信令到港] ➔ type=[" + type + "], action=[" + action + "]");

                 // 2. 震动控制逻辑修复版
                if ("vibration".equalsIgnoreCase(type)) {
                String configJsonStr = json.optString("config", "");
                if (!configJsonStr.isEmpty()) {
                    JSONObject configJson = new JSONObject(configJsonStr);
                    // 从 JSON 中直接提取自定义参数
                    int onDuration = configJson.optInt("onDuration", 500);
                    int offDuration = configJson.optInt("offDuration", 200);
                    int repeatIndex = configJson.optInt("repeatIndex", -1);

                    if ("preview".equalsIgnoreCase(action)) {
                        // 📳 修复：直接使用传过来的参数震动，而不是读本地配置
                        // 假设 WearVibratorHelper 有一个可以直接接收参数的方法
                        // 如果没有，你需要在 WearVibratorHelper 里加一个这样的方法
                        WearVibratorHelper.vibratePattern(this, onDuration, offDuration, repeatIndex);
                        WearLog.i(TAG, "🔄 收到预览指令，已触发即时自定义震动: on=" + onDuration + ", off=" + offDuration + ", repeat=" + repeatIndex);
                    } else if ("save".equalsIgnoreCase(action)) {
                        // 💾 持久化到手表本地
                        android.content.SharedPreferences sp = getSharedPreferences("wear_vibration_prefs", Context.MODE_PRIVATE);
                        sp.edit().putInt("on_duration", onDuration)
                                .putInt("off_duration", offDuration)
                                .putInt("repeat_index", repeatIndex)
                                .apply();
                        // 刷新内存配置
                        WearVibratorHelper.initFromPhone(this);
                        WearLog.i(TAG, "💾 收到保存指令，配置参数已更新持久化");
                    }
                }
                return; // 震动指令处理完毕，直接返回
            }


                // 3. 原有：勿扰同步包
                if ("dnd".equalsIgnoreCase(type)) {
                    int dndStatePhone = json.optInt("dnd_state", -1);
                    if (dndStatePhone == -1) {
                        WearLog.w(TAG, "⚠️ [DND同步] 收到勿扰包但缺少 dnd_state");
                        return;
                    }

                    // ✅ 提取最新延迟值，仅用于透传，绝不写入本地 SP
                    int pullDownDelayMs = json.optInt("pullDownDelayMs", 500);

                    WearLog.d(TAG, "📥 [DND同步] 收到手机勿扰状态=" + dndStatePhone
                            + " mask=" + json.optInt("mask", -1)
                            + " delay=" + pullDownDelayMs);

                    WearSyncDndManager.updateConfigs(json);       // 仅更新 mask 等必要配置
                    WearSyncDndManager.executeDndSync(this, dndStatePhone, pullDownDelayMs); // ✅ 透传延迟值
                    return;
                }
                // 4. 原有：闹钟拦截控制模组（推荐最终版，纯启动，不提前震动）
                if ("alarm".equalsIgnoreCase(type)) {
                WearLog.d(TAG, "⏰ 收到手机闹钟信令，正在将其无损打包并直发 WearAlarmActivity ➔ " + action);

                Intent alarmIntent = new Intent(this, WearAlarmActivity.class);

                // === 关键优化（解决 3\~4 秒延迟）===
                alarmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                alarmIntent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
                alarmIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
                alarmIntent.putExtra("raw_alarm_json", json.toString());
                alarmIntent.putExtra("alarm_action", action);

                WearLog.d(TAG, "⏰ 准备启动 WearAlarmActivity...");
                startActivity(alarmIntent);
                return;
            }
                // 5. 新增：处理手机发来的强制停止闹钟指令
if ("alarm".equalsIgnoreCase(type) && "FORCE_STOP_WEAR_ALARM".equalsIgnoreCase(action)) {
    WearLog.d(TAG, "🛑 收到手机发来的强制停止闹钟指令，正在关闭 WearAlarmActivity...");
    
    // 1. 创建一个 Intent 指向 WearAlarmActivity
    Intent stopIntent = new Intent(this, WearAlarmActivity.class);
    
    // 2. 设置关键 Flags
    //    NEW_TASK: 因为是从 Service 启动
    //    CLEAR_TOP: 如果 Activity 已在任务栈中，则将其上方的所有 Activity 都出栈
    //    SINGLE_TOP: 配合 CLEAR_TOP，确保复用已存在的实例，并触发 onNewIntent
    stopIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
    
    // 3. 放入一个“动作”标识，告诉 Activity 这次是来停止的，不是来启动的
    stopIntent.putExtra("alarm_action", "FORCE_STOP");
    
    // 4. 启动 Activity
    startActivity(stopIntent);
    
    // 5. 处理完毕，直接返回，避免执行后续逻辑
    return; 
}


                
                // 6. 原有：相机穿透控制模组
        if ("camera_control".equalsIgnoreCase(type)) {
            if ("CAMERA_HANDSHAKE".equalsIgnoreCase(action)) {
                WearLog.d(TAG, "CAM-W001 收到 CAMERA_HANDSHAKE");
                return;
            }
            if ("STREAM_START".equalsIgnoreCase(action)) {
                // ... 处理视频流启动
                return;
            }
            if ("STOP_CAMERA".equalsIgnoreCase(action) || "FORCE_QUIT_CAMERA".equalsIgnoreCase(action)) {
                sendBroadcast(new Intent("cn.luke.wearsync.ACTION_FORCE_QUIT_WEAR_CAMERA"));
                return;
            }
            // ✅ 新增：处理开启手机相机的动作
            if ("open_phone_camera".equalsIgnoreCase(action)) {
                WearLog.d(TAG, "📸 收到开启手机相机信令，准备启动 WearCameraActivity...");
                Intent cameraIntent = new Intent(this, WearCameraActivity.class);
                cameraIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(cameraIntent);
                return;
            }
        }

// 7. 原有：手飙日志无线远程联控模组
if ("wearlog".equalsIgnoreCase(type)) {
    boolean wearDebug = json.optBoolean("wear_log_debug", true);
    WearLog.DEBUG = wearDebug;

    WearLog.d(TAG, "🎛️ [远程同步] 接收到手机端远程控场，手表日志开闭状态同步修改为 ➔ " + wearDebug);

    if (wearDebug) {
        // ✅ 1. 先建立数据通道
        String logPath = DATA_CHANNEL_BASE_PATH + "/log";
        openLogChannelToPhone(messageEvent.getSourceNodeId(), logPath);

        // ✅ 2. 再发送一个手机能识别的“握手”信令
        try {
            JSONObject handshakeJson = new JSONObject();
            handshakeJson.put("sender", "wear");
            handshakeJson.put("type", "camera");
            handshakeJson.put("action", "LOG_CHANNEL_HANDSHAKE");
            Wearable.getMessageClient(this)
                    .sendMessage(
                            messageEvent.getSourceNodeId(),
                            UNIVERSAL_SYNC_PATH,
                            handshakeJson.toString().getBytes(StandardCharsets.UTF_8)
                    );
            WearLog.d(TAG, "📡 已发送日志通道握手信令");
        } catch (Exception e) {
            WearLog.e(TAG, "发送日志通道握手信令失败", e);
        }
    } 
    // ✅ 关键：不再用 else！直接在这里写关闭逻辑
    else {
        WearLog.d(TAG, "🛑 收到关闭日志指令，正在执行清理...");

        // ✅ 只需这一行：从源头关闭，d/i/w/e 全部静默
        WearLog.DEBUG = false;

        // ✅ 保留通道关闭：释放系统资源
        if (mLogChannel != null) {
            Wearable.getChannelClient(this).close(mLogChannel)
                    .addOnSuccessListener(aVoid -> {
                        mLogChannel = null;
                    })
                    .addOnFailureListener((Exception e) -> {
                        Log.e(TAG, "❌ 关闭日志通道失败", e);
                    });
        } else {
            WearLog.d(TAG, "⚠️ 尝试关闭日志通道，但通道引用为空，可能尚未建立或已关闭");
        }
    }
} 
// ✅ 缺失的右大括号在这里（已经修复） // 🔴 这里补上缺失的右大括号，用来闭合最外层的 if ("wearlog"...)

            } catch (Exception e) {
                WearLog.e(TAG, "🔴 解析手机发往手表的指令崩溃: " + e.getMessage(), e);
            }
        }

    @Override
    public void onChannelOpened(@NonNull ChannelClient.Channel channel) {
        String path = channel.getPath();
        WearLog.d(TAG, "CAM-W004 Channel opened path=" + path);
        super.onChannelOpened(channel);
        if (CAMERA_PREVIEW_STREAM_PATH.equals(path)) {
            WearLog.d(TAG, "CAM-W005 Camera stream channel matched");
            readH264ChannelStream(channel);
        } else {
            WearLog.d(TAG, "CAM-W006 Ignore channel " + path);
        }
    }

    @Override
    public void onChannelClosed(@NonNull ChannelClient.Channel channel, int closeReason, int appSpecificErrorCode) {
        WearLog.d(TAG, "CAM-W004C closed path=" + channel.getPath() + " reason=" + closeReason);
    }

    private void readH264ChannelStream(ChannelClient.Channel channel) {
        new Thread(() -> {
            WearLog.d(TAG, "CAM-W010 Start H264 reader thread");
            try {
                InputStream is = Tasks.await(Wearable.getChannelClient(this).getInputStream(channel));
                WearLog.d(TAG, "CAM-W011 InputStream ready");
                byte[] buffer = new byte[40960];
                long totalBytes = 0;
                int frameCount = 0;
                while (true) {
                    int length = is.read(buffer);
                    if(length <= 0){ continue; }
                    totalBytes += length;
                    frameCount++;
                    if(frameCount == 1){ WearLog.d(TAG, "CAM-W012 FIRST DATA length=" + length); }
                    if(frameCount % 50 == 0){ WearLog.d(TAG, "CAM-W013 frames=" + frameCount +" bytes=" + totalBytes); }
                    WearCameraActivity activity = WearCameraActivity.sActivityRef.get();
                    if(activity == null){ WearLog.w(TAG, "CAM-W014 Activity null"); continue; }
                    byte[] frame = new byte[length];
                    System.arraycopy(buffer, 0, frame, 0, length);
                    activity.feedH264Data(frame, length);
                }
            } catch(Exception e){
                WearLog.e(TAG, "CAM-W015 H264 reader error", e);
            }
        }).start();
    }

    // 修改方法签名，增加 path 参数
    /**
     * ⚠️ 仅用于日志传输！
     * 日志是唯一由手表主动向手机推送的数据流
     */
        // 修改方法签名，增加 path 参数
    /**
     * ⚠️ 仅用于日志传输！
     * 日志是唯一由手表主动向手机推送的数据流
     */
    private void openLogChannelToPhone(String phoneNodeId, String logPath) {
        WearLog.d(TAG, "🔌 正在建立日志 Channel: " + logPath);
        Wearable.getChannelClient(this)
            .openChannel(phoneNodeId, logPath)
            .addOnSuccessListener(channel -> {
                // ✅ 新增：保存通道引用
                mLogChannel = channel;

                Wearable.getChannelClient(this)
                    .getOutputStream(channel)
                    .addOnSuccessListener(outputStream -> {
                        WearLog.setLogOutputStream(outputStream);
                        WearLog.d(TAG, "🟢 日志输出通道就绪");
                    })
                    .addOnFailureListener(e -> WearLog.e(TAG, "❌ 获取日志输出流失败", e));
            })
            .addOnFailureListener(e -> WearLog.e(TAG, "❌ 建立日志通道失败", e));
    }
}
