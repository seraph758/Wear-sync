package cn.luke.wearsync

import android.Manifest
import android.app.Activity
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
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import androidx.core.view.WindowCompat
import androidx.core.net.toUri
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.animation.animateContentSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import kotlinx.coroutines.tasks.await
import org.json.JSONObject
import java.nio.charset.StandardCharsets


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
    val isHeifFallbackEnabled: Boolean = true,
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
    val onHeifFallbackToggle: (Boolean) -> Unit = {},
    val onFloatingLogClick: () -> Unit = {},
    val onConnectionClick: () -> Unit = {},
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
fun GlowCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    glowColor: Color = Color.Transparent,
    content: @Composable ColumnScope.() -> Unit
) {
    val isDark = isSystemInDarkTheme()
    val baseColor = if (isDark) Color(0xFF1E1E24) else Color(0xFFFFFFFF)
    
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = baseColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // 🎯 光照效果：底部圆环渐变
            Box(
                modifier = Modifier
                    .size(100.dp)
                    .align(Alignment.BottomEnd)
                    .offset(x = 30.dp, y = 30.dp)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(glowColor.copy(alpha = if (isDark) 0.15f else 0.1f), Color.Transparent),
                            center = Offset(50f, 50f)
                        )
                    )
            )
            
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .fillMaxSize(),
                content = content
            )
        }
    }
}

@Composable
fun StatusItem(
    title: String,
    icon: String,
    isActive: Boolean,
    onClick: () -> Unit
) {
    val isDark = isSystemInDarkTheme()
    Column(
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(text = icon, fontSize = 24.sp)
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = title,
            fontSize = 11.sp,
            color = if (isDark) Color.White else Color.Black,
            fontWeight = FontWeight.Medium
        )
        Spacer(modifier = Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .size(6.dp)
                .background(
                    if (isActive) Color(0xFF4CAF50) else Color(0xFFF44336),
                    CircleShape
                )
        )
    }
}

