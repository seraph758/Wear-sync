package cn.luke.wearsync;

import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
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

        // ✅ 新增：检查并申请管理所有文件的权限
        checkAndRequestStoragePermission();

        PhoneLog.d(TAG, "🚀 [主界面] PhoneSyncMainActivity -> onCreate() 启动");
        setContentView(R.layout.activity_main);

        if (savedInstanceState == null) {
            FragmentTransaction ft = getSupportFragmentManager().beginTransaction();
            ft.replace(R.id.settings, new PhoneSyncMainFragment());
            ft.commit();
        }
    }

    // ✅ 新增：权限检查方法
    private void checkAndRequestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                PhoneLog.d(TAG, "⚠️ [权限] 缺少管理所有文件的权限，即将跳转设置页面");
                Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                intent.setData(Uri.fromParts("package", getPackageName(), null));
                startActivity(intent);
            }
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
