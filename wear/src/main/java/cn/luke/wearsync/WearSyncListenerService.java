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

    private ChannelClient.Channel mLogChannel;

    @Override
    public void onCreate() {
        super.onCreate();
        WearLog.d(TAG, "【SYS-001】WearSyncListenerService 已创建");
    }

    @Override
    public void onMessageReceived(@NonNull MessageEvent messageEvent) {
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
                handleAlarmCommand(json, messageEvent.getSourceNodeId());
                return;
            }
            if ("camera_control".equalsIgnoreCase(type)) {
                handleCameraCommand(json, messageEvent.getSourceNodeId());
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

    private void handleAlarmCommand(JSONObject json, String sourceNodeId) {
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
            alarmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            alarmIntent.putExtra("raw_alarm_json", json.toString());
            alarmIntent.putExtra("alarm_action", action);
            startActivity(alarmIntent);
        }
    }

    private void handleCameraCommand(JSONObject json, String sourceNodeId) {
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
            cameraIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(cameraIntent);
            return;
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
                        .addOnSuccessListener(_ -> mLogChannel = null)
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
                        .addOnSuccessListener(_ -> WearLog.d(TAG, "【APK-003】ACK已发送"))
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
            long expectedSize = -1L;
            String fileName;
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

    // --- 文件接收核心方法（已增强日志） ---
    private void receiveFileFromChannel(ChannelClient.Channel channel, String fileName, String nodeId, long expectedSize) {
        WearLog.i(TAG, "【FIL-001】开始接收文件: " + fileName + " (期望大小: " + expectedSize + "B)");
        
        Uri collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
        
        // 🎯 修复：根据扩展名动态设置 MIME，不再强制 APK
        String mimeType = "application/octet-stream";
        if (fileName.endsWith(".jpg") || fileName.endsWith(".jpeg")) {
            mimeType = "image/jpeg";
        } else if (fileName.endsWith(".apk")) {
            mimeType = "application/vnd.android.package-archive";
        }
        values.put(MediaStore.Downloads.MIME_TYPE, mimeType);
        values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Received");
        
        Uri fileUri = null;
        try {
            fileUri = getContentResolver().insert(collection, values);
        } catch (Exception e) {
            WearLog.e(TAG, "【FIL-ERR】MediaStore 插入异常: " + e.getMessage(), e);
        }

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
            WearLog.d(TAG, "【FIL-001-2】输入流与输出流已成功建立，开始循环读取...");

            byte[] buffer = new byte[32768];
            long bytesReceived = 0L;
            int bytesRead;
            int packetCount = 0;

            while ((bytesRead = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
                bytesReceived += bytesRead;
                packetCount++;
                // 每接收约 500KB 打印一次进度日志，防止日志刷屏但能看清是否在推进
                if (packetCount % 16 == 0) {
                    WearLog.d(TAG, "【FIL-READ】已接收字节数: " + bytesReceived + " / " + expectedSize);
                }
            }
            outputStream.flush();
            WearLog.d(TAG, "【FIL-001-3】循环读取结束，总共接收: " + bytesReceived + "B");

            if (expectedSize != -1L && bytesReceived != expectedSize) {
                throw new Exception("文件不完整: 期望 " + expectedSize + "B, 实际 " + bytesReceived + "B");
            }
            WearLog.i(TAG, "【FIL-002】文件接收成功! 大小: " + bytesReceived + "B");
            sendFileTransferStatus(nodeId, "success:" + fileName);

            // 🚀 发送本地广播，通知 Activity 预览照片
            Intent intent = new Intent("cn.luke.wearsync.ACTION_FILE_RECEIVED");
            intent.putExtra("file_uri", fileUri.toString());
            sendBroadcast(intent);

        } catch (Exception e) {
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
