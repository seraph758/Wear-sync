package de.rhaeus.wearsync;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;

import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.ChannelClient;
import com.google.android.gms.wearable.MessageClient;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;

import org.json.JSONObject;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class WearCameraActivity extends Activity implements MessageClient.OnMessageReceivedListener {
    private static final String TAG = "WearSync_WearCamera";
    private static final String UNIVERSAL_SYNC_PATH = "/wear-universal-sync";

    private ImageView frameView;
    private TextView tvCountdown;
    private Button btnCapture;
    private Button btnClose; // 🎯 關閉按鈕
    private int countdown = 3;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private ChannelClient.ChannelCallback mChannelCallback;
    private ChannelClient.Channel mActiveChannel = null;
    private volatile boolean isListening = false; // 控制 Camera Active

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_wear_camera);

        frameView = findViewById(R.id.frameView);
        tvCountdown = findViewById(R.id.tvCountdown);
        btnCapture = findViewById(R.id.btnCapture);
        btnClose = findViewById(R.id.btnClose); // 🎯 請確保布局 XML 中有這個 ID

        btnCapture.setOnClickListener(v -> startCountdownFlow());
        
        // 🎯 點擊關閉按鈕，觸發嚴格的三步驟退出機制
        btnClose.setOnClickListener(v -> executeThreeStepCloseFlow());

        Wearable.getMessageClient(this).addListener(this);
        setupChannelStreamListener();
        notifyPhoneCameraService("START_CAMERA");
    }

    /**
     * 🎯 核心精髓：您提出的完美三步驟關閉邏輯
     */
    private void executeThreeStepCloseFlow() {
        Log.d(TAG, "🔒 觸發完美三步驟關閉機制...");

        new Thread(() -> {
            // 【第一步】主動中斷 Channel Client 通道，並向手機發送停止訊號
            try {
                // 先通知手機端前台服務關閉硬體
                JSONObject json = new JSONObject();
                json.put("sender", "wear");
                json.put("type", "camera_control");
                json.put("action", "STOP_CAMERA");
                byte[] data = json.toString().getBytes(StandardCharsets.UTF_8);
                List<Node> nodes = Tasks.await(Wearable.getNodeClient(this).getConnectedNodes());
                for (Node n : nodes) {
                    Wearable.getMessageClient(this).sendMessage(n.getId(), UNIVERSAL_SYNC_PATH, data);
                }
                Log.d(TAG, "步聚 1.1：已向手機發送 STOP_CAMERA 關閉命令");
            } catch (Exception e) {
                Log.e(TAG, "發送關閉訊號失敗", e);
            }

            // 主動關閉本地的 Channel 管道
            if (mActiveChannel != null) {
                try {
                    Wearable.getChannelClient(this).close(mActiveChannel);
                    Log.d(TAG, "步驟 1.2：Channel 管道已主動從手錶端斷開中斷");
                } catch (Exception ignored) {}
                mActiveChannel = null;
            }

            // 【第二步】退出 Camera Active 狀態（阻斷非同步線程與 UI 渲染）
            isListening = false;
            mainHandler.removeCallbacksAndMessages(null);
            Log.d(TAG, "步驟 2：Camera Active 狀態已徹底停用，取消所有排隊影格");

            // 【第三步】整體退出手錶端頁面
            mainHandler.post(() -> {
                Log.d(TAG, "步驟 3：整體關閉手錶端 UI 頁面 (finish)");
                finish();
            });
        }).start();
    }

    private void setupChannelStreamListener() {
        mChannelCallback = new ChannelClient.ChannelCallback() {
            @Override
            public void onChannelOpened(@NonNull ChannelClient.Channel channel) {
                if (channel.getPath().equals("/wear-camera-frame-stream")) {
                    mActiveChannel = channel;
                    if (!isListening) {
                        isListening = true; // 開啟 Camera Active
                        readStreamDataLoop(channel);
                    }
                }
            }

            @Override
            public void onChannelClosed(@NonNull ChannelClient.Channel channel, int closeReason, int appSpecificErrorCode) {
                if (channel == mActiveChannel) {
                    isListening = false;
                    mActiveChannel = null;
                }
            }
        };
        Wearable.getChannelClient(this).registerChannelCallback(mChannelCallback);
    }

    private void readStreamDataLoop(ChannelClient.Channel channel) {
        new Thread(() -> {
            try {
                InputStream is = Tasks.await(Wearable.getChannelClient(this).getInputStream(channel));
                byte[] lengthBuffer = new byte[4];

                while (isListening && mActiveChannel != null) {
                    int bytesRead = 0;
                    while (bytesRead < 4) {
                        int r = is.read(lengthBuffer, bytesRead, 4 - bytesRead);
                        if (r == -1) throw new Exception("Stream EOF");
                        bytesRead += r;
                    }

                    int imgLength = ((lengthBuffer[0] & 0xFF) << 24) |
                                    ((lengthBuffer[1] & 0xFF) << 16) |
                                    ((lengthBuffer[2] & 0xFF) << 8)  |
                                    (lengthBuffer[3] & 0xFF);

                    if (imgLength <= 0 || imgLength > 2000000) continue;

                    byte[] imgBuffer = new byte[imgLength];
                    int imgBytesRead = 0;
                    boolean streamError = false;
                    
                    while (imgBytesRead < imgLength) {
                        int r = is.read(imgBuffer, imgBytesRead, imgLength - imgBytesRead);
                        if (r == -1) {
                            streamError = true;
                            break;
                        }
                        imgBytesRead += r;
                    }

                    if (streamError || !isListening) break;

                    Bitmap bitmap = BitmapFactory.decodeByteArray(imgBuffer, 0, imgLength);
                    if (bitmap != null && isListening) {
                        mainHandler.post(() -> {
                            if (isListening) {
                                frameView.setImageBitmap(bitmap);
                            }
                        });
                    }
                }
            } catch (Exception e) {
                Log.d(TAG, "手錶端長連接連線已如預期斷開跳出: " + e.getMessage());
                isListening = false;
            }
        }).start();
    }

    @Override
    public void onMessageReceived(@NonNull MessageEvent messageEvent) {
        if (!UNIVERSAL_SYNC_PATH.equalsIgnoreCase(messageEvent.getPath())) return;
        try {
            String jsonStr = new String(messageEvent.getData(), StandardCharsets.UTF_8);
            JSONObject json = new JSONObject(jsonStr);
            String type = json.optString("type", "");
            String action = json.optString("action", "");

            if ("camera_control".equalsIgnoreCase(type) && "PHONE_TAKE_PICTURE_DONE".equalsIgnoreCase(action)) {
                mainHandler.post(() -> {
                    if (tvCountdown != null) {
                        tvCountdown.setVisibility(View.VISIBLE);
                        tvCountdown.setText("📸 拍照成功！");
                    }
                    mainHandler.postDelayed(() -> {
                        if (tvCountdown != null) tvCountdown.setVisibility(View.GONE);
                    }, 1500);
                });
            }
        } catch (Exception e) {
            Log.e(TAG, "解析手機端發送的狀態失敗", e);
        }
    }

    private void startCountdownFlow() {
        countdown = 3;
        btnCapture.setEnabled(false);
        tvCountdown.setVisibility(View.VISIBLE);
        tvCountdown.setText(String.valueOf(countdown));

        Runnable r = new Runnable() {
            @Override
            public void run() {
                countdown--;
                if (countdown > 0) {
                    tvCountdown.setText(String.valueOf(countdown));
                    mainHandler.postDelayed(this, 1000);
                } else {
                    tvCountdown.setText("🔥 咔嚓！");
                    notifyPhoneCameraService("TAKE_PICTURE");
                    mainHandler.postDelayed(() -> {
                        if (tvCountdown != null) tvCountdown.setVisibility(View.GONE);
                        if (btnCapture != null) btnCapture.setEnabled(true);
                    }, 800);
                }
            }
        };
        mainHandler.postDelayed(r, 1000);
    }

    private void notifyPhoneCameraService(String action) {
        new Thread(() -> {
            try {
                JSONObject json = new JSONObject();
                json.put("sender", "wear");
                json.put("type", "camera_control");
                json.put("action", action);
                byte[] data = json.toString().getBytes(StandardCharsets.UTF_8);

                List<Node> nodes = Tasks.await(Wearable.getNodeClient(this).getConnectedNodes());
                for (Node n : nodes) {
                    Wearable.getMessageClient(this).sendMessage(n.getId(), UNIVERSAL_SYNC_PATH, data);
                }
            } catch (Exception e) {
                Log.e(TAG, "發送指令失敗: " + action, e);
            }
        }).start();
    }

    @Override
    protected void onDestroy() {
        // 兜底防禦：如果用戶直接側滑手勢退出，同樣強制執行完美釋放
        if (isListening || mActiveChannel != null) {
            executeThreeStepCloseFlow();
        }
        Wearable.getMessageClient(this).removeListener(this);
        if (mChannelCallback != null) {
            Wearable.getChannelClient(this).unregisterChannelCallback(mChannelCallback);
        }
        super.onDestroy();
    }
}
