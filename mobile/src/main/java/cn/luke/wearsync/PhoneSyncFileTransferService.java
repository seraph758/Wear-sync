package cn.luke.wearsync;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;

import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.ChannelClient;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

public class PhoneSyncFileTransferService extends Service {

    private static final String TAG = "WearSyncFileTransferService";
    private static final String CHANNEL_ID = "transfer_channel";
    private static final int NOTIFICATION_ID = 2;

    // --- 必須公開，供 Manager 調用 ---
    public static final String ACTION_ADD_TRANSFER = "cn.luke.wearsync.action.ADD_TRANSFER";
    public static final String EXTRA_NODE_ID = "extra_node_id";
    public static final String EXTRA_FILE_URI = "extra_file_uri";
    public static final String EXTRA_FILE_NAME = "extra_file_name";
    public static final String EXTRA_FILE_SIZE = "extra_file_size";

    // 需與手錶端 WearSyncListenerService.FILE_TRANSFER_CHANNEL_PATH 保持一致
    private static final String FILE_TRANSFER_CHANNEL_PATH = "/wear-sync/file-transfer";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final TransferRepository repository = TransferRepository.getInstance();

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification("服務已啟動"));
    }
    
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_ADD_TRANSFER.equals(intent.getAction())) {
            String nodeId = intent.getStringExtra(EXTRA_NODE_ID);
            
            // ✅ minSdkVersion = 35 专属写法：直接传 Uri.class，无警告、无 if-else 分支
            Uri fileUri = intent.getParcelableExtra(EXTRA_FILE_URI, Uri.class);
    
            String fileName = intent.getStringExtra(EXTRA_FILE_NAME);
            long fileSize = intent.getLongExtra(EXTRA_FILE_SIZE, 0L);
    
            if (nodeId != null && fileUri != null && fileName != null) {
                TransferItem item = new TransferItem(UUID.randomUUID().toString(), nodeId, fileUri, fileName, fileSize);
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

    // --- 核心邏輯 ---

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
                // 隊列空了或沒有待處理任務
                stopSelf();
                return;
            }

            processItem(item);
        }
    }

    private void processItem(TransferItem item) {
        repository.updateItem(item.getId(), it -> it.setStatus(TransferStatus.SENDING));
        startForeground(NOTIFICATION_ID, buildNotification("正在發送: " + item.getFileName()));

        try {
            // 1. 找到節點
            Node node = Tasks.await(Wearable.getNodeClient(this).getConnectedNodes())
                    .stream()
                    .filter(n -> n.getId().equals(item.getNodeId()))
                    .findFirst()
                    .orElse(null);

            if (node == null) throw new IOException("Node not found");

            // 2. 打開檔案輸入流
            InputStream inputStream = getContentResolver().openInputStream(item.getFileUri());
            if (inputStream == null) throw new IOException("Cannot open file input stream");

            // 3. 拼接符合手錶端解析格式的 Channel Path: /wear-sync/file-transfer/{fileSize}/{encodedFileName}
            String channelPath = FILE_TRANSFER_CHANNEL_PATH + "/" + item.getFileSize() + "/" + Uri.encode(item.getFileName());
            PhoneLog.d(TAG, "開啟 Channel Path: " + channelPath);

            // 4. 打開 Wearable 通道
            ChannelClient.Channel channel = Tasks.await(Wearable.getChannelClient(this)
                    .openChannel(node.getId(), channelPath));

            // 5. 獲取輸出流並傳輸
            try (OutputStream outputStream = Tasks.await(Wearable.getChannelClient(this).getOutputStream(channel))) {
                byte[] buffer = new byte[16384];
                int len;
                long totalRead = 0;
     int packetCount = 0;

                while ((len = inputStream.read(buffer)) != -1) {
                    // 檢查是否被取消
                    boolean isCancelled = repository.getQueue().stream()
                            .anyMatch(i -> i.getId().equals(item.getId()) && i.getStatus() == TransferStatus.CANCELLED);
                    if (isCancelled) break;

                    outputStream.write(buffer, 0, len);
                    totalRead += len;
                    packetCount++;
        // 1. 每發送一定數量數據包，手動 flush 確保推送到底層管道
        if (packetCount % 4 == 0) {
            outputStream.flush();
        }

        // 2. 主動休眠 2 毫秒，給藍牙底層傳輸和手錶端寫入 MediaStore 留出緩衝時間
        try {
            Thread.sleep(2);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
            break;
        }

                    
                    // 更新進度
                    long fileSize = item.getFileSize();
                    int progress = fileSize > 0 ? (int) ((totalRead * 100) / fileSize) : 100;
                    final int finalProgress = Math.min(progress, 100);
                    repository.updateItem(item.getId(), it -> it.setProgress(finalProgress));
                }
                outputStream.flush();
            }

            // 6. 關閉通道與流
            Tasks.await(Wearable.getChannelClient(this).close(channel));
            inputStream.close();

            // 7. 完成狀態更新
            boolean isCancelled = repository.getQueue().stream()
                    .anyMatch(i -> i.getId().equals(item.getId()) && i.getStatus() == TransferStatus.CANCELLED);

            if (isCancelled) {
                repository.updateItem(item.getId(), it -> it.setStatus(TransferStatus.CANCELLED));
            } else {
                repository.updateItem(item.getId(), it -> it.setStatus(TransferStatus.SUCCESS));
            }
            repository.removeItem(item.getId());

        } catch (Exception e) {
            PhoneLog.e(TAG, "傳輸失敗", e);
            repository.updateItem(item.getId(), it -> it.setStatus(TransferStatus.ERROR));
        }
    }

    // --- 輔助方法 ---

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "檔案傳輸服務", NotificationManager.IMPORTANCE_LOW);
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.createNotificationChannel(channel);
    }

    private Notification buildNotification(String text) {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Wear 檔案同步")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setOngoing(true)
                .build();
    }

    // ==========================================
    // 內部類：TransferStatus (枚舉)
    // ==========================================
    public enum TransferStatus {
        PENDING, SENDING, SUCCESS, ERROR, CANCELLED
    }

    // ==========================================
    // 內部類：TransferItem (數據模型)
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
    // 內部類：TransferRepository (單例倉庫)
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
