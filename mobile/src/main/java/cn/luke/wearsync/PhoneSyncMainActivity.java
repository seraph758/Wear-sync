package cn.luke.wearsync;

import android.content.Intent;
import android.os.Bundle;
import android.os.Environment;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.FragmentTransaction;

import com.google.android.material.color.DynamicColors;

/**
 * 📲 手機端主設置界面容器 (Android 16/17 精简版)
 */
public class PhoneSyncMainActivity extends AppCompatActivity {

    private static final String TAG = "WearSync_PhoneMain";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        DynamicColors.applyToActivityIfAvailable(this);
        super.onCreate(savedInstanceState);

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
        checkStoragePermissionOnResume();
    }

    private void checkStoragePermissionOnResume() {
        if (!Environment.isExternalStorageManager()) {
            PhoneLog.w(TAG, "⚠️ 尚未取得所有檔案存取權限 (MANAGE_EXTERNAL_STORAGE)");
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
        PhoneLog.d(TAG, "🛑 [主界面] PhoneSyncMainActivity -> onDestroy() 銷毀");
        super.onDestroy();
    }
}
