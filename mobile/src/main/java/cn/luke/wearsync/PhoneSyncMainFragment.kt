package cn.luke.wearsync

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import android.content.ComponentName
import androidx.compose.foundation.isSystemInDarkTheme
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
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import androidx.compose.ui.graphics.Color
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import androidx.compose.ui.text.TextStyle
import androidx.compose.foundation.interaction.MutableInteractionSource

class PhoneSyncMainFragment : Fragment(), MessageClient.OnMessageReceivedListener {

    private val isNotificationAllowedState = mutableStateOf(false)
    private val isCameraAllowedState = mutableStateOf(false)
    private val isConnectedState = mutableStateOf(false)
    private var capabilityChangedListener: CapabilityClient.OnCapabilityChangedListener? = null

    // 🧪 响应式状态：用于在手机主界面实时挂载展示手表的佩戴状态
    private val watchWearState = mutableStateOf("未知 (等待手表上报...)")

    private val uiLogDebugSwitch = mutableStateOf(PhoneLog.DEBUG)
    private val uiWearLogDebugSwitch = mutableStateOf(true)
    private val alarmPickerLauncher =
    registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val pkg = it.data?.getStringExtra("selected_alarm_package")
        val name = it.data?.getStringExtra("selected_alarm_name")

        if(pkg != null){
            requireContext()
                .getSharedPreferences("dndsync_prefs", Context.MODE_PRIVATE)
                .edit()
                .putString("selected_alarm_package", pkg)
                .putString("selected_alarm_name", name ?: pkg)
                .apply()
        }
    }

    private val requestCameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        isCameraAllowedState.value = isGranted
        if (isGranted) {
            PhoneLog.d("WearSync_Main", "📸 相机硬件权限申请成功")
        } else {
            PhoneLog.w("WearSync_Main", "⚠️ 相机硬件权限被拒绝")
        }
    }

    // 🚀 底层联调：实时拦截并处理来自三星手表端的离腕报文
    override fun onMessageReceived(messageEvent: MessageEvent) {
        if (messageEvent.path == "/wearsync-body-status") {
            val rawStatus = String(messageEvent.data, StandardCharsets.UTF_8)
            activity?.runOnUiThread {
                if (rawStatus == "off_body") {
                    watchWearState.value = "🚫 已摘下 (Off-Body)"
                    PhoneLog.d("WearSync_Main", "📥 收到解绑信号：手表已被用户摘下。")
                } else if (rawStatus == "on_body") {
                    watchWearState.value = "🟢 已佩戴 (On-Body)"
                    PhoneLog.d("WearSync_Main", "📥 收到绑定信号：手表已重新佩戴。")
                }
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val sp = requireContext().getSharedPreferences("dndsync_prefs", Context.MODE_PRIVATE)
        uiLogDebugSwitch.value = sp.getBoolean("phone_log_debug_visible", PhoneLog.DEBUG)
        uiWearLogDebugSwitch.value = sp.getBoolean("wear_log_debug_visible", true)

        return ComposeView(requireContext()).apply {
            setContent {
                MaterialTheme {
                    val isDark = isSystemInDarkTheme()
                    val backgroundColor = if (isDark) Color(0xFF121214) else Color(0xFFF4F4F6)
                    val cardBgColor = if (isDark) Color(0xFF1E1E24) else Color(0xFFFFFFFF)
                    val textColor = if (isDark) Color.White else Color(0xFF1C1C1E)
                    val subTextColor = if (isDark) Color.Gray else Color(0xFF757575)
                    val dividerColor = if (isDark) Color(0xFF2C2C2C) else Color(0xFFE5E5EA)

                    // 🛠️ 展开状态变量控制
                    var isDndExpanded by remember { mutableStateOf(false) }
                    var isAlarmExpanded by remember { mutableStateOf(false) }
                    var isCameraExpanded by remember { mutableStateOf(false) }
                    var isLogExpanded by remember { mutableStateOf(false) }
                    var isConnectionExpanded by remember { mutableStateOf(false) }
                    var isPermissionExpanded by remember { mutableStateOf(false) }
                    var isFileTransferExpanded by remember { mutableStateOf(false) }

                    // 勿扰联动变量
                    var mask by remember { mutableStateOf(sp.getInt("dnd_sync_mask", 15)) }
                    var masterOn by remember(mask) { mutableStateOf((mask and 1) != 0) }
                    var vibrateOn by remember(mask) { mutableStateOf((mask and 2) != 0) }
                    var sleepOn by remember(mask) { mutableStateOf((mask and 4) != 0) }
                    var powerOn by remember(mask) { mutableStateOf((mask and 8) != 0) }
                    val updateMask = { newMaster: Boolean, newVibrate: Boolean, newSleep: Boolean, newPower: Boolean ->
                        var newMask = 0
                        if (newMaster) newMask = newMask or 1
                        if (newVibrate) newMask = newMask or 2
                        if (newSleep) newMask = newMask or 4
                        if (newPower) newMask = newMask or 8
                        mask = newMask
                        sp.edit().putInt("dnd_sync_mask", newMask).apply()
                        PhoneLog.d("WearSync_Main", "⚙️ DND联动配置已保存 mask=$newMask")
                    }

                    // 闹钟全域代点变量
                    var selectedAlarmName by remember { mutableStateOf(sp.getString("selected_alarm_name", "Google 时钟") ?: "Google 时钟") }
                    var selectedAlarmPkg by remember { mutableStateOf(sp.getString("selected_alarm_package", "com.google.android.deskclock") ?: "com.google.android.deskclock") }
                    var dismissKeyText by remember { mutableStateOf(sp.getString("alarm_dismiss_key", "停止") ?: "停止") }
                    var snoozeKeyText by remember { mutableStateOf(sp.getString("alarm_snooze_key", "延后") ?: "延后") }
                    var isAlarmMasterEnabled by remember { mutableStateOf(sp.getBoolean("alarm_proxy_master_switch", true)) }

                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = backgroundColor
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(16.dp)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Text(
                                text = "WearSync 枢纽",
                                fontSize = 28.sp,
                                fontWeight = FontWeight.Bold,
                                color = textColor,
                                modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
                            )

                            // ==========================================================
                            // 🧪 临时增加测试卡片：三星手表离腕状态响应式检测区
                            // ==========================================================
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = if (isDark) Color(0xFF251818) else Color(0xFFFFF0F0))
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Text("🧪 离腕检测沙盒中心 (业务调通后可整块删除)", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFFE53935))
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("当前手表佩戴状态:", fontSize = 13.sp, color = textColor)
                                        Text(
                                            text = watchWearState.value, 
                                            fontSize = 14.sp, 
                                            fontWeight = FontWeight.Bold, 
                                            color = if (watchWearState.value.contains("🟢")) Color(0xFF4CAF50) else Color(0xFFE53935)
                                        )
                                    }
                                }
                            }

                            // ==========================================
                            // 第一排：勿扰联动 & 闹钟代点 (分立按钮，共用下方展开槽)
                            // ==========================================
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    // 勿扰联动按钮卡片
                                    Card(
                                        onClick = {
                                            isDndExpanded = !isDndExpanded
                                            if (isDndExpanded) isAlarmExpanded = false // 互斥展开
                                        },
                                        modifier = Modifier.weight(1f).height(72.dp),
                                        shape = RoundedCornerShape(16.dp),
                                        colors = CardDefaults.cardColors(containerColor = cardBgColor)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column {
                                                Text("勿扰联动", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = textColor)
                                                Text("同步状态控制", fontSize = 11.sp, color = subTextColor)
                                            }
                                            Text(
                                                text = if (masterOn) "🟢 🌙" else "⚪ 🌙",
                                                fontSize = 15.sp
                                            )
                                        }
                                    }

                                    // 闹钟代点按钮卡片
                                    Card(
                                        onClick = {
                                            isAlarmExpanded = !isAlarmExpanded
                                            if (isAlarmExpanded) isDndExpanded = false // 互斥展开
                                        },
                                        modifier = Modifier.weight(1f).height(72.dp),
                                        shape = RoundedCornerShape(16.dp),
                                        colors = CardDefaults.cardColors(containerColor = cardBgColor)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column {
                                                Text("闹钟全域代点", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = if (isAlarmMasterEnabled) textColor else textColor.copy(alpha = 0.5f))
                                                Text(selectedAlarmName, fontSize = 11.sp, color = if (isAlarmMasterEnabled) subTextColor else subTextColor.copy(alpha = 0.5f))
                                            }
                                            Text(text = "⏰", fontSize = 16.sp)
                                        }
                                    }
                                }

                                // 勿扰联动的展开空间
                                AnimatedVisibility(visible = isDndExpanded) {
                                    Card(
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(12.dp),
                                        colors = CardDefaults.cardColors(containerColor = cardBgColor)
                                    ) {
                                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                                Text("启用勿扰主联动", fontWeight = FontWeight.SemiBold, color = textColor, fontSize = 14.sp)
                                                Switch(checked = masterOn, onCheckedChange = { masterOn = it; updateMask(it, vibrateOn, sleepOn, powerOn) })
                                            }
                                            AnimatedVisibility(visible = masterOn) {
                                                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                                    HorizontalDivider(color = dividerColor)
                                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                                        Text("手表端震动反馈", color = textColor, fontSize = 13.sp)
                                                        Switch(checked = vibrateOn, onCheckedChange = { vibrateOn = it; updateMask(masterOn, it, sleepOn, powerOn) })
                                                    }
                                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                                        Text("睡眠模式同步", color = textColor, fontSize = 13.sp)
                                                        Switch(checked = sleepOn, onCheckedChange = { sleepOn = it; updateMask(masterOn, vibrateOn, it, powerOn) })
                                                    }
                                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                                        Text("省电模式同步", color = textColor, fontSize = 13.sp)
                                                        Switch(checked = powerOn, onCheckedChange = { powerOn = it; updateMask(masterOn, vibrateOn, sleepOn, it) })
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }

                                // 闹钟代点的展开空间
                                AnimatedVisibility(visible = isAlarmExpanded) {
                                    Card(
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(12.dp),
                                        colors = CardDefaults.cardColors(containerColor = cardBgColor)
                                    ) {
                                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                                Button(
                                                    enabled = isAlarmMasterEnabled,
                                                    modifier = Modifier.weight(1f),
                                                    onClick = {
                                                        PhoneSyncAppPicker.show(requireContext()) { pkg, name ->
                                                            selectedAlarmName = name
                                                            selectedAlarmPkg = pkg
                                                            sp.edit().putString("selected_alarm_package", pkg).putString("selected_alarm_name", name).apply()
                                                            PhoneLog.d("WearSync_Main", "🎯 切换时钟源: $name [$pkg]")
                                                        }
                                                    }
                                                ) { Text("切换时钟源", fontSize = 12.sp) }

                                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = DashboardTokens.padding(4.dp)) {
                                                    Text(text = if (isAlarmMasterEnabled) "已启用" else "已禁用", fontSize = 12.sp, color = textColor)
                                                    Switch(checked = isAlarmMasterEnabled, onCheckedChange = { isEnabled ->
                                                        isAlarmMasterEnabled = isEnabled
                                                        sp.edit().putBoolean("alarm_proxy_master_switch", isEnabled).apply()
                                                    })
                                                }
                                            }
                                            Text("目标包名: $selectedAlarmPkg", fontSize = 11.sp, color = subTextColor)
                                            HorizontalDivider(color = dividerColor)
                                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                OutlinedTextField(
                                                    enabled = isAlarmMasterEnabled, value = dismissKeyText,
                                                    onValueChange = { dismissKeyText = it; sp.edit().putString("alarm_dismiss_key", it).apply() },
                                                    label = { Text("停止关键字") }, singleLine = true, modifier = Modifier.weight(1f), textStyle = TextStyle(fontSize = 13.sp)
                                                )
                                                OutlinedTextField(
                                                    enabled = isAlarmMasterEnabled, value = snoozeKeyText,
                                                    onValueChange = { snoozeKeyText = it; sp.edit().putString("alarm_snooze_key", it).apply() },
                                                    label = { Text("延后关键字") }, singleLine = true, modifier = Modifier.weight(1f), textStyle = TextStyle(fontSize = 13.sp)
                                                )
                                            }
                                            HorizontalDivider(color = dividerColor)
                                            Text("🧪 业务流联调测试面板", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = textColor)
                                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                Button(
                                                    enabled = isAlarmMasterEnabled, modifier = Modifier.weight(1f),
                                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828)),
                                                    onClick = { try { PhoneAlarmManager.handleWatchCommand(requireContext(), "DISMISS") } catch (_: Exception) {} }
                                                ) { Text("模拟停止测试", color = Color.White, fontSize = 11.sp) }
                                                Button(
                                                    enabled = isAlarmMasterEnabled, modifier = Modifier.weight(1f),
                                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE65100)),
                                                    onClick = { try { PhoneAlarmManager.handleWatchCommand(requireContext(), "SNOOZE") } catch (_: Exception) {} }
                                                ) { Text("模拟延后测试", color = Color.White, fontSize = 11.sp) }
                                            }
                                        }
                                    }
                                }
                            }

                            // ==========================================
                            // 第二排：相机控场 & 终端调试 (分立按钮，共用下方展开槽)
                            // ==========================================
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    // 相机控场卡片
                                    Card(
                                        onClick = {
                                            isCameraExpanded = !isCameraExpanded
                                            if (isCameraExpanded) isLogExpanded = false
                                        },
                                        modifier = Modifier.weight(1f).height(72.dp),
                                        shape = RoundedCornerShape(16.dp),
                                        colors = CardDefaults.cardColors(containerColor = cardBgColor)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column {
                                                Text("相机控场中心", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = textColor)
                                                Text("同步手表取景屏", fontSize = 11.sp, color = subTextColor)
                                            }
                                            Text(text = "📷", fontSize = 16.sp)
                                        }
                                    }

                                    // 终端调试卡片
                                    Card(
                                        onClick = {
                                            isLogExpanded = !isLogExpanded
                                            if (isLogExpanded) isCameraExpanded = false
                                        },
                                        modifier = Modifier.weight(1f).height(72.dp),
                                        shape = RoundedCornerShape(16.dp),
                                        colors = CardDefaults.cardColors(containerColor = cardBgColor)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column {
                                                Text("终端调试与日志", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = textColor)
                                                Text("底层报文错误追踪", fontSize = 11.sp, color = subTextColor)
                                            }
                                            Text(text = "📝", fontSize = 16.sp)
                                        }
                                    }
                                }

                                // 相机控场的展开空间
                                AnimatedVisibility(visible = isCameraExpanded) {
                                    Card(
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(12.dp),
                                        colors = CardDefaults.cardColors(containerColor = cardBgColor)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth().padding(14.dp),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Button(
                                                modifier = Modifier.weight(1f),
                                                colors = ButtonDefaults.buttonColors(containerColor = if (isCameraAllowedState.value) Color(0xFF2E7D32) else Color(0xFF333333)),
                                                onClick = {
                                                    if (!isCameraAllowedState.value) {
                                                        requestCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                                                        return@Button
                                                    }
                                                    val nodeId = WearSyncState.getNodeId(requireContext())
                                                    requireContext().startService(Intent(requireContext(), PhoneSyncCameraService::class.java).setAction(PhoneSyncCameraService.ACTION_START_CAMERA))
                                                    if (!nodeId.isNullOrEmpty()) {
                                                        Thread {
                                                            try {
                                                                val json = JSONObject().apply { put("sender", "phone"); put("type", "camera_control"); put("action", "START_CAMERA"); put("timestamp", System.currentTimeMillis()) }
                                                                Wearable.getMessageClient(requireContext()).sendMessage(nodeId, "/wear-universal-sync", json.toString().toByteArray(StandardCharsets.UTF_8))
                                                            } catch (_: Exception) {}
                                                        }.start()
                                                    }
                                                }
                                            ) { Text("呼叫手表相机", color = Color.White, fontSize = 12.sp) }

                                            Button(
                                                modifier = Modifier.weight(1f),
                                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7F1D1D)),
                                                onClick = {
                                                    val nodeId = WearSyncState.getNodeId(requireContext())
                                                    requireContext().startService(Intent(requireContext(), PhoneSyncCameraService::class.java).setAction(PhoneSyncCameraService.ACTION_STOP_CAMERA))
                                                    if (!nodeId.isNullOrEmpty()) {
                                                        Thread {
                                                            try {
                                                                val json = JSONObject().apply { put("sender", "phone"); put("type", "camera_control"); put("action", "FORCE_QUIT_CAMERA"); put("timestamp", System.currentTimeMillis()) }
                                                                Wearable.getMessageClient(requireContext()).sendMessage(nodeId, "/wear-universal-sync", json.toString().toByteArray(StandardCharsets.UTF_8))
                                                            } catch (_: Exception) {}
                                                        }.start()
                                                    }
                                                }
                                            ) { Text("强制关闭相机", color = Color.White, fontSize = 12.sp) }
                                        }
                                    }
                                }

                                // 终端调试的展开空间
                                AnimatedVisibility(visible = isLogExpanded) {
                                    Card(
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(12.dp),
                                        colors = CardDefaults.cardColors(containerColor = cardBgColor)
                                    ) {
                                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                                Text("开启手机端调试日志", color = textColor, fontSize = 14.sp)
                                                Switch(checked = uiLogDebugSwitch.value, onCheckedChange = { isChecked -> uiLogDebugSwitch.value = isChecked; sp.edit().putBoolean("phone_log_debug_visible", isChecked).apply(); PhoneLog.DEBUG = isChecked })
                                            }
                                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                                Text("同步监听手表端核心日志", color = textColor, fontSize = 14.sp)
                                                Switch(checked = uiWearLogDebugSwitch.value, onCheckedChange = { isChecked -> uiWearLogDebugSwitch.value = isChecked; sp.edit().putBoolean("wear_log_debug_visible", isChecked).apply() })
                                            }
                                            HorizontalDivider(color = dividerColor)
                                            
                                            // 🚀 替换升级：加入带安全过滤与温柔引导的“全局悬浮窗挂载”按钮
                                            Button(
                                                modifier = Modifier.fillMaxWidth(),
                                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                                                onClick = {
                                                    if (Settings.canDrawOverlays(requireContext())) {
                                                        try {
                                                            val intent = Intent(requireContext(), PhoneLogFloatingService::class.java)
                                                            requireContext().startService(intent)
                                                            PhoneLog.d("WearSync_Main", "📂 成功挂载全局悬浮日志小窗")
                                                        } catch (e: Exception) {
                                                            PhoneLog.e("WearSync_Main", "启动悬浮窗服务失败: ${e.message}")
                                                        }
                                                    } else {
                                                        Toast.makeText(requireContext(), "请先授予 WearSync 后台悬浮窗权限", Toast.LENGTH_LONG).show()
                                                        try {
                                                            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:${requireContext().packageName}"))
                                                            startActivity(intent)
                                                        } catch (e: Exception) {
                                                            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                                                            startActivity(intent)
                                                        }
                                                    }
                                                }
                                            ) {
                                                Text("挂载全局悬浮日志屏", fontSize = 13.sp)
                                            }
                                        }
                                    }
                                }
                            }

                            // ==========================================
                            // 第三排：连接状态 & 核心接管权限 (已移到下方，分立按钮，共用展开槽)
                            // ==========================================
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    // 连接状态卡片
                                    Card(
                                        onClick = {
                                            isConnectionExpanded = !isConnectionExpanded
                                            if (isConnectionExpanded) isPermissionExpanded = false
                                        },
                                        modifier = Modifier.weight(1f).height(72.dp),
                                        shape = RoundedCornerShape(16.dp),
                                        colors = CardDefaults.cardColors(containerColor = cardBgColor)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column {
                                                Text("连接状态", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = textColor)
                                                Text("骨干网络检测", fontSize = 11.sp, color = subTextColor)
                                            }
                                            Text(
                                                text = if (isConnectedState.value) "🟢" else "🔴",
                                                fontSize = 14.sp
                                            )
                                        }
                                    }

                                    // 核心接管权限卡片
                                    Card(
                                        onClick = {
                                            isPermissionExpanded = !isPermissionExpanded
                                            if (isPermissionExpanded) isConnectionExpanded = false
                                        },
                                        modifier = Modifier.weight(1f).height(72.dp),
                                        shape = RoundedCornerShape(16.dp),
                                        colors = CardDefaults.cardColors(containerColor = cardBgColor)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column {
                                                Text("核心权限", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = textColor)
                                                Text("底层硬件授信", fontSize = 11.sp, color = subTextColor)
                                            }
                                            Text(
                                                text = if (isNotificationAllowedState.value && isCameraAllowedState.value) "🟢" else "⚠️",
                                                fontSize = 14.sp
                                            )
                                        }
                                    }
                                }

                                // 连接状态展开空间
                                AnimatedVisibility(visible = isConnectionExpanded) {
                                    Card(
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(12.dp),
                                        colors = CardDefaults.cardColors(containerColor = cardBgColor)
                                    ) {
                                        Column(modifier = Modifier.padding(14.dp)) {
                                            Text(
                                                text = if (isConnectedState.value) "骨干网络畅通。正在通过 GMS Wearable CapabilityClient 实时监听手表节点的动态响应。" 
                                                      else "未检测到处于活动状态的手表节点。请检查手表是否开机、蓝牙是否连接、或 Wear OS 专属配对 App 是否在后台运行。",
                                                fontSize = 13.sp, color = textColor, lineHeight = 18.sp
                                            )
                                        }
                                    }
                                }

                                // 核心权限展开空间
                                AnimatedVisibility(visible = isPermissionExpanded) {
                                    Card(
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(12.dp),
                                        colors = CardDefaults.cardColors(containerColor = cardBgColor)
                                    ) {
                                        Row(modifier = Modifier.padding(12.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                            // 通知卡片
                                            Card(modifier = Modifier.weight(1f).height(110.dp), shape = RoundedCornerShape(8.dp), colors = CardDefaults.cardColors(containerColor = backgroundColor)) {
                                                Column(modifier = Modifier.padding(10.dp).fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                                                    Column {
                                                        Text("通知接管", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = textColor)
                                                        Text(text = if (isNotificationAllowedState.value) "核心状态已就绪" else "拦截状态必备", fontSize = 11.sp, color = if (isNotificationAllowedState.value) Color(0xFF4CAF50) else Color(0xFFF44336))
                                                    }
                                                    if (!isNotificationAllowedState.value) {
                                                        Button(onClick = { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }, contentPadding = PaddingValues(horizontal = 6.dp), modifier = Modifier.align(Alignment.End).height(26.dp)) { Text("去授权", fontSize = 10.sp) }
                                                    } else {
                                                        Text("🟢 已放行", fontSize = 11.sp, color = Color(0xFF4CAF50), modifier = Modifier.align(Alignment.End))
                                                    }
                                                }
                                            }
                                            // 相机卡片
                                            Card(modifier = Modifier.weight(1f).height(110.dp), shape = RoundedCornerShape(8.dp), colors = CardDefaults.cardColors(containerColor = backgroundColor)) {
                                                Column(modifier = Modifier.padding(10.dp).fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                                                    Column {
                                                        Text("相机硬件", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = textColor)
                                                        Text(text = if (isCameraAllowedState.value) "取景功能正常" else "取景控制必备", fontSize = 11.sp, color = if (isCameraAllowedState.value) Color(0xFF4CAF50) else Color(0xFFF44336))
                                                    }
                                                    if (!isCameraAllowedState.value) {
                                                        Button(onClick = { requestCameraPermissionLauncher.launch(Manifest.permission.CAMERA) }, contentPadding = PaddingValues(horizontal = 6.dp), modifier = Modifier.align(Alignment.End).height(26.dp)) { Text("授相机", fontSize = 10.sp) }
                                                    } else {
                                                        Text("🟢 已放行", fontSize = 11.sp, color = Color(0xFF4CAF50), modifier = Modifier.align(Alignment.End))
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            // ==========================================================
                            // 第四排：跨端文件传输枢纽 (独立一行)
                            // ==========================================================
                            Card(
                                onClick = { isFileTransferExpanded = !isFileTransferExpanded },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = cardBgColor)
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column {
                                            Text("跨端文件传输枢纽 (暂未启用)", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = textColor.copy(alpha = 0.6f))
                                            Text("双向传输照片、音频及自定义配置文件", fontSize = 12.sp, color = subTextColor.copy(alpha = 0.6f))
                                        }
                                        Text(text = "⏳ 预留", fontSize = 14.sp, color = subTextColor.copy(alpha = 0.6f))
                                    }

                                    AnimatedVisibility(visible = isFileTransferExpanded) {
                                        Column(modifier = Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                            HorizontalDivider(color = dividerColor)
                                            Text(
                                                text = "📌 模块提示：前五个核心联动模块（连接、权限、勿扰、闹钟、相机）调通后，取消下方代码注释即可对接底层 ChannelClient 实现双端大文件高速传输。",
                                                fontSize = 12.sp, color = Color(0xFFFF9800), lineHeight = 16.sp
                                            )
                                        }
                                    }
                                }
                            }

                        } // 结束 Column
                    } // 结束 Surface
                } // 结束 MaterialTheme
            } // 结束 setContent
        } // 结束 ComposeView
    }

    override fun onResume() {
        super.onResume()
        checkNotificationPermission()
        isCameraAllowedState.value = requireContext().checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        registerConnectivityListener()
        
        // 🚀 核心增加：当界面在前台活跃时，注册接收手表的 Message 报文监听
        Wearable.getMessageClient(requireContext()).addListener(this)
    }

    override fun onPause() {
        super.onPause()
        unregisterConnectivityListener()
        
        // 🚀 核心增加：当界面退到后台时，注销监听释放资源
        Wearable.getMessageClient(requireContext()).removeListener(this)
    }

    private fun checkNotificationPermission() {
        val flat = Settings.Secure.getString(requireContext().contentResolver, "enabled_notification_listeners")
        isNotificationAllowedState.value = flat != null && flat.contains(requireContext().packageName)
    }

    private fun registerConnectivityListener() {
        val context = requireContext()
        Wearable.getCapabilityClient(context).getCapability("dnd_sync", CapabilityClient.FILTER_REACHABLE).addOnSuccessListener {
            isConnectedState.value = it.nodes.isNotEmpty()
        }
        capabilityChangedListener = CapabilityClient.OnCapabilityChangedListener {
            isConnectedState.value = it.nodes.isNotEmpty()
        }
        capabilityChangedListener?.let { Wearable.getCapabilityClient(context).addListener(it, "dnd_sync") }
    }

    private fun unregisterConnectivityListener() {
        capabilityChangedListener?.let {
            Wearable.getCapabilityClient(requireContext()).removeListener(it)
        }
    }
}
