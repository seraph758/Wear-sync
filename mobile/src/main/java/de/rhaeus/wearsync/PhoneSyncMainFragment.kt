package de.rhaeus.wearsync

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.util.Log
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

    // === [AI_SECURITY_FIREWALL: MAIN_FRAGMENT_DND_STATE_DECLARATION] ===
    private val dndMasterSwitch = mutableStateOf(true)
    private val wearSleepSwitch = mutableStateOf(false)
    private val wearPowerSavingSwitch = mutableStateOf(false)
    private val dndVibrateSwitch = mutableStateOf(false) 
    // === [AI_SECURITY_FIREWALL_END: MAIN_FRAGMENT_DND_STATE_DECLARATION] ===

    // === [AI_SECURITY_FIREWALL: MAIN_FRAGMENT_ALARM_STATE_DECLARATION] ===
    private val alarmMasterSwitch = mutableStateOf(true) 
    private val alarmPkgState = mutableStateOf("com.google.android.deskclock")
    private val alarmDismissKeyState = mutableStateOf("停止")
    private val alarmSnoozeKeyState = mutableStateOf("延后")
    // === [AI_SECURITY_FIREWALL_END: MAIN_FRAGMENT_ALARM_STATE_DECLARATION] ===

    private val requestCameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        isCameraAllowedState.value = isGranted
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val sp = requireContext().getSharedPreferences("wearsync_prefs", Context.MODE_PRIVATE)
        dndMasterSwitch.value = sp.getBoolean("dnd_master", true)
        wearSleepSwitch.value = sp.getBoolean("wear_sleep", false)
        wearPowerSavingSwitch.value = sp.getBoolean("wear_power_saving", false)
        dndVibrateSwitch.value = sp.getBoolean("dnd_vibrate", false)

        alarmMasterSwitch.value = sp.getBoolean("alarm_master", true)
        alarmPkgState.value = sp.getString("alarm_pkg", "com.google.android.deskclock") ?: "com.google.android.deskclock"
        alarmDismissKeyState.value = sp.getString("alarm_dismiss_key", "停止") ?: "停止"
        alarmSnoozeKeyState.value = sp.getString("alarm_snooze_key", "延后") ?: "延后"

        fun calculateAndSaveMask() {
            var score = 0
            if (wearSleepSwitch.value) score += 1       
            if (wearPowerSavingSwitch.value) score += 2 
            if (dndVibrateSwitch.value) score += 4     
            sp.edit().putInt("switches_mask", score).apply()
            Log.d("WearSync_Main", "📊 本地开关实时组合总分数更新为: $score")
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

                            // 核心權限卡片
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
                                            Button(onClick = { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }) { Text("去授权") }
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

                            // 連線狀態卡片
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

                            // 勿擾同步卡片
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
                                        Switch(checked = dndMasterSwitch.value, onCheckedChange = { dndMasterSwitch.value = it; sp.edit().putBoolean("dnd_master", it).apply() })
                                    }

                                    AnimatedVisibility(visible = dndMasterSwitch.value) {
                                        Column(modifier = Modifier.fillMaxWidth().background(Color(0xFF252525), RoundedCornerShape(12.dp)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                                Text("1. 同步状态时手表震动", fontSize = 14.sp, color = Color.White)
                                                Switch(checked = dndVibrateSwitch.value, onCheckedChange = { dndVibrateSwitch.value = it; sp.edit().putBoolean("dnd_vibrate", it).apply(); calculateAndSaveMask() })
                                            }
                                            HorizontalDivider(color = Color(0xFF383838))
                                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                                Text("2. 连动睡眠模式", fontSize = 14.sp, color = Color.White)
                                                Switch(checked = wearSleepSwitch.value, onCheckedChange = { wearSleepSwitch.value = it; sp.edit().putBoolean("wear_sleep", it).apply(); calculateAndSaveMask() })
                                            }
                                            HorizontalDivider(color = Color(0xFF383838))
                                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                                Text("3. 连动省电模式", fontSize = 14.sp, color = Color.White)
                                                Switch(checked = wearPowerSavingSwitch.value, onCheckedChange = { wearPowerSavingSwitch.value = it; sp.edit().putBoolean("wear_power_saving", it).apply(); calculateAndSaveMask() })
                                            }
                                        }
                                    }
                                }
                            }

                            // 鬧鐘攔截卡片
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
                                        Switch(checked = alarmMasterSwitch.value, onCheckedChange = { alarmMasterSwitch.value = it; sp.edit().putBoolean("alarm_master", it).apply() })
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

                            // 🧪 偵錯功能：拉起遠端相機控制（改用標準 Intent 路由投遞，避免調用不存在的靜態方法）
                            Button(
                                onClick = {
                                    try {
                                        val context = requireContext()
                                        val svcIntent = Intent(context, PhoneSyncCameraService::class.java)
                                        svcIntent.action = "START_CAMERA"
                                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                                            context.startForegroundService(svcIntent)
                                        } else {
                                            context.startService(svcIntent)
                                        }
                                        Log.d("WearSync_Main", "🧪 用户点击测试按钮，发送 START_CAMERA 启动背景相机流")
                                    } catch (e: Exception) {
                                        Log.e("WearSync_Main", "通过控制台启动相机服务失败", e)
                                    }
                                },
                                modifier = Modifier.fillMaxWidth().height(50.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE91E63)),
                                shape = RoundedCornerShape(12.dp)
                            ) { 
                                Text("🧪 调试：拉起远端相机控制", fontSize = 15.sp, fontWeight = FontWeight.Bold) 
                            }

                            // 🎯【紅色安全按鈕】：當相機已授權時，在控制台最底部提供一鍵強制釋放手機相機硬體與背景服務的開關
                            if (isCameraAllowedState.value) {
                                Button(
                                    onClick = {
                                        try {
                                            val context = requireContext()
                                            val svcIntent = Intent(context, PhoneSyncCameraService::class.java)
                                            svcIntent.action = "STOP_CAMERA"
                                            context.startService(svcIntent)
                                            Log.d("WearSync_Main", "🎯 用户点击控制台按钮，主动发送 STOP_CAMERA 关闭背景相机流")
                                        } catch (e: Exception) {
                                            Log.e("WearSync_Main", "通过控制台关闭相机服务失败", e)
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
    override fun onPause() { super.onPause(); unregisterConnectivityListener() }

    private fun checkNotificationPermission() {
        val flat = Settings.Secure.getString(requireContext().contentResolver, "enabled_notification_listeners")
        isNotificationAllowedState.value = flat != null && flat.contains(requireContext().packageName)
    }

    private fun registerConnectivityListener() {
        val context = requireContext()
        Wearable.getCapabilityClient(context).getCapability("dnd_sync", CapabilityClient.FILTER_REACHABLE).addOnSuccessListener { isConnectedState.value = it.nodes.isNotEmpty() }
        capabilityChangedListener = CapabilityClient.OnCapabilityChangedListener { isConnectedState.value = it.nodes.isNotEmpty() }
        capabilityChangedListener?.let { Wearable.getCapabilityClient(context).addListener(it, "dnd_sync") }
    }

    private fun unregisterConnectivityListener() {
        capabilityChangedListener?.let { Wearable.getCapabilityClient(requireContext()).removeListener(it) }
    }
}