@Composable
fun PhoneSyncMainScreen(
    state: PhoneSyncUIState,
    actions: PhoneSyncUIActions
) {
    val context = LocalContext.current
    val isDark = isSystemInDarkTheme()

    // 状态栏美化
    SideEffect {
        val window = (context as? Activity)?.window ?: return@SideEffect
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.isAppearanceLightStatusBars = !isDark
    }

    MaterialTheme {
        val backgroundColor = if (isDark) Color(0xFF000000) else Color(0xFFF8F9FA)
        val textColor = if (isDark) Color.White else Color(0xFF1A1A1A)
        val cardBgColor = if (isDark) Color(0xFF1C1C1E) else Color(0xFFFFFFFF)

        // 展开状态管理
        var isVibrationExpanded by remember { mutableStateOf(false) }
        var isDndExpanded by remember { mutableStateOf(false) }
        var isAlarmExpanded by remember { mutableStateOf(false) }
        var isCameraExpanded by remember { mutableStateOf(false) }
        var isLogExpanded by remember { mutableStateOf(false) }
        var isFileTransferExpanded by remember { mutableStateOf(false) }

        // 状态与权限面板自动展开逻辑
        val hasError = !state.isConnected || !state.isNotificationAllowed || !state.isCameraAllowed
        var isStatusExpanded by remember { mutableStateOf(hasError) }
        
        // 当发生错误时自动展开
        LaunchedEffect(hasError) {
            if (hasError) isStatusExpanded = true
        }

        Surface(
            modifier = Modifier.fillMaxSize(),
            color = backgroundColor
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .padding(horizontal = 20.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // === 1. 顶部标题 ===
                Row(
                    modifier = Modifier.padding(top = 24.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = buildAnnotatedString {
                            withStyle(SpanStyle(color = if (isDark) Color.White else Color.Black)) {
                                append("Wear")
                            }
                            withStyle(SpanStyle(color = Color(0xFF4CAF50))) {
                                append("Sync")
                            }
                        },
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = (-1).sp
                    )
                }

                // === 2. 状态与权限卡片 ===
                Card(
                    modifier = Modifier.fillMaxWidth().animateContentSize(),
                    shape = RoundedCornerShape(28.dp),
                    colors = CardDefaults.cardColors(containerColor = cardBgColor),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                    onClick = { isStatusExpanded = !isStatusExpanded }
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier.size(48.dp).background(if (isDark) Color(0xFF2C2C2E) else Color(0xFFF2F2F7), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(text = if (hasError) "🛡️" else "✅", fontSize = 24.sp)
                            }
                            Spacer(modifier = Modifier.width(16.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text("状态与权限", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = textColor)
                                Text(
                                    text = if (state.isConnected) "设备已连接" else "未发现已连接设备",
                                    fontSize = 13.sp,
                                    color = if (state.isConnected) Color(0xFF4CAF50) else Color(0xFFF44336)
                                )
                            }
                            Box(
                                modifier = Modifier.size(24.dp).background(if (hasError) Color(0xFFF44336) else Color(0xFF4CAF50), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(text = if (hasError) "!" else "✓", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        AnimatedVisibility(visible = isStatusExpanded) {
                            Column {
                                Spacer(modifier = Modifier.height(24.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceAround
                                ) {
                                    StatusItem("手表", "⌚", state.isConnected, actions.onConnectionClick)
                                    StatusItem("通知权限", "🔔", state.isNotificationAllowed, actions.onNotificationPermissionClick)
                                    StatusItem("摄像机权限", "📷", state.isCameraAllowed, actions.onCameraPermissionClick)
                                }
                            }
                        }
                    }
                }

                // === 3. 功能网格 ===
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    // 第一行
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        GlowCard(
                            modifier = Modifier.weight(1f),
                            onClick = { isDndExpanded = !isDndExpanded },
                            glowColor = Color(0xFF9C27B0)
                        ) {
                            FeatureHeader("勿扰同步", "状态控制器", "🌙", actions = isDndExpanded)
                        }
                        GlowCard(
                            modifier = Modifier.weight(1f),
                            onClick = { isAlarmExpanded = !isAlarmExpanded },
                            glowColor = Color(0xFFF44336)
                        ) {
                            FeatureHeader("闹钟代点", "Google 时钟", "⏰", actions = isAlarmExpanded)
                        }
                    }
                    
                    // 展开内容
                    ExpandableContent(isDndExpanded) {
                        DndSettingsContent(state, actions, isDark)
                    }
                    ExpandableContent(isAlarmExpanded) {
                        AlarmSettingsContent(state, actions, isDark)
                    }

                    // 第二行
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        GlowCard(
                            modifier = Modifier.weight(1f),
                            onClick = { isCameraExpanded = !isCameraExpanded },
                            glowColor = Color(0xFF2196F3)
                        ) {
                            FeatureHeader("相机中心", "全远端取景", "📷", actions = isCameraExpanded)
                        }
                        GlowCard(
                            modifier = Modifier.weight(1f),
                            onClick = { isLogExpanded = !isLogExpanded },
                            glowColor = Color(0xFFFFC107)
                        ) {
                            FeatureHeader("调试终端", "底层日志监控", "📝", actions = isLogExpanded)
                        }
                    }
                    
                    ExpandableContent(isCameraExpanded) {
                        CameraSettingsContent(state, actions, isDark)
                    }
                    ExpandableContent(isLogExpanded) {
                        LogSettingsContent(state, actions, isDark)
                    }

                    // 第三行
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        GlowCard(
                            modifier = Modifier.weight(1f),
                            onClick = { isFileTransferExpanded = !isFileTransferExpanded },
                            glowColor = Color(0xFFFF9800)
                        ) {
                            FeatureHeader("文件传输", "双向枢纽", "📂", actions = isFileTransferExpanded)
                        }
                        GlowCard(
                            modifier = Modifier.weight(1f),
                            onClick = { isVibrationExpanded = !isVibrationExpanded },
                            glowColor = Color(0xFF4CAF50)
                        ) {
                            FeatureHeader("震动反馈", "波形定义", "📳", actions = isVibrationExpanded)
                        }
                    }
                    
                    ExpandableContent(isFileTransferExpanded) {
                        FileTransferContent(state, actions, isDark)
                    }
                    ExpandableContent(isVibrationExpanded) {
                        VibrationContent(actions)
                    }
                }
                
                Spacer(modifier = Modifier.height(40.dp))
            }
        }
    }
}

