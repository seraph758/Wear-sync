package cn.luke.wearsync;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Bundle;
import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.ContextCompat;

import java.util.concurrent.atomic.AtomicBoolean;

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

    private final AtomicBoolean mIsFgsReadyReceived = new AtomicBoolean(false);

    private final BroadcastReceiver mFgsReadyReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (PhoneSyncCameraService.ACTION_CAMERA_FGS_READY.equals(intent.getAction())) {
                if (mIsFgsReadyReceived.compareAndSet(false, true)) {
                    PhoneLog.d(TAG, "🟢 [RemoteCameraActivity] 收到 CAMERA_FGS_READY");
                    PhoneLog.d(TAG, "🟢 [RemoteCameraActivity] FGS 已就绪，finish Activity");
                    finish();
                }
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        PhoneLog.d(TAG, "🟢 PhoneSyncRemoteCameraActivity onCreate");

        // 注册 FGS 就绪通知接收器
        IntentFilter filter = new IntentFilter(PhoneSyncCameraService.ACTION_CAMERA_FGS_READY);
        // 🚀 适配 Android 16/17：minSdk 为 35，始终使用 RECEIVER_NOT_EXPORTED 确保安全
        registerReceiver(mFgsReadyReceiver, filter, Context.RECEIVER_NOT_EXPORTED);

        // 无论是本地还是远程触发，都走这个统一的权限检查流程
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            PhoneLog.d(TAG, "✅ 相机权限已获得，直接启动服务");
            startCameraServiceAndFinish();
        } else {
            PhoneLog.w(TAG, "⚠️ 缺少相机权限，准备请求...");
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA);
        }
    }

    @Override
    protected void onDestroy() {
        try {
            unregisterReceiver(mFgsReadyReceiver);
        } catch (Exception ignored) {}
        super.onDestroy();
    }

    private void startCameraServiceAndFinish() {
        // 将启动来源传递给 Service，方便 Service 内部做不同处理（如果需要）
        Intent intent = getIntent();
        String source = intent.getStringExtra(EXTRA_SOURCE);
        String nodeId = intent.getStringExtra("remote_node_id");
        PhoneLog.d(TAG, "🚀 [RemoteCameraActivity] 启动 PhoneSyncCameraService. Source=" + source + ", NodeID=" + nodeId);
        
        Intent serviceIntent = new Intent(this, PhoneSyncCameraService.class);
        serviceIntent.setAction(PhoneSyncCameraService.ACTION_START_CAMERA);
        if (source != null) {
            serviceIntent.putExtra(EXTRA_SOURCE, source);
        }
        if (nodeId != null) {
            serviceIntent.putExtra("remote_node_id", nodeId);
        }
        
        // 使用 startForegroundService 确保在 Android 8.0+ 上能正常启动
        try {
            ContextCompat.startForegroundService(this, serviceIntent);
            PhoneLog.d(TAG, "✅ startForegroundService 调用成功，等待 FGS 就绪信号...");
        } catch (Exception e) {
            PhoneLog.e(TAG, "❌ startForegroundService 失败", e);
            finish(); // 如果启动失败，就没必要等了
        }
    }
}
