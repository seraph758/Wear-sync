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
    private static ImageView ivLocalPreview;
    private static PhoneSyncMainActivity instance;

    public static PhoneSyncMainActivity getInstance() {
        return instance;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        com.google.android.material.color.DynamicColors.applyToActivityIfAvailable(this);
        super.onCreate(savedInstanceState);
        instance = this;

        if (getSupportActionBar() != null) {
            getSupportActionBar().hide();
        }

        // 🎯 [手機端核心防禦]：防止手機端彈出 Activity 提權後，因無人觸控自動熄屏導致相機被系統強行凍結
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);

        // 綁定本地顯示小視窗
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
            
            // 點亮並顯示本地小預覽視窗
            if (ivLocalPreview != null) {
                ivLocalPreview.setVisibility(View.VISIBLE);
            }

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
     * 🎯 提供給 Service 調用，將採集到的相機幀畫面同步刷新到手機端的小窗上
     */
    public void updateLocalPreview(Bitmap bitmap) {
        runOnUiThread(() -> {
            if (ivLocalPreview != null && ivLocalPreview.getVisibility() == View.VISIBLE && bitmap != null) {
                ivLocalPreview.setImageBitmap(bitmap);
            }
        });
    }

    /**
     * 🎯 提供給 Service 雙向關閉調用，當相機關閉時隱藏小預覽窗並安全歸位
     */
    public void hideLocalPreview() {
        runOnUiThread(() -> {
            if (ivLocalPreview != null) {
                ivLocalPreview.setVisibility(View.GONE);
                ivLocalPreview.setImageDrawable(null);
            }
        });
    }

    @Override
    protected void onDestroy() {
        if (instance == this) {
            instance = null;
        }
        super.onDestroy();
    }
}
