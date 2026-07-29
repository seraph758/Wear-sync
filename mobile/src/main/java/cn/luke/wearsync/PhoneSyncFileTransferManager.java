package cn.luke.wearsync;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.android.gms.wearable.ChannelClient;
import com.google.android.gms.wearable.Wearable;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;

/**
 * WearSync 跨端文件传输管理器 (手机端)
 * ⚠️ 已适配 play-services-wearable:19.0.0
 * ⚠️ 注意：v19.0.0 移除了文件传输进度回调，onProgress 将不会被调用
 */
public class PhoneSyncFileTransferManager {

    private static final String TAG = "WearSyncFileTransfer";
    private static final String UNIVERSAL_SYNC_PATH = "/wear-sync/universal";
    private static final String FILE_TRANSFER_CHANNEL_PATH = "/wear-sync/file-transfer";
    private static final long CHANNEL_OPEN_DELAY_MS = 500L;

    public interface TransferCallback {
        void onProgress(long bytesTransferred, long totalBytes);
        void onComplete();
        void onError(@NonNull String message);
    }

    public static void sendFileToWear(
            @NonNull Context context,
            @NonNull String nodeId,
            @NonNull Uri fileUri,
            @NonNull String fileName,
            @Nullable TransferCallback callback) {

        ChannelClient channelClient = Wearable.getChannelClient(context);
        var messageClient = Wearable.getMessageClient(context);

        // 1. 获取文件大小
        long fileSize = 0L;
        try (var pfd = context.getContentResolver().openFileDescriptor(fileUri, "r")) {
            if (pfd != null) fileSize = pfd.getStatSize();
        } catch (Exception e) {
            PhoneLog.e(TAG, "❌ 获取文件大小失败", e);
            if (callback != null) callback.onError("无法读取文件: " + e.getMessage());
            return;
        }

        // 2. 构建信令 JSON
        JSONObject prepareJson = new JSONObject();
        try {
            prepareJson.put("sender", "phone");
            prepareJson.put("type", "file_transfer");
            prepareJson.put("action", "PREPARE_RECEIVE");
            prepareJson.put("fileName", fileName);
            prepareJson.put("fileSize", fileSize);
        } catch (Exception e) {
            PhoneLog.e(TAG, "❌ 构建信令JSON失败", e);
            if (callback != null) callback.onError("信令构建异常");
            return;
        }

        // 3. 发送信令 (v19 返回 Task<Integer>)
        PhoneLog.d(TAG, "✉️ 发送 PREPARE_RECEIVE 信令到节点: " + nodeId);
        messageClient.sendMessage(
                        nodeId,
                        UNIVERSAL_SYNC_PATH,
                        prepareJson.toString().getBytes(StandardCharsets.UTF_8)
                )
                .addOnSuccessListener(new OnSuccessListener<Integer>() {
                    @Override
                    public void onSuccess(Integer statusCode) {
                        PhoneLog.d(TAG, "✅ PREPARE_RECEIVE 已送达(status=" + statusCode + ")，" + CHANNEL_OPEN_DELAY_MS + "ms 后打开 Channel...");
                        new Handler(Looper.getMainLooper()).postDelayed(() ->
                                        openChannelAndSend(channelClient, nodeId, fileUri, callback),
                                CHANNEL_OPEN_DELAY_MS
                        );
                    }
                })
                .addOnFailureListener(new OnFailureListener() {
                    @Override
                    public void onFailure(@NonNull Exception e) {
                        PhoneLog.e(TAG, "❌ PREPARE_RECEIVE 发送失败", e);
                        if (callback != null) callback.onError("信令发送失败: " + e.getMessage());
                    }
                });
    }

    private static void openChannelAndSend(
            @NonNull ChannelClient channelClient,
            @NonNull String nodeId,
            @NonNull Uri fileUri,
            @Nullable TransferCallback callback) {

        channelClient.openChannel(nodeId, FILE_TRANSFER_CHANNEL_PATH)
                .addOnSuccessListener(new OnSuccessListener<ChannelClient.Channel>() {
                    @Override
                    public void onSuccess(ChannelClient.Channel channel) {
                        PhoneLog.d(TAG, "📡 Channel 已打开，开始推送文件流...");
                        pushFile(channelClient, channel, fileUri, callback);
                    }
                })
                .addOnFailureListener(new OnFailureListener() {
                    @Override
                    public void onFailure(@NonNull Exception e) {
                        PhoneLog.e(TAG, "❌ Channel 打开失败", e);
                        if (callback != null) callback.onError("通道建立失败: " + e.getMessage());
                    }
                });
    }

    /**
     * v19.0.0 的 sendFile 返回 Task<Void>，无进度回调
     */
    private static void pushFile(
            @NonNull ChannelClient channelClient,
            @NonNull ChannelClient.Channel channel,
            @NonNull Uri fileUri,
            @Nullable TransferCallback callback) {

        // v19.0.0: sendFile(Channel, Uri) 返回 Task<Void>
        channelClient.sendFile(channel, fileUri)
                .addOnSuccessListener(new OnSuccessListener<Void>() {
                    @Override
                    public void onSuccess(Void unused) {
                        PhoneLog.d(TAG, "✅ 文件推送完成，关闭 Channel");
                        channelClient.close(channel);
                        if (callback != null) callback.onComplete();
                    }
                })
                .addOnFailureListener(new OnFailureListener() {
                    @Override
                    public void onFailure(@NonNull Exception e) {
                        PhoneLog.e(TAG, "❌ 文件推送失败", e);
                        channelClient.close(channel);
                        if (callback != null) callback.onError("文件传输失败: " + e.getMessage());
                    }
                });
    }
}


