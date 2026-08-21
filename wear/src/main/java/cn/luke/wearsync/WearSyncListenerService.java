package cn.luke.wearsync;

import android.app.NotificationManager;
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
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public class WearSyncListenerService extends WearableListenerService {

    private static final String TAG = "WearSync_WearListener";

    // --- 路径常量 ---
    private static final String UNIVERSAL_SYNC_PATH = "/wear-universal-sync";
    private static final String DATA_CHANNEL_BASE_PATH = "/wear_data_channel";
    private static final String FILE_TRANSFER_CHANNEL_PATH = "/wear-sync/file-transfer";
    private static final String FILE_TRANSFER_STATUS_PATH = "/file-transfer-status";
    public static final String WEAR_MSG_PATH_CAMERA_LIST = "/camera/info_list";

    private ChannelClient.Channel mLogChannel;

    @Override
    public void onCreate() {
        super.onCreate();
        WearLog.d(TAG, "【SYS-001】WearSyncListenerService 已创建");
    }

    @Override
    public void onMessageReceived(@NonNull MessageEvent messageEvent) {
        WearLog.d(TAG, "【MSG-001】收到消息. Path: " + messageEvent.getPath() + " | Source: " + messageEvent.getSourceNodeId());

        String path = messageEvent.getPath();
        if (WEAR_MSG_PATH_CAMERA_LIST.equals(path)) {
            byte[] data = messageEvent.getData();
            Intent intent = new Intent("cn.luke.wearsync.ACTION_CAMERA_LIST_RECEIVED");
            intent.putExtra("camera_list", new String(data, StandardCharsets.UTF_8));
            sendBroadcast(intent);
            return;
        }

        if (!UNIVERSAL_SYNC_PATH.equalsIgnoreCase(path)) {
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

            if ("wear".equalsIgnoreCase(sender)) {
                WearLog.d(TAG, "【MSG-004】检测到来自手表的消息，防止循环，忽略");
                return;
            }

            WearLog.d(TAG, "【MSG-005】解析指令成功. Type: [" + type + "], Action: [" + action + "]");

            if ("vibration".equalsIgnoreCase(type)) {
                handleVibrationCommand(json);
                return;
            }
            if ("dnd".equalsIgnoreCase(type)) {
                handleDndCommand(json);
                return;
            }
            if ("alarm".equalsIgnoreCase(type)) {
                handleAlarmCommand(json);
                return;
            }
            if ("camera_control".equalsIgnoreCase(type)) {
                handleCameraCommand(json);
                return;
            }
            if ("wearlog".equalsIgnoreCase(type)) {
                handleWearLogCommand(json, messageEvent.getSourceNodeId());
                return;
            }
            if ("file_transfer".equalsIgnoreCase(type)) {
                handleFileTransferCommand(json, messageEvent.getSourceNodeId());
                return;
            }

            WearLog.w(TAG, "【MSG-999】未知指令类型，忽略. Type: " + type);

        } catch (Exception e) {
            WearLog.e(TAG, "【MSG-ERR】解析或处理消息时崩溃", e);
        }
    }

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

    private void handleDndCommand(JSONObject json) {
        WearLog.d(TAG, "【DND-001】开始处理勿扰指令");
        int dndStatePhone = json.optInt("dnd_state", -1);
        if (dndStatePhone == -1) {
            WearLog.w(TAG, "【DND-002】指令缺少 dnd_state");
            return;
        }

        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        int currentWatchFilter = (nm != null) ? nm.getCurrentInterruptionFilter() : -1;

        WearLog.d(TAG, "🔍 [DND状态检查] 手机目标值=" + dndStatePhone + " 手表当前值=" + currentWatchFilter);

        if (dndStatePhone == currentWatchFilter) {
            WearLog.d(TAG, "✅ [DND一致] 状态相同，跳过系统变更与子联动，直接 Return");
            return; 
        }

        int pullDownDelayMs = json.optInt("pullDownDelayMs", 500);
        WearSyncDndManager.updateConfigs(json);

        WearLog.d(TAG, "⚡ [DND变化] 手表=" + currentWatchFilter + " → 手机=" + dndStatePhone + "，准备执行变更");
        WearSyncDndManager.executeDndSync(this, dndStatePhone, pullDownDelayMs);
    }

    private void handleAlarmCommand(JSONObject json) {
        WearLog.d(TAG, "【ALM-001】开始处理闹钟指令");
        String action = json.optString("action", "");

        if ("FORCE_STOP_WEAR_ALARM".equalsIgnoreCase(action) || "FORCE_STOP".equalsIgnoreCase(action)) {
            WearLog.d(TAG, "【ALM-002】收到强制停止指令");
            WearAlarmActivity activity = WearAlarmActivity.getInstance();
            if (activity != null) {
                activity.runOnUiThread(() -> {
                    activity.cleanExit();
                    WearLog.d(TAG, "【ALM-003】已在主线程关闭闹钟界面");
                });
            } else {
                WearLog.d(TAG, "【ALM-004】闹钟界面未运行，无需关闭");
            }
        } else {
            WearLog.d(TAG, "【ALM-005】准备启动闹钟界面. Action: " + action);
            Intent alarmIntent = new Intent(this, WearAlarmActivity.class);
            // 🎯 从 Service 启动 Activity 必须使用 NEW_TASK。
            alarmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            alarmIntent.putExtra("raw_alarm_json", json.toString());
            alarmIntent.putExtra("alarm_action", action);
            startActivity(alarmIntent);
        }
    }

    private void handleCameraCommand(JSONObject json) {
        WearLog.d(TAG, "【CAM-001】开始处理相机指令");
        String action = json.optString("action", "");

        if ("CAMERA_HANDSHAKE".equalsIgnoreCase(action) || "STREAM_START".equalsIgnoreCase(action)) {
            return;
        }
        if ("STOP_CAMERA".equalsIgnoreCase(action) || "FORCE_QUIT_CAMERA".equalsIgnoreCase(action)) {
            WearCameraActivity.forceClose();
            return;
        }
        if ("open_phone_camera".equalsIgnoreCase(action)) {
            Intent cameraIntent = new Intent(this, WearCameraActivity.class);
            // 🎯 在 Service 环境下启动 Activity 必须使用 NEW_TASK。
            cameraIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(cameraIntent);
        }
    }

    private void handleWearLogCommand(JSONObject json, String sourceNodeId) {
        boolean wearDebug = json.optBoolean("wear_log_debug", true);
        WearLog.DEBUG = wearDebug;

        if (wearDebug) {
            String logPath = DATA_CHANNEL_BASE_PATH + "/log";
            openLogChannelToPhone(sourceNodeId, logPath);
        } else {
            if (mLogChannel != null) {
                Wearable.getChannelClient(this).close(mLogChannel)
                        .addOnSuccessListener(taskResult -> mLogChannel = null)
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
            try {
                JSONObject ack = new JSONObject();
                ack.put("sender", "wear");
                ack.put("type", "file_transfer");
                ack.put("action", "READY_TO_RECEIVE");
                Wearable.getMessageClient(this)
                        .sendMessage(sourceNodeId, UNIVERSAL_SYNC_PATH, ack.toString().getBytes(StandardCharsets.UTF_8))
                        .addOnSuccessListener(taskResult -> WearLog.d(TAG, "【APK-003】ACK已发送"))
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

        if (path.startsWith(FILE_TRANSFER_CHANNEL_PATH)) {
            WearLog.i(TAG, "【CHN-003】匹配到文件传输通道，准备接收");
            String pathData = path.substring(FILE_TRANSFER_CHANNEL_PATH.length() + 1);
            
            int slashIndex = pathData.indexOf('/');
            final long finalExpectedSize;
            final String finalFileName;
            
            if (slashIndex != -1) {
                long size;
                try {
                    size = Long.parseLong(pathData.substring(0, slashIndex));
                } catch (NumberFormatException e) {
                    WearLog.w(TAG, "【CHN-WARN】无法解析文件大小");
                    size = -1L;
                }
                finalExpectedSize = size;
                finalFileName = Uri.decode(pathData.substring(slashIndex + 1));
            } else {
                finalExpectedSize = -1L;
                finalFileName = Uri.decode(pathData);
            }
            
            final String nodeId = channel.getNodeId();
            // 启动独立线程接收
            new Thread(() -> receiveFileFromChannel(channel, finalFileName, nodeId, finalExpectedSize)).start();
        } else {
            WearLog.d(TAG, "【CHN-004】忽略未知通道: " + path);
        }
    }

    @Override
    public void onChannelClosed(@NonNull ChannelClient.Channel channel, int closeReason, int appSpecificErrorCode) {
        WearLog.d(TAG, "【CHN-005】通道已关闭. Path: " + channel.getPath() + " | Reason: " + closeReason + " | AppErrorCode: " + appSpecificErrorCode);
    }

    private void receiveFileFromChannel(ChannelClient.Channel channel, String fileName, String nodeId, long expectedSize) {
        WearLog.i(TAG, "【FIL-001】开始接收文件: " + fileName + " (期望大小: " + expectedSize + "B)");
        
        Uri fileUri = insertFileIntoMediaStore(fileName);
        if (fileUri == null) {
            WearLog.e(TAG, "【FIL-ERR】无法创建文件 Uri，返回值为空");
            sendFileTransferStatus(nodeId, "error:" + fileName);
            return;
        }
        
        WearLog.d(TAG, "【FIL-001-1】成功创建文件 Uri: " + fileUri);

        try (InputStream inputStream = Tasks.await(Wearable.getChannelClient(this).getInputStream(channel));
             OutputStream outputStream = getContentResolver().openOutputStream(fileUri)) {
            
            if (outputStream == null) {
                throw new Exception("无法打开输出流 OutputStream 为空");
            }
            
            transferData(inputStream, outputStream, expectedSize);
            
            WearLog.i(TAG, "【FIL-002】文件接收成功! 大小: " + expectedSize + "B");
            sendFileTransferStatus(nodeId, "success:" + fileName);

            // 🚀 发送本地广播，通知 Activity 预览照片
            notifyFileReceived(fileUri);

        } catch (Exception e) {
            handleReceiveError(fileUri, fileName, nodeId, e);
        } finally {
            closeChannel(channel);
        }
    }

    private Uri insertFileIntoMediaStore(String fileName) {
        Uri collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        ContentValues values = createContentValuesForFile(fileName);
        try {
            return getContentResolver().insert(collection, values);
        } catch (Exception e) {
            WearLog.e(TAG, "【FIL-ERR】MediaStore 插入异常: " + e.getMessage(), e);
            return null;
        }
    }

    private void transferData(InputStream is, OutputStream os, long expectedSize) throws IOException {
        WearLog.d(TAG, "【FIL-001-2】开始数据传输...");
        byte[] buffer = new byte[32768];
        long bytesReceived = 0L;
        int bytesRead;
        int packetCount = 0;

        while ((bytesRead = is.read(buffer)) != -1) {
            os.write(buffer, 0, bytesRead);
            bytesReceived += bytesRead;
            packetCount++;
            if (packetCount % 16 == 0) {
                WearLog.d(TAG, "【FIL-READ】已接收字节数: " + bytesReceived + " / " + expectedSize);
            }
        }
        os.flush();
        WearLog.d(TAG, "【FIL-001-3】传输结束，总共接收: " + bytesReceived + "B");

        if (expectedSize != -1L && bytesReceived != expectedSize) {
            throw new IOException("文件不完整: 期望 " + expectedSize + "B, 实际 " + bytesReceived + "B");
        }
    }

    private void notifyFileReceived(Uri fileUri) {
        Intent intent = new Intent("cn.luke.wearsync.ACTION_FILE_RECEIVED");
        intent.putExtra("file_uri", fileUri.toString());
        sendBroadcast(intent);
    }

    private void handleReceiveError(Uri fileUri, String fileName, String nodeId, Exception e) {
        WearLog.e(TAG, "【FIL-ERR】文件接收过程中发生异常", e);
        if (fileUri != null) {
            try {
                getContentResolver().delete(fileUri, null);
                WearLog.d(TAG, "【FIL-CLEAN】已清理损坏的目标文件");
            } catch (Exception ex) {
                WearLog.w(TAG, "【FIL-WARN】清理文件失败: " + ex.getMessage());
            }
        }
        sendFileTransferStatus(nodeId, "error:" + fileName);
    }

    private void closeChannel(ChannelClient.Channel channel) {
        try {
            Tasks.await(Wearable.getChannelClient(this).close(channel));
            WearLog.d(TAG, "【FIL-003】文件传输通道已关闭");
        } catch (Exception e) {
            WearLog.w(TAG, "【FIL-WARN】关闭通道时出错: " + e.getMessage());
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

    private ContentValues createContentValuesForFile(String fileName) {
        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
        
        String mimeType = "application/octet-stream";
        String lowerName = fileName.toLowerCase();
        if (lowerName.endsWith(".jpg") || lowerName.endsWith(".jpeg")) {
            mimeType = "image/jpeg";
        } else if (lowerName.endsWith(".heic") || lowerName.endsWith(".heif")) {
            mimeType = "image/heif";
        } else if (lowerName.endsWith(".webp")) {
            mimeType = "image/webp";
        } else if (lowerName.endsWith(".apk")) {
            mimeType = "application/vnd.android.package-archive";
        }
        values.put(MediaStore.Downloads.MIME_TYPE, mimeType);
        values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Received");
        return values;
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
