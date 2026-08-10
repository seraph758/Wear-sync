package cn.luke.wearsync;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.gms.wearable.MessageClient;
import com.google.android.gms.wearable.Wearable;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;

public class PhoneSyncFileTransferManager {
    private static final String TAG = "WearSyncFileTransfer";
    private static final String UNIVERSAL_SYNC_PATH = "/wear-universal-sync";
    private static final long ACK_TIMEOUT_MS = 10_000L;

    // 暫存數據，用於等待手錶回復
    private static String sPendingNodeId;
    private static Uri sPendingFileUri;
    private static String sPendingFileName;
    private static long sPendingFileSize; // 新增：暫存檔案大小
    private static TransferCallback sPendingCallback;
    private static Context sAppContext;
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    public interface TransferCallback {
        void onHandshakeSuccess();
        void onStatusUpdate(@NonNull String status);
        void onError(@NonNull String message);
    }

    /**
     * 发送文件到手表（握手机制）
     */
    public static void sendFileToWear(@NonNull Context context, @NonNull String nodeId, @NonNull Uri fileUri, @NonNull String fileName, @Nullable TransferCallback callback) {
        sAppContext = context.getApplicationContext();
        MessageClient messageClient = Wearable.getMessageClient(context);

        // 1. 获取文件大小
        long fileSize = 0L;
        try (ParcelFileDescriptor pfd = context.getContentResolver().openFileDescriptor(fileUri, "r")) {
            if (pfd != null) fileSize = pfd.getStatSize();
        } catch (Exception e) {
            PhoneLog.e(TAG, "获取文件大小失败", e);
            if (callback != null) callback.onError("无法读取文件: " + e.getMessage());
            return;
        }

        // 2. 暂存数据，等待手表回复
        sPendingNodeId = nodeId;
        sPendingFileUri = fileUri;
        sPendingFileName = fileName;
        sPendingFileSize = fileSize; 
        sPendingCallback = callback;

        // 3. 构建并发送 PREPARE 信令
        JSONObject prepareJson = new JSONObject();
        try {
            prepareJson.put("sender", "phone");
            prepareJson.put("type", "file_transfer");
            prepareJson.put("action", "PREPARE_RECEIVE");
            prepareJson.put("fileName", fileName);
            prepareJson.put("fileSize", fileSize);
        } catch (Exception e) {
            PhoneLog.e(TAG, "构建信令JSON失败", e);
            if (callback != null) callback.onError("信令构建异常");
            return;
        }

        PhoneLog.d(TAG, "发送 PREPARE_RECEIVE 信令到节点: " + nodeId);
        if (callback != null) callback.onStatusUpdate("正在请求手表准备接收...");

        // 4. 发送信令
        messageClient.sendMessage(nodeId, UNIVERSAL_SYNC_PATH, prepareJson.toString().getBytes(StandardCharsets.UTF_8))
                .addOnSuccessListener(statusCode -> PhoneLog.d(TAG, "PREPARE_RECEIVE 信令已发出，等待手表 ACK..."))
                .addOnFailureListener(e -> {
                    PhoneLog.e(TAG, "PREPARE_RECEIVE 发送失败", e);
                    if (sPendingCallback != null) sPendingCallback.onError("信令发送失败: " + e.getMessage());
                    clearPendingData();
                });

        // 5. 启动超时刻度器
        MAIN_HANDLER.postDelayed(() -> {
            if (sPendingFileUri != null) {
                PhoneLog.w(TAG, "等待手表 ACK 超时(10s)，取消传输");
                if (sPendingCallback != null) sPendingCallback.onError("手表响应超时");
                clearPendingData();
            }
        }, ACK_TIMEOUT_MS);
    }

    /**
     * 当收到手表的 READY_TO_RECEIVE 信号时调用此方法
     */
    public static void onWearReadyToReceive() {
        // 收到ACK，取消超时刻度器
        MAIN_HANDLER.removeCallbacksAndMessages(null);
        PhoneLog.d(TAG, "收到手表准备就绪信号，开始传输文件...");

        if (sPendingFileUri == null || sPendingNodeId == null) {
            PhoneLog.w(TAG, "收到就绪信号，但无待传输任务");
            return;
        }

        // 启动后台服务进行传输
        Intent serviceIntent = new Intent(sAppContext, PhoneSyncFileTransferService.class);
        serviceIntent.setAction(PhoneSyncFileTransferService.ACTION_ADD_TRANSFER);
        serviceIntent.putExtra(PhoneSyncFileTransferService.EXTRA_NODE_ID, sPendingNodeId);
        serviceIntent.putExtra(PhoneSyncFileTransferService.EXTRA_FILE_URI, sPendingFileUri);
        serviceIntent.putExtra(PhoneSyncFileTransferService.EXTRA_FILE_NAME, sPendingFileName);
        serviceIntent.putExtra(PhoneSyncFileTransferService.EXTRA_FILE_SIZE, sPendingFileSize);

        // 🎯 核心修复：授予 URI 读取权限，防止 Service 无法读取文件
        serviceIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        sAppContext.startForegroundService(serviceIntent);

        if (sPendingCallback != null) {
            sPendingCallback.onHandshakeSuccess();
            sPendingCallback.onStatusUpdate("握手成功，正在后台发送中...");
        }
        
        // 注意：这里不要清理 sPendingCallback，因为后续还需要接收 status_update
        sPendingFileUri = null; 
        sPendingNodeId = null;
    }

    /**
     * 更新当前传输任务的状态（由 PhoneSyncListenerService 调用）
     */
    public static void updateTransferStatus(String status) {
        if (sPendingCallback != null) {
            if (status.startsWith("success")) {
                sPendingCallback.onStatusUpdate("✅ 传输完成: " + status.substring(status.indexOf(":") + 1));
                clearPendingData();
            } else if (status.startsWith("error")) {
                sPendingCallback.onError("❌ 手表端接收失败: " + status.substring(status.indexOf(":") + 1));
                clearPendingData();
            } else {
                sPendingCallback.onStatusUpdate(status);
            }
        }
    }

    private static void clearPendingData() {
        sPendingNodeId = null;
        sPendingFileUri = null;
        sPendingFileName = null;
        sPendingFileSize = 0L;
        sPendingCallback = null;
    }
}
