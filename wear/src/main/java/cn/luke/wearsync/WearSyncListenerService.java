package cn.luke.wearsync;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import androidx.annotation.NonNull;

import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.ChannelClient;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.Wearable;
import com.google.android.gms.wearable.WearableListenerService;

import org.json.JSONObject;

import java.io.File;
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
    private static final String DATA_CHANNEL_BASE_PATH = "/wear_data_channel";

    private static final String CAMERA_PREVIEW_STREAM_PATH = DATA_CHANNEL_BASE_PATH + "/camera";
    private ChannelClient.Channel mLogChannel;
    // ========== 新增：APK文件传输通道常量 ==========
    private static final String FILE_TRANSFER_CHANNEL_PATH = "/wear-sync/file-transfer";
    private static final String APK_SAVE_DIR_NAME = "apk"; // APK固定保存子目录
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
             if (data.length == 0) return;

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
                        // ✅ 修复：直接使用从手机端传过来的最新参数进行震动预览
                        // 这样就绕过了读取本地旧配置的步骤
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
        // 4. 闹钟控制模组 (最终合并版)
        if ("alarm".equalsIgnoreCase(type)) {
            WearLog.d(TAG, "⏰ 收到手机闹钟信令，action=" + action);

            // ✅ 优先判断：是否是强制停止指令？
            if ("FORCE_STOP_WEAR_ALARM".equalsIgnoreCase(action) || "FORCE_STOP".equalsIgnoreCase(action)) {
                WearLog.d(TAG, "🛑 收到强制停止指令，通过 handleIncomingCommand 关闭闹钟界面...");
                // 统一走重构后的静态方法，不再在 Listener 里手动 startActivity
                WearAlarmActivity.handleIncomingCommand(this, json);
            } else {
                // ✅ 正常启动闹钟：仍然需要 startActivity，因为 Activity 可能尚未创建
                WearLog.d(TAG, "⏰ 准备启动 WearAlarmActivity...");

                Intent alarmIntent = new Intent(this, WearAlarmActivity.class);
                alarmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                                   | Intent.FLAG_ACTIVITY_SINGLE_TOP
                                   | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                alarmIntent.putExtra("raw_alarm_json", json.toString());
                alarmIntent.putExtra("alarm_action", action);
                startActivity(alarmIntent);

                // 启动后也将数据传递给静态方法，确保 UI 状态同步
                WearAlarmActivity.handleIncomingCommand(this, json);
            }
            return; // 闹钟逻辑处理完毕，返回
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
            // ✅ 修复：处理开启手机相机的动作，并主动连接手机
            if ("open_phone_camera".equalsIgnoreCase(action)) {
                WearLog.d(TAG, "📸 收到开启手机相机信令，准备启动 WearCameraActivity 并连接手机...");
                
                // 1. 启动手表上的相机预览界面
                Intent cameraIntent = new Intent(this, WearCameraActivity.class);
                cameraIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(cameraIntent);
            
                // 2. 【关键修复】主动向手机发起 Channel 连接，触发手机开始推流
                // 注意：这里的 CAMERA_PREVIEW_STREAM_PATH 必须和手机端的 UNIVERSAL_SYNC_PATH + "/camera" 完全一致
                // 根据你之前的代码，手机端的完整路径是 "/wear-universal-sync/camera"
                String cameraStreamPath = "/wear-universal-sync/camera"; 
                
                Wearable.getChannelClient(this)
                    .openChannel(messageEvent.getSourceNodeId(), cameraStreamPath)
                    .addOnSuccessListener(channel -> WearLog.d(TAG, "✅ 相机数据通道连接请求已发送"))
                    .addOnFailureListener(e -> WearLog.e(TAG, "❌ 发送相机数据通道连接请求失败", e));
            
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
                                .addOnSuccessListener(aVoid -> mLogChannel = null)
                                .addOnFailureListener((Exception e) -> WearLog.e(TAG, "❌ 关闭日志通道失败", e));
                    } else {
                        WearLog.d(TAG, "⚠️ 尝试关闭日志通道，但通道引用为空，可能尚未建立或已关闭");
                    }
                }
            }
            // ========== 新增：APK文件传输准备信令 ==========
           if ("file_transfer".equalsIgnoreCase(type)) {
                if ("PREPARE_RECEIVE".equalsIgnoreCase(action)) {
                    String fileName = json.optString("fileName", "unknown.apk");
                    long fileSize = json.optLong("fileSize", 0);
                    WearLog.i(TAG, "📂 [APK接收] 准备接收: " + fileName + " (" + fileSize + "B)");

                    // 回复ACK通知手机端可以打开Channel
                    try {
                        JSONObject ack = new JSONObject();
                        ack.put("sender", "wear");
                        ack.put("type", "file_transfer");
                        ack.put("action", "READY_TO_RECEIVE");

                        Wearable.getMessageClient(this)
                                .sendMessage(messageEvent.getSourceNodeId(), UNIVERSAL_SYNC_PATH,
                                        ack.toString().getBytes(StandardCharsets.UTF_8))
                                .addOnSuccessListener(aVoid ->
                                        WearLog.d(TAG, "📡 [APK接收] ACK已发送"))
                                .addOnFailureListener(e ->
                                        WearLog.e(TAG, "❌ [APK接收] ACK发送失败", e));
                    } catch (Exception e) {
                        WearLog.e(TAG, "❌ [APK接收] 构建ACK异常", e);
                    }
                }
               // ⚠️ 关键：处理完毕立即return，避免落入下方其他模块的逻辑
           }

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
        // ========== 新增：APK文件传输通道处理 ==========
        if (FILE_TRANSFER_CHANNEL_PATH.equals(path)) {
            WearLog.i(TAG, "📡 [APK接收] Channel已建立，开始写入文件...");

            // 确保APK保存目录存在（使用内部存储，无需申请权限）
            File apkDir = new File(getFilesDir(), APK_SAVE_DIR_NAME);
            if (!apkDir.exists() && !apkDir.mkdirs()) {
                WearLog.e(TAG, "❌ [APK接收] 创建目录失败: " + apkDir.getAbsolutePath());
                return;
            }

            // 使用时间戳命名避免覆盖
            File outFile = new File(apkDir, System.currentTimeMillis() + "_incoming.apk");
            Uri fileUri = Uri.fromFile(outFile);

            Wearable.getChannelClient(this)
                    .receiveFile(channel, fileUri, false)   // 返回 Task<Void>
                    .addOnSuccessListener(unused -> WearLog.i(TAG, "✅ [APK接收] 保存成功: " + outFile.getAbsolutePath()))
                    .addOnFailureListener(e -> {
                        WearLog.e(TAG, "❌ [APK接收] 接收失败", e);
                        if (outFile.exists() && !outFile.delete()) {
                            WearLog.w(TAG, "删除旧文件失败: " + outFile.getName());
                        }

                    });
        }
    }

    @Override
    public void onChannelClosed(@NonNull ChannelClient.Channel channel, int closeReason, int appSpecificErrorCode) {
        WearLog.d(TAG, "CAM-W004C closed path=" + channel.getPath() + " reason=" + closeReason);
    }

    private void readH264ChannelStream(ChannelClient.Channel channel) {
        new Thread(() -> {
            WearLog.d(TAG, "CAM-W010 Start H264 reader thread");
            try (InputStream is = Tasks.await(Wearable.getChannelClient(this).getInputStream(channel))) {

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
