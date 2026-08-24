package cn.luke.wearsync;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.net.Uri;
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
            PhoneLog.d(TAG, "🚀 [开始传输] " + item.getFileName() + " Size: " + item.getFileSize());
            // 1. 找到节点
            Node node = Tasks.await(Wearable.getNodeClient(this).getConnectedNodes())
                    .stream()
                    .filter(n -> n.getId().equals(item.getNodeId()))
                    .findFirst()
                    .orElse(null);

            if (node == null) throw new IOException("无法找到指定的手表节点 (Node ID: " + item.getNodeId() + ")");

            // 2. 打开文件输入流
            InputStream inputStream = getContentResolver().openInputStream(item.getFileUri());
            if (inputStream == null) throw new IOException("无法通过 Uri 打开文件流: " + item.getFileUri());

            // 3. 拼接符合手表端解析格式的 Channel Path
            String channelPath = FILE_TRANSFER_CHANNEL_PATH + "/" + item.getFileSize() + "/" + Uri.encode(item.getFileName());
            PhoneLog.d(TAG, "📡 正在建立传输通道: " + channelPath);

            // 4. 打开 Wearable 通道
            ChannelClient.Channel channel = Tasks.await(Wearable.getChannelClient(this)
                    .openChannel(node.getId(), channelPath));
            PhoneLog.d(TAG, "✅ 通道已建立，准备获取输出流...");

            // 5. 获取输出流并传输
            try (OutputStream outputStream = Tasks.await(Wearable.getChannelClient(this).getOutputStream(channel))) {
                PhoneLog.d(TAG, "🟢 数据流已开启，开始写入字节...");
                byte[] buffer = new byte[16384];
                int len;
                long totalRead = 0;
                int packetCount = 0;

                while ((len = inputStream.read(buffer)) != -1) {
                    // 检查是否被取消
                    boolean isCancelled = repository.getQueue().stream()
                            .anyMatch(i -> i.getId().equals(item.getId()) && i.getStatus() == TransferStatus.CANCELLED);
                    if (isCancelled) {
                        PhoneLog.w(TAG, "🛑 传输任务被中途取消");
                        break;
                    }

                    outputStream.write(buffer, 0, len);
                    totalRead += len;
                    packetCount++;

                    // 每隔几包同步一次状态
                    if (packetCount % 8 == 0) {
                        outputStream.flush();
                        long fileSize = item.getFileSize();
                        int progress = fileSize > 0 ? (int) ((totalRead * 100) / fileSize) : 100;
                        final int finalProgress = Math.min(progress, 100);
                        repository.updateItem(item.getId(), it -> it.setProgress(finalProgress));
                        // 告知 Manager 进度
                        PhoneSyncFileTransferManager.updateTransferStatus("正在发送: " + finalProgress + "%");
                    }
                }
                outputStream.flush();
                PhoneLog.d(TAG, "🏁 字节流写入完毕，总计: " + totalRead + " bytes");
            }

            // 6. 关闭通道与流
            Tasks.await(Wearable.getChannelClient(this).close(channel));
            inputStream.close();
            PhoneLog.d(TAG, "📤 后台文件分块推送完成，等待手表端落盘回执...");

            // 7. 完成状态更新
            boolean isCancelled = repository.getQueue().stream()
                    .anyMatch(i -> i.getId().equals(item.getId()) && i.getStatus() == TransferStatus.CANCELLED);

            if (isCancelled) {
                repository.updateItem(item.getId(), it -> it.setStatus(TransferStatus.CANCELLED));
            } else {
                repository.updateItem(item.getId(), it -> it.setStatus(TransferStatus.SUCCESS));
                // 注意：这里不要直接告诉 UI 成功，等 Listener 收到手表端 SUCCESS 回传再更新
            }
            repository.removeItem(item.getId());

        } catch (Exception e) {
            PhoneLog.e(TAG, "❌ 传输核心链路异常", e);
            repository.updateItem(item.getId(), it -> it.setStatus(TransferStatus.ERROR));
            PhoneSyncFileTransferManager.updateTransferStatus("error:底层链路异常 - " + e.getMessage());
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
