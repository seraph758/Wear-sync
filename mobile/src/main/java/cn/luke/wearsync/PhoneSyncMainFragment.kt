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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.fragment.app.Fragment
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import androidx.compose.material3.Slider
import androidx.compose.material3.OutlinedButton
import cn.luke.wearsync.PhoneLog
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import androidx.compose.ui.text.style.TextAlign
import kotlinx.coroutines.tasks.await
import androidx.compose.foundation.layout.widthIn


class PhoneSyncMainFragment : Fragment(), MessageClient.OnMessageReceivedListener {
    companion object {
        private const val TAG = "PhoneSyncMainFragment"
    }
    private val isNotificationAllowedState = mutableStateOf(false)
    private val isCameraAllowedState = mutableStateOf(false)
    private val isConnectedState = mutableStateOf(false)
    private var capabilityChangedListener: CapabilityClient.OnCapabilityChangedListener? = null
    private val UNIVERSAL_SYNC_PATH = "/wear-universal-sync"

    private val watchWearState = mutableStateOf("未知 (等待手表上报...)")
    
    // 💡 修复提示：原先在这里的 4 个由 remember 委托的变量已被成功移至下方的 setContent 块内。
    
    private val uiLogDebugSwitch = mutableStateOf(false)
    private val uiWearLogDebugSwitch = mutableStateOf(false)
    private val alarmPickerLauncher =
    registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val pkg = it.data?.getStringExtra("selected_alarm_package")
        val name = it.data?.getStringExtra("selected_alarm_name")

        if(pkg != null){
            requireContext()
                .getSharedPreferences("dndsync_prefs", Context.MODE_PRIVATE)
                .edit { 
                    putString("selected_alarm_package", pkg)
                    putString("selected_alarm_name", name ?: pkg)
                }
        }
    }

