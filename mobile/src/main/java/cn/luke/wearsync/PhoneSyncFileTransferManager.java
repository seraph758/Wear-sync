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
    private static String sPendingNodeId;
  private static Uri sPendingFileUri;
  private static String sPendingFileName;
  private static TransferCallback sPendingCallback;
  private static File sPendingTempFile;
  private static Context sAppContext;

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

    sAppContext = context.getApplicationContext(); // 防止内存泄漏
    MessageClient messageClient = Wearable.getMessageClient(context);

    // 1. 获取文件大小
    long fileSize = 0L;
    try (ParcelFileDescriptor pfd = context.getContentResolver().openFileDescriptor(fileUri, "r")) {
        if (pfd != null) fileSize = pfd.getStatSize();
    } catch (Exception e) {
        PhoneLog.e(TAG, "❌ 获取文件大小失败", e);
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
        PhoneLog.e(TAG, "❌ 构建信令JSON失败", e);
        if (callback != null) callback.onError("信令构建异常");
        return;
    }

    PhoneLog.d(TAG, "✉️ 发送 PREPARE_RECEIVE 信令到节点: " + nodeId);
    
    // 4. 发送信令，但 onSuccess 里什么都不做，只等待手表的回复
    messageClient.sendMessage(nodeId, UNIVERSAL_SYNC_PATH, prepareJson.toString().getBytes(StandardCharsets.UTF_8))
            .addOnSuccessListener(statusCode -> {
                PhoneLog.d(TAG, "✅ PREPARE_RECEIVE 信令已发出，等待手表 ACK...");
                // 注意：这里不再执行任何文件操作，也不再调用 callback.onComplete()
            })
            .addOnFailureListener(e -> {
                PhoneLog.e(TAG, "❌ PREPARE_RECEIVE 发送失败", e);
                if (callback != null) callback.onError("信令发送失败: " + e.getMessage());
                clearPendingData();
            });
    }
    
    /**
 * 当收到手表的 READY_TO_RECEIVE 信号时调用此方法
 */
public static void onWearReadyToReceive() {
    PhoneLog.d(TAG, "🚀 收到手表准备就绪信号，开始传输文件...");

    if (sPendingFileUri == null || sPendingNodeId == null) {
        PhoneLog.w(TAG, "⚠️ 收到就绪信号，但无待传输任务");
        return;
    }

    // 在后台线程处理耗时操作（复制文件）
    new Thread(() -> {
        try {
            // 1. 复制临时文件
            File tempFile = copyUriToCacheFile(sAppContext, sPendingFileUri, sPendingFileName);
            sPendingTempFile = tempFile;
            
            Uri fileUriForSending = Uri.fromFile(tempFile);
            ChannelClient channelClient = Wearable.getChannelClient(sAppContext);

            // 2. 打开 Channel 并发送
            // 注意：这里去掉了之前的 postDelayed，因为手表已经说它准备好了
            openChannelAndSend(channelClient, sPendingNodeId, fileUriForSending, sPendingCallback, tempFile);

        } catch (Exception e) {
            PhoneLog.e(TAG, "❌ 准备临时文件失败", e);
            if (sPendingCallback != null) sPendingCallback.onError("文件准备失败: " + e.getMessage());
            clearPendingData();
        }
    }).start();
}

private static void clearPendingData() {
    sPendingNodeId = null;
    sPendingFileUri = null;
    sPendingFileName = null;
    sPendingCallback = null;
    sPendingTempFile = null;
}


    // 修改方法签名，增加 tempFile 参数
    private static void openChannelAndSend(
        @NonNull ChannelClient channelClient,
        @NonNull String nodeId,
        @NonNull Uri fileUri,
        @Nullable TransferCallback callback,
        @NonNull File tempFile) {

    channelClient.openChannel(nodeId, FILE_TRANSFER_CHANNEL_PATH)
            .addOnSuccessListener(channel -> pushFile(channelClient, channel, fileUri, callback, tempFile))
            .addOnFailureListener(e -> {
                PhoneLog.e(TAG, "❌ Channel 打开失败", e);
                if (callback != null) callback.onError("通道建立失败: " + e.getMessage());
                safeDeleteTempFile(tempFile);
                clearPendingData(); // 清理
            });
}

private static void pushFile(
        @NonNull ChannelClient channelClient,
        @NonNull ChannelClient.Channel channel,
        @NonNull Uri fileUri,
        @Nullable TransferCallback callback,
        @NonNull File tempFile) {

    channelClient.sendFile(channel, fileUri)
            .addOnSuccessListener(unused -> {
                PhoneLog.d(TAG, "✅ 文件推送完成，关闭 Channel");
                channelClient.close(channel);
                if (callback != null) callback.onComplete(); // 这里才是真正的完成
                safeDeleteTempFile(tempFile);
                clearPendingData(); // 清理
            })
            .addOnFailureListener(e -> {
                PhoneLog.e(TAG, "❌ 文件推送失败", e);
                channelClient.close(channel);
                if (callback != null) callback.onError("文件传输失败: " + e.getMessage());
                safeDeleteTempFile(tempFile);
                clearPendingData(); // 清理
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


