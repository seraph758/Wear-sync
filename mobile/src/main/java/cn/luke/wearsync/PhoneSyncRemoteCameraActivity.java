package cn.luke.wearsync;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;

import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.ContextCompat;

/**
 * 远程相机跳板 Activity (最终修复版)
 * - 职责简化：不再负责查找节点，仅从 WearSyncState 读取已缓存的节点 ID。
 * - 完美匹配：与你提供的 PhoneConnectionManager 和 WearSyncState 无缝协作。
 */
public class PhoneSyncRemoteCameraActivity extends ComponentActivity {

    private static final String TAG = "WearSync_RemoteActivity";

    private final ActivityResultLauncher<String> cameraPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
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

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            // 已有权限，直接启动服务
            startCameraServiceAndFinish();
        } else {
            // 未授权，发起运行时权限申请
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA);
        }
    }

    private void startCameraServiceAndFinish() {
        // ✅ 不再读取和传递 nodeId，仅做权限检查和启动服务
        Intent serviceIntent = new Intent(this, PhoneSyncCameraService.class);
        serviceIntent.setAction(PhoneSyncCameraService.ACTION_START_CAMERA);
        startForegroundService(serviceIntent);
        finish();
    }

}
