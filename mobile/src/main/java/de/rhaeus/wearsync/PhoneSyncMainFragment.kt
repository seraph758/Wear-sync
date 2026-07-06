package de.rhaeus.wearsync

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Build
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import android.app.Activity
import android.content.ComponentName
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import android.app.NotificationManager
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

                            // 1. 连接状态卡片
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = cardBgColor)
                            ) {
                                Row(
                                    modifier = Modifier.padding(16.dp).fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text("连接状态", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = textColor)
                                        Text("检测手机与手表的骨干网络连接", fontSize = 12.sp, color = subTextColor)
                                    }
                                    Text(
                                        text = if (isConnectedState.value) "🟢 已连接手表" else "🔴 未连接",
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isConnectedState.value) Color(0xFF4CAF50) else Color(0xFFF44336)
                                    )
                                }
                            }

                            // 2. 双权限卡片并排
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Card(
                                    modifier = Modifier.weight(1f).height(125.dp),
                                    shape = RoundedCornerShape(16.dp),
                                    colors = CardDefaults.cardColors(containerColor = cardBgColor),
                                    border = if (!isDark) BorderStroke(1.dp, dividerColor) else null
                                ) {
                                    Column(
                                        modifier = Modifier.padding(12.dp).fillMaxSize(),
                                        verticalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Column {
                                            Text("通知接管", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = textColor)
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text(
                                                text = if (isNotificationAllowedState.value) "核心状态已就绪" else "拦截状态必备",
                                                fontSize = 11.sp,
                                                lineHeight = 14.sp,
                                                color = if (isNotificationAllowedState.value) Color(0xFF4CAF50) else Color(0xFFF44336)
                                            )
                                        }
                                        if (!isNotificationAllowedState.value) {
                                            Button(
                                                onClick = { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                                modifier = Modifier.align(Alignment.End).height(28.dp)
                                            ) {
                                                Text("去授权", fontSize = 11.sp)
                                            }
                                        } else {
                                            Text("🟢 已放行", fontSize = 11.sp, color = Color(0xFF4CAF50), modifier = Modifier.align(Alignment.End))
                                        }
                                    }
                                }

                                Card(
                                    modifier = Modifier.weight(1f).height(125.dp),
                                    shape = RoundedCornerShape(16.dp),
                                    colors = CardDefaults.cardColors(containerColor = cardBgColor),
                                    border = if (!isDark) BorderStroke(1.dp, dividerColor) else null
                                ) {
                                    Column(
                                        modifier = Modifier.padding(12.dp).fillMaxSize(),
                                        verticalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Column {
                                            Text("相机硬件", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = textColor)
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text(
                                                text = if (isCameraAllowedState.value) "取景功能正常" else "取景控制必备",
                                                fontSize = 11.sp,
                                                lineHeight = 14.sp,
                                                color = if (isCameraAllowedState.value) Color(0xFF4CAF50) else Color(0xFFF44336)
                                            )
                                        }
                                        if (!isCameraAllowedState.value) {
                                            Button(
                                                onClick = { requestCameraPermissionLauncher.launch(android.Manifest.permission.CAMERA) },
                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                                modifier = Modifier.align(Alignment.End).height(28.dp)
                                            ) {
                                                Text("授相机", fontSize = 11.sp)
                                            }
                                        } else {
                                            Text("🟢 已放行", fontSize = 11.sp, color = Color(0xFF4CAF50), modifier = Modifier.align(Alignment.End))
                                        }
                                    }
                                }
                            }

                            // 3. 勿扰联动控制台
                            var mask by remember { mutableStateOf(sp.getInt("dnd_sync_mask",15)) }
                            var masterOn by remember(mask) { mutableStateOf((mask and 1)!=0) }
                            var vibrateOn by remember(mask) { mutableStateOf((mask and 2)!=0) }
                            var sleepOn by remember(mask) { mutableStateOf((mask and 4)!=0) }
                            var powerOn by remember(mask) { mutableStateOf((mask and 8)!=0) }
                            val updateMask={newMaster:Boolean,newVibrate:Boolean,newSleep:Boolean,newPower:Boolean->
                                var newMask=0
                                if(newMaster)newMask=newMask or 1
                                if(newVibrate)newMask=newMask or 2
                                if(newSleep)newMask=newMask or 4
                                if(newPower)newMask=newMask or 8
                            
                                mask=newMask
                                sp.edit().putInt("dnd_sync_mask",newMask).apply()
                            
                                     PhoneLog.d("WearSync_Main","⚙️ DND联动配置已保存 mask=$newMask")
                            }
                            Card(
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
                                        Text("勿扰联动总干线", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = textColor)
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



                              // 🎯 4. 闹钟全域代点中心（终极完整版：自定义关键字 + 模拟手表真实消息流）
                            
                            var selectedAlarmName by remember {
                                mutableStateOf(
                                    sp.getString(
                                        "selected_alarm_name",
                                        "Google 时钟"
                                    ) ?: "Google 时钟"
                                )
                            }
                            
                            
                            var selectedAlarmPkg by remember {
                                mutableStateOf(
                                    sp.getString(
                                        "selected_alarm_package",
                                        "com.google.android.deskclock"
                                    ) ?: "com.google.android.deskclock"
                                )
                            }
                            
                            
                            // 自定义关键字状态
                            var dismissKeyText by remember {
                                mutableStateOf(
                                    sp.getString(
                                        "alarm_dismiss_key",
                                        "停止"
                                    ) ?: "停止"
                                )
                            }
                            
                            
                            var snoozeKeyText by remember {
                                mutableStateOf(
                                    sp.getString(
                                        "alarm_snooze_key",
                                        "延后"
                                    ) ?: "延后"
                                )
                            }
                            
                            
                            
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = cardBgColor
                                )
                            ) {
                            
                            
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                            
                            
                                    Text(
                                        "闹钟全域代点中心",
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = textColor
                                    )
                            
                            
                            
                                    // 1. 时钟源选择
                            
                                    Button(
                                        modifier = Modifier.fillMaxWidth(),
                                        onClick = {
                            
                            
                                            PhoneSyncAppPicker.show(
                                                requireContext()
                                            ){ pkg,name ->
                            
                            
                                                selectedAlarmName = name
                                                selectedAlarmPkg = pkg
                            
                            
                                                sp.edit()
                                                    .putString(
                                                        "selected_alarm_package",
                                                        pkg
                                                    )
                                                    .putString(
                                                        "selected_alarm_name",
                                                        name
                                                    )
                                                    .apply()
                            
                            
                            
                                                PhoneLog.d(
                                                    "WearSync_Main",
                                                    "🎯 切换时钟源: $name [$pkg]"
                                                )
                            
                                            }
                            
                            
                                        }
                            
                                    ){
                            
                                        Text(
                                            "当前时钟源: $selectedAlarmName",
                                            fontSize = 13.sp
                                        )
                            
                                    }
                            
                            
                            
                                    Text(
                                        "目标包名: $selectedAlarmPkg",
                                        fontSize = 11.sp,
                                        color = subTextColor
                                    )
                            
                            
                            
                                    HorizontalDivider(
                                        color = textColor.copy(alpha = 0.1f),
                                        thickness = 1.dp
                                    )
                            
                            
                            // 使用 Row 让内部组件水平并排
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp), // 给这一行上下加一点点间距
                                horizontalArrangement = Arrangement.spacedBy(8.dp) // 自动让两个输入框之间留出 8dp 的间距
                            ) {
                            
                                // 2. 停止关键字（左侧）
                                OutlinedTextField(
                                    value = dismissKeyText,
                                    onValueChange = { newValue ->
                                        dismissKeyText = newValue
                                        val saveValue = if(newValue.trim().isEmpty()){
                                            "停止"
                                        } else {
                                            newValue.trim()
                                        }
                                        sp.edit()
                                            .putString("alarm_dismiss_key", saveValue)
                                            .apply()
                                        PhoneLog.d("WearSync_Main", "💾 保存停止关键字: $saveValue")
                                    },
                                    label = { Text("停止关键字") },
                                    placeholder = { Text("例如：停止") }, // 并排后空间变小，建议提示词精简一下
                                    singleLine = true,
                                    // ⭐ 关键：改为 weight(1f)，让其平分空间
                                    modifier = Modifier.weight(1f), 
                                    textStyle = TextStyle(
                                        fontSize = 14.sp,
                                        color = textColor
                                    )
                                )
                            
                                // 3. 延后关键字（右侧）
                                OutlinedTextField(
                                    value = snoozeKeyText,
                                    onValueChange = { newValue ->
                                        snoozeKeyText = newValue
                                        val saveValue = if(newValue.trim().isEmpty()){
                                            "延后"
                                        } else {
                                            newValue.trim()
                                        }
                                        sp.edit()
                                            .putString("alarm_snooze_key", saveValue)
                                            .apply()
                                        PhoneLog.d("WearSync_Main", "💾 保存延后关键字: $saveValue")
                                    },
                                    label = { Text("延后关键字") },
                                    placeholder = { Text("例如：延后") }, // 并排后空间变小，建议提示词精简一下
                                    singleLine = true,
                                    // ⭐ 关键：改为 weight(1f)，让其平分空间
                                    modifier = Modifier.weight(1f), 
                                    textStyle = TextStyle(
                                        fontSize = 14.sp,
                                        color = textColor
                                    )
                                )
                            }
                            
                            // 下面依然接你原来的分割线
                            HorizontalDivider(
                                color = textColor.copy(alpha = 0.1f),
                                thickness = 1.dp
                            )
                            
                            Text(
                                "🧪 业务流联调测试面板 (模拟手表端按键)",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = textColor
                            )
                            
                           
                            
                                    // 4. 模拟手表真实消息
                            
                                    Row(
                            
                                        modifier = Modifier.fillMaxWidth(),
                            
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                            
                                    ){
                            
                            
                            
                                        Button(
                            
                                            modifier = Modifier.weight(1f),
                            
                            
                                            colors = ButtonDefaults.buttonColors(
                            
                                                containerColor = Color(0xFFC62828)
                            
                                            ),
                            
                            
                            
                                            onClick = {
                            
                            
                                                try{
                            
                            
                                                    val json =
                                                        JSONObject()
                            
                            
                            
                                                    json.put(
                                                        "sender",
                                                        "watch"
                                                    )
                            
                            
                                                    json.put(
                                                        "type",
                                                        "alarm"
                                                    )
                            
                            
                                                    json.put(
                                                        "action",
                                                        "DISMISS"
                                                    )
                            
                            
                                                    json.put(
                                                        "timestamp",
                                                        System.currentTimeMillis()
                                                    )
                            
                            
                            
                                                    val data =
                            
                                                        json.toString()
                                                            .toByteArray(
                                                                StandardCharsets.UTF_8
                                                            )
                            
                            
                            
                                                    PhoneLog.d(
                            
                                                        "WearSync_Main",
                            
                                                        "🧪 模拟手表发送 DISMISS"
                            
                                                    )
                            
                            
                            
                                                    PhoneAlarmManager.handleWatchCommand(
                                requireContext(),
                                "DISMISS"
                            )
                            
                            
                            
                                                }catch(e:Exception){
                            
                            
                            
                                                    PhoneLog.e(
                            
                                                        "WearSync_Main",
                            
                                                        "模拟停止失败: ${e.message}"
                            
                                                    )
                            
                            
                                                }
                            
                            
                                            }
                            
                                        ){
                            
                            
                                            Text(
                            
                                                "模拟停止测试",
                            
                                                color = Color.White,
                            
                                                fontSize = 12.sp
                            
                                            )
                            
                                        }
                            
                            
                            
                            
                            
                            
                            
                                        Button(
                            
                            
                                            modifier = Modifier.weight(1f),
                            
                            
                                            colors = ButtonDefaults.buttonColors(
                            
                                                containerColor = Color(0xFFE65100)
                            
                                            ),
                            
                            
                            
                                            onClick = {
                            
                            
                                                try{
                            
                            
                                                    val json =
                                                        JSONObject()
                            
                            
                            
                                                    json.put(
                                                        "sender",
                                                        "watch"
                                                    )
                            
                            
                                                    json.put(
                                                        "type",
                                                        "alarm"
                                                    )
                            
                            
                                                    json.put(
                                                        "action",
                                                        "SNOOZE"
                                                    )
                            
                            
                                                    json.put(
                                                        "timestamp",
                                                        System.currentTimeMillis()
                                                    )
                            
                            
                            
                                                    val data =
                            
                                                        json.toString()
                                                            .toByteArray(
                                                                StandardCharsets.UTF_8
                                                            )
                            
                            
                            
                                                    PhoneLog.d(
                            
                                                        "WearSync_Main",
                            
                                                        "🧪 模拟手表发送 SNOOZE"
                            
                                                    )
                            
                            
                            
                                                    PhoneAlarmManager.handleWatchCommand(
                                requireContext(),
                                "DISMISS"
                            )
                            
                            
                            
                                                }catch(e:Exception){
                            
                            
                            
                                                    PhoneLog.e(
                            
                                                        "WearSync_Main",
                            
                                                        "模拟延后失败: ${e.message}"
                            
                                                    )
                            
                            
                                                }
                            
                            
                                            }
                            
                            
                                        ){
                            
                            
                                            Text(
                            
                                                "模拟延后测试",
                            
                                                color = Color.White,
                            
                                                fontSize = 12.sp
                            
                                            )
                            
                            
                                        }
                            
                            
                                    }
                            
                            
                            
                            
                            
                                    Text(
                            
                                        "💡 测试：设置一个即将响起的闹钟，响起后点击测试按钮，观察是否和手表真实点击流程一致。",
                            
                                        fontSize = 10.sp,
                            
                                        color = subTextColor,
                            
                                        lineHeight = 14.sp
                            
                                    )
                            
                            
                            
                                }
                            
                            }
