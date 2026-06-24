package de.rhaeus.wearsync

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.Fragment
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Wearable
import androidx.compose.ui.graphics.Color

class PhoneSyncMainFragment : Fragment() {
    private val isNotificationAllowedState = mutableStateOf(false)
    private val isCameraAllowedState = mutableStateOf(false) 
    private val isConnectedState = mutableStateOf(false)
    private var capabilityChangedListener: CapabilityClient.OnCapabilityChangedListener? = null

    // 🌟 新增：介面日誌開關狀態（預設同步讀取 PhoneLog.DEBUG 記憶體狀態）
    private val uiLogDebugSwitch = mutableStateOf(PhoneLog.DEBUG)

    private val dndMasterSwitch = mutableStateOf(true)
    private val wearSleepSwitch = mutableStateOf(false)
    private val wearPowerSavingSwitch = mutableStateOf(false)
    private val dndVibrateSwitch = mutableStateOf(false) 

    private val alarmMasterSwitch = mutableStateOf(true) 
    private val alarmPkgState = mutableStateOf("com.google.android.deskclock")
    private val alarmDismissKeyState = mutableStateOf("停止")
    private val alarmSnoozeKeyState = mutableStateOf("延后")

    private val requestCameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        isCameraAllowedState.value = isGranted
        PhoneLog.d("WearSync_Main", "📸 [权限申请] 用户交互相机权限结果: $isGranted")
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val sp = requireContext().getSharedPreferences("wearsync_prefs", Context.MODE_PRIVATE)
        
        // 🌟 初始化讀取：從持久化數據中回復用戶上次設定的日誌開關狀態
        val savedLogConfig = sp.getBoolean("phone_log_debug_visible", true)
        PhoneLog.DEBUG = savedLogConfig
        uiLogDebugSwitch.value = savedLogConfig

        dndMasterSwitch.value = sp.getBoolean("dnd_master", true)
        wearSleepSwitch.value = sp.getBoolean("wear_sleep", false)
        wearPowerSavingSwitch.value = sp.getBoolean("wear_power_saving", false)
        dndVibrateSwitch.value = sp.getBoolean("dnd_vibrate", false)

        alarmMasterSwitch.value = sp.getBoolean("alarm_master", true)
        alarmPkgState.value = sp.getString("alarm_pkg", "com.google.android.deskclock") ?: "com.google.android.deskclock"
        alarmDismissKeyState.value = sp.getString("alarm_dismiss_key", "停止") ?: "停止"
        alarmSnoozeKeyState.value = sp.getString("alarm_snooze_key", "延后") ?: "延后"

        fun calculateAndSaveMask() {
            var mask = 0
            if (dndMasterSwitch.value) mask = mask or 0x01 
            if (dndVibrateSwitch.value) mask = mask or 0x02 
            if (wearSleepSwitch.value) mask = mask or 0x04 
            if (wearPowerSavingSwitch.value) mask = mask or 0x08 
            sp.edit().putInt("switches_mask", mask).apply()
            PhoneLog.d("WearSync_Main", "📊 [控制台同步] 全新计算出的全局 Mask 总值为: $mask")
        }

