package cn.luke.wearsync;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build; // 修复：添加缺失的导入
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.util.Log;

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

    // 暂存数据，用于等待手表回复
    private static String sPendingNodeId;
    private static Uri sPendingFileUri;
    private static String sPendingFileName;
    private static TransferCallback sPendingCallback;
    private static Context sAppContext;
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    public interface TransferCallback {
        void onComplete();
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
            Log.e(TAG, "获取文件大小失败", e);
            if (callback != null) callback.onError("无法读取文件: " + e.getMessage());
            return;
        }

        // 2. 暂存数据，等待手表回复
        sPendingNodeId = nodeId;
        sPendingFileUri = fileUri;
        sPendingFileName = fileName;
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
            Log.e(TAG, "构建信令JSON失败", e);
            if (callback != null) callback.onError("信令构建异常");
            return;
        }

        Log.d(TAG, "发送 PREPARE_RECEIVE 信令到节点: " + nodeId);

        // 4. 发送信令
        messageClient.sendMessage(nodeId, UNIVERSAL_SYNC_PATH, prepareJson.toString().getBytes(StandardCharsets.UTF_8))
                .addOnSuccessListener(statusCode -> Log.d(TAG, "PREPARE_RECEIVE 信令已发出，等待手表 ACK..."))
                .addOnFailureListener(e -> {
                    Log.e(TAG, "PREPARE_RECEIVE 发送失败", e);
                    if (sPendingCallback != null) sPendingCallback.onError("信令发送失败: " + e.getMessage());
                    clearPendingData();
                });

        // 5. 启动超时计时器
        MAIN_HANDLER.postDelayed(() -> {
            if (sPendingFileUri != null) {
                Log.w(TAG, "等待手表 ACK 超时(10s)，取消传输");
                if (sPendingCallback != null) sPendingCallback.onError("手表响应超时");
                clearPendingData();
            }
        }, ACK_TIMEOUT_MS);
    }

    /**
     * 当收到手表的 READY_TO_RECEIVE 信号时调用此方法
     */
    public static void onWearReadyToReceive() {
        // 收到ACK，取消超时计时器
        MAIN_HANDLER.removeCallbacksAndMessages(null);
        Log.d(TAG, "收到手表准备就绪信号，开始传输文件...");

        if (sPendingFileUri == null || sPendingNodeId == null) {
            Log.w(TAG, "收到就绪信号，但无待传输任务");
            return;
        }

        // 启动后台服务进行传输
        Intent serviceIntent = new Intent(sAppContext, PhoneSyncFileTransferService.class);
        serviceIntent.setAction(PhoneSyncFileTransferService.ACTION_ADD_TRANSFER);
        serviceIntent.putExtra(PhoneSyncFileTransferService.EXTRA_NODE_ID, sPendingNodeId);
        serviceIntent.putExtra(PhoneSyncFileTransferService.EXTRA_FILE_URI, sPendingFileUri);
        serviceIntent.putExtra(PhoneSyncFileTransferService.EXTRA_FILE_NAME, sPendingFileName);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            sAppContext.startForegroundService(serviceIntent);
        } else {
            sAppContext.startService(serviceIntent);
        }

        // 通知调用方任务已移交后台服务处理
        if (sPendingCallback != null) {
            sPendingCallback.onComplete();
        }
        
        // 清理暂存数据
        clearPendingData();
    }

    private static void clearPendingData() {
        sPendingNodeId = null;
        sPendingFileUri = null;
        sPendingFileName = null;
        sPendingCallback = null;
    }
}
