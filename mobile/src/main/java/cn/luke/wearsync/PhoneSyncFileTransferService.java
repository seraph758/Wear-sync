package cn.luke.wearsync; // 请根据你的实际包名修改

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.provider.OpenableColumns;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.Channel;
import com.google.android.gms.wearable.Wearable;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PhoneSyncFileTransferService extends Service {

    private static final String TAG = "FileTransferService";
    private static final String CHANNEL_ID = "file_transfer_channel";
    private static final int NOTIFICATION_ID = 1;

    private static final String ACTION_ADD_TRANSFER = "ACTION_ADD_TRANSFER";
    private static final String ACTION_CANCEL_TRANSFER = "ACTION_CANCEL_TRANSFER";

    private static final String EXTRA_NODE_ID = "extra_node_id";
    private static final String EXTRA_FILE_URI = "extra_file_uri";
    private static final String EXTRA_FILE_NAME = "extra_file_name";
    private static final String EXTRA_ITEM_ID = "extra_item_id";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private boolean isProcessing = false;

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;

        String action = intent.getAction();
        if (ACTION_CANCEL_TRANSFER.equals(action)) {
            String id = intent.getStringExtra(EXTRA_ITEM_ID);
            if (id != null) {
                TransferRepository.getInstance().removeItem(id);
            }
        } else if (ACTION_ADD_TRANSFER.equals(action)) {
            Uri uri = intent.getParcelableExtra(EXTRA_FILE_URI);
            String fileName = intent.getStringExtra(EXTRA_FILE_NAME);
            String nodeId = intent.getStringExtra(EXTRA_NODE_ID);

            if (uri != null && nodeId != null) {
                if (fileName == null) fileName = "unknown_file";

                TransferItem newItem = new TransferItem(
                        UUID.randomUUID().toString(),
                        nodeId,
                        uri,
                        fileName
                );
                TransferRepository.getInstance().addItem(newItem);
                startProcessing();
            }
        }
        return START_NOT_STICKY;
    }

    private void startProcessing() {
        if (isProcessing) return;
        isProcessing = true;

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("文件正在传输")
                .setContentText("正在处理队列...")
                .setSmallIcon(android.R.drawable.stat_sys_download) // 替换为你自己的图标
                .setOngoing(true)
                .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }

        executor.submit(this::processQueue);
    }

    private void processQueue() {
        try {
            while (true) {
                List<TransferItem> queue = TransferRepository.getInstance().getQueue();
                TransferItem nextItem = null;
                for (TransferItem item : queue) {
                    if (item.getStatus() == TransferStatus.PENDING) {
                        nextItem = item;
                        break;
                    }
                }

                if (nextItem == null) {
                    isProcessing = false;
                    stopForeground(STOP_FOREGROUND_DETACH);
                    stopSelf();
                    break;
                }

                processItem(nextItem);
            }
        } catch (Exception e) {
            Log.e(TAG, "队列处理出错", e);
        }
    }

    private void processItem(TransferItem item) {
        try {
            // 1. 更新状态为发送中
            TransferRepository.getInstance().updateItem(item.getId(), it -> {
                it.setStatus(TransferStatus.SENDING);
                return it;
            });

            // 2. 获取文件大小
            long totalSize = getFileSize(item.getUri());

            // 3. 打开 Wear OS 通道
            String encodedName = URLEncoder.encode(item.getFileName(), "UTF-8");
            String path = "/wear-sync/file-transfer/" + totalSize + "/" + encodedName;
            Channel channel = Tasks.await(Wearable.getChannelClient(this).openChannel(item.getTargetNodeId(), path));

            // 4. 开始流式传输
            try (InputStream inputStream = getContentResolver().openInputStream(item.getUri());
                 OutputStream outputStream = Tasks.await(Wearable.getChannelClient(this).getOutputStream(channel))) {

                if (inputStream == null) throw new Exception("无法打开源文件");

                byte[] buffer = new byte[32768]; // 32KB 缓冲区
                long bytesWritten = 0;
                long lastUpdate = 0;
                int len;

                while ((len = inputStream.read(buffer)) != -1) {
                    // 检查任务是否被取消
                    boolean isCancelled = TransferRepository.getInstance().getQueue().stream()
                            .noneMatch(i -> i.getId().equals(item.getId()));
                    if (isCancelled) throw new Exception("传输已取消");

                    outputStream.write(buffer, 0, len);
                    bytesWritten += len;

                    // 更新进度
                    long now = System.currentTimeMillis();
                    if (now - lastUpdate > 300) {
                        int progress = totalSize > 0 ? (int) ((bytesWritten * 100) / totalSize) : 0;
                        final int finalProgress = progress;
                        TransferRepository.getInstance().updateItem(item.getId(), it -> {
                            it.setProgress(finalProgress);
                            return it;
                        });
                        lastUpdate = now;
                    }
                }
                outputStream.flush();
            } finally {
                // 5. 关闭通道
                try {
                    Tasks.await(Wearable.getChannelClient(this).close(channel));
                } catch (Exception e) {
                    Log.w(TAG, "关闭通道时出错", e);
                }
            }

            // 6. 传输完成
            TransferRepository.getInstance().updateItem(item.getId(), it -> {
                it.setStatus(TransferStatus.SUCCESS);
                it.setProgress(100);
                return it;
            });

        } catch (Exception e) {
            Log.e(TAG, "传输文件失败: " + item.getFileName(), e);
            TransferRepository.getInstance().updateItem(item.getId(), it -> {
                it.setStatus(TransferStatus.ERROR);
                return it;
            });
        }
    }

    private long getFileSize(Uri uri) {
        long size = -1;
        try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (sizeIndex != -1) {
                    size = cursor.getLong(sizeIndex);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "获取文件大小失败", e);
        }
        return size;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "文件传输",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }
}