// 5. 远程相机控场中心
Card(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(16.dp),
    colors = CardDefaults.cardColors(containerColor = cardBgColor)
) {
    Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {

        Text(
            "远程相机控场中心",
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = textColor
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {

            // 🟢 START CAMERA
            Button(
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor =
                        if (isCameraAllowedState.value) Color(0xFF2E7D32)
                        else Color(0xFF333333)
                ),
                onClick = {

                    if (!isCameraAllowedState.value) {
                        requestCameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
                        return@Button
                    }

                    PhoneLog.d("WearSync_Main", "🚀 START_CAMERA -> Service + Watch dual trigger")

                    val nodeId = WearSyncState.getNodeId(requireContext())

                    // 1. 本地启动 Service（只发意图，不假设状态）
                    requireContext().startService(
                        Intent(requireContext(), PhoneSyncCameraService::class.java)
                            .setAction(PhoneSyncCameraService.ACTION_START_CAMERA)
                    )

                    // 2. 远端通知手表（仅控制信号）
                    if (!nodeId.isNullOrEmpty()) {

                        Thread {
                            try {
                                val json = JSONObject().apply {
                                    put("sender", "phone")
                                    put("type", "camera_control")
                                    put("action", "START_CAMERA")
                                    put("timestamp", System.currentTimeMillis())
                                }

                                Wearable.getMessageClient(requireContext())
                                    .sendMessage(
                                        nodeId,
                                        "/wear-universal-sync",
                                        json.toString().toByteArray(StandardCharsets.UTF_8)
                                    )

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

            // 🔴 STOP CAMERA
            Button(
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7F1D1D)),
                onClick = {

                    PhoneLog.w("WearSync_Main", "🛑 STOP_CAMERA -> full shutdown sync")

                    val nodeId = WearSyncState.getNodeId(requireContext())

                    // 1. 优先关闭本地 Service（唯一真实执行者）
                    requireContext().startService(
                        Intent(requireContext(), PhoneSyncCameraService::class.java)
                            .setAction(PhoneSyncCameraService.ACTION_STOP_CAMERA)
                    )

                    // 2. 同步通知手表
                    if (!nodeId.isNullOrEmpty()) {

                        Thread {
                            try {
                                val json = JSONObject().apply {
                                    put("sender", "phone")
                                    put("type", "camera_control")
                                    put("action", "FORCE_QUIT_CAMERA")
                                    put("timestamp", System.currentTimeMillis())
                                }

                                Wearable.getMessageClient(requireContext())
                                    .sendMessage(
                                        nodeId,
                                        "/wear-universal-sync",
                                        json.toString().toByteArray(StandardCharsets.UTF_8)
                                    )

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


                            // 6. 调试日志(手机)
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = cardBgColor)
                            ) {
                                Row(
                                    modifier = Modifier.padding(16.dp).fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("开发者调试日志 (手机)", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = textColor)
                                        Text("全面控制手机后台排查调试Log流的输出", fontSize = 12.sp, color = subTextColor)
                                    }
                                    Switch(
                                        checked = uiLogDebugSwitch.value,
                                        onCheckedChange = { isChecked ->
                                            uiLogDebugSwitch.value = isChecked
                                            PhoneLog.DEBUG = isChecked
                                            sp.edit().putBoolean("phone_log_debug_visible", isChecked).apply()
                                            PhoneLog.d("WearSync_Main", "🎛️ [手机日志开关] 状态变更为: $isChecked")
                                        }
                                    )
                                }
                            }

                            // 7. 调试日志(手表)
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = cardBgColor)
                            ) {
                                Row(
                                    modifier = Modifier.padding(16.dp).fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("开发者调试日志 (手表)", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = textColor)
                                        Text("通过骨干网信令远程压制或放行手表日志流", fontSize = 12.sp, color = subTextColor)
                                    }
                                    Switch(
                                        checked = uiWearLogDebugSwitch.value,
                                        onCheckedChange = { isChecked ->
                                            uiWearLogDebugSwitch.value = isChecked
                                            sp.edit().putBoolean("wear_log_debug_visible", isChecked).apply()
                                            PhoneLog.d("WearSync_Main", "🎛️ [手表日志开关] 准备送达远程流控信令: $isChecked")

                                            Thread {
                                                try {
                                                    val json = JSONObject()
                                                    json.put("sender", "phone")
                                                    json.put("type", "wear_log_control")
                                                    json.put("wear_log_debug", isChecked)
                                                    json.put("timestamp", System.currentTimeMillis())

                                                    val data = json.toString().toByteArray(StandardCharsets.UTF_8)
                                                    val nodeId = WearSyncState.getNodeId(requireContext())
                                                    if (!nodeId.isNullOrEmpty()) {
                                                        Wearable.getMessageClient(requireContext()).sendMessage(nodeId, "/wear-universal-sync", data)
                                                    }
                                                } catch (e: Exception) {
                                                    PhoneLog.e("WearSync_Main", "🔴 发送手表日志控制信令失败", e)
                                                }
                                            }.start()
                                        }
                                    )
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