// 修改函数签名，接收参数
    private fun sendVibrationCommand(action: String, onDuration: Int, offDuration: Int, repeat: Int) {
        val nodeId = WearSyncState.getNodeId(requireContext())
        if (nodeId.isNullOrEmpty()) {
            PhoneLog.w(TAG, "⚠️ 无法发送震动指令：手表未连接")
            return
        }
    
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                // 1. 发送 STOP
                val stopJson = JSONObject().apply {
                    put("sender", "phone")
                    put("type", "vibration")
                    put("action", "stop")
                    put("timestamp", System.currentTimeMillis())
                }
                Wearable.getMessageClient(requireContext()).sendMessage(
                    nodeId, UNIVERSAL_SYNC_PATH, stopJson.toString().toByteArray(StandardCharsets.UTF_8)
                ).await()
    
                // 2. 发送新配置
                if (action == "preview" || action == "save") {
                    val configJson = JSONObject().apply {
                        put("mode", 0)
                        // 使用传入的参数，而不是直接引用 UI 变量
                        put("pattern", intArrayOf(0, onDuration, offDuration, onDuration))
                        put("repeatIndex", if (action == "preview") -1 else repeat)
                    }
                    val json = JSONObject().apply {
                        put("sender", "phone")
                        put("type", "vibration")
                        put("action", action)
                        put("config", configJson.toString())
                        put("timestamp", System.currentTimeMillis())
                    }
                    Wearable.getMessageClient(requireContext()).sendMessage(
                        nodeId, UNIVERSAL_SYNC_PATH, json.toString().toByteArray(StandardCharsets.UTF_8)
                    ).await()
                }
            } catch (e: Exception) {
                PhoneLog.e(TAG, "❌ 震动指令异常: ${e.message}", e)
            }
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

    override fun onMessageReceived(messageEvent: MessageEvent) {
        PhoneLog.d("WearSync_Main", "📩 收到原始消息: path=${messageEvent.path}, data=${String(messageEvent.data, StandardCharsets.UTF_8)}")
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
        uiLogDebugSwitch.value = sp.getBoolean("phone_log_debug_visible", false)
        uiWearLogDebugSwitch.value = sp.getBoolean("wear_log_debug_visible", false)

        return ComposeView(requireContext()).apply {
            setContent {
                MaterialTheme {
                    val isDark = isSystemInDarkTheme()
                    val backgroundColor = if (isDark) Color(0xFF121214) else Color(0xFFF4F4F6)
                    var screenPullDownInterval by remember { 
        mutableIntStateOf(sp.getInt("screen_pull_down_interval", 500)) 
    }
                        
                    val cardBgColor = if (isDark) Color(0xFF1E1E24) else Color(0xFFFFFFFF)
                    val textColor = if (isDark) Color.White else Color(0xFF1C1C1E)
                    val subTextColor = if (isDark) Color.Gray else Color(0xFF757575)
                    val dividerColor = if (isDark) Color(0xFF2C2C2C) else Color(0xFFE5E5EA)

                    // 🛠️ 修复：将这 4 个变量正确放入了 Composable 上下文
                    var isVibrationExpanded by remember { mutableStateOf(false) }
                    var patternOnDuration by remember { mutableIntStateOf(500) }
                    var patternOffDuration by remember { mutableIntStateOf(200) }
                    var repeatIndex by remember { mutableIntStateOf(-1) }

                    var isDndExpanded by remember { mutableStateOf(false) }
                    var isAlarmExpanded by remember { mutableStateOf(false) }
                    var isCameraExpanded by remember { mutableStateOf(false) }
                    var isLogExpanded by remember { mutableStateOf(false) }
                    var isConnectionExpanded by remember { mutableStateOf(false) }
                    var isPermissionExpanded by remember { mutableStateOf(false) }
                    var isFileTransferExpanded by remember { mutableStateOf(false) }

                    var mask by remember { mutableIntStateOf (sp.getInt("KEY_MASK", 15)) }
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
                        sp.edit { putInt("KEY_MASK", newMask) }
                        PhoneLog.d("WearSync_Main", "⚙️ 勿扰同步配置已更新：mask=$newMask")
                    }

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

                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = if (isDark) Color(0xFF251818) else Color(0xFFFFF0F0))
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Text("🧪 离腕检测沙盒中心", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFFE53935))
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

                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Card(
                                        onClick = {
                                            isDndExpanded = !isDndExpanded
                                            if (isDndExpanded) isAlarmExpanded = false
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
                                                Text("勿扰同步", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = textColor)
                                                Text("同步状态控制", fontSize = 11.sp, color = subTextColor)
                                            }
                                            Text(text = "🌙", fontSize = 16.sp)
                                        }
                                    }

                                    Card(
                                        onClick = {
                                            isAlarmExpanded = !isAlarmExpanded
                                            if (isAlarmExpanded) isDndExpanded = false
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

                     AnimatedVisibility(visible = isDndExpanded) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = cardBgColor)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // 主开关
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("启用勿扰主同步", fontWeight = FontWeight.SemiBold, color = textColor, fontSize = 14.sp)
                Switch(
                    checked = masterOn,
                    onCheckedChange = { masterOn = it; updateMask(it, vibrateOn, sleepOn, powerOn) }
                )
            }

            // ✅ 子项区域：受 masterOn 控制
            AnimatedVisibility(visible = masterOn) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    HorizontalDivider(color = dividerColor)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("手表端振动反馈", color = textColor, fontSize = 13.sp)
                        Switch(
                            checked = vibrateOn,
                            onCheckedChange = { vibrateOn = it; updateMask(masterOn, it, sleepOn, powerOn) }
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("睡眠模式同步", color = textColor, fontSize = 13.sp)
                        Switch(
                            checked = sleepOn,
                            onCheckedChange = { sleepOn = it; updateMask(masterOn, vibrateOn, it, powerOn) }
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("省电模式同步", color = textColor, fontSize = 13.sp)
                        Switch(
                            checked = powerOn,
                            onCheckedChange = { powerOn = it; updateMask(masterOn, vibrateOn, sleepOn, it) }
                        )
                    }

                    // ✅ 新增：下拉菜单间隔（在 masterOn 的 Column 内部）
                    HorizontalDivider(color = dividerColor, modifier = Modifier.padding(vertical = 4.dp))

                    Text(
                        text = "亮屏与下拉菜单间隔",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = textColor
                    )
                    Text(
                        text = "手机同步勿扰时，手表亮屏后延迟多久允许下拉菜单响应",
                        fontSize = 11.sp,
                        color = subTextColor,
                        lineHeight = 14.sp
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
Slider(
    value = screenPullDownInterval.toFloat(),
    onValueChange = { newValue ->
        val newInterval = newValue.toInt()
        
        // 1. 更新 UI 状态
        screenPullDownInterval = newInterval
        
        // 2. 持久化到 SharedPreferences
        sp.edit { putInt("screen_pull_down_interval", newInterval) }
        
        // 3. 同步到手表
        // ✅ 修改后：只传 context 和 间隔值，删掉 interruptionFilter 参数
        PhoneDndManager.syncDndToWear(
            context = requireContext(),
            pullDownDelayMs = newInterval 
        )
    },
    valueRange = 0f..2000f,
    steps = 19,
    modifier = Modifier.weight(1f)
)

                        Text(
                            text = "${screenPullDownInterval}ms",
                            fontSize = 13.sp,
                            color = textColor,
                            modifier = Modifier.padding(start = 8.dp).widthIn(min = 56.dp),
                            textAlign = TextAlign.End
                        )
                    }
                } // ← masterOn 的 Column 结束
            } // ← masterOn 的 AnimatedVisibility 结束
        } // ← Card 内部的 Column 结束
    } // ← Card 结束
} // ← isDndExpanded 的 AnimatedVisibility 结束

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
                                                            sp.edit { putBoolean("alarm_proxy_master_switch", isAlarmMasterEnabled) }
                                                            PhoneLog.d("WearSync_Main", "🎯 切换时钟源: $name [$pkg]")
                                                        }
                                                    }
                                                ) { Text("切换时钟源", fontSize = 12.sp) }

                                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                                    Text(text = if (isAlarmMasterEnabled) "已启用" else "已禁用", fontSize = 12.sp, color = textColor)
                                                    Switch(checked = isAlarmMasterEnabled, onCheckedChange = { isEnabled ->
                                                        isAlarmMasterEnabled = isEnabled
                                                        sp.edit {
                                                            putBoolean("alarm_proxy_master_switch", isEnabled)
                                                        }
                                                    })
                                                } 
                                            }

                                            Text("目标包名: $selectedAlarmPkg", fontSize = 11.sp, color = subTextColor)
                                            HorizontalDivider(color = dividerColor)

                                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                OutlinedTextField(
                                                    enabled = isAlarmMasterEnabled, value = dismissKeyText,
                                                    onValueChange = { dismissKeyText = it; sp.edit { putString("alarm_dismiss_key", it) } },
                                                    label = { Text("停止关键字") }, singleLine = true, modifier = Modifier.weight(1f), textStyle = TextStyle(fontSize = 13.sp)
                                                )
                                                OutlinedTextField(
                                                    enabled = isAlarmMasterEnabled, value = snoozeKeyText,
                                                    onValueChange = { snoozeKeyText = it; sp.edit { putString("alarm_snooze_key", it) } },
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

                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
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
                                                Text("相机中心", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = textColor)
                                                Text("同步手表取景屏", fontSize = 11.sp, color = subTextColor)
                                            }
                                            Text(text = "📷", fontSize = 16.sp)
                                        }
                                    }

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
                                                Text("终端调试", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = textColor)
                                                Text("底层日志错误追踪", fontSize = 11.sp, color = subTextColor)
                                            }
                                            Text(text = "📝", fontSize = 16.sp)
                                        }
                                    }
                                }

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

                                AnimatedVisibility(visible = isLogExpanded) {
                                    Card(
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(12.dp),
                                        colors = CardDefaults.cardColors(containerColor = cardBgColor)
                                    ) {
                                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                                Text("开启手机端调试日志", color = textColor, fontSize = 14.sp)
                                                Switch(
                                                    checked = uiLogDebugSwitch.value,
                                                    onCheckedChange = { isEnabled ->
                                                        uiLogDebugSwitch.value = isEnabled
                                                        cn.luke.wearsync.PhoneLog.DEBUG = isEnabled
                                                        val context = requireContext()
                                                        if (isEnabled) {
                                                            context.startService(Intent(context, PhoneLogFloatingService::class.java))
                                                        } else {
                                                            context.stopService(Intent(context, PhoneLogFloatingService::class.java))
                                                        }
                                                    }
                                                )
                                            }
                                            
                                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                                Text("同步监听手表端核心日志", color = textColor, fontSize = 14.sp)
                                                Switch(
                                                    checked = uiWearLogDebugSwitch.value, 
                                                    onCheckedChange = { isChecked -> 
                                                        uiWearLogDebugSwitch.value = isChecked
                                                        sp.edit { putBoolean("wear_log_debug_visible", isChecked)}
                                                        PhoneLog.d("WearSync_Main", "用户切换同步监听手表端核心日志开关，当前状态: $isChecked")
                                                        try {
                                                            val msgJson = org.json.JSONObject().apply {
                                                                put("type", "wear_log_control")      
                                                                put("wear_log_debug", isChecked)     
                                                                put("timestamp", System.currentTimeMillis())
                                                            }
                                                            PhoneLog.d("WearSync_Main", "准备向手表端发送日志控制信令: $msgJson")
                                                            val context = requireContext()
                                                            val nodeId = WearSyncState.getNodeId(context) 
                                                            if (!nodeId.isNullOrEmpty()) {
                                                                kotlinx.coroutines.MainScope().launch(kotlinx.coroutines.Dispatchers.IO) {
                                                                    try {
                                                                        com.google.android.gms.wearable.Wearable.getMessageClient(context)
                                                                            .sendMessage(
                                                                                nodeId,
                                                                                "/wear-universal-sync", 
                                                                                msgJson.toString().toByteArray(java.nio.charset.StandardCharsets.UTF_8)
                                                                            )
                                                                        PhoneLog.d("WearSync_Main", "日志控制信令已成功送出到消息队列")
                                                                    } catch (sendError: Exception) {
                                                                        PhoneLog.e("WearSync_Main", "通过MessageClient发送信令时遭遇物理失败", sendError)
                                                                    }
                                                                }
                                                            } else {
                                                                PhoneLog.w("WearSync_Main", "无法发送信令给手表：当前未捕获到有效的手表 NodeId")
                                                            }
                                                        } catch (e: Exception) {
                                                            PhoneLog.e("WearSync_Main", "组装手表日志开关信令数据失败", e)
                                                        }
                                                    }
                                                )
                                            }
                                            
                                            HorizontalDivider(color = dividerColor)

                                            Button(
                                                modifier = Modifier.fillMaxWidth(),
                                                onClick = {
                                                    val context = requireContext()
                                                    if (!android.provider.Settings.canDrawOverlays(context)) {
                                                        val intent = Intent(
                                                            android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                                            "package:${context.packageName}".toUri()
                                                        )
                                                        startActivity(intent)
                                                    } else {
                                                        val intent = Intent(context, PhoneLogFloatingService::class.java)
                                                        context.startService(intent)
                                                    }
                                                }
                                            ) { Text("开启实时悬浮监视器", fontSize = 13.sp) }
                                        }
                                    }
                                }
                            }

                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
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
                                            Text(text = if (isConnectedState.value) "🟢" else "🔴", fontSize = 14.sp)
                                        }
                                    }

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
                                            Text(text = if (isNotificationAllowedState.value && isCameraAllowedState.value) "🟢" else "⚠️", fontSize = 14.sp)
                                        }
                                    }
                                }

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

                                AnimatedVisibility(visible = isPermissionExpanded) {
                                    Card(
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(12.dp),
                                        colors = CardDefaults.cardColors(containerColor = cardBgColor)
                                    ) {
                                        Row(modifier = Modifier.padding(12.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
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
                            
                            // ========== 震动反馈设置 ==========
                            Card(
                                onClick = { isVibrationExpanded = !isVibrationExpanded },
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
                                            Text("震动反馈设置", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = textColor)
                                            Text("自定义手表端提醒震动波形与频率", fontSize = 12.sp, color = subTextColor)
                                        }
                                        Text(text = if (isVibrationExpanded) "▲" else "▼", fontSize = 14.sp, color = subTextColor)
                                    }

                                    AnimatedVisibility(visible = isVibrationExpanded) {
                                        Column(modifier = Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                            HorizontalDivider(color = dividerColor)

                                            Text("震动时长: ${patternOnDuration}ms", fontSize = 13.sp, color = textColor)
                                            Slider(value = patternOnDuration.toFloat(), onValueChange = { patternOnDuration = it.toInt() }, valueRange = 100f..2000f, steps = 19, modifier = Modifier.fillMaxWidth())

                                            Text("间隔时长: ${patternOffDuration}ms", fontSize = 13.sp, color = textColor)
                                            Slider(value = patternOffDuration.toFloat(), onValueChange = { patternOffDuration = it.toInt() }, valueRange = 100f..1000f, steps = 8, modifier = Modifier.fillMaxWidth())

                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text("循环震动", fontSize = 13.sp, color = textColor)
                                                Spacer(Modifier.weight(1f))
                                                Switch(checked = repeatIndex == 0, onCheckedChange = { repeatIndex = if (it) 0 else -1 })
                                            }

                                            HorizontalDivider(color = dividerColor)

                                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                OutlinedButton(onClick = { sendVibrationCommand("preview", patternOnDuration, patternOffDuration, repeatIndex) }, modifier = Modifier.weight(1f)) { Text("📳 预览") }
                                                
                                                Button(
                                                    modifier = Modifier.weight(1f),
                                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50)),
                                                    onClick = {
                                                        val prefs = requireContext().getSharedPreferences("wear_vibration_prefs", Context.MODE_PRIVATE)
                                                        prefs.edit {
                                                            putInt("on_duration", patternOnDuration)
                                                            putInt("off_duration", patternOffDuration)
                                                            putInt("repeat_index", repeatIndex)
                                                        }
                                                        PhoneLog.d("WearSync_Main", "💾 震动参数已保存到手机端: on=${patternOnDuration}ms, off=${patternOffDuration}ms, repeat=${repeatIndex}")
                                                        sendVibrationCommand("save", patternOnDuration, patternOffDuration, repeatIndex)
                                                    }
                                                ) { Text("💾 保存") }
                                            }
                                        }
                                    }
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
        isCameraAllowedState.value = requireContext().checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        registerConnectivityListener()
        Wearable.getMessageClient(requireContext()).addListener(this)
    }

    override fun onPause() {
        super.onPause()
        unregisterConnectivityListener()
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
