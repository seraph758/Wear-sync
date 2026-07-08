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
import androidx.compose.ui.graphics.Color
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import androidx.compose.ui.text.TextStyle

class PhoneSyncMainFragment : Fragment() {

    private val isNotificationAllowedState = mutableStateOf(false)
    private val isCameraAllowedState = mutableStateOf(false)
    private val isConnectedState = mutableStateOf(false)
    private var capabilityChangedListener: CapabilityClient.OnCapabilityChangedListener? = null

    private val uiLogDebugSwitch = mutableStateOf(PhoneLog.DEBUG)
    private val uiWearLogDebugSwitch = mutableStateOf(true)
    private val alarmPickerLauncher =
    registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val pkg = it.data?.getStringExtra("selected_alarm_package")
        val name = it.data?.getStringExtra("selected_alarm_name")

        if(pkg != null){
            requireContext()
                .getSharedPreferences("dndsync_prefs",Context.MODE_PRIVATE)
                .edit()
                .putString("selected_alarm_package",pkg)
                .putString("selected_alarm_name",name ?: pkg)
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

                    // 🛠️ 为每个核心板块单独定义“是否展开”的状态，默认全部为 false (收起)
                    var isConnectionExpanded by remember { mutableStateOf(false) }
                    var isPermissionExpanded by remember { mutableStateOf(false) }
                    var isDndExpanded by remember { mutableStateOf(false) }
                    var isAlarmExpanded by remember { mutableStateOf(false) }
                    var isCameraExpanded by remember { mutableStateOf(false) }
                    var isLogExpanded by remember { mutableStateOf(false) }
                    var isFileTransferExpanded by remember { mutableStateOf(false) } // 预留文件传输状态

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

                            // ==========================================
                            // 1. 连接状态卡片 (支持点击整块整卡折叠/展开)
                            // ==========================================
                            Card(
                                onClick = { isConnectionExpanded = !isConnectionExpanded }, // 点击切换状态
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = cardBgColor)
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    // 无论展开与否，始终暴露的标题栏
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column {
                                            Text("连接状态", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = textColor)
                                            Text(
                                                text = if (isConnectionExpanded) "点击收起面板" else "检测手机与手表的骨干网络连接", 
                                                fontSize = 12.sp, 
                                                color = subTextColor
                                            )
                                        }
                                        Text(
                                            text = if (isConnectionExpanded) "🔼" else if (isConnectedState.value) "🟢 已连接" else "🔴 未连接",
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isConnectedState.value) Color(0xFF4CAF50) else Color(0xFFF44336)
                                        )
                                    }

                                    // 点击展开后才显示的详细业务流内容
                                    AnimatedVisibility(visible = isConnectionExpanded) {
                                        Column(modifier = Modifier.padding(top = 12.dp)) {
                                            HorizontalDivider(color = dividerColor, modifier = Modifier.padding(bottom = 8.dp))
                                            Text(
                                                text = if (isConnectedState.value) "骨干网络畅通。正在通过 GMS Wearable CapabilityClient 实时监听手表节点的动态响应。" 
                                                       else "未检测到处于活动状态的手表节点。请检查手表是否开机、蓝牙是否连接、或 Wear OS 专属配对 App 是否在后台运行。",
                                                fontSize = 13.sp,
                                                color = textColor,
                                                lineHeight = 18.sp
                                            )
                                        }
                                    }
                                }
                            }

                            // ==========================================
                            // 2. 双权限状态卡片 (合二为一，统一控场折叠)
                            // ==========================================
                            Card(
                                onClick = { isPermissionExpanded = !isPermissionExpanded },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = cardBgColor)
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    // 始终暴露的权限概要栏
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column {
                                            Text("核心接管权限", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = textColor)
                                            Text("通知联动与相机取景的底层硬件授信", fontSize = 12.sp, color = subTextColor)
                                        }
                                        Text(
                                            text = if (isPermissionExpanded) "🔼" else if (isNotificationAllowedState.value && isCameraAllowedState.value) "🟢 全就绪" else "⚠️ 待授权",
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isNotificationAllowedState.value && isCameraAllowedState.value) Color(0xFF4CAF50) else Color(0xFFFF9800)
                                        )
                                    }

                                    // 点击展开后显示你原先并排的两个子权限卡片
                                    AnimatedVisibility(visible = isPermissionExpanded) {
                                        Column(modifier = Modifier.padding(top = 12.dp)) {
                                            HorizontalDivider(color = dividerColor, modifier = Modifier.padding(bottom = 12.dp))
                                            
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                                            ) {
                                                // 通知卡片
                                                Card(
                                                    modifier = Modifier.weight(1f).height(125.dp),
                                                    shape = RoundedCornerShape(12.dp),
                                                    colors = CardDefaults.cardColors(containerColor = backgroundColor) // 嵌套卡片用稍微深/浅一点的背景区分
                                                ) {
                                                    Column(modifier = Modifier.padding(12.dp).fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                                                        Column {
                                                            Text("通知接管", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = textColor)
                                                            Spacer(modifier = Modifier.height(2.dp))
                                                            Text(
                                                                text = if (isNotificationAllowedState.value) "核心状态已就绪" else "拦截状态必备",
                                                                fontSize = 11.sp, lineHeight = 14.sp,
                                                                color = if (isNotificationAllowedState.value) Color(0xFF4CAF50) else Color(0xFFF44336)
                                                            )
                                                        }
                                                        if (!isNotificationAllowedState.value) {
                                                            Button(
                                                                onClick = { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
                                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                                                modifier = Modifier.align(Alignment.End).height(28.dp)
                                                            ) { Text("去授权", fontSize = 11.sp) }
                                                        } else {
                                                            Text("🟢 已放行", fontSize = 11.sp, color = Color(0xFF4CAF50), modifier = Modifier.align(Alignment.End))
                                                        }
                                                    }
                                                }

                                                // 相机卡片
                                                Card(
                                                    modifier = Modifier.weight(1f).height(125.dp),
                                                    shape = RoundedCornerShape(12.dp),
                                                    colors = CardDefaults.cardColors(containerColor = backgroundColor)
                                                ) {
                                                    Column(modifier = Modifier.padding(12.dp).fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                                                        Column {
                                                            Text("相机硬件", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = textColor)
                                                            Spacer(modifier = Modifier.height(2.dp))
                                                            Text(
                                                                text = if (isCameraAllowedState.value) "取景功能正常" else "取景控制必备",
                                                                fontSize = 11.sp, lineHeight = 14.sp,
                                                                color = if (isCameraAllowedState.value) Color(0xFF4CAF50) else Color(0xFFF44336)
                                                            )
                                                        }
                                                        if (!isCameraAllowedState.value) {
                                                            Button(
                                                                onClick = { requestCameraPermissionLauncher.launch(
                                                                    Manifest.permission.CAMERA) },
                                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                                                modifier = Modifier.align(Alignment.End).height(28.dp)
                                                            ) { Text("授相机", fontSize = 11.sp) }
                                                        } else {
                                                            Text("🟢 已放行", fontSize = 11.sp, color = Color(0xFF4CAF50), modifier = Modifier.align(Alignment.End))
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            // 3. 勿扰联动控制台
                            // ==========================================
                            // 3. 勿扰联动总干线 (支持折叠)
                            // ==========================================
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

                            Card(
                                onClick = { isDndExpanded = !isDndExpanded },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = cardBgColor)
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    // 始终暴露的标题栏
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column {
                                            Text("勿扰联动总干线", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = textColor)
                                            Text("同步手机与手表的勿扰及状态控制", fontSize = 12.sp, color = subTextColor)
                                        }
                                        Text(
                                            text = if (isDndExpanded) "🔼" else if (masterOn) "🟢 联动中" else "⚪ 已关闭",
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (masterOn) Color(0xFF4CAF50) else Color.Gray
                                        )
                                    }

                                    // 点击展开后的内部控制开关
                                    AnimatedVisibility(visible = isDndExpanded) {
                                        Column(modifier = Modifier.padding(top = 12.dp)) {
                                            HorizontalDivider(color = dividerColor, modifier = Modifier.padding(bottom = 8.dp))
                                            
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text("启用勿扰主联动", fontWeight = FontWeight.SemiBold, color = textColor)
                                                Switch(checked = masterOn, onCheckedChange = { masterOn = it; updateMask(it, vibrateOn, sleepOn, powerOn) })
                                            }

                                            AnimatedVisibility(visible = masterOn) {
                                                Column(
                                                    modifier = Modifier.padding(top = 14.dp),
                                                    verticalArrangement = Arrangement.spacedBy(14.dp)
                                                ) {
                                                    HorizontalDivider(color = dividerColor)
                                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                                        Text("手表端震动反馈", color = textColor)
                                                        Switch(checked = vibrateOn, onCheckedChange = { vibrateOn = it; updateMask(masterOn, it, sleepOn, powerOn) })
                                                    }
                                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                                        Text("睡眠模式同步", color = textColor)
                                                        Switch(checked = sleepOn, onCheckedChange = { sleepOn = it; updateMask(masterOn, vibrateOn, it, powerOn) })
                                                    }
                                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                                        Text("省电模式同步", color = textColor)
                                                        Switch(checked = powerOn, onCheckedChange = { powerOn = it; updateMask(masterOn, vibrateOn, sleepOn, it) })
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                           // ==========================================
                            // 4. 闹钟全域代点中心 (支持折叠与总开关)
                            // ==========================================
                            var selectedAlarmName by remember { mutableStateOf(sp.getString("selected_alarm_name", "Google 时钟") ?: "Google 时钟") }
                            var selectedAlarmPkg by remember { mutableStateOf(sp.getString("selected_alarm_package", "com.google.android.deskclock") ?: "com.google.android.deskclock") }
                            var dismissKeyText by remember { mutableStateOf(sp.getString("alarm_dismiss_key", "停止") ?: "停止") }
                            var snoozeKeyText by remember { mutableStateOf(sp.getString("alarm_snooze_key", "延后") ?: "延后") }
                            
                            // 🎯 新增：总开关状态状态持久化读取（默认开启 true）
                            var isAlarmMasterEnabled by remember { mutableStateOf(sp.getBoolean("alarm_proxy_master_switch", true)) }
                            
                            Card(
                                onClick = { isAlarmExpanded = !isAlarmExpanded },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = cardBgColor)
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    // 始终暴露的标题栏
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text("闹钟全域代点中心", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = if (isAlarmMasterEnabled) textColor else textColor.copy(alpha = 0.5f))
                                            Text("当前代点源: $selectedAlarmName", fontSize = 12.sp, color = if (isAlarmMasterEnabled) subTextColor else subTextColor.copy(alpha = 0.5f))
                                        }
                                        
                                        // 🎯 新增：优雅的 Switch 总开关（阻止点击卡片触发折叠）
                                        Switch(
                                            checked = isAlarmMasterEnabled,
                                            onCheckedChange = { isEnabled ->
                                                isAlarmMasterEnabled = isEnabled
                                                sp.edit().putBoolean("alarm_proxy_master_switch", isEnabled).apply()
                                                PhoneLog.d("WearSync_Main", "🎛️ 闹钟代点总开关变更为: $isEnabled")
                                            },
                                            modifier = Modifier.padding(end = 8.dp),
                                            // 屏蔽 Switch 自身的点击冒泡，防止点击 Switch 的同时把卡片折叠/展开了
                                            interactionSource = remember { MutableInteractionSource() }
                                        )
                            
                                        Text(text = if (isAlarmExpanded) "🔼" else "⚙️ 配置", fontSize = 14.sp, color = subTextColor)
                                    }
                            
                                    // 点击展开后的详细配置和测试按钮
                                    AnimatedVisibility(visible = isAlarmExpanded) {
                                        Column(
                                            modifier = Modifier.padding(top = 12.dp),
                                            verticalArrangement = Arrangement.spacedBy(12.dp)
                                        ) {
                                            HorizontalDivider(color = dividerColor)
                            
                                            // 🎯 核心逻辑：如果总开关关闭，下方整个面板全部通过其 enabled 属性进行熔断致灰
                                            Button(
                                                enabled = isAlarmMasterEnabled,
                                                modifier = Modifier.fillMaxWidth(),
                                                onClick = {
                                                    PhoneSyncAppPicker.show(requireContext()) { pkg, name ->
                                                        selectedAlarmName = name
                                                        selectedAlarmPkg = pkg
                                                        sp.edit().putString("selected_alarm_package", pkg).putString("selected_alarm_name", name).apply()
                                                        PhoneLog.d("WearSync_Main", "🎯 切换时钟源: $name [$pkg]")
                                                    }
                                                }
                                            ) {
                                                Text("切换时钟源 (当前: $selectedAlarmName)", fontSize = 13.sp)
                                            }
                            
                                            Text("目标包名: $selectedAlarmPkg", fontSize = 11.sp, color = if (isAlarmMasterEnabled) subTextColor else subTextColor.copy(alpha = 0.4f))
                            
                                            HorizontalDivider(color = textColor.copy(alpha = 0.1f), thickness = 1.dp)
                            
                                            Row(
                                                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                OutlinedTextField(
                                                    enabled = isAlarmMasterEnabled,
                                                    value = dismissKeyText,
                                                    onValueChange = { newValue ->
                                                        dismissKeyText = newValue
                                                        val saveValue = if (newValue.trim().isEmpty()) "停止" else newValue.trim()
                                                        sp.edit().putString("alarm_dismiss_key", saveValue).apply()
                                                        PhoneLog.d("WearSync_Main", "💾 保存停止关键字: $saveValue")
                                                    },
                                                    label = { Text("停止关键字") },
                                                    singleLine = true,
                                                    modifier = Modifier.weight(1f),
                                                    textStyle = TextStyle(fontSize = 14.sp, color = if (isAlarmMasterEnabled) textColor else textColor.copy(alpha = 0.4f))
                                                )
                            
                                                OutlinedTextField(
                                                    enabled = isAlarmMasterEnabled,
                                                    value = snoozeKeyText,
                                                    onValueChange = { newValue ->
                                                        snoozeKeyText = newValue
                                                        val saveValue = if (newValue.trim().isEmpty()) "延后" else newValue.trim()
                                                        sp.edit().putString("alarm_snooze_key", saveValue).apply()
                                                        PhoneLog.d("WearSync_Main", "💾 保存延后关键字: $saveValue")
                                                    },
                                                    label = { Text("延后关键字") },
                                                    singleLine = true,
                                                    modifier = Modifier.weight(1f),
                                                    textStyle = TextStyle(fontSize = 14.sp, color = if (isAlarmMasterEnabled) textColor else textColor.copy(alpha = 0.4f))
                                                )
                                            }
                            
                                            HorizontalDivider(color = textColor.copy(alpha = 0.1f), thickness = 1.dp)
                            
                                            Text("🧪 业务流联调测试面板 (模拟手表端按键)", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = if (isAlarmMasterEnabled) textColor else textColor.copy(alpha = 0.4f))
                            
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                Button(
                                                    enabled = isAlarmMasterEnabled,
                                                    modifier = Modifier.weight(1f),
                                                    colors = ButtonDefaults.buttonColors(
                                                        containerColor = Color(0xFFC62828),
                                                        disabledContainerColor = Color(0xFFC62828).copy(alpha = 0.3f)
                                                    ),
                                                    onClick = {
                                                        try {
                                                            PhoneAlarmManager.handleWatchCommand(requireContext(), "DISMISS")
                                                            PhoneLog.d("WearSync_Main", "🧪 模拟手表发送 DISMISS")
                                                        } catch (e: Exception) {
                                                            PhoneLog.e("WearSync_Main", "模拟停止失败: ${e.message}")
                                                        }
                                                    }
                                                ) {
                                                    Text("模拟停止测试", color = if (isAlarmMasterEnabled) Color.White else Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
                                                }
                            
                                                Button(
                                                    enabled = isAlarmMasterEnabled,
                                                    modifier = Modifier.weight(1f),
                                                    colors = ButtonDefaults.buttonColors(
                                                        containerColor = Color(0xFFE65100),
                                                        disabledContainerColor = Color(0xFFE65100).copy(alpha = 0.3f)
                                                    ),
                                                    onClick = {
                                                        try {
                                                            PhoneAlarmManager.handleWatchCommand(requireContext(), "SNOOZE")
                                                            PhoneLog.d("WearSync_Main", "🧪 模拟手表发送 SNOOZE")
                                                        } catch (e: Exception) {
                                                            PhoneLog.e("WearSync_Main", "模拟延后失败: ${e.message}")
                                                        }
                                                    }
                                                ) {
                                                    Text("模拟延后测试", color = if (isAlarmMasterEnabled) Color.White else Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
                                                }
                                            }
                            
                                            Text(
                                                "💡 测试：设置一个即将响起的闹钟，响起后点击测试按钮，观察是否和手表真实点击流程一致。",
                                                fontSize = 10.sp, color = if (isAlarmMasterEnabled) subTextColor else subTextColor.copy(alpha = 0.4f), lineHeight = 14.sp
                                            )
                                        }
                                    }
                                }
                            }
                            // ==========================================
                            // 5. 远程相机控场中心 (支持折叠)
                            // ==========================================
                            Card(
                                onClick = { isCameraExpanded = !isCameraExpanded },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = cardBgColor)
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    // 始终暴露的标题栏
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column {
                                            Text("远程相机控场中心", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = textColor)
                                            Text("唤醒手机相机硬件与同步手表取景屏", fontSize = 12.sp, color = subTextColor)
                                        }
                                        Text(text = if (isCameraExpanded) "🔼" else "📷 控制", fontSize = 14.sp, color = subTextColor)
                                    }

                                    // 点击展开后的相机控制按钮
                                    AnimatedVisibility(visible = isCameraExpanded) {
                                        Column(modifier = Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                            HorizontalDivider(color = dividerColor)
                                            
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                // 呼叫手表相机
                                                Button(
                                                    modifier = Modifier.weight(1f),
                                                    colors = ButtonDefaults.buttonColors(containerColor = if (isCameraAllowedState.value) Color(0xFF2E7D32) else Color(0xFF333333)),
                                                    onClick = {
                                                        if (!isCameraAllowedState.value) {
                                                            requestCameraPermissionLauncher.launch(
                                                                Manifest.permission.CAMERA)
                                                            return@Button
                                                        }
                                                        PhoneLog.d("WearSync_Main", "🚀 START_CAMERA -> Service + Watch dual trigger")
                                                        val nodeId = WearSyncState.getNodeId(requireContext())
                                                        requireContext().startService(
                                                            Intent(requireContext(), PhoneSyncCameraService::class.java).setAction(PhoneSyncCameraService.ACTION_START_CAMERA)
                                                        )
                                                        if (!nodeId.isNullOrEmpty()) {
                                                            Thread {
                                                                try {
                                                                    val json = JSONObject().apply {
                                                                        put("sender", "phone")
                                                                        put("type", "camera_control")
                                                                        put("action", "START_CAMERA")
                                                                        put("timestamp", System.currentTimeMillis())
                                                                    }
                                                                    Wearable.getMessageClient(requireContext()).sendMessage(nodeId, "/wear-universal-sync", json.toString().toByteArray(StandardCharsets.UTF_8))
                                                                    PhoneLog.w("WearSync_Main", "START_CAMERA sent -> $nodeId")
                                                                } catch (e: Exception) {
                                                                    PhoneLog.e("WearSync_Main", "START_CAMERA failed: ${e.message}")
                                                                }
                                                            }.start()
                                                        }
                                                    }
                                                ) {
                                                    Text("呼叫手表相机", color = Color.White, fontSize = 12.sp)
                                                }

                                                // 强制关闭相机
                                                Button(
                                                    modifier = Modifier.weight(1f),
                                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7F1D1D)),
                                                    onClick = {
                                                        PhoneLog.w("WearSync_Main", "🛑 STOP_CAMERA -> full shutdown sync")
                                                        val nodeId = WearSyncState.getNodeId(requireContext())
                                                        requireContext().startService(
                                                            Intent(requireContext(), PhoneSyncCameraService::class.java).setAction(PhoneSyncCameraService.ACTION_STOP_CAMERA)
                                                        )
                                                        if (!nodeId.isNullOrEmpty()) {
                                                            Thread {
                                                                try {
                                                                    val json = JSONObject().apply {
                                                                        put("sender", "phone")
                                                                        put("type", "camera_control")
                                                                        put("action", "FORCE_QUIT_CAMERA")
                                                                        put("timestamp", System.currentTimeMillis())
                                                                    }
                                                                    Wearable.getMessageClient(requireContext()).sendMessage(nodeId, "/wear-universal-sync", json.toString().toByteArray(StandardCharsets.UTF_8))
                                                                    PhoneLog.w("WearSync_Main", "FORCE_QUIT_CAMERA sent -> $nodeId")
                                                                } catch (e: Exception) {
                                                                    PhoneLog.e("WearSync_Main", "STOP_CAMERA failed: ${e.message}")
                                                                }
                                                            }.start()
                                                        }
                                                    }
                                                ) {
                                                    Text("强制关闭相机", color = Color.White, fontSize = 12.sp)
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            // 📥 接下来的部分将处理“日志板块”和全新的“文件传输（预留不生效）”板块，准备好后回复我继续！


                            // ==========================================
                            // 6. 終端調試與日誌輸出 (支持折疊)
                            // ==========================================
                            Card(
                                onClick = { isLogExpanded = !isLogExpanded },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = cardBgColor)
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    // 始終暴露的標題欄
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column {
                                            Text("終端調試與日誌輸出", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = textColor)
                                            Text("運行時核心通信報文與底層錯誤追蹤", fontSize = 12.sp, color = subTextColor)
                                        }
                                        Text(text = if (isLogExpanded) "🔼" else "📝 日誌", fontSize = 14.sp, color = subTextColor)
                                    }

                                    // 點擊展開後的日誌控制與顯示面板
                                    AnimatedVisibility(visible = isLogExpanded) {
                                        Column(modifier = Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                            HorizontalDivider(color = dividerColor)
                                            
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text("開啟手機端 Logcat 調試日誌", color = textColor, fontSize = 14.sp)
                                                Switch(
                                                    checked = uiLogDebugSwitch.value,
                                                    onCheckedChange = { isChecked ->
                                                        uiLogDebugSwitch.value = isChecked
                                                        sp.edit().putBoolean("phone_log_debug_visible", isChecked).apply()
                                                        PhoneLog.DEBUG = isChecked
                                                        PhoneLog.d("WearSync_Main", "🎛️ 本地日誌開關切換為: $isChecked")
                                                    }
                                                )
                                            }

                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text("同步監聽手錶端核心日誌", color = textColor, fontSize = 14.sp)
                                                Switch(
                                                    checked = uiWearLogDebugSwitch.value,
                                                    onCheckedChange = { isChecked ->
                                                        uiWearLogDebugSwitch.value = isChecked
                                                        sp.edit().putBoolean("wear_log_debug_visible", isChecked).apply()
                                                        PhoneLog.d("WearSync_Main", "🎛️ 遠端日誌監聽切換為: $isChecked")
                                                    }
                                                )
                                            }

                                            HorizontalDivider(color = textColor.copy(alpha = 0.1f), thickness = 1.dp)

                                            Button(
                                                modifier = Modifier.fillMaxWidth(),
                                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                                                onClick = {
                                                    try {
                                                        val intent = Intent().apply {
                                                            component = ComponentName("cn.luke.wearsync", "cn.luke.wearsync.PhoneLogActivity")
                                                        }
                                                        startActivity(intent)
                                                        PhoneLog.d("WearSync_Main", "📂 成功打開日誌可視化看板")
                                                    } catch (e: Exception) {
                                                        PhoneLog.e("WearSync_Main", "無法打開日誌界面: ${e.message}")
                                                    }
                                                }
                                            ) {
                                                Text("進入獨立日誌觀察屏", fontSize = 13.sp)
                                            }
                                        }
                                    }
                                }
                            }

                            // ==========================================================
                            // 7. 文件傳輸樞紐 (已寫入，帶完整代碼結構與註釋，目前暫不生效)
                            // ==========================================================
                            Card(
                                onClick = { isFileTransferExpanded = !isFileTransferExpanded },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = cardBgColor)
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    // 始終暴露的文件板塊概要欄
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column {
                                            Text("跨端文件傳輸樞紐 (暫未啟用)", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = textColor.copy(alpha = 0.6f))
                                            Text("雙向傳輸照片、音頻及自定義配置文件", fontSize = 12.sp, color = subTextColor.copy(alpha = 0.6f))
                                        }
                                        Text(
                                            text = if (isFileTransferExpanded) "🔼" else "⏳ 預留", 
                                            fontSize = 14.sp, 
                                            color = subTextColor.copy(alpha = 0.6f)
                                        )
                                    }

                                    // 點擊展開後的文件管理介面 (內部核心邏輯已被全部註釋封鎖)
                                    AnimatedVisibility(visible = isFileTransferExpanded) {
                                        Column(modifier = Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                            HorizontalDivider(color = dividerColor)
                                            
                                            Text(
                                                text = "📌 模塊提示：前五個核心聯動模塊（連接、權限、勿擾、鬧鐘、相機）調通後，取消下方代碼註釋即可對接底層 ChannelClient 實現雙端大文件高速傳輸。",
                                                fontSize = 12.sp,
                                                color = Color(0xFFFF9800), // 用黃色醒目提醒
                                                lineHeight = 16.sp
                                            )

                                            /* ⏳ 預留大文件傳輸 UI 交互流（未來解鎖區）
                                            
                                            // 1. 文件選擇展示欄
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text("待傳輸文件:", color = textColor, fontSize = 13.sp)
                                                Text("尚未選擇任何文件", color = subTextColor, fontSize = 13.sp)
                                            }

                                            // 2. 選取與傳輸操作按鈕
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                Button(
                                                    modifier = Modifier.weight(1f),
                                                    onClick = { 
                                                        // TODO: 觸發系統文件選擇器 (Intent.ACTION_GET_CONTENT) 
                                                        PhoneLog.d("WearSync_File", "觸發文件選取...")
                                                    }
                                                ) {
                                                    Text("選擇本地文件", fontSize = 12.sp)
                                                }

                                                Button(
                                                    modifier = Modifier.weight(1f),
                                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0288D1)),
                                                    onClick = {
                                                        // TODO: 調用 Wearable.getChannelClient 構建大文件流對接
                                                        PhoneLog.w("WearSync_File", "準備啟動遠端文件投遞...")
                                                    }
                                                ) {
                                                    Text("開始投遞手錶", fontSize = 12.sp)
                                                }
                                            }

                                            // 3. 傳輸進度條預留
                                            LinearProgressIndicator(
                                                progress = 0f,
                                                modifier = Modifier.fillMaxWidth().height(6.dp),
                                                color = Color(0xFF0288D1),
                                                trackColor = dividerColor
                                            )
                                            */
                                        }
                                    }
                                }
                            }

                        } // 結束 Column
                    } // 結束 Surface
                } // 結束 MaterialTheme
            } // 結束 setContent
        } // 結束 ComposeView
    } // 結束 onCreateView

    // ... 後續的 onResume(), onPause(), checkNotificationPermission() 等核心生命周期回調保持完全不變 ...
        override fun onResume() {
        super.onResume()
        checkNotificationPermission()
        isCameraAllowedState.value = requireContext().checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
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