@Composable
fun FeatureHeader(title: String, subtitle: String, icon: String, actions: Boolean) {
    val textColor = if (isSystemInDarkTheme()) Color.White else Color.Black
    val subTextColor = if (isSystemInDarkTheme()) Color(0xFF8E8E93) else Color(0xFF636366)
    
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = textColor)
                Spacer(modifier = Modifier.width(4.dp))
                Text(text = if (actions) "▾" else "›", fontSize = 14.sp, color = subTextColor)
            }
            Text(subtitle, fontSize = 12.sp, color = subTextColor)
        }
        Box(
            modifier = Modifier.size(40.dp).background(if (isSystemInDarkTheme()) Color(0xFF2C2C2E) else Color(0xFFF2F2F7), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(text = icon, fontSize = 20.sp)
        }
    }
}

@Composable
fun ExpandableContent(visible: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(visible = visible) {
        Card(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = if (isSystemInDarkTheme()) Color(0xFF1C1C1E) else Color.White),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
        ) {
            content()
        }
    }
}

// 抽取子内容组件以保持主结构清晰
@Composable
fun DndSettingsContent(state: PhoneSyncUIState, actions: PhoneSyncUIActions, isDark: Boolean) {
    val masterOn = (state.mask and 1) != 0
    val vibrateOn = (state.mask and 2) != 0
    val sleepOn = (state.mask and 4) != 0
    val powerOn = (state.mask and 8) != 0
    val textColor = if (isDark) Color.White else Color.Black

    Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Text("启用勿扰主同步", fontWeight = FontWeight.Bold, color = textColor)
            Switch(checked = masterOn, onCheckedChange = { actions.onDndMaskUpdate(it, vibrateOn, sleepOn, powerOn) })
        }
        if (masterOn) {
            HorizontalDivider(color = if (isDark) Color(0xFF2C2C2E) else Color(0xFFE5E5EA))
            // ✅ 使用 state.mask 来动态获取值，消除 IDE 对 "always true" 变量的警告
            SettingsToggle("手表端震动反馈", vibrateOn) { actions.onDndMaskUpdate((state.mask and 1) != 0, it, sleepOn, powerOn) }
            SettingsToggle("睡眠模式同步", sleepOn) { actions.onDndMaskUpdate((state.mask and 1) != 0, vibrateOn, it, powerOn) }
            SettingsToggle("省电模式同步", powerOn) { actions.onDndMaskUpdate((state.mask and 1) != 0, vibrateOn, sleepOn, it) }
        }
    }
}

@Composable
fun SettingsToggle(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
        Text(label, fontSize = 14.sp, color = if (isSystemInDarkTheme()) Color.White else Color.Black)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

// ... 这里的其他子内容组件根据需要可以继续完善，先保证整体结构和要求实现

@Composable
fun AlarmSettingsContent(state: PhoneSyncUIState, actions: PhoneSyncUIActions, isDark: Boolean) {
    val dividerColor = if (isDark) Color(0xFF2C2C2E) else Color(0xFFE5E5EA)
    val textColor = if (isDark) Color.White else Color.Black

    Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Text("闹钟主同步", fontWeight = FontWeight.Bold, color = textColor)
            Switch(checked = state.isAlarmMasterEnabled, onCheckedChange = actions.onAlarmMasterToggle)
        }
        if (state.isAlarmMasterEnabled) {
            HorizontalDivider(color = dividerColor)
            Button(
                onClick = actions.onAlarmSourceSwitch,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) { Text("切换时钟源: ${state.selectedAlarmName}") }
            
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = state.dismissKeyText,
                    onValueChange = actions.onDismissKeyChange,
                    label = { Text("停止关键字") },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                )
                OutlinedTextField(
                    value = state.snoozeKeyText,
                    onValueChange = actions.onSnoozeKeyChange,
                    label = { Text("延后关键字") },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                )
            }
        }
    }
}

@Composable
fun CameraSettingsContent(state: PhoneSyncUIState, actions: PhoneSyncUIActions, isDark: Boolean) {
    val dividerColor = if (isDark) Color(0xFF2C2C2E) else Color(0xFFE5E5EA)

    Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = actions.onCameraCall,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50)),
                shape = RoundedCornerShape(12.dp)
            ) { Text("呼叫手表相机") }
            Button(
                onClick = actions.onCameraForceStop,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF44336)),
                shape = RoundedCornerShape(12.dp)
            ) { Text("强制关闭") }
        }
        HorizontalDivider(color = dividerColor)
        SettingsToggle("启用 HEIF 高质量压缩", state.isHeifFallbackEnabled, actions.onHeifFallbackToggle)
    }
}

