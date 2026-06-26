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

class PhoneSyncMainFragment : Fragment() {

    private val isNotificationAllowedState = mutableStateOf(false)
    private val isCameraAllowedState = mutableStateOf(false)
    private val isConnectedState = mutableStateOf(false)
    private var capabilityChangedListener: CapabilityClient.OnCapabilityChangedListener? = null

    private val uiLogDebugSwitch = mutableStateOf(PhoneLog.DEBUG)
    private val uiWearLogDebugSwitch = mutableStateOf(true)

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

                            // 4. 闹钟全域代点
                            var customAlarmPkg by remember { mutableStateOf(sp.getString("custom_alarm_package", "com.google.android.deskclock") ?: "") }

                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = cardBgColor)
                            ) {
                                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text("闹钟全域代点中心", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = textColor)

                                    OutlinedTextField(
                                        value = customAlarmPkg,
                                        onValueChange = {
                                            customAlarmPkg = it
                                            sp.edit().putString("custom_alarm_package", it).apply()
                                        },
                                        label = { Text("监控时钟源包名") },
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = textColor,
                                            unfocusedBorderColor = subTextColor,
                                            focusedLabelColor = textColor,
                                            unfocusedLabelColor = subTextColor
                                        )
                                    )

                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Button(
                                            modifier = Modifier.weight(1f),
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828)),
                                            onClick = {
                                                PhoneLog.d("WearSync_Main", "🎯 模拟发送【停止闹钟】物理拦截信令")
                                                requireContext().sendBroadcast(Intent("de.rhaeus.wearsync.ACTION_ALARM_DISMISS_TRIGGER"))
                                            }
                                        ) {
                                            Text("模拟停止闹钟", color = Color.White, fontSize = 12.sp)
                                        }

                                        Button(
                                            modifier = Modifier.weight(1f),
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE65100)),
                                            onClick = {
                                                PhoneLog.d("WearSync_Main", "🎯 模拟发送【延后闹钟】物理拦截信令")
                                                requireContext().sendBroadcast(Intent("de.rhaeus.wearsync.ACTION_ALARM_SNOOZE_TRIGGER"))
                                            }
                                        ) {
                                            Text("模拟延后闹钟", color = Color.White, fontSize = 12.sp)
                                        }
                                    }
                                }
                            }

                            // 5. 远程相机控场中心
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = cardBgColor)
                            ) {
                                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text("远程相机控场中心", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = textColor)

                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Button(
                                            modifier = Modifier.weight(1f),
                                            colors = ButtonDefaults.buttonColors(containerColor = if (isCameraAllowedState.value) Color(0xFF2E7D32) else Color(0xFF333333)),
                                            onClick = {
                                                if (!isCameraAllowedState.value) {
                                                    requestCameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
                                                } else {
                                                    PhoneLog.d("WearSync_Main", "🚀 [手动控场] 激活手机相机采集管道")
                                                    requireContext().startService(Intent(requireContext(), PhoneSyncCameraService::class.java).setAction(PhoneSyncCameraService.ACTION_START_CAMERA))
                                                }
                                            }
                                        ) {
                                            Text("呼叫手表相机", color = Color.White, fontSize = 12.sp)
                                        }

                                        Button(
                                            modifier = Modifier.weight(1f),
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7F1D1D)),
                                            onClick = {
                                                PhoneLog.d("WearSync_Main", "🧹 [手动控场] 拦截手机端相机 service 并发送下线信令")
                                                requireContext().stopService(Intent(requireContext(), PhoneSyncCameraService::class.java))

                                                Thread {
                                                    try {
                                                        val json = JSONObject()
                                            // 在 PhoneSyncMainFragment.kt 中，將強制關閉按鈕的 JSON 替換為：
            json.put("sender", "phone")
            json.put("type", "camera_control") // 👈 對齊手錶端
            json.put("action", "FORCE_QUIT_CAMERA") // 👈 對齊手錶端
            json.put("timestamp", System.currentTimeMillis())

                                                        val data = json.toString().toByteArray(StandardCharsets.UTF_8)
                                                        val nodeId = WearSyncState.getNodeId(requireContext())
                                                        if (!nodeId.isNullOrEmpty()) {
                                                            Wearable.getMessageClient(requireContext()).sendMessage(nodeId, "/wear-universal-sync", data)
                                                        }
                                                    } catch (ignored: Exception) {}
                                                }.start()
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
