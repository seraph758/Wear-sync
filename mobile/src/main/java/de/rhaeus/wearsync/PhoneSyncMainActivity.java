package de.rhaeus.wearsync;

import android.content.Intent;
import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.FragmentTransaction;

/**
 * 📲 手机端主设置界面容器
 * 核心变更：已彻底移除老旧的相机跳板逻辑，拍照职能由专门的 WearSyncRemoteCameraActivity 承接。
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

        PhoneLog.d(TAG, "🚀 [主界面] PhoneSyncMainActivity -> onCreate() 启动");
        setContentView(R.layout.activity_main);

        if (savedInstanceState == null) {
            FragmentTransaction ft = getSupportFragmentManager().beginTransaction();
            ft.replace(R.id.settings, new PhoneSyncMainFragment());
            ft.commit();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        PhoneLog.d(TAG, "🔄 [主界面] PhoneSyncMainActivity -> onNewIntent() 唤醒");
    }

    @Override
    protected void onDestroy() {
        if (instance == this) {
            instance = null;
        }
        PhoneLog.d(TAG, "🛑 [主界面] PhoneSyncMainActivity -> onDestroy() 销毁");
        super.onDestroy();
    }
}
