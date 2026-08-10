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
        void onComplete();
        void onError(@NonNull String message);
    }

    /**
     * 發送檔案到手錶（握手機制）
     */
    public static void sendFileToWear(@NonNull Context context, @NonNull String nodeId, @NonNull Uri fileUri, @NonNull String fileName, @Nullable TransferCallback callback) {
        sAppContext = context.getApplicationContext();
        MessageClient messageClient = Wearable.getMessageClient(context);

        // 1. 獲取檔案大小
        long fileSize = 0L;
        try (ParcelFileDescriptor pfd = context.getContentResolver().openFileDescriptor(fileUri, "r")) {
            if (pfd != null) fileSize = pfd.getStatSize();
        } catch (Exception e) {
            PhoneLog.e(TAG, "獲取檔案大小失敗", e);
            if (callback != null) callback.onError("無法讀取檔案: " + e.getMessage());
            return;
        }

        // 2. 暫存數據，等待手錶回復
        sPendingNodeId = nodeId;
        sPendingFileUri = fileUri;
        sPendingFileName = fileName;
        sPendingFileSize = fileSize; // 儲存 fileSize
        sPendingCallback = callback;

        // 3. 構建並發送 PREPARE 信令
        JSONObject prepareJson = new JSONObject();
        try {
            prepareJson.put("sender", "phone");
            prepareJson.put("type", "file_transfer");
            prepareJson.put("action", "PREPARE_RECEIVE");
            prepareJson.put("fileName", fileName);
            prepareJson.put("fileSize", fileSize);
        } catch (Exception e) {
            PhoneLog.e(TAG, "構建信令JSON失敗", e);
            if (callback != null) callback.onError("信令構建異常");
            return;
        }

        PhoneLog.d(TAG, "發送 PREPARE_RECEIVE 信令到節點: " + nodeId);

        // 4. 發送信令
        messageClient.sendMessage(nodeId, UNIVERSAL_SYNC_PATH, prepareJson.toString().getBytes(StandardCharsets.UTF_8))
                .addOnSuccessListener(statusCode -> PhoneLog.d(TAG, "PREPARE_RECEIVE 信令已發出，等待手錶 ACK..."))
                .addOnFailureListener(e -> {
                    PhoneLog.e(TAG, "PREPARE_RECEIVE 發送失敗", e);
                    if (sPendingCallback != null) sPendingCallback.onError("信令發送失敗: " + e.getMessage());
                    clearPendingData();
                });

        // 5. 啟動超時計時器
        MAIN_HANDLER.postDelayed(() -> {
            if (sPendingFileUri != null) {
                PhoneLog.w(TAG, "等待手錶 ACK 超時(10s)，取消傳輸");
                if (sPendingCallback != null) sPendingCallback.onError("手錶響應超時");
                clearPendingData();
            }
        }, ACK_TIMEOUT_MS);
    }

    /**
     * 當收到手錶的 READY_TO_RECEIVE 信號時調用此方法
     */
    public static void onWearReadyToReceive() {
        // 收到ACK，取消超時計時器
        MAIN_HANDLER.removeCallbacksAndMessages(null);
        PhoneLog.d(TAG, "收到手錶準備就緒信號，開始傳輸檔案...");

        if (sPendingFileUri == null || sPendingNodeId == null) {
            PhoneLog.w(TAG, "收到就緒信號，但無待傳輸任務");
            return;
        }

        // 啟動後台服務進行傳輸
        Intent serviceIntent = new Intent(sAppContext, PhoneSyncFileTransferService.class);
        serviceIntent.setAction(PhoneSyncFileTransferService.ACTION_ADD_TRANSFER);
        serviceIntent.putExtra(PhoneSyncFileTransferService.EXTRA_NODE_ID, sPendingNodeId);
        serviceIntent.putExtra(PhoneSyncFileTransferService.EXTRA_FILE_URI, sPendingFileUri);
        serviceIntent.putExtra(PhoneSyncFileTransferService.EXTRA_FILE_NAME, sPendingFileName);
        serviceIntent.putExtra(PhoneSyncFileTransferService.EXTRA_FILE_SIZE, sPendingFileSize); // 將 fileSize 傳給 Service

        sAppContext.startForegroundService(serviceIntent);

        // 通知調用方任務已移交後台服務處理（建議 UI 提示“已開始後台傳輸”）
        if (sPendingCallback != null) {
            sPendingCallback.onComplete();
        }
        
        // 清理暫存數據
        clearPendingData();
    }

    private static void clearPendingData() {
        sPendingNodeId = null;
        sPendingFileUri = null;
        sPendingFileName = null;
        sPendingFileSize = 0L;
        sPendingCallback = null;
    }
}
