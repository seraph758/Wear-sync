package cn.luke.wearsync;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.gms.wearable.ChannelClient;
import com.google.android.gms.wearable.MessageClient;
import com.google.android.gms.wearable.Wearable;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * WearSync 跨端文件传输管理器 (手机端)
 * ⚠️ 已适配 play-services-wearable:19.0.0
 * ⚠️ 注意：v19.0.0 移除了文件传输进度回调，onProgress 将不会被调用
 */
public class PhoneSyncFileTransferManager {

    private static final String TAG = "WearSyncFileTransfer";
    private static final String UNIVERSAL_SYNC_PATH = "/wear-universal-sync";
    private static final String FILE_TRANSFER_CHANNEL_PATH = "/wear-sync/file-transfer";
    private static final long CHANNEL_OPEN_DELAY_MS = 500L;

    public interface TransferCallback {
        void onComplete();
        void onError(@NonNull String message);
    }

    private static File copyUriToCacheFile(Context context, Uri sourceUri, String fileName) throws Exception {
        // 在缓存目录下创建一个同名文件
        File cacheFile = new File(context.getCacheDir(), fileName);

        try (
                InputStream inputStream = context.getContentResolver().openInputStream(sourceUri);
                OutputStream outputStream = new FileOutputStream(cacheFile)
        ) {
            if (inputStream == null) {
                throw new Exception("无法打开源文件输入流");
            }
            // 执行拷贝
            byte[] buffer = new byte[4096];
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
            }
        }
        return cacheFile;
    }
    public static void sendFileToWear(
            @NonNull Context context,
            @NonNull String nodeId,
            @NonNull Uri fileUri,
            @NonNull String fileName,
            @Nullable TransferCallback callback) {

        ChannelClient channelClient = Wearable.getChannelClient(context);
        MessageClient messageClient = Wearable.getMessageClient(context);

        // 获取文件大小（保持不变）
        long fileSize = 0L;
        try (ParcelFileDescriptor pfd = context.getContentResolver().openFileDescriptor(fileUri, "r")) {
            if (pfd != null) fileSize = pfd.getStatSize();
        } catch (Exception e) {
            PhoneLog.e(TAG, "❌ 获取文件大小失败", e);
            if (callback != null) callback.onError("无法读取文件: " + e.getMessage());
            return;
        }

        // 构建信令 JSON（保持不变）
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

        // ✅ Lambda + 钻石操作符 + 移除冗余 null 初始化
        PhoneLog.d(TAG, "✉️ 发送 PREPARE_RECEIVE 信令到节点: " + nodeId);
        messageClient.sendMessage(nodeId, UNIVERSAL_SYNC_PATH, prepareJson.toString().getBytes(StandardCharsets.UTF_8))
                .addOnSuccessListener(statusCode -> {
                    PhoneLog.d(TAG, "✅ PREPARE_RECEIVE 已送达，准备打开 Channel...");
                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        File tempFile;
                        try {
                            tempFile = copyUriToCacheFile(context, fileUri, fileName);
                            Uri fileUriForSending = Uri.fromFile(tempFile);
                            openChannelAndSend(channelClient, nodeId, fileUriForSending, callback, tempFile);
                        } catch (Exception e) {
                            PhoneLog.e(TAG, "❌ 准备临时文件失败", e);
                            if (callback != null) callback.onError("文件准备失败: " + e.getMessage());
                        }
                    }, CHANNEL_OPEN_DELAY_MS);
                })
                .addOnFailureListener(e -> {
                    PhoneLog.e(TAG, "❌ PREPARE_RECEIVE 发送失败", e);
                    if (callback != null) callback.onError("信令发送失败: " + e.getMessage());
                });
    }

    // 修改方法签名，增加 tempFile 参数
    private static void openChannelAndSend(
            @NonNull ChannelClient channelClient,
            @NonNull String nodeId,
            @NonNull Uri fileUri,
            @Nullable TransferCallback callback,
            @NonNull File tempFile) {

        // ✅ Lambda + 钻石操作符 + 安全删除
        channelClient.openChannel(nodeId, FILE_TRANSFER_CHANNEL_PATH)
                .addOnSuccessListener(channel ->
                        pushFile(channelClient, channel, fileUri, callback, tempFile)
                )
                .addOnFailureListener(e -> {
                    PhoneLog.e(TAG, "❌ Channel 打开失败", e);
                    if (callback != null) callback.onError("通道建立失败: " + e.getMessage());
                    safeDeleteTempFile(tempFile);
                });
    }

    /**
     * v19.0.0 的 sendFile 返回 Task<Void>，无进度回调
     */
    // 修改方法签名，增加 tempFile 参数
    private static void pushFile(
            @NonNull ChannelClient channelClient,
            @NonNull ChannelClient.Channel channel,
            @NonNull Uri fileUri,
            @Nullable TransferCallback callback,
            @NonNull File tempFile) {

        // ✅ Lambda + 钻石操作符 + 安全删除
        channelClient.sendFile(channel, fileUri)
                .addOnSuccessListener(unused -> {
                    PhoneLog.d(TAG, "✅ 文件推送完成，关闭 Channel");
                    channelClient.close(channel);
                    if (callback != null) callback.onComplete();
                    safeDeleteTempFile(tempFile);
                })
                .addOnFailureListener(e -> {
                    PhoneLog.e(TAG, "❌ 文件推送失败", e);
                    channelClient.close(channel);
                    if (callback != null) callback.onError("文件传输失败: " + e.getMessage());
                    safeDeleteTempFile(tempFile);
                });
    }
    /**
     * 安全删除临时文件，记录删除失败日志
     */
    private static void safeDeleteTempFile(@NonNull File file) {
        if (!file.delete() && file.exists()) {
            PhoneLog.w(TAG, "⚠️ 临时文件删除失败: " + file.getAbsolutePath());
        }
    }
}


