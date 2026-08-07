package cn.luke.wearsync;

import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.FragmentTransaction;

/**
 * 📲 手機端主設置界面容器
 */
public class PhoneSyncMainActivity extends AppCompatActivity {

    private static final String TAG = "WearSync_PhoneMain";
    private static PhoneSyncMainActivity instance;

    public static PhoneSyncMainActivity getInstance() {
        return instance;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        com.google.android.material.color.DynamicColors.applyToActivityIfAvailable(this);
        super.onCreate(savedInstanceState);
        instance = this;

        PhoneLog.d(TAG, "🚀 [主界面] PhoneSyncMainActivity -> onCreate() 啟動");
        setContentView(R.layout.activity_main);

        if (savedInstanceState == null) {
            FragmentTransaction ft = getSupportFragmentManager().beginTransaction();
            ft.replace(R.id.settings, new PhoneSyncMainFragment());
            ft.commit();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // ✅ 建議：在 onResume 中檢查權限，若無權限則彈出 Toast 引導，而非在 onCreate 中強制 startActivity
        checkStoragePermissionOnResume();
    }

    private void checkStoragePermissionOnResume() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                PhoneLog.w(TAG, "⚠️ 尚未取得所有檔案存取權限 (MANAGE_EXTERNAL_STORAGE)");
                // 提示用戶，避免直接硬切換造成視窗焦點異常
            }
        }
    }

    /**
     * 提供給 Fragment 或按鈕點擊時主動調用的權限請求方法
     */
    public void requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                    intent.setData(Uri.fromParts("package", getPackageName(), null));
                    startActivity(intent);
                } catch (Exception e) {
                    PhoneLog.e(TAG, "❌ 打開所有檔案存取權限頁面失敗", e);
                    Intent intent = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                    startActivity(intent);
                }
            } else {
                Toast.makeText(this, "已取得所有檔案存取權限", Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    protected void onNewIntent(@NonNull Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        PhoneLog.d(TAG, "🔄 [主界面] PhoneSyncMainActivity -> onNewIntent() 喚醒");
    }

    @Override
    protected void onDestroy() {
        if (instance == this) {
            instance = null;
        }
        PhoneLog.d(TAG, "🛑 [主界面] PhoneSyncMainActivity -> onDestroy() 銷毀");
        super.onDestroy();
    }
}
