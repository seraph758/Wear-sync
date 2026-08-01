package cn.luke.wearsync

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

/**
 * 远程相机跳板 Activity (开源精简版)
 * - 保留运行时权限申请（面向 Android 6.0+ 现代标准）
 * - 移除所有低版本兼容分支
 * - 修复 Action 常量不匹配导致的黑屏问题
 */
class PhoneSyncRemoteCameraActivity : ComponentActivity() {

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startCameraServiceAndFinish()
        } else {
            // 权限被拒绝，直接退出，避免无意义的等待
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        when {
            // 已有权限，直接启动服务
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED -> {
                startCameraServiceAndFinish()
            }
            // 未授权，发起运行时权限申请
            else -> {
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
    }

    private fun startCameraServiceAndFinish() {
        val serviceIntent = Intent(this, PhoneSyncCameraService::class.java).apply {
            // ✅ 核心修复：使用 Service 定义的常量，而非硬编码字符串
            action = PhoneSyncCameraService.ACTION_START_CAMERA
        }
        startForegroundService(serviceIntent)
        finish()
    }
}
