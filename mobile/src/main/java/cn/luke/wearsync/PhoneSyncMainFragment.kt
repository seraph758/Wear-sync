package cn.luke.wearsync

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow


object AppState {
    private val _isServiceRunning = MutableStateFlow(false)
    val isServiceRunning: StateFlow<Boolean> = _isServiceRunning.asStateFlow()
    fun setServiceRunning(running: Boolean) {
        _isServiceRunning.value = running
    }
}

/**
 * 将状态和回调分层包装，解决 Compose 编译器在参数过多（>30个）时可能导致的预览索引失效问题
 */
data class PhoneSyncUIState(
    val isConnected: Boolean = false,
    val isNotificationAllowed: Boolean = false,
    val isCameraAllowed: Boolean = false,
    val isServiceRunning: Boolean = false,
    val isWatchPreviewEnabled: Boolean = true,
    val fileTransferStatus: String = "",
    val uiLogDebug: Boolean = false,
    val uiWearLogDebug: Boolean = false,
    val screenPullDownInterval: Int = 500,
    val mask: Int = 15,
    val selectedAlarmName: String = "",
    val selectedAlarmPkg: String = "",
    val dismissKeyText: String = "",
    val snoozeKeyText: String = "",
    val isAlarmMasterEnabled: Boolean = true
)

data class PhoneSyncUIActions(
    val onPullDownIntervalChange: (Int) -> Unit = {},
    val onDndMaskUpdate: (master: Boolean, vibrate: Boolean, sleep: Boolean, power: Boolean) -> Unit = { _, _, _, _ -> },
    val onVibrationCommand: (action: String, onDuration: Int, offDuration: Int, repeat: Int) -> Unit = { _, _, _, _ -> },
    val onVibrationSave: (onDuration: Int, offDuration: Int, repeat: Int) -> Unit = { _, _, _ -> },
    val onAlarmSourceSwitch: () -> Unit = {},
    val onAlarmMasterToggle: (Boolean) -> Unit = {},
    val onDismissKeyChange: (String) -> Unit = {},
    val onSnoozeKeyChange: (String) -> Unit = {},
    val onAlarmTest: (String) -> Unit = {},
    val onCameraCall: () -> Unit = {},
    val onCameraForceStop: () -> Unit = {},
    val onPhoneLogToggle: (Boolean) -> Unit = {},
    val onWearLogToggle: (Boolean) -> Unit = {},
    val onWatchPreviewToggle: (Boolean) -> Unit = {},
    val onFloatingLogClick: () -> Unit = {},
    val onNotificationPermissionClick: () -> Unit = {},
    val onCameraPermissionClick: () -> Unit = {},
    val onFileTransferClick: () -> Unit = {}
)

@Preview(showBackground = true)
@Composable
fun PhoneSyncMainScreenPreview() {
    PhoneSyncMainScreen(
        state = PhoneSyncUIState(
            isConnected = true,
            isNotificationAllowed = true,
            isCameraAllowed = true,
            isServiceRunning = false,
            fileTransferStatus = "等待选择文件...",
            uiLogDebug = true
        ),
        actions = PhoneSyncUIActions()
    )
}

