package cn.luke.wearsync; // ⚠️ 请根据你实际的项目包名修改此处

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

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
 * 负责：发送准备信令 -> 等待ACK(简化为延迟) -> 打开Channel -> 推送文件流
 */
public class WearSyncFileTransferManager {

    private static final String TAG = "WearSyncFileTransfer";

    // ⚠️ 必须与手表端 WearableListenerService 中的常量保持完全一致
    private static final String UNIVERSAL_SYNC_PATH = "/wear-sync/universal";
    private static final String FILE_TRANSFER_CHANNEL_PATH = "/wear-sync/file-transfer";

    // 发送信令后等待手表端就绪的延迟时间(ms)
    // 生产环境建议改为监听 MessageClient 的 READY_TO_RECEIVE 回调，此处为简化演示使用固定延迟
    private static final long CHANNEL_OPEN_DELAY_MS = 500L;

    /**
     * 文件传输进度回调接口
     */
    public interface TransferCallback {
        void onProgress(long bytesTransferred, long totalBytes);
        void onComplete();
        void onError(@NonNull String message);
    }

    /**
     * 向手表端发送文件
     *
     * @param context   应用上下文
     * @param nodeId    目标手表节点ID (通过 CapabilityClient 或 NodeClient 获取)
     * @param fileUri   待发送文件的 Content URI
     * @param fileName  文件名 (用于手表端识别和保存)
     * @param callback  传输状态回调
     */
    public static void sendFileToWear(
            @NonNull Context context,
            @NonNull String nodeId,
            @NonNull Uri fileUri,
            @NonNull String fileName,
            @Nullable TransferCallback callback
    ) {
        ChannelClient channelClient = Wearable.getChannelClient(context);
        var messageClient = Wearable.getMessageClient(context);

        // 1. 获取文件大小用于信令元数据
        long fileSize = 0L;
        try (var pfd = context.getContentResolver().openFileDescriptor(fileUri, "r")) {
            if (pfd != null) {
                fileSize = pfd.getStatSize();
            }
        } catch (Exception e) {
            Log.e(TAG, "❌ 获取文件大小失败", e);
            if (callback != null) callback.onError("无法读取文件: " + e.getMessage());
            return;
        }

        // 2. 构建 PREPARE_RECEIVE 信令 JSON
        JSONObject prepareJson = new JSONObject();
        try {
            prepareJson.put("sender", "phone");
            prepareJson.put("type", "file_transfer");
            prepareJson.put("action", "PREPARE_RECEIVE");
            prepareJson.put("fileName", fileName);
            prepareJson.put("fileSize", fileSize);
        } catch (Exception e) {
            Log.e(TAG, "❌ 构建信令JSON失败", e);
            if (callback != null) callback.onError("信令构建异常");
            return;
        }

        // 3. 发送信令到手表端
        Log.d(TAG, "✉️ 发送 PREPARE_RECEIVE 信令到节点: " + nodeId);
        messageClient.sendMessage(
                nodeId,
                UNIVERSAL_SYNC_PATH,
                prepareJson.toString().getBytes(StandardCharsets.UTF_8)
        ).addOnSuccessListener(new OnSuccessListener<Void>() {
            @Override
            public void onSuccess(Void unused) {
                Log.d(TAG, "✅ PREPARE_RECEIVE 已送达，" + CHANNEL_OPEN_DELAY_MS + "ms 后打开 Channel...");
                // 延迟后打开 Channel 并推送文件
                new Handler(Looper.getMainLooper()).postDelayed(() ->
                        openChannelAndSend(channelClient, nodeId, fileUri, callback),
                        CHANNEL_OPEN_DELAY_MS
                );
            }
        }).addOnFailureListener(new OnFailureListener() {
            @Override
            public void onFailure(@NonNull Exception e) {
                Log.e(TAG, "❌ PREPARE_RECEIVE 发送失败", e);
                if (callback != null) callback.onError("信令发送失败: " + e.getMessage());
            }
        });
    }

    /**
     * 打开 Channel 并推送文件流 (内部方法)
     */
    private static void openChannelAndSend(
            @NonNull ChannelClient channelClient,
            @NonNull String nodeId,
            @NonNull Uri fileUri,
            @Nullable TransferCallback callback
    ) {
        channelClient.openChannel(nodeId, FILE_TRANSFER_CHANNEL_PATH)
                .addOnSuccessListener(new OnSuccessListener<ChannelClient.Channel>() {
                    @Override
                    public void onSuccess(ChannelClient.Channel channel) {
                        Log.d(TAG, "📡 Channel 已打开，开始推送文件流...");
                        pushFile(channelClient, channel, fileUri, callback);
                    }
                })
                .addOnFailureListener(new OnFailureListener() {
                    @Override
                    public void onFailure(@NonNull Exception e) {
                        Log.e(TAG, "❌ Channel 打开失败", e);
                        if (callback != null) callback.onError("通道建立失败: " + e.getMessage());
                    }
                });
    }

    /**
     * 通过已打开的 Channel 推送文件 (内部方法)
     */
    private static void pushFile(
            @NonNull ChannelClient channelClient,
            @NonNull ChannelClient.Channel channel,
            @NonNull Uri fileUri,
            @Nullable TransferCallback callback
    ) {
        channelClient.sendFile(channel, fileUri, null, new ChannelClient.FileCallback() {
            @Override
            public void onProgressChanged(long bytesTransferred, long totalBytes) {
                if (callback != null) {
                    callback.onProgress(bytesTransferred, totalBytes);
                }
            }

            @Override
            public void onComplete() {
                Log.d(TAG, "✅ 文件推送完成，关闭 Channel");
                channelClient.close(channel);
                if (callback != null) callback.onComplete();
            }

            @Override
            public void onFailure(int errorCode) {
                Log.e(TAG, "❌ 文件推送失败, errorCode=" + errorCode);
                channelClient.close(channel);
                if (callback != null) callback.onError("文件传输失败 code=" + errorCode);
            }
        });
    }
}


