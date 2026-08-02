package cn.luke.wearsync;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;

import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.Channel;
import com.google.android.gms.wearable.ChannelClient;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

public class PhoneSyncFileTransferService extends Service {

    private static final String TAG = "WearSyncFileTransferService";
    private static final String CHANNEL_ID = "transfer_channel";
    private static final int NOTIFICATION_ID = 2;

    // --- 必须公开，供 Manager 调用 ---
    public static final String ACTION_ADD_TRANSFER = "cn.luke.wearsync.action.ADD_TRANSFER";
    public static final String EXTRA_NODE_ID = "extra_node_id";
    public static final String EXTRA_FILE_URI = "extra_file_uri";
    public static final String EXTRA_FILE_NAME = "extra_file_name";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final TransferRepository repository = TransferRepository.getInstance();

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification("服务已启动"));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_ADD_TRANSFER.equals(intent.getAction())) {
            String nodeId = intent.getStringExtra(EXTRA_NODE_ID);
            Uri fileUri;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                fileUri = intent.getParcelableExtra(EXTRA_FILE_URI, Uri.class);
            } else {
                fileUri = intent.getParcelableExtra(EXTRA_FILE_URI);
            }

            String fileName = intent.getStringExtra(EXTRA_FILE_NAME);

            if (nodeId != null && fileUri != null && fileName != null) {
                TransferItem item = new TransferItem(UUID.randomUUID().toString(), nodeId, fileUri, fileName, 0);
                repository.addItem(item);
                executor.submit(this::processQueue);
            }
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // --- 核心逻辑 ---

    private void processQueue() {
        while (true) {
            TransferItem item = null;
            List<TransferItem> queue = repository.getQueue();
            for (TransferItem i : queue) {
                if (i.getStatus() == TransferStatus.PENDING) {
                    item = i;
                    break;
                }
            }

            if (item == null) {
                // 队列空了或没有待处理任务
                stopSelf();
                return;
            }

            processItem(item);
        }
    }

    private void processItem(TransferItem item) {
        repository.updateItem(item.getId(), it -> it.setStatus(TransferStatus.SENDING));
        startForeground(NOTIFICATION_ID, buildNotification("正在发送: " + item.getFileName()));

        try {
            // 1. 找到节点
            Node node = Tasks.await(Wearable.getNodeClient(this).getConnectedNodes())
                    .stream()
                    .filter(n -> n.getId().equals(item.getNodeId()))
                    .findFirst()
                    .orElse(null);

            if (node == null) throw new IOException("Node not found");

            // 2. 打开文件输入流
            InputStream inputStream = getContentResolver().openInputStream(item.getFileUri());
            if (inputStream == null) throw new IOException("Cannot open file input stream");

            // 3. 打开 Wearable 通道
            ChannelClient.Channel channel = Tasks.await(Wearable.getChannelClient(this)
                    .openChannel(node.getId(), "/wear-universal-sync/file-transfer"));

            // 4. 获取输出流并传输
            try (OutputStream outputStream = Tasks.await(Wearable.getChannelClient(this).getOutputStream(channel))) {
                byte[] buffer = new byte[4096];
                int len;
                long totalRead = 0;

                while ((len = inputStream.read(buffer)) != -1) {
                    // 检查是否被取消
                    boolean isCancelled = repository.getQueue().stream()
                            .anyMatch(i -> i.getId().equals(item.getId()) && i.getStatus() == TransferStatus.CANCELLED);
                    if (isCancelled) break;

                    outputStream.write(buffer, 0, len);
                    totalRead += len;
                    
                    // 更新进度 (简单计算)
                    int progress = (int) ((totalRead * 100) / (item.getFileSize() > 0 ? item.getFileSize() : totalRead));
                    final int finalProgress = Math.min(progress, 100);
                    repository.updateItem(item.getId(), it -> it.setProgress(finalProgress));
                }
                outputStream.flush();
            }

            // 5. 关闭通道
            Tasks.await(Wearable.getChannelClient(this).close(channel));
            inputStream.close();

            // 6. 完成
            boolean isCancelled = repository.getQueue().stream()
                    .anyMatch(i -> i.getId().equals(item.getId()) && i.getStatus() == TransferStatus.CANCELLED);

            if (isCancelled) {
                repository.updateItem(item.getId(), it -> it.setStatus(TransferStatus.CANCELLED));
            } else {
                repository.updateItem(item.getId(), it -> it.setStatus(TransferStatus.SUCCESS));
            }
            repository.removeItem(item.getId());

        } catch (Exception e) {
            Log.e(TAG, "传输失败", e);
            repository.updateItem(item.getId(), it -> it.setStatus(TransferStatus.ERROR));
            // 失败不移除，或者根据需求移除
        }
    }

    // --- 辅助方法 ---

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "文件传输服务", NotificationManager.IMPORTANCE_LOW);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String text) {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Wear 文件同步")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setOngoing(true)
                .build();
    }

    // ==========================================
    // 内部类：TransferStatus (枚举)
    // ==========================================
    public enum TransferStatus {
        PENDING, SENDING, SUCCESS, ERROR, CANCELLED
    }

    // ==========================================
    // 内部类：TransferItem (数据模型)
    // ==========================================
    public static class TransferItem {
        private final String id;
        private final String nodeId;
        private final Uri fileUri;
        private final String fileName;
        private final long fileSize;
        private TransferStatus status;
        private int progress;

        public TransferItem(String id, String nodeId, Uri fileUri, String fileName, long fileSize) {
            this.id = id;
            this.nodeId = nodeId;
            this.fileUri = fileUri;
            this.fileName = fileName;
            this.fileSize = fileSize;
            this.status = TransferStatus.PENDING;
            this.progress = 0;
        }

        public String getId() { return id; }
        public String getNodeId() { return nodeId; }
        public Uri getFileUri() { return fileUri; }
        public String getFileName() { return fileName; }
        public long getFileSize() { return fileSize; }
        public TransferStatus getStatus() { return status; }
        public void setStatus(TransferStatus status) { this.status = status; }
        public int getProgress() { return progress; }
        public void setProgress(int progress) { this.progress = progress; }
    }

    // ==========================================
    // 内部类：TransferRepository (单例仓库)
    // ==========================================
    private static class TransferRepository {
        private static volatile TransferRepository instance;
        private final List<TransferItem> queue = new CopyOnWriteArrayList<>();

        private TransferRepository() {}

        public static TransferRepository getInstance() {
            if (instance == null) {
                synchronized (TransferRepository.class) {
                    if (instance == null) instance = new TransferRepository();
                }
            }
            return instance;
        }

        public void addItem(TransferItem item) { queue.add(item); }
        public void removeItem(String id) { queue.removeIf(item -> item.getId().equals(id)); }
        public List<TransferItem> getQueue() { return Collections.unmodifiableList(queue); }
        
        public void updateItem(String id, Consumer<TransferItem> updater) {
            for (TransferItem item : queue) {
                if (item.getId().equals(id)) {
                    updater.accept(item);
                    break;
                }
            }
        }
    }
}
