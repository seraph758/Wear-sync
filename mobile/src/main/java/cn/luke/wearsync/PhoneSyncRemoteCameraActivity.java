package cn.luke.wearsync;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.ContextCompat;

/**
 * 相机功能总控台
 * 职责：统一处理本地和远程的相机启动请求，进行权限检查，并启动 PhoneSyncCameraService。
 */
public class PhoneSyncRemoteCameraActivity extends ComponentActivity {

    private static final String TAG = "WearSync_RemoteActivity";
    // 用于区分启动来源的 Key
    public static final String EXTRA_SOURCE = "extra_source";
    public static final String SOURCE_REMOTE = "source_remote";
    public static final String SOURCE_LOCAL = "source_local";

    private final ActivityResultLauncher<String> cameraPermissionLauncher = registerForActivityResult(
        new ActivityResultContracts.RequestPermission(),
        isGranted -> {
            if (isGranted) {
                startCameraServiceAndFinish();
            } else {
                // 权限被拒绝，直接退出
                finish();
            }
        });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 无论是本地还是远程触发，都走这个统一的权限检查流程
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCameraServiceAndFinish();
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA);
        }
    }

    private void startCameraServiceAndFinish() {
        // 将启动来源传递给 Service，方便 Service 内部做不同处理（如果需要）
        String source = getIntent().getStringExtra(EXTRA_SOURCE);
        
        Intent serviceIntent = new Intent(this, PhoneSyncCameraService.class);
        serviceIntent.setAction(PhoneSyncCameraService.ACTION_START_CAMERA);
        if (source != null) {
            serviceIntent.putExtra(EXTRA_SOURCE, source);
        }
        
        // 使用 startForegroundService 确保在 Android 8.0+ 上能正常启动
        ContextCompat.startForegroundService(this, serviceIntent);
        finish(); // 任务完成，立即关闭自身
    }
}
