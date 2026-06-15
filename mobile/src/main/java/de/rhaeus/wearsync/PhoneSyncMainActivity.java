package de.rhaeus.wearsync;

import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.FragmentTransaction;

public class PhoneSyncMainActivity extends AppCompatActivity {
    private static final String TAG = "WearSync_PhoneMain";
    private ImageView ivLocalPreview;

    // 🎯 [全新追加]：靜態持有單例實例，為 PhoneSyncCameraService 提供 getInstance() 的應答鏈條
    private static PhoneSyncMainActivity instance;

    // 🎯 [全新追加]：向全域暴露當前運行的實例，完美相容 CameraService 既有呼叫
    public static PhoneSyncMainActivity getInstance() {
        return instance;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        com.google.android.material.color.DynamicColors.applyToActivityIfAvailable(this);
        super.onCreate(savedInstanceState);

        // 🎯 綁定當前實例
        instance = this;

        if (getSupportActionBar() != null) {
            getSupportActionBar().hide();
        }

        // 🎯 [手機端核心防禦]：防止手機端彈出 Activity 提權後，因無人觸控自動熄屏導致相機被一加系統強行凍結
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);

        // 初始化本地相機小窗預覽控制項
        ivLocalPreview = findViewById(R.id.iv_local_preview);

        if (savedInstanceState == null) {
            FragmentTransaction ft = getSupportFragmentManager().beginTransaction();
            ft.replace(R.id.settings, new PhoneSyncMainFragment()); 
            ft.commit();
        }

        // 🎯 處理第一次創建 Activity 時的拉起指令
        handleIncomingIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        // 🎯 處理 Activity 已經在後台存活時，再次被複用拉起時的指令
        handleIncomingIntent(intent);
    }

    private void handleIncomingIntent(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        Log.d(TAG, "📥 MainActivity 收到意圖 Action: " + action);

        if ("ACTION_START_CAMERA_FLOW".equalsIgnoreCase(action)) {
            Log.d(TAG, "🎬 [前台合法接力] 已經處於前台活躍狀態，正在開啟相機前台服務...");
            Intent svc = new Intent(this, PhoneSyncCameraService.class);
            svc.setAction("START_CAMERA");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(svc);
            } else {
                startService(svc);
            }
        }
    }

    /**
     * 🎯 [相機小窗安全歸位保險]：供前台相機服務動態投遞局部幀，實現低延遲小窗預覽
     */
    public void updateLocalPreview(Bitmap bitmap) {
        runOnUiThread(() -> {
            if (ivLocalPreview != null && ivLocalPreview.getVisibility() == View.VISIBLE && bitmap != null) {
                ivLocalPreview.setImageBitmap(bitmap);
            }
        });
    }

    /**
     * 🎯 [相機小窗安全歸位保險]：顯示本地相機預覽小窗
     */
    public void showLocalPreview() {
        runOnUiThread(() -> {
            if (ivLocalPreview != null) {
                ivLocalPreview.setVisibility(View.VISIBLE);
                Log.d(TAG, "📸 本地相機小窗預覽已設為 VISIBLE");
            }
        });
    }

    /**
     * 🎯 [相機小窗安全歸位保險]：隱藏本地相機預覽小窗并清空殘留幀
     */
    public void hideLocalPreview() {
        runOnUiThread(() -> {
            if (ivLocalPreview != null) {
                ivLocalPreview.setVisibility(View.GONE);
                ivLocalPreview.setImageBitmap(null);
                Log.d(TAG, "🔒 本地相機小窗預覽已安全隱藏 (GONE) 並清空殘留緩衝");
            }
        });
    }

    @Override
    protected void onDestroy() {
        // 🎯 [核心防禦保險]：防止銷毀時產生的記憶體洩漏，將單例引用安全歸零
        if (instance == this) {
            instance = null;
        }
        super.onDestroy();
    }
}