@Composable
fun LogSettingsContent(state: PhoneSyncUIState, actions: PhoneSyncUIActions, isDark: Boolean) {
    val dividerColor = if (isDark) Color(0xFF2C2C2E) else Color(0xFFE5E5EA)

    Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SettingsToggle("手机端调试日志", state.uiLogDebug, actions.onPhoneLogToggle)
        SettingsToggle("同步监听手表日志", state.uiWearLogDebug, actions.onWearLogToggle)
        HorizontalDivider(color = dividerColor)
        Button(
            onClick = actions.onFloatingLogClick,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text(if (state.isServiceRunning) "关闭悬浮监视器" else "开启悬浮监视器")
        }
    }
}

@Composable
fun FileTransferContent(state: PhoneSyncUIState, actions: PhoneSyncUIActions, isDark: Boolean) {
    val textColor = if (isDark) Color.White else Color.Black
    val subTextColor = if (isDark) Color(0xFF8E8E93) else Color(0xFF636366)

    Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("文件传输中心", fontWeight = FontWeight.Bold, color = textColor)
        Text("选择照片或文件同步到手表端", fontSize = 12.sp, color = subTextColor)
        Button(
            onClick = actions.onFileTransferClick,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        ) { Text("选择文件并发送") }
        Text(state.fileTransferStatus, fontSize = 11.sp, color = subTextColor, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
fun VibrationContent(actions: PhoneSyncUIActions) {
    val isDark = isSystemInDarkTheme()
    val textColor = if (isDark) Color.White else Color.Black
    var onDuration by remember { mutableIntStateOf(500) }
    var offDuration by remember { mutableIntStateOf(200) }

    Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("震动波形自定义", fontWeight = FontWeight.Bold, color = textColor)
        Text("震动时长: ${onDuration}ms", fontSize = 13.sp, color = textColor)
        Slider(value = onDuration.toFloat(), onValueChange = { onDuration = it.toInt() }, valueRange = 100f..2000f)
        Text("间隔时长: ${offDuration}ms", fontSize = 13.sp, color = textColor)
        Slider(value = offDuration.toFloat(), onValueChange = { offDuration = it.toInt() }, valueRange = 100f..1000f)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                onClick = { actions.onVibrationCommand("preview", onDuration, offDuration, -1) },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp)
            ) { Text("预览") }
            Button(
                onClick = { actions.onVibrationSave(onDuration, offDuration, -1) },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50)),
                shape = RoundedCornerShape(12.dp)
            ) { Text("保存") }
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
    private val isHeifFallbackEnabledState = mutableStateOf(true)
    private val fileTransferStatus = mutableStateOf("等待选择文件...")

    private val overlayPermissionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (Settings.canDrawOverlays(requireContext())) {
            handleFloatingLogClick()
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
        isHeifFallbackEnabledState.value = sp.getBoolean("heif_fallback_enabled", true)

        return ComposeView(requireContext()).apply {
            setContent {
                val isConnectedVal by isConnectedState
                val isNotificationAllowedVal by isNotificationAllowedState
                val isCameraAllowedVal by isCameraAllowedState
                val fileTransferStatusVal by fileTransferStatus
                val uiLogDebugVal by uiLogDebugSwitch
                val uiWearLogDebugVal by uiWearLogDebugSwitch
                val isWatchPreviewEnabledVal by isWatchPreviewEnabledState
                val isHeifFallbackEnabledVal by isHeifFallbackEnabledState
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
                        isHeifFallbackEnabled = isHeifFallbackEnabledVal,
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
                        onHeifFallbackToggle = { isEnabled ->
                            isHeifFallbackEnabledState.value = isEnabled
                            sp.edit { putBoolean("heif_fallback_enabled", isEnabled) }
                        },
                        onFloatingLogClick = { handleFloatingLogClick() },
                        onConnectionClick = {
                            Toast.makeText(requireContext(), "手表未连接，请检查蓝牙和WiFi设置", Toast.LENGTH_SHORT).show()
                        },
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
                "package:${context.packageName}".toUri()
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