        return ComposeView(requireContext()).apply {
            setContent {
                MaterialTheme {
                    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF121212)).statusBarsPadding()) {
                        Column(
                            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Text(text = "WearSync 控制台", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White)

                            // 系统核心权限卡片
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))
                            ) {
                                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                    Text("系统核心权限", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.LightGray)
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                        Column {
                                            Text("通知接管服务", fontSize = 15.sp, color = Color.White)
                                            Text(text = if (isNotificationAllowedState.value) "已授权" else "未授权 (核心状态同步必备)", fontSize = 12.sp, color = if (isNotificationAllowedState.value) Color(0xFF4CAF50) else Color(0xFFF44336))
                                        }
                                        if (!isNotificationAllowedState.value) {
                                            Button(onClick = { 
                                                PhoneLog.d("WearSync_Main", "🔏 [权限引导] 用户点击引导：跳转系统通知接管授权页")
                                                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) 
                                            }) { Text("去授权") }
                                        }
                                    }
                                    HorizontalDivider(color = Color(0xFF2C2C2C))
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                        Column {
                                            Text("相机硬件权限", fontSize = 15.sp, color = Color.White)
                                            Text(text = if (isCameraAllowedState.value) "已授权" else "未授权 (远程拍照必备)", fontSize = 12.sp, color = if (isCameraAllowedState.value) Color(0xFF4CAF50) else Color(0xFFF44336))
                                        }
                                        if (!isCameraAllowedState.value) {
                                            Button(onClick = { requestCameraPermissionLauncher.launch(android.Manifest.permission.CAMERA) }) { Text("授权相机") }
                                        }
                                    }
                                }
                            }

                            // 手表连线状态卡片
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))
                            ) {
                                Row(modifier = Modifier.padding(16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Text("手表连线状态", fontSize = 16.sp, color = Color.White)
                                    Box(modifier = Modifier.background(if (isConnectedState.value) Color(0xFF2E7D32) else Color(0xFFC62828), RoundedCornerShape(20.dp)).padding(horizontal = 12.dp, vertical = 6.dp)) {
                                        Text(if (isConnectedState.value) "已连接" else "未就绪", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }

                            // 🌟 新增：後台調試日誌可視化開關卡片
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))
                            ) {
                                Row(modifier = Modifier.padding(16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("开发者调试日志", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                        Text("关闭后将彻底隐藏手机端后台所有排查Log，极度省电", fontSize = 12.sp, color = Color.Gray)
                                    }
                                    Switch(
                                        checked = uiLogDebugSwitch.value,
                                        onCheckedChange = { isChecked ->
                                            uiLogDebugSwitch.value = isChecked
                                            PhoneLog.DEBUG = isChecked // 🔥 一鍵影響 Java 底層所有代碼
                                            sp.edit().putBoolean("phone_log_debug_visible", isChecked).apply()
                                            PhoneLog.d("WearSync_Main", "🎛️ [日志开关改变] 用户在界面切换日志状态 = $isChecked")
                                        }
                                    )
                                }
                            }

                            // 勿扰同步卡片
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))
                            ) {
                                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text("同步勿扰状态", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                            Text("开启后将双向对齐手表状态(支持开启与联动关闭)", fontSize = 12.sp, color = Color.Gray)
                                        }
                                       Switch(
                                            checked = dndMasterSwitch.value,
                                            onCheckedChange = { isChecked -> 
                                                dndMasterSwitch.value = isChecked
                                                sp.edit().putBoolean("dnd_master", isChecked).apply()
                                                PhoneLog.d("WearSync_Main", "🎛️ [控制台交互] 勿扰总开关切换为: $isChecked")
                                                calculateAndSaveMask() 
                                            }
                                        )
                                    }

                                    AnimatedVisibility(visible = dndMasterSwitch.value) {
                                        Column(modifier = Modifier.fillMaxWidth().background(Color(0xFF252525), RoundedCornerShape(12.dp)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                                Text("1. 同步状态时手表震动", fontSize = 14.sp, color = Color.White)
                                                Switch(
                                                    checked = dndVibrateSwitch.value,
                                                    onCheckedChange = { it ->
                                                        dndVibrateSwitch.value = it
                                                        requireContext().getSharedPreferences("wearsync_prefs", Context.MODE_PRIVATE).edit().putBoolean("dnd_vibrate", it).apply()
                                                        PhoneLog.d("WearSync_Main", "🎛️ [控制台交互] 子开关-勿扰时手表震动改变为: $it")
                                                        calculateAndSaveMask()
                                                    }
                                                )
                                            }
                                            HorizontalDivider(color = Color(0xFF383838))
                                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                                Text("2. 连动睡眠模式", fontSize = 14.sp, color = Color.White)
                                                Switch(
                                                    checked = wearSleepSwitch.value,
                                                    onCheckedChange = { it ->
                                                        wearSleepSwitch.value = it
                                                        requireContext().getSharedPreferences("wearsync_prefs", Context.MODE_PRIVATE).edit().putBoolean("wear_sleep", it).apply()
                                                        PhoneLog.d("WearSync_Main", "🎛️ [控制台交互] 子开关-连动睡眠模式改变为: $it")
                                                        calculateAndSaveMask()
                                                    }
                                                )
                                            }
                                            HorizontalDivider(color = Color(0xFF383838))
                                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                                Text("3. 连动省电模式", fontSize = 14.sp, color = Color.White)
                                                Switch(
                                                    checked = wearPowerSavingSwitch.value,
                                                    onCheckedChange = { it ->
                                                        wearPowerSavingSwitch.value = it
                                                        requireContext().getSharedPreferences("wearsync_prefs", Context.MODE_PRIVATE).edit().putBoolean("wear_power_saving", it).apply()
                                                        PhoneLog.d("WearSync_Main", "🎛️ [控制台交互] 子开关-连动省电模式改变为: $it")
                                                        calculateAndSaveMask()
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            // 闹钟拦截卡片
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))
                            ) {
                                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text("同步手机闹钟状态", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                            Text("开启后支持手表端接管非标闹钟的全屏响铃拦截与控制", fontSize = 12.sp, color = Color.Gray)
                                        }
                                        Switch(checked = alarmMasterSwitch.value, onCheckedChange = { alarmMasterSwitch.value = it; sp.edit().putBoolean("alarm_master", it).apply(); PhoneLog.d("WearSync_Main", "🎛️ [控制台交互] 闹钟接管总开关改变为: $it") })
                                    }

                                    AnimatedVisibility(visible = alarmMasterSwitch.value) {
                                        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                            OutlinedTextField(
                                                value = alarmPkgState.value,
                                                onValueChange = { alarmPkgState.value = it; sp.edit().putString("alarm_pkg", it).apply() },
                                                label = { Text("目标闹钟应用包名", color = Color.Gray) },
                                                modifier = Modifier.fillMaxWidth(),
                                                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedBorderColor = Color(0xFF3F51B5), unfocusedBorderColor = Color.DarkGray)
                                            )

                                            OutlinedTextField(
                                                value = alarmDismissKeyState.value,
                                                onValueChange = { alarmDismissKeyState.value = it; sp.edit().putString("alarm_dismiss_key", it).apply() },
                                                label = { Text("自定义“停止/关闭”动作按钮文本匹配词", color = Color.Gray) },
                                                modifier = Modifier.fillMaxWidth(),
                                                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedBorderColor = Color(0xFF4CAF50), unfocusedBorderColor = Color.DarkGray)
                                            )

                                            OutlinedTextField(
                                                value = alarmSnoozeKeyState.value,
                                                onValueChange = { alarmSnoozeKeyState.value = it; sp.edit().putString("alarm_snooze_key", it).apply() },
                                                label = { Text("自定义“延后/稍后”动作按钮文本匹配词", color = Color.Gray) },
                                                modifier = Modifier.fillMaxWidth(),
                                                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedBorderColor = Color(0xFFFF9800), unfocusedBorderColor = Color.DarkGray)
                                            )
                                        }
                                    }
                                }
                            }
                            // 🧪 侦错功能按钮
                            Button(
                                onClick = {
                                    try {
                                        val context = requireContext()
                                        val svcIntent = Intent(context, PhoneSyncCameraService::class.java).apply { action = "START_CAMERA" }
                                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                                            context.startForegroundService(svcIntent)
                                        } else {
                                            context.startService(svcIntent)
                                        }
                                        PhoneLog.d("WearSync_Main", "🧪 [调试主动拉起] 用户在控制台触发测试：尝试唤醒手机端相机背景流 PhoneSyncCameraService")
                                    } catch (e: Exception) {
                                        PhoneLog.e("WearSync_Main", "❌ [调试主动拉起失败] 唤醒相机前台服务发生异常: " + e.message)
                                    }
                                },
                                modifier = Modifier.fillMaxWidth().height(50.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE91E63)),
                                shape = RoundedCornerShape(12.dp)
                            ) { 
                                Text("🧪 调试：拉起远端相机控制", fontSize = 15.sp, fontWeight = FontWeight.Bold) 
                            }

                            // 🎯【红色安全按钮】
                            if (isCameraAllowedState.value) {
                                Button(
                                    onClick = {
                                        try {
                                            val context = requireContext()
                                            val svcIntent = Intent(context, PhoneSyncCameraService::class.java).apply { action = "STOP_CAMERA" }
                                            context.startService(svcIntent)
                                            PhoneLog.d("WearSync_Main", "🎯 [安全中止] 用户强制关闭手机相机背景服务")
                                        } catch (e: Exception) {
                                            PhoneLog.e("WearSync_Main", "❌ [安全中止失败] 强行停止相机服务异常: " + e.message)
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F)),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth().height(50.dp)
                                ) {
                                    Text("❌ 强制关闭手机相机背景服务", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold) 
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        checkNotificationPermission()
        isCameraAllowedState.value = requireContext().checkSelfPermission(android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        registerConnectivityListener()
    }

    override fun onPause() { 
        super.onPause()
        unregisterConnectivityListener() 
    }

    private fun checkNotificationPermission() {
        val flat = Settings.Secure.getString(requireContext().contentResolver, "enabled_notification_listeners")
        isNotificationAllowedState.value = flat != null && flat.contains(requireContext().packageName)
    }

    private fun registerConnectivityListener() {
        val context = requireContext()
        Wearable.getCapabilityClient(context).getCapability("dnd_sync", CapabilityClient.FILTER_REACHABLE).addOnSuccessListener { 
            isConnectedState.value = it.nodes.isNotEmpty() 
            PhoneLog.d("WearSync_Main", "📡 [连线探针] 初始化连线检查成功，当前节点就绪状态 = " + it.nodes.isNotEmpty())
        }
        capabilityChangedListener = CapabilityClient.OnCapabilityChangedListener { 
            isConnectedState.value = it.nodes.isNotEmpty() 
            PhoneLog.d("WearSync_Main", "📡 [连线探针回调] 手表连接状态发生突变！当前存活状态 = " + it.nodes.isNotEmpty())
        }
        capabilityChangedListener?.let { Wearable.getCapabilityClient(context).addListener(it, "dnd_sync") }
    }

    private fun unregisterConnectivityListener() {
        capabilityChangedListener?.let { 
            Wearable.getCapabilityClient(requireContext()).removeListener(it) 
            PhoneLog.d("WearSync_Main", "📡 [连线探针注销] 主界面进入后台，解绑 Google 连线监听器")
        }
    }
}
