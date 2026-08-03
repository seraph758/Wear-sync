package cn.luke.wearsync;

import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import androidx.annotation.NonNull;
import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.ChannelClient;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.Wearable;
import com.google.android.gms.wearable.WearableListenerService;
import org.json.JSONObject;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public class WearSyncListenerService extends WearableListenerService {

    private static final String TAG = "WearSync_WearListener";

    // --- 路径常量 ---
    private static final String UNIVERSAL_SYNC_PATH = "/wear-universal-sync";
    private static final String DATA_CHANNEL_BASE_PATH = "/wear_data_channel";
    private static final String SIGNAL_BASE_PATH = "/wear-universal-sync";
    private static final String CAMERA_DATA_PATH = SIGNAL_BASE_PATH + "/camera";
    private static final String CAMERA_PREVIEW_STREAM_PATH = DATA_CHANNEL_BASE_PATH + "/camera";
    private static final String FILE_TRANSFER_CHANNEL_PATH = "/wear-sync/file-transfer";
    private static final String FILE_TRANSFER_STATUS_PATH = "/file-transfer-status";

    private ChannelClient.Channel mLogChannel;

    @Override
    public void onCreate() {
        super.onCreate();
        WearLog.d(TAG, "【SYS-001】WearSyncListenerService 已创建");
    }

    @Override
    public void onMessageReceived(@NonNull MessageEvent messageEvent) {
        // 【1. 入口检查】
        WearLog.d(TAG, "【MSG-001】收到消息. Path: " + messageEvent.getPath() + " | Source: " + messageEvent.getSourceNodeId());

        if (!UNIVERSAL_SYNC_PATH.equalsIgnoreCase(messageEvent.getPath())) {
            WearLog.w(TAG, "【MSG-002】路径不匹配，忽略. Expected: " + UNIVERSAL_SYNC_PATH);
            return;
        }

        byte[] data = messageEvent.getData();
        if (data.length == 0) {
            WearLog.w(TAG, "【MSG-003】收到空数据包，忽略");
            return;
        }

        try {
            String jsonStr = new String(data, StandardCharsets.UTF_8);
            JSONObject json = new JSONObject(jsonStr);
            String sender = json.optString("sender", "");
            String type = json.optString("type", "");
            String action = json.optString("action", "");

            // 【2. 防循环】
            if ("wear".equalsIgnoreCase(sender)) {
                WearLog.d(TAG, "【MSG-004】检测到来自手表的消息，防止循环，忽略");
                return;
            }

            WearLog.d(TAG, "【MSG-005】解析指令成功. Type: [" + type + "], Action: [" + action + "]");

            // --- 模块分发 ---

            // 【3. 震动模块】
            if ("vibration".equalsIgnoreCase(type)) {
                handleVibrationCommand(json);
                return;
            }

            // 【4. DND模块】
            if ("dnd".equalsIgnoreCase(type)) {
                handleDndCommand(json, messageEvent.getSourceNodeId());
                return;
            }

            // 【5. 闹钟模块】
            if ("alarm".equalsIgnoreCase(type)) {
                handleAlarmCommand(json, messageEvent.getSourceNodeId());
                return;
            }

            // 【6. 相机模块】
            if ("camera_control".equalsIgnoreCase(type)) {
                handleCameraCommand(json, messageEvent.getSourceNodeId());
                return;
            }

            // 【7. 日志模块】
            if ("wearlog".equalsIgnoreCase(type)) {
                handleWearLogCommand(json, messageEvent.getSourceNodeId());
                return;
            }

            // 【8. 文件传输模块】
            if ("file_transfer".equalsIgnoreCase(type)) {
                handleFileTransferCommand(json, messageEvent.getSourceNodeId());
                return;
            }

            WearLog.w(TAG, "【MSG-999】未知指令类型，忽略. Type: " + type);

        } catch (Exception e) {
            WearLog.e(TAG, "【MSG-ERR】解析或处理消息时崩溃", e);
        }
    }

    // --- 模块处理函数 ---

    private void handleVibrationCommand(JSONObject json) {
        WearLog.d(TAG, "【VIB-001】开始处理震动指令");
        String action = json.optString("action", "");
        String configJsonStr = json.optString("config", "");
        if (configJsonStr.isEmpty()) {
            WearLog.w(TAG, "【VIB-002】震动指令缺少配置信息");
            return;
        }
        try {
            JSONObject configJson = new JSONObject(configJsonStr);
            int onDuration = configJson.optInt("onDuration", 500);
            int offDuration = configJson.optInt("offDuration", 200);
            int repeatIndex = configJson.optInt("repeatIndex", -1);

            if ("preview".equalsIgnoreCase(action)) {
                WearLog.d(TAG, "【VIB-003】执行震动预览. on=" + onDuration + ", off=" + offDuration);
                WearVibratorHelper.vibratePattern(this, onDuration, offDuration, repeatIndex);
            } else if ("save".equalsIgnoreCase(action)) {
                WearLog.d(TAG, "【VIB-004】保存震动配置");
                getSharedPreferences("wear_vibration_prefs", Context.MODE_PRIVATE)
                        .edit()
                        .putInt("onDuration", onDuration)
                        .putInt("offDuration", offDuration)
                        .putInt("repeatIndex", repeatIndex)
                        .apply();
                WearVibratorHelper.initFromPhone(this);
            }
        } catch (Exception e) {
            WearLog.e(TAG, "【VIB-ERR】处理震动指令异常", e);
        }
    }

    private void handleDndCommand(JSONObject json, String sourceNodeId) {
        WearLog.d(TAG, "【DND-001】开始处理勿扰指令");
        int dndStatePhone = json.optInt("dnd_state", -1);
        if (dndStatePhone == -1) {
            WearLog.w(TAG, "【DND-002】指令缺少 dnd_state");
            return;
        }
        int pullDownDelayMs = json.optInt("pullDownDelayMs", 500);
        WearLog.d(TAG, "【DND-003】状态=" + dndStatePhone + " | 延迟=" + pullDownDelayMs);
        WearSyncDndManager.updateConfigs(json);
        WearSyncDndManager.executeDndSync(this, dndStatePhone, pullDownDelayMs);
    }

    private void handleAlarmCommand(JSONObject json, String sourceNodeId) {
        WearLog.d(TAG, "【ALM-001】开始处理闹钟指令");
        String action = json.optString("action", "");

        if ("FORCE_STOP_WEAR_ALARM".equalsIgnoreCase(action) || "FORCE_STOP".equalsIgnoreCase(action)) {
            WearLog.d(TAG, "【ALM-002】收到强制停止指令");
            WearAlarmActivity.handleIncomingCommand(this, json);
        } else {
            WearLog.d(TAG, "【ALM-003】准备启动闹钟界面. Action: " + action);
            Intent alarmIntent = new Intent(this, WearAlarmActivity.class);
            alarmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            alarmIntent.putExtra("raw_alarm_json", json.toString());
            alarmIntent.putExtra("alarm_action", action);
            startActivity(alarmIntent);
            WearAlarmActivity.handleIncomingCommand(this, json);
        }
    }

    private void handleCameraCommand(JSONObject json, String sourceNodeId) {
        WearLog.d(TAG, "【CAM-001】开始处理相机指令");
        String action = json.optString("action", "");

        if ("CAMERA_HANDSHAKE".equalsIgnoreCase(action)) {
            WearLog.d(TAG, "【CAM-002】收到相机握手信令");
            return;
        }
        if ("STREAM_START".equalsIgnoreCase(action)) {
            WearLog.d(TAG, "【CAM-003】收到视频流启动信令");
            return;
        }
        if ("STOP_CAMERA".equalsIgnoreCase(action) || "FORCE_QUIT_CAMERA".equalsIgnoreCase(action)) {
            WearLog.d(TAG, "【CAM-004】收到停止相机指令，准备强制关闭");
            WearCameraActivity.forceClose();
            return;
        }
        if ("open_phone_camera".equalsIgnoreCase(action)) {
            WearLog.d(TAG, "【CAM-005】收到开启手机相机指令，准备启动预览界面");
            Intent cameraIntent = new Intent(this, WearCameraActivity.class);
            cameraIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(cameraIntent);

            String cameraStreamPath = "/wear-universal-sync/camera";
            Wearable.getChannelClient(this)
                    .openChannel(sourceNodeId, cameraStreamPath)
                    .addOnSuccessListener(channel -> WearLog.d(TAG, "【CAM-006】相机数据通道连接请求已发送"))
                    .addOnFailureListener(e -> WearLog.e(TAG, "【CAM-ERR】发送相机数据通道连接请求失败", e));
            return;
        }
        WearLog.w(TAG, "【CAM-007】未知的相机指令Action: " + action);
    }

    private void handleWearLogCommand(JSONObject json, String sourceNodeId) {
        boolean wearDebug = json.optBoolean("wear_log_debug", true);
        WearLog.d(TAG, "【LOG-001】收到远程日志控制指令. 状态: " + wearDebug);
        WearLog.DEBUG = wearDebug;

        if (wearDebug) {
            WearLog.d(TAG, "【LOG-002】日志已开启，准备建立日志通道");
            String logPath = DATA_CHANNEL_BASE_PATH + "/log";
            openLogChannelToPhone(sourceNodeId, logPath);
            // 发送握手信令...
        } else {
            WearLog.d(TAG, "【LOG-003】日志已关闭，正在清理资源");
            if (mLogChannel != null) {
                Wearable.getChannelClient(this).close(mLogChannel)
                        .addOnSuccessListener(aVoid -> mLogChannel = null)
                        .addOnFailureListener(e -> WearLog.e(TAG, "【LOG-ERR】关闭日志通道失败", e));
            }
        }
    }

    private void handleFileTransferCommand(JSONObject json, String sourceNodeId) {
        WearLog.d(TAG, "【APK-001】开始处理文件传输指令");
        String action = json.optString("action", "");
        if ("PREPARE_RECEIVE".equalsIgnoreCase(action)) {
            String fileName = json.optString("fileName", "unknown.apk");
            long fileSize = json.optLong("fileSize", 0);
            WearLog.i(TAG, "【APK-002】准备接收文件: " + fileName + " (" + fileSize + "B)");
            // 回复ACK...
            try {
                JSONObject ack = new JSONObject();
                ack.put("sender", "wear");
                ack.put("type", "file_transfer");
                ack.put("action", "READY_TO_RECEIVE");
                Wearable.getMessageClient(this)
                        .sendMessage(sourceNodeId, UNIVERSAL_SYNC_PATH, ack.toString().getBytes(StandardCharsets.UTF_8))
                        .addOnSuccessListener(aVoid -> WearLog.d(TAG, "【APK-003】ACK已发送"))
                        .addOnFailureListener(e -> WearLog.e(TAG, "【APK-ERR】ACK发送失败", e));
            } catch (Exception e) {
                WearLog.e(TAG, "【APK-ERR】构建ACK异常", e);
            }
        }
    }

    // --- Channel 回调 ---

    @Override
    public void onChannelOpened(@NonNull ChannelClient.Channel channel) {
        String path = channel.getPath();
        WearLog.d(TAG, "【CHN-001】通道已打开. Path: " + path);
        super.onChannelOpened(channel);

        if (CAMERA_PREVIEW_STREAM_PATH.equals(path)) {
            WearLog.d(TAG, "【CHN-002】匹配到相机流通道，开始读取");
            readH264ChannelStream(channel);
        } else if (path.startsWith(FILE_TRANSFER_CHANNEL_PATH)) {
            WearLog.i(TAG, "【CHN-003】匹配到文件传输通道，准备接收");
            // 解析路径中的元数据
            String pathData = path.substring(FILE_TRANSFER_CHANNEL_PATH.length() + 1);
            long expectedSize = -1L;
            String fileName = "unknown_file";
            int slashIndex = pathData.indexOf('/');
            if (slashIndex != -1) {
                try {
                    expectedSize = Long.parseLong(pathData.substring(0, slashIndex));
                } catch (NumberFormatException e) {
                    WearLog.w(TAG, "【CHN-WARN】无法解析文件大小");
                }
                fileName = Uri.decode(pathData.substring(slashIndex + 1));
            } else {
                fileName = Uri.decode(pathData);
            }
            final String finalFileName = fileName;
            final long finalExpectedSize = expectedSize;
            final String nodeId = channel.getNodeId();
            new Thread(() -> receiveFileFromChannel(channel, finalFileName, nodeId, finalExpectedSize)).start();
        } else {
            WearLog.d(TAG, "【CHN-004】忽略未知通道: " + path);
        }
    }

    @Override
    public void onChannelClosed(@NonNull ChannelClient.Channel channel, int closeReason, int appSpecificErrorCode) {
        WearLog.d(TAG, "【CHN-005】通道已关闭. Path: " + channel.getPath() + " | Reason: " + closeReason);
    }

    // --- 核心读取方法 ---

    private void readH264ChannelStream(ChannelClient.Channel channel) {
        new Thread(() -> {
            WearLog.d(TAG, "【STR-001】启动H264读取线程");
            try (DataInputStream dis = new DataInputStream(Tasks.await(Wearable.getChannelClient(this).getInputStream(channel)))) {
                WearLog.d(TAG, "【STR-002】数据流准备就绪，开始循环读取");
                long totalBytes = 0;
                int frameCount = 0;
                while (!Thread.currentThread().isInterrupted()) {
                    int frameLen = dis.readInt();
                    if (frameLen <= 0 || frameLen > 4 * 1024 * 1024) {
                        WearLog.e(TAG, "【STR-ERR】异常帧长度: " + frameLen + "，断开连接");
                        break;
                    }
                    long timestamp = dis.readLong();
                    int flags = dis.readInt();
                    byte[] h264Data = new byte[frameLen];
                    dis.readFully(h264Data);

                    totalBytes += frameLen + 16;
                    frameCount++;

                    if (frameCount == 1) WearLog.d(TAG, "【STR-003】收到第一帧. Len: " + frameLen);
                    if (frameCount % 50 == 0) WearLog.d(TAG, "【STR-004】流状态. 帧数: " + frameCount + " | 总字节: " + totalBytes);

                    WearCameraActivity activity = WearCameraActivity.sActivityRef.get();
                    if (activity != null) {
                        activity.feedH264Data(h264Data);
                    } else {
                        WearLog.w(TAG, "【STR-WARN】Activity为空，丢弃帧");
                    }
                }
            } catch (EOFException e) {
                WearLog.d(TAG, "【STR-005】流正常结束 (EOF)");
            } catch (Exception e) {
                WearLog.e(TAG, "【STR-ERR】读取H264流时发生错误", e);
            }
        }, "CameraStreamReader").start();
    }

    // --- 文件接收核心方法 ---
    private void receiveFileFromChannel(ChannelClient.Channel channel, String fileName, String nodeId, long expectedSize) {
        WearLog.i(TAG, "【FIL-001】开始接收文件: " + fileName + " (期望大小: " + expectedSize + "B)");
        Uri collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
        values.put(MediaStore.Downloads.MIME_TYPE, "application/vnd.android.package-archive");
        values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Received");
        
        Uri fileUri = getContentResolver().insert(collection, values);
        if (fileUri == null) {
            WearLog.e(TAG, "【FIL-ERR】无法创建文件 Uri");
            sendFileTransferStatus(nodeId, "error:" + fileName);
            return;
        }

        try (InputStream inputStream = Tasks.await(Wearable.getChannelClient(this).getInputStream(channel));
             OutputStream outputStream = getContentResolver().openOutputStream(fileUri)) {
            
            if (outputStream == null) throw new Exception("无法打开输出流");

            byte[] buffer = new byte[32768];
            long bytesReceived = 0L;
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
                bytesReceived += bytesRead;
            }
            outputStream.flush();

            if (expectedSize != -1L && bytesReceived != expectedSize) {
                throw new Exception("文件不完整: 期望 " + expectedSize + "B, 实际 " + bytesReceived + "B");
            }
            WearLog.i(TAG, "【FIL-002】文件接收成功! 大小: " + bytesReceived + "B");
            sendFileTransferStatus(nodeId, "success:" + fileName);

        } catch (Exception e) {
            WearLog.e(TAG, "【FIL-ERR】文件接收失败", e);
            getContentResolver().delete(fileUri, null);
            sendFileTransferStatus(nodeId, "error:" + fileName);
        } finally {
            try {
                Tasks.await(Wearable.getChannelClient(this).close(channel));
                WearLog.d(TAG, "【FIL-003】文件传输通道已关闭");
            } catch (Exception e) {
                WearLog.w(TAG, "【FIL-WARN】关闭通道时出错: " + e.getMessage());
            }
        }
    }

    private void sendFileTransferStatus(String nodeId, String status) {
        try {
            Tasks.await(Wearable.getMessageClient(this).sendMessage(nodeId, FILE_TRANSFER_STATUS_PATH, status.getBytes(StandardCharsets.UTF_8)));
            WearLog.d(TAG, "【FIL-004】状态回执已发送: " + status);
        } catch (Exception e) {
            WearLog.w(TAG, "【FIL-WARN】发送状态回执失败: " + e.getMessage());
        }
    }

    private void openLogChannelToPhone(String phoneNodeId, String logPath) {
        WearLog.d(TAG, "【LOG-004】正在建立日志通道: " + logPath);
        Wearable.getChannelClient(this)
                .openChannel(phoneNodeId, logPath)
                .addOnSuccessListener(channel -> {
                    mLogChannel = channel;
                    Wearable.getChannelClient(this).getOutputStream(channel)
                            .addOnSuccessListener(outputStream -> {
                                WearLog.setLogOutputStream(outputStream);
                                WearLog.d(TAG, "【LOG-005】日志输出通道就绪");
                            })
                            .addOnFailureListener(e -> WearLog.e(TAG, "【LOG-ERR】获取日志输出流失败", e));
                })
                .addOnFailureListener(e -> WearLog.e(TAG, "【LOG-ERR】建立日志通道失败", e));
    }
}
