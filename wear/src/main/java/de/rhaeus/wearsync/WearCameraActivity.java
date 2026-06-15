package de.rhaeus.wearsync;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
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
    private int countdown = 3;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private ChannelClient.ChannelCallback mChannelCallback;
    private ChannelClient.Channel mActiveChannel = null;
    private volatile boolean isListening = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // 🔒 強制鎖定手錶端相機介面為標準豎屏（或者不隨重力旋轉），防止它污染系統設置
        setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_wear_camera);

        frameView = findViewById(R.id.frameView);
        tvCountdown = findViewById(R.id.tvCountdown);
        btnCapture = findViewById(R.id.btnCapture);

        btnCapture.setOnClickListener(v -> startCountdownFlow());
        
        // 🎯 依靠重寫的 onBackPressed() 攔截物理側滑/返回鍵，完美釋放
        Wearable.getMessageClient(this).addListener(this);
        setupChannelStreamListener();
        notifyPhoneCameraService("START_CAMERA");
    }

    /**
     * 🎯 完美三步驟關閉邏輯：當用戶側滑返回、或按下實體返回鍵時精準觸發
     */
    @Override
    public void onBackPressed() {
        Log.d(TAG, "🔒 檢測到返回動作，觸發完美三步驟關閉機制...");
        executeThreeStepCloseFlow();
    }

    private void executeThreeStepCloseFlow() {
        new Thread(() -> {
            // 【第一步】主動中斷 Channel Client 通道，並向手機發送停止訊號
            try {
                JSONObject json = new JSONObject();
                json.put("sender", "wear");
                json.put("type", "camera_control");
                json.put("action", "STOP_CAMERA");
                byte[] data = json.toString().getBytes(StandardCharsets.UTF_8);
                List<Node> nodes = Tasks.await(Wearable.getNodeClient(this).getConnectedNodes());
                for (Node n : nodes) {
                    Wearable.getMessageClient(this).sendMessage(n.getId(), UNIVERSAL_SYNC_PATH, data);
                }
                Log.d(TAG, "步驟 1.1：已向手機發送 STOP_CAMERA 關閉命令");
            } catch (Exception e) {
                Log.e(TAG, "發送關閉訊號失敗", e);
            }

            if (mActiveChannel != null) {
                try {
                    Wearable.getChannelClient(this).close(mActiveChannel);
                    Log.d(TAG, "步驟 1.2：Channel 管道已從手錶端安全中斷");
                } catch (Exception ignored) {}
                mActiveChannel = null;
            }

            // 【第二步】退出 Camera Active 狀態（阻斷非同步線程與 UI 渲染）
            isListening = false;
            mainHandler.removeCallbacksAndMessages(null);
            Log.d(TAG, "步驟 2：Camera Active 狀態已徹底停用，阻斷所有異步渲染");

            // 【第三步】整體退出手錶端頁面
            mainHandler.post(() -> {
                Log.d(TAG, "步驟 3：整體關閉手錶端 UI 頁面 (finish)");
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    finishAndRemoveTask();
                } else {
                    finish();
                }
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
                        isListening = true;
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
                        // 🎯 核心修正：利用 Matrix 將手錶預覽畫面逆時針旋轉 90 度，校正方向
                        android.graphics.Matrix matrix = new android.graphics.Matrix();
                        matrix.postRotate(-90f);
                        Bitmap rotatedBitmap = Bitmap.createBitmap(
                            bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true
                        );

                        mainHandler.post(() -> {
                            if (isListening) {
                                frameView.setImageBitmap(rotatedBitmap);
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

            if ("camera_control".equalsIgnoreCase(type)) {
                if ("PHONE_TAKE_PICTURE_DONE".equalsIgnoreCase(action)) {
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
                // 🎯 完美補正：當收到手機端主動關閉的訊號時，清除任務棧，乾淨退出
                else if ("PHONE_CAMERA_CLOSED".equalsIgnoreCase(action)) {
                    Log.d(TAG, "🚨 收到手機端關閉訊號，手錶端開始退出任務棧...");
                    isListening = false;
                    
                    mainHandler.post(() -> {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                            finishAndRemoveTask(); 
                        } else {
                            finish();
                        }
                    });
                }
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
        Log.d(TAG, "🔒 WearCameraActivity 正在回呼 onDestroy，開始執行最高規格安全釋放...");
        
        // 1. 確保切斷監聽與通道
        isListening = false;
        if (mActiveChannel != null) {
            try {
                Wearable.getChannelClient(this).close(mActiveChannel);
            } catch (Exception ignored) {}
            mActiveChannel = null;
        }
        
        // 2. 註銷各種 Wear 監聽器，防止記憶體洩漏
        Wearable.getMessageClient(this).removeListener(this);
        if (mChannelCallback != null) {
            Wearable.getChannelClient(this).unregisterChannelCallback(mChannelCallback);
        }
        
        // 3. 清空主線程排隊的繪製任務，防止銷毀時還在非同步繪製圖像
        if (mainHandler != null) {
            mainHandler.removeCallbacksAndMessages(null);
        }

        // 4. 調用父類銷毀
        super.onDestroy();

        // 5. 🎯【終極根治旋轉 Bug】強行讓當前進程自殘歸位，確保 Wear OS 系統完全回收重力感應器與全域旋轉鎖
        Log.d(TAG, "🚀 進程即將自殘退出，乾淨歸還全域窗口旋轉鎖...");
        android.os.Process.killProcess(android.os.Process.myPid());
    }
}