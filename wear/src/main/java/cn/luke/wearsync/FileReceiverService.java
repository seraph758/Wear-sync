package cn.luke.wearsync;

import android.net.Uri;
import android.os.Environment;
import android.util.Log;

import androidx.annotation.NonNull;

import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.ChannelClient;
import com.google.android.gms.wearable.MessageClient;
import com.google.android.gms.wearable.Wearable;
import com.google.android.gms.wearable.WearableListenerService;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 纯Java实现的文件接收服务
 * 完全替代Kotlin协程版本，保持项目语言统一
 */
public class FileReceiverService extends WearableListenerService {

    private static final String TAG = "FileReceiverService";
    // ⚠️ 必须与手机端 PhoneSyncFileTransferService 中的路径前缀完全一致
    private static final String FILE_TRANSFER_PATH_PREFIX = "/wear-sync/file-transfer";
    private static final String STATUS_PATH = "/file-transfer-status";

    // 单线程池，保证文件接收任务串行执行，避免并发IO冲突
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override
    public void onChannelOpened(@NonNull ChannelClient.Channel channel) {
        super.onChannelOpened(channel);
        Log.d(TAG, "Channel opened: " + channel.getPath());

        if (channel.getPath().startsWith(FILE_TRANSFER_PATH_PREFIX)) {
            // 解析路径元数据: /wear-sync/file-transfer/{size}/{encodedFileName}
            String pathData = channel.getPath().substring(FILE_TRANSFER_PATH_PREFIX.length() + 1);
            long expectedSize = -1L;
            String fileName;

            int slashIndex = pathData.indexOf('/');
            if (slashIndex != -1) {
                try {
                    expectedSize = Long.parseLong(pathData.substring(0, slashIndex));
                } catch (NumberFormatException e) {
                    Log.w(TAG, "无法解析文件大小，将跳过完整性校验");
                }
                fileName = Uri.decode(pathData.substring(slashIndex + 1));
            } else {
                fileName = Uri.decode(pathData);
            }

            String nodeId = channel.getNodeId();
            // 提交到后台线程池执行，不阻塞WearableListenerService主线程
            executor.submit(() -> receiveFileFromChannel(channel, fileName, nodeId, expectedSize));
        }
    }

    private void receiveFileFromChannel(ChannelClient.Channel channel,
                                        String fileName,
                                        String nodeId,
                                        long expectedSize) {
        ChannelClient channelClient = Wearable.getChannelClient(this);
        File receivedDir = new File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "Received"
        );
        File file = new File(receivedDir, fileName);

        try {
            // 1. 确保目录存在
            if (!receivedDir.exists() && !receivedDir.mkdirs()) {
                throw new Exception("无法创建接收目录: " + receivedDir.getAbsolutePath());
            }

            Log.d(TAG, "开始接收文件: " + fileName + " -> " + file.getAbsolutePath());

            // 2. 获取输入流（同步等待，在后台线程中安全）
            InputStream inputStream = Tasks.await(channelClient.getInputStream(channel));

            // 3. 流式写入文件
            long bytesReceived = 0L;
            try (OutputStream outputStream = new FileOutputStream(file);
                 InputStream input = inputStream) {

                byte[] buffer = new byte[32768]; // 32KB缓冲区
                int bytesRead;
                while ((bytesRead = input.read(buffer)) != -1) {
                    outputStream.write(buffer, 0, bytesRead);
                    bytesReceived += bytesRead;
                }
                outputStream.flush();
            }

            // 4. ✅ 完整性校验（核心优势：防止接收到残缺文件）
            if (expectedSize != -1L && bytesReceived != expectedSize) {
                throw new Exception("文件不完整: 期望 " + expectedSize + "B, 实际收到 " + bytesReceived + "B");
            }

            Log.i(TAG, "✅ 文件接收成功! 大小: " + bytesReceived + "B");
            sendStatusToPhone(nodeId, "success:" + fileName);

        } catch (Exception e) {
            Log.e(TAG, "❌ 文件接收失败", e);

            // 失败时删除残缺文件，避免用户误用
            if (file.exists()) {
                boolean deleted = file.delete();
                Log.d(TAG, "已删除残缺文件: " + deleted);
            }

            sendStatusToPhone(nodeId, "error:" + fileName);
        } finally {
            // 5. 关闭Channel
            try {
                Tasks.await(channelClient.close(channel));
                Log.d(TAG, "Channel已关闭");
            } catch (Exception e) {
                Log.w(TAG, "关闭Channel时出错: " + e.getMessage());
            }
        }
    }

    /**
     * 向手机端发送传输状态回执
     */
    private void sendStatusToPhone(String nodeId, String status) {
        try {
            MessageClient messageClient = Wearable.getMessageClient(this);
            Tasks.await(messageClient.sendMessage(
                    nodeId,
                    STATUS_PATH,
                    status.getBytes(StandardCharsets.UTF_8)
            ));
            Log.d(TAG, "状态回执已发送: " + status);
        } catch (Exception e) {
            Log.w(TAG, "发送状态回执失败: " + e.getMessage());
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }
}