@Composable
fun PhoneSyncMainScreen(
    state: PhoneSyncUIState,
    actions: PhoneSyncUIActions
) {
    MaterialTheme {
        val isDark = isSystemInDarkTheme()
        val backgroundColor = if (isDark) Color(0xFF121214) else Color(0xFFF4F4F6)
        val cardBgColor = if (isDark) Color(0xFF1E1E24) else Color(0xFFFFFFFF)
        val textColor = if (isDark) Color.White else Color(0xFF1C1C1E)
        val subTextColor = if (isDark) Color.Gray else Color(0xFF757575)
        val dividerColor = if (isDark) Color(0xFF2C2C2C) else Color(0xFFE5E5EA)

        // UI-only expansion states
        var isVibrationExpanded by remember { mutableStateOf(false) }
        var isDndExpanded by remember { mutableStateOf(false) }
        var isAlarmExpanded by remember { mutableStateOf(false) }
        var isCameraExpanded by remember { mutableStateOf(false) }
        var isLogExpanded by remember { mutableStateOf(false) }
        var isConnectionExpanded by remember { mutableStateOf(false) }
        var isPermissionExpanded by remember { mutableStateOf(false) }
        var isFileTransferExpanded by remember { mutableStateOf(false) }

        // Local vibration pattern states
        var patternOnDuration by remember { mutableIntStateOf(500) }
        var patternOffDuration by remember { mutableIntStateOf(200) }
        var repeatIndex by remember { mutableIntStateOf(-1) }

        // DND mask components
        val masterOn = (state.mask and 1) != 0
        val vibrateOn = (state.mask and 2) != 0
        val sleepOn = (state.mask and 4) != 0
        val powerOn = (state.mask and 8) != 0
        
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = backgroundColor
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "WearSync",
                    fontSize = 33.sp,
                    fontWeight = FontWeight.Bold,
                    color = textColor,
                    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
                )

                // DND and Alarm Rows
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Card(
                            onClick = { isDndExpanded = !isDndExpanded; if (isDndExpanded) isAlarmExpanded = false },
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
                            onClick = { isAlarmExpanded = !isAlarmExpanded; if (isAlarmExpanded) isDndExpanded = false },
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
                                    Text(
                                        "闹钟全域代点",
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (state.isAlarmMasterEnabled) textColor else textColor.copy(alpha = 0.5f)
                                    )
                                    Text(
                                        state.selectedAlarmName,
                                        fontSize = 11.sp,
                                        color = if (state.isAlarmMasterEnabled) subTextColor else subTextColor.copy(alpha = 0.5f)
                                    )
                                }
                                Text(text = "⏰", fontSize = 16.sp)
                            }
                        }
                    }

                    // DND Expandable
                    AnimatedVisibility(visible = isDndExpanded) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = cardBgColor)
                        ) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("启用勿扰主同步", fontWeight = FontWeight.SemiBold, color = textColor, fontSize = 14.sp)
                                    Switch(checked = masterOn, onCheckedChange = { actions.onDndMaskUpdate(it, vibrateOn, sleepOn, powerOn) })
                                }

                                AnimatedVisibility(visible = masterOn) {
                                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        HorizontalDivider(color = dividerColor)
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text("手表端振动反馈", color = textColor, fontSize = 13.sp)
                                            Switch(checked = vibrateOn, onCheckedChange = { actions.onDndMaskUpdate(masterOn, it, sleepOn, powerOn) })
                                        }
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text("睡眠模式同步", color = textColor, fontSize = 13.sp)
                                            Switch(checked = sleepOn, onCheckedChange = { actions.onDndMaskUpdate(masterOn, vibrateOn, it, powerOn) })
                                        }
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text("省电模式同步", color = textColor, fontSize = 13.sp)
                                            Switch(checked = powerOn, onCheckedChange = { actions.onDndMaskUpdate(masterOn, vibrateOn, sleepOn, it) })
                                        }
                                        HorizontalDivider(color = dividerColor, modifier = Modifier.padding(vertical = 4.dp))
                                        Text("亮屏与下拉菜单间隔", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = textColor)
                                        Text("手机同步勿扰时，手表亮屏后延迟多久允许下拉菜单响应", fontSize = 11.sp, color = subTextColor, lineHeight = 14.sp)
                                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                            Slider(
                                                value = state.screenPullDownInterval.toFloat(),
                                                onValueChange = { actions.onPullDownIntervalChange(it.toInt()) },
                                                valueRange = 0f..2000f,
                                                steps = 19,
                                                modifier = Modifier.weight(1f)
                                            )
                                            Text(
                                                text = "${state.screenPullDownInterval}ms",
                                                fontSize = 13.sp,
                                                color = textColor,
                                                modifier = Modifier.padding(start = 8.dp).widthIn(min = 56.dp),
                                                textAlign = TextAlign.End
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Alarm Expandable
                    AnimatedVisibility(visible = isAlarmExpanded) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = cardBgColor)
                        ) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Button(
                                        enabled = state.isAlarmMasterEnabled,
                                        modifier = Modifier.weight(1f),
                                        onClick = actions.onAlarmSourceSwitch
                                    ) { Text("切换时钟源", fontSize = 12.sp) }

                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(text = if (state.isAlarmMasterEnabled) "已启用" else "已禁用", fontSize = 12.sp, color = textColor)
                                        Switch(checked = state.isAlarmMasterEnabled, onCheckedChange = actions.onAlarmMasterToggle)
                                    }
                                }
                                Text("目标包名: ${state.selectedAlarmPkg}", fontSize = 11.sp, color = subTextColor)
                                HorizontalDivider(color = dividerColor)
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedTextField(
                                        enabled = state.isAlarmMasterEnabled,
                                        value = state.dismissKeyText,
                                        onValueChange = actions.onDismissKeyChange,
                                        label = { Text("停止关键字") },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f),
                                        textStyle = TextStyle(fontSize = 13.sp)
                                    )
                                    OutlinedTextField(
                                        enabled = state.isAlarmMasterEnabled,
                                        value = state.snoozeKeyText,
                                        onValueChange = actions.onSnoozeKeyChange,
                                        label = { Text("延后关键字") },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f),
                                        textStyle = TextStyle(fontSize = 13.sp)
                                    )
                                }
                                HorizontalDivider(color = dividerColor)
                                Text("🧪 业务流联调测试面板", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = textColor)
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(
                                        enabled = state.isAlarmMasterEnabled,
                                        modifier = Modifier.weight(1f),
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828)),
                                        onClick = { actions.onAlarmTest("DISMISS") }
                                    ) { Text("模拟停止测试", color = Color.White, fontSize = 11.sp) }
                                    Button(
                                        enabled = state.isAlarmMasterEnabled,
                                        modifier = Modifier.weight(1f),
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE65100)),
                                        onClick = { actions.onAlarmTest("SNOOZE") }
                                    ) { Text("模拟延后测试", color = Color.White, fontSize = 11.sp) }
                                }
                            }
                        }
                    }
                }

                // Camera and Log Rows
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Card(
                            onClick = { isCameraExpanded = !isCameraExpanded; if (isCameraExpanded) isLogExpanded = false },
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
                            onClick = { isLogExpanded = !isLogExpanded; if (isLogExpanded) isCameraExpanded = false },
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

                    // Camera Expandable
                    AnimatedVisibility(visible = isCameraExpanded) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = cardBgColor)
                        ) {
                            Row(modifier = Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(containerColor = if (state.isCameraAllowed) Color(0xFF2E7D32) else Color(0xFF333333)),
                                    onClick = actions.onCameraCall
                                ) { Text("呼叫手表相机", color = Color.White, fontSize = 12.sp) }
                                Button(
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7F1D1D)),
                                    onClick = actions.onCameraForceStop
                                ) { Text("强制关闭相机", color = Color.White, fontSize = 12.sp) }
                            }
                            HorizontalDivider(color = dividerColor, modifier = Modifier.padding(horizontal = 14.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(14.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("拍照后发送预览到手表", color = textColor, fontSize = 14.sp)
                                    Text("开启后将自动压缩并发送缩略图，关闭可节省电量与带宽", color = subTextColor, fontSize = 11.sp)
                                }
                                Switch(checked = state.isWatchPreviewEnabled, onCheckedChange = actions.onWatchPreviewToggle)
                            }
                        }
                    }

                    // Log Expandable
                    AnimatedVisibility(visible = isLogExpanded) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = cardBgColor)
                        ) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Text("开启手机端调试日志", color = textColor, fontSize = 14.sp)
                                    Switch(checked = state.uiLogDebug, onCheckedChange = actions.onPhoneLogToggle)
                                }
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Text("同步监听手表端核心日志", color = textColor, fontSize = 14.sp)
                                    Switch(checked = state.uiWearLogDebug, onCheckedChange = actions.onWearLogToggle)
                                }
                                
                                HorizontalDivider(color = dividerColor)
                                
                                Button(
                                    modifier = Modifier.fillMaxWidth(),
                                    onClick = { actions.onFloatingLogClick() }
                                ) {
                                    Text(text = if (state.isServiceRunning) "关闭实时悬浮监视器" else "开启实时悬浮监视器", fontSize = 13.sp)
                                }
                            }
                        }
                    }
                }

                // Connection and Permission Rows
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Card(
                            onClick = { isConnectionExpanded = !isConnectionExpanded; if (isConnectionExpanded) isPermissionExpanded = false },
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
                                Text(text = if (state.isConnected) "🟢" else "🔴", fontSize = 14.sp)
                            }
                        }

                        Card(
                            onClick = { isPermissionExpanded = !isPermissionExpanded; if (isPermissionExpanded) isConnectionExpanded = false },
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
                                Text(text = if (state.isNotificationAllowed && state.isCameraAllowed) "🟢" else "⚠️", fontSize = 14.sp)
                            }
                        }
                    }

                    AnimatedVisibility(visible = isConnectionExpanded) {
                        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = cardBgColor)) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Text(
                                    text = if (state.isConnected) "骨干网络畅通。正在通过 GMS Wearable CapabilityClient 实时监听手表节点的动态响应。"
                                    else "未检测到处于活动状态的手表节点。请检查手表是否开机、蓝牙是否连接、或 Wear OS 专属配对 App 是否在后台运行。",
                                    fontSize = 13.sp, color = textColor, lineHeight = 18.sp
                                )
                            }
                        }
                    }

                    AnimatedVisibility(visible = isPermissionExpanded) {
                        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = cardBgColor)) {
                            Row(modifier = Modifier.padding(12.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Card(modifier = Modifier.weight(1f).height(110.dp), shape = RoundedCornerShape(8.dp), colors = CardDefaults.cardColors(containerColor = backgroundColor)) {
                                    Column(modifier = Modifier.padding(10.dp).fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                                        Column {
                                            Text("通知接管", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = textColor)
                                            Text(text = if (state.isNotificationAllowed) "核心状态已就绪" else "拦截状态必备", fontSize = 11.sp, color = if (state.isNotificationAllowed) Color(0xFF4CAF50) else Color(0xFFF44336))
                                        }
                                        if (!state.isNotificationAllowed) {
                                            Button(onClick = actions.onNotificationPermissionClick, contentPadding = PaddingValues(horizontal = 6.dp), modifier = Modifier.align(Alignment.End).height(26.dp)) {
                                                Text("去授权", fontSize = 10.sp)
                                            }
                                        } else {
                                            Text("🟢 已放行", fontSize = 11.sp, color = Color(0xFF4CAF50), modifier = Modifier.align(Alignment.End))
                                        }
                                    }
                                }
                                Card(modifier = Modifier.weight(1f).height(110.dp), shape = RoundedCornerShape(8.dp), colors = CardDefaults.cardColors(containerColor = backgroundColor)) {
                                    Column(modifier = Modifier.padding(10.dp).fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                                        Column {
                                            Text("相机硬件", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = textColor)
                                            Text(text = if (state.isCameraAllowed) "取景功能正常" else "取景控制必备", fontSize = 11.sp, color = if (state.isCameraAllowed) Color(0xFF4CAF50) else Color(0xFFF44336))
                                        }
                                        if (!state.isCameraAllowed) {
                                            Button(onClick = actions.onCameraPermissionClick, contentPadding = PaddingValues(horizontal = 6.dp), modifier = Modifier.align(Alignment.End).height(26.dp)) {
                                                Text("授相机", fontSize = 10.sp)
                                            }
                                        } else {
                                            Text("🟢 已放行", fontSize = 11.sp, color = Color(0xFF4CAF50), modifier = Modifier.align(Alignment.End))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // File Transfer Card
                Card(
                    onClick = { isFileTransferExpanded = !isFileTransferExpanded },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = cardBgColor)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column {
                                Text("跨端文件传输枢纽", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = textColor)
                                Text("双向传输照片、音频及自定义配置文件", fontSize = 12.sp, color = subTextColor)
                            }
                            Text(text = "📤", fontSize = 14.sp, color = textColor)
                        }
                        AnimatedVisibility(visible = isFileTransferExpanded) {
                            Column(modifier = Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                HorizontalDivider(color = dividerColor)
                                Button(onClick = actions.onFileTransferClick, modifier = Modifier.fillMaxWidth()) { Text("选择并发送文件") }
                                Text(
                                    text = state.fileTransferStatus,
                                    fontSize = 12.sp,
                                    color = if (state.fileTransferStatus.contains("成功")) Color(0xFF4CAF50) else if (state.fileTransferStatus.contains("失败") || state.fileTransferStatus.contains("错误")) Color(0xFFF44336) else subTextColor,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                }

                // Vibration Feedback Card
                Card(
                    onClick = { isVibrationExpanded = !isVibrationExpanded },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = cardBgColor)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
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
                                    OutlinedButton(
                                        onClick = { actions.onVibrationCommand("preview", patternOnDuration, patternOffDuration, repeatIndex) },
                                        modifier = Modifier.weight(1f)
                                    ) { Text("📳 预览") }
                                    Button(
                                        modifier = Modifier.weight(1f),
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50)),
                                        onClick = { actions.onVibrationSave(patternOnDuration, patternOffDuration, repeatIndex) }
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

class PhoneSyncMainFragment : Fragment(), MessageClient.OnMessageReceivedListener {
    companion object {
        private const val TAG = "PhoneSyncMainFragment"
    }

    private val isNotificationAllowedState = mutableStateOf(false)
    private val isCameraAllowedState = mutableStateOf(false)
    private val isConnectedState = mutableStateOf(false)
    private var capabilityChangedListener: CapabilityClient.OnCapabilityChangedListener? = null
    private val UNIVERSAL_SYNC_PATH = "/wear-universal-sync"

    private val uiLogDebugSwitch = mutableStateOf(false)
    private val uiWearLogDebugSwitch = mutableStateOf(false)
    private val isWatchPreviewEnabledState = mutableStateOf(true)
    private val fileTransferStatus = mutableStateOf("等待选择文件...")

    private val overlayPermissionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (Settings.canDrawOverlays(requireContext())) {
            handleFloatingLogClick()
        }
    }

    private val alarmPickerLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            val pkg = it.data?.getStringExtra("selected_alarm_package")
            val name = it.data?.getStringExtra("selected_alarm_name")

            if (pkg != null) {
                requireContext()
                    .getSharedPreferences("dndsync_prefs", Context.MODE_PRIVATE)
                    .edit {
                        putString("selected_alarm_package", pkg)
                        putString("selected_alarm_name", name ?: pkg)
                    }
            }
        }

    private fun sendVibrationCommand(action: String, onDuration: Int, offDuration: Int, repeat: Int) {
        val nodeId = WearSyncState.getNodeId(requireContext())
        if (nodeId.isNullOrEmpty()) {
            PhoneLog.w(TAG, "⚠️ 无法发送震动指令：手表未连接")
            return
        }

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val stopJson = JSONObject().apply {
                    put("sender", "phone")
                    put("type", "vibration")
                    put("action", "stop")
                    put("timestamp", System.currentTimeMillis())
                }
                Wearable.getMessageClient(requireContext()).sendMessage(
                    nodeId, UNIVERSAL_SYNC_PATH, stopJson.toString().toByteArray(StandardCharsets.UTF_8)
                ).await()

                if (action == "preview" || action == "save") {
                    val configJson = JSONObject().apply {
                        put("mode", 0)
                        put("onDuration", onDuration)
                        put("offDuration", offDuration)
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

    private val fileTransferLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            val nodeId = WearSyncState.getNodeId(requireContext())
            if (!nodeId.isNullOrEmpty()) {
                val fileName = requireContext().contentResolver.query(it, null, null, null, null)?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    cursor.moveToFirst()
                    cursor.getString(nameIndex)
                } ?: "unknown_file"

                PhoneSyncFileTransferManager.sendFileToWear(
                    requireContext(),
                    nodeId,
                    it,
                    fileName,
                    object : PhoneSyncFileTransferManager.TransferCallback {
                        override fun onHandshakeSuccess() {
                            // 握手成功，不需要立刻设为成功，等待后续 status 变化
                            PhoneLog.d(TAG, "🤝 文件传输握手成功")
                        }

                        override fun onStatusUpdate(status: String) {
                            fileTransferStatus.value = status
                        }

                        override fun onError(message: String) {
                            fileTransferStatus.value = "❌ 传输失败: $message"
                        }
                    }
                )
            } else {
                fileTransferStatus.value = "❌ 错误：手表未连接"
            }
        }
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        // Body status logic removed
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val sp = requireContext().getSharedPreferences("dndsync_prefs", Context.MODE_PRIVATE)
        uiLogDebugSwitch.value = sp.getBoolean("phone_log_debug_visible", false)
        uiWearLogDebugSwitch.value = sp.getBoolean("wear_log_debug_visible", false)
        isWatchPreviewEnabledState.value = sp.getBoolean("camera_watch_preview_enabled", true)

        return ComposeView(requireContext()).apply {
            setContent {
                val isConnectedVal by isConnectedState
                val isNotificationAllowedVal by isNotificationAllowedState
                val isCameraAllowedVal by isCameraAllowedState
                val fileTransferStatusVal by fileTransferStatus
                val uiLogDebugVal by uiLogDebugSwitch
                val uiWearLogDebugVal by uiWearLogDebugSwitch
                val isWatchPreviewEnabledVal by isWatchPreviewEnabledState
                val isServiceRunningVal by AppState.isServiceRunning.collectAsState()
                
                var mask by remember { mutableIntStateOf(sp.getInt("KEY_MASK", 15)) }
                var screenPullDownInterval by remember { mutableIntStateOf(sp.getInt("screen_pull_down_interval", 500)) }
                var selectedAlarmName by remember {
                    mutableStateOf(sp.getString("selected_alarm_name", "Google 时钟") ?: "Google 时钟")
                }
                var selectedAlarmPkg by remember {
                    mutableStateOf(sp.getString("selected_alarm_package", "com.google.android.deskclock") ?: "com.google.android.deskclock")
                }
                var dismissKeyText by remember {
                    mutableStateOf(sp.getString("alarm_dismiss_key", "停止") ?: "停止")
                }
                var snoozeKeyText by remember {
                    mutableStateOf(sp.getString("alarm_snooze_key", "延后") ?: "延后")
                }
                var isAlarmMasterEnabled by remember {
                    mutableStateOf(sp.getBoolean("alarm_proxy_master_switch", true))
                }

                PhoneSyncMainScreen(
                    state = PhoneSyncUIState(
                        isConnected = isConnectedVal,
                        isNotificationAllowed = isNotificationAllowedVal,
                        isCameraAllowed = isCameraAllowedVal,
                        isServiceRunning = isServiceRunningVal,
                        isWatchPreviewEnabled = isWatchPreviewEnabledVal,
                        fileTransferStatus = fileTransferStatusVal,
                        uiLogDebug = uiLogDebugVal,
                        uiWearLogDebug = uiWearLogDebugVal,
                        screenPullDownInterval = screenPullDownInterval,
                        mask = mask,
                        selectedAlarmName = selectedAlarmName,
                        selectedAlarmPkg = selectedAlarmPkg,
                        dismissKeyText = dismissKeyText,
                        snoozeKeyText = snoozeKeyText,
                        isAlarmMasterEnabled = isAlarmMasterEnabled
                    ),
                    actions = PhoneSyncUIActions(
                        onPullDownIntervalChange = { newValue ->
                            screenPullDownInterval = newValue
                            sp.edit { putInt("screen_pull_down_interval", newValue) }
                        },
                        onDndMaskUpdate = { newMaster, newVibrate, newSleep, newPower ->
                            var newMask = 0
                            if (newMaster) newMask = newMask or 1
                            if (newVibrate) newMask = newMask or 2
                            if (newSleep) newMask = newMask or 4
                            if (newPower) newMask = newMask or 8
                            mask = newMask
                            sp.edit { putInt("KEY_MASK", newMask) }
                        },
                        onVibrationCommand = { action, on, off, repeat ->
                            sendVibrationCommand(action, on, off, repeat)
                        },
                        onVibrationSave = { on, off, repeat ->
                            val prefs = requireContext().getSharedPreferences("wear_vibration_prefs", Context.MODE_PRIVATE)
                            prefs.edit {
                                putInt("onDuration", on)
                                putInt("offDuration", off)
                                putInt("repeatIndex", repeat)
                            }
                            sendVibrationCommand("save", on, off, repeat)
                        },
                        onAlarmSourceSwitch = {
                            PhoneSyncAppPicker.show(requireContext()) { pkg, name ->
                                selectedAlarmName = name
                                selectedAlarmPkg = pkg
                                sp.edit {
                                    putString("selected_alarm_package", pkg)
                                    putString("selected_alarm_name", name)
                                }
                            }
                        },
                        onAlarmMasterToggle = { isEnabled ->
                            isAlarmMasterEnabled = isEnabled
                            sp.edit { putBoolean("alarm_proxy_master_switch", isEnabled) }
                        },
                        onDismissKeyChange = {
                            dismissKeyText = it
                            sp.edit { putString("alarm_dismiss_key", it) }
                        },
                        onSnoozeKeyChange = {
                            snoozeKeyText = it
                            sp.edit { putString("alarm_snooze_key", it) }
                        },
                        onAlarmTest = { action ->
                            try {
                                PhoneAlarmManager.handleWatchCommand(requireContext(), action)
                            } catch (_: Exception) { }
                        },
                        onCameraCall = {
                            if (!isCameraAllowedVal) {
                                requestCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                            } else {
                                val localIntent = Intent(requireContext(), PhoneSyncRemoteCameraActivity::class.java).apply {
                                    putExtra(PhoneSyncRemoteCameraActivity.EXTRA_SOURCE, PhoneSyncRemoteCameraActivity.SOURCE_LOCAL)
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                requireContext().startActivity(localIntent)

                                val nodeId = WearSyncState.getNodeId(requireContext())
                                if (!nodeId.isNullOrEmpty()) {
                                    viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                                        try {
                                            val json = JSONObject().apply {
                                                put("sender", "phone")
                                                put("type", "camera_control")
                                                put("action", "open_phone_camera")
                                                put("timestamp", System.currentTimeMillis())
                                            }
                                            Wearable.getMessageClient(requireContext()).sendMessage(
                                                nodeId, UNIVERSAL_SYNC_PATH, json.toString().toByteArray(StandardCharsets.UTF_8)
                                            ).await()
                                        } catch (_: Exception) { }
                                    }
                                }
                            }
                        },
                        onCameraForceStop = {
                            requireContext().startService(Intent(requireContext(), PhoneSyncCameraService::class.java)
                                .setAction(PhoneSyncCameraService.ACTION_STOP_CAMERA))

                            val nodeId = WearSyncState.getNodeId(requireContext())
                            if (!nodeId.isNullOrEmpty()) {
                                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                                    try {
                                        val json = JSONObject().apply {
                                            put("sender", "phone")
                                            put("type", "camera_control")
                                            put("action", "FORCE_QUIT_CAMERA")
                                            put("timestamp", System.currentTimeMillis())
                                        }
                                        Wearable.getMessageClient(requireContext()).sendMessage(
                                            nodeId, UNIVERSAL_SYNC_PATH, json.toString().toByteArray(StandardCharsets.UTF_8)
                                        ).await()
                                    } catch (_: Exception) { }
                                }
                            }
                        },
                        onPhoneLogToggle = { isEnabled ->
                            uiLogDebugSwitch.value = isEnabled
                            PhoneLog.DEBUG = isEnabled
                            sp.edit { putBoolean("phone_log_debug_visible", isEnabled) }
                        },
                        onWearLogToggle = { isChecked ->
                            uiWearLogDebugSwitch.value = isChecked
                            sp.edit { putBoolean("wear_log_debug_visible", isChecked) }
                            val context = requireContext()
                            val nodeId = WearSyncState.getNodeId(context)
                            if (!nodeId.isNullOrEmpty()) {
                                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                                    try {
                                        val msgJson = JSONObject().apply {
                                            put("type", "wearlog")
                                            put("wear_log_debug", isChecked)
                                            put("timestamp", System.currentTimeMillis())
                                        }
                                        Wearable.getMessageClient(context).sendMessage(
                                            nodeId, UNIVERSAL_SYNC_PATH, msgJson.toString().toByteArray(StandardCharsets.UTF_8)
                                        ).await()
                                    } catch (_: Exception) { }
                                }
                            }
                        },
                        onWatchPreviewToggle = { isEnabled ->
                            isWatchPreviewEnabledState.value = isEnabled
                            sp.edit { putBoolean("camera_watch_preview_enabled", isEnabled) }
                        },
                        onFloatingLogClick = { handleFloatingLogClick() },
                        onNotificationPermissionClick = {
                            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                        },
                        onCameraPermissionClick = {
                            requestCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                        },
                        onFileTransferClick = {
                            fileTransferLauncher.launch("*/*")
                        }
                    )
                )
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

    private fun handleFloatingLogClick() {
        val context = requireContext()
        if (!Settings.canDrawOverlays(context)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}")
            )
            overlayPermissionLauncher.launch(intent)
        } else {
            val intent = Intent(context, PhoneLogFloatingService::class.java)
            if (AppState.isServiceRunning.value) {
                context.stopService(intent)
            } else {
                context.startService(intent)
            }
        }
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
        Wearable.getCapabilityClient(context).getCapability("wear_sync", CapabilityClient.FILTER_REACHABLE).addOnSuccessListener {
            isConnectedState.value = it.nodes.isNotEmpty()
        }
        capabilityChangedListener = CapabilityClient.OnCapabilityChangedListener {
            isConnectedState.value = it.nodes.isNotEmpty()
        }
        capabilityChangedListener?.let { Wearable.getCapabilityClient(context).addListener(it, "wear_sync") }
    }

    private fun unregisterConnectivityListener() {
        capabilityChangedListener?.let {
            Wearable.getCapabilityClient(requireContext()).removeListener(it)
        }
    }
}
