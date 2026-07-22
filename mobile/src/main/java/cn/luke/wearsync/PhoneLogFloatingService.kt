package cn.luke.wearsync

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.view.Gravity
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.Button
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.*
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.delay
import java.io.File
import kotlin.math.max
import kotlin.math.min

// 🎨 现代化配色系统
private object LogColors {
    val windowBg = Color(0xFF121212)
    val headerBg = Color(0xFF1E1E1E)
    val splitterIdle = Color(0xFF2C2C2C)
    val splitterActive = Color(0xFF00B0FF)
    
    // 手机日志：绿色系
    val phonePanelBg = Color(0xFF0D1A0F)
    val phoneText = Color(0xFF4ADE80)
    val phoneTag = Color(0xFF22572A)
    
    // 手表日志：蓝色系
    val wearPanelBg = Color(0xFF0A1628)
    val wearText = Color(0xFF60A5FA)
    val wearTag = Color(0xFF1E3A5F)
    
    // 通用语义色
    val errorText = Color(0xFFFF6B6B)
    val testText = Color(0xFFE879F9)
    val dimText = Color.White.copy(alpha = 0.4f)
}

class PhoneLogFloatingService : Service(), SavedStateRegistryOwner {

    private lateinit var windowManager: WindowManager
    private var floatingView: ComposeView? = null
    private lateinit var windowParams: WindowManager.LayoutParams

    private val controller = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry = controller.savedStateRegistry

    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        controller.performRestore(null)
        super.onCreate()

        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val layoutType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

        windowParams = WindowManager.LayoutParams(
            1000, 900,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or 
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 50
            y = 200
        }

        floatingView = ComposeView(this).apply {
            val viewModelStore = ViewModelStore()
            setViewTreeLifecycleOwner(this@PhoneLogFloatingService)
            setViewTreeViewModelStoreOwner(object : ViewModelStoreOwner {
                override val viewModelStore: ViewModelStore = viewModelStore
            })
            setViewTreeSavedStateRegistryOwner(this@PhoneLogFloatingService)

            setContent {
                var phoneLogs by remember { mutableStateOf(listOf<String>()) }
                var wearLogs by remember { mutableStateOf(listOf<String>()) }
                val phoneListState = rememberLazyListState()
                val wearListState = rememberLazyListState()

                var splitRatio by remember { mutableFloatStateOf(0.5f) }
                var isDraggingSplitter by remember { mutableStateOf(false) }

                LaunchedEffect(Unit) {
                    while (true) {
                        val freshLogs = PhoneLog.getLatestTenMinutesLogs()
                        val newPhone = freshLogs.filter { !it.contains("[WEAR]") }
                        val newWear = freshLogs.filter { it.contains("[WEAR]") }
                        if (phoneLogs.size != newPhone.size) phoneLogs = newPhone
                        if (wearLogs.size != newWear.size) wearLogs = newWear
                        delay(400)
                    }
                }

                LaunchedEffect(phoneLogs.size) {
                    if (phoneLogs.isNotEmpty()) phoneListState.animateScrollToItem(phoneLogs.lastIndex)
                }
                LaunchedEffect(wearLogs.size) {
                    if (wearLogs.isNotEmpty()) wearListState.animateScrollToItem(wearLogs.lastIndex)
                }

                // 🪟 窗口容器：带圆角和边框
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(12.dp))
                        .background(LogColors.windowBg)
                        .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
                ) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        
                        // === 🖐️ 全区域可拖拽标题栏 ===
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(36.dp)
                                .background(LogColors.headerBg)
                                .pointerInput(Unit) {
                                    detectDragGestures { change, dragAmount ->
                                        change.consume()
                                        windowParams.x += dragAmount.x.toInt()
                                        windowParams.y += dragAmount.y.toInt()
                                        try { windowManager.updateViewLayout(floatingView, windowParams) } catch (_: Exception) {}
                                    }
                                }
                                .padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("⚡ WearSync Monitor", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            Text("拖动此处移动", color = LogColors.dimText, fontSize = 10.sp)
                        }

                        // === 📱 上半区：手机日志 ===
                        Box(
                            modifier = Modifier
                                .weight(splitRatio)
                                .fillMaxWidth()
                                .background(LogColors.phonePanelBg)
                        ) {
                            androidx.compose.foundation.text.selection.SelectionContainer {
                                LazyColumn(
                                    state = phoneListState,
                                    modifier = Modifier.fillMaxSize(),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                                ) {
                                    items(phoneLogs.size) { index ->
                                        LogLineItem(line = phoneLogs[index], defaultColor = LogColors.phoneText)
                                    }
                                }
                            }
                            // 面板标签
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(6.dp)
                                    .background(LogColors.phoneTag, RoundedCornerShape(4.dp))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text("📱 PHONE", color = LogColors.phoneText, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        // === ↔️ 可拖拽分割条 ===
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(if (isDraggingSplitter) 16.dp else 8.dp)
                                .background(if (isDraggingSplitter) LogColors.splitterActive.copy(alpha = 0.3f) else LogColors.splitterIdle)
                                .pointerInput(Unit) {
                                    detectDragGestures(
                                        onDragStart = { isDraggingSplitter = true },
                                        onDragEnd = { isDraggingSplitter = false },
                                        onDragCancel = { isDraggingSplitter = false }
                                    ) { change, dragAmount ->
                                        change.consume()
                                        val currentHeight = windowParams.height.toFloat()
                                        val deltaRatio = dragAmount.y / currentHeight
                                        splitRatio = min(0.8f, max(0.2f, splitRatio + deltaRatio))
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            // 分割条把手指示器
                            Box(
                                modifier = Modifier
                                    .width(40.dp)
                                    .height(3.dp)
                                    .background(
                                        if (isDraggingSplitter) LogColors.splitterActive else Color.White.copy(alpha = 0.2f),
                                        RoundedCornerShape(2.dp)
                                    )
                            )
                        }

                        // === ⌚ 下半区：手表日志 ===
                        Box(
                            modifier = Modifier
                                .weight(1f - splitRatio)
                                .fillMaxWidth()
                                .background(LogColors.wearPanelBg)
                        ) {
                            androidx.compose.foundation.text.selection.SelectionContainer {
                                LazyColumn(
                                    state = wearListState,
                                    modifier = Modifier.fillMaxSize(),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                                ) {
                                    items(wearLogs.size) { index ->
                                        LogLineItem(line = wearLogs[index], defaultColor = LogColors.wearText)
                                    }
                                }
                            }
                            // 面板标签
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(6.dp)
                                    .background(LogColors.wearTag, RoundedCornerShape(4.dp))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text("⌚ WEAR", color = LogColors.wearText, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        // === 🔧 底部操作栏 ===
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(40.dp)
                                .background(LogColors.headerBg)
                                .padding(horizontal = 6.dp),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Button(
                                    onClick = { PhoneLog.clear(); phoneLogs = emptyList(); wearLogs = emptyList() },
                                    contentPadding = PaddingValues(horizontal = 10.dp),
                                    modifier = Modifier.height(28.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3A3A3C))
                                ) { Text("清空", fontSize = 11.sp) }

                                Button(
                                    onClick = {
                                        startActivity(Intent(this@PhoneLogFloatingService, PhoneLogActivity::class.java).apply {
                                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                                        })
                                        stopSelf()
                                    },
                                    contentPadding = PaddingValues(horizontal = 10.dp),
                                    modifier = Modifier.height(28.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = LogColors.splitterActive)
                                ) { Text("全屏", fontSize = 11.sp, color = Color.Black, fontWeight = FontWeight.Bold) }

                                Button(
                                    onClick = {
                                        val file = PhoneLog.exportBackupFile()
                                        if (file != null && file.exists()) Toast.makeText(this@PhoneLogFloatingService, "已保存", Toast.LENGTH_SHORT).show()
                                    },
                                    contentPadding = PaddingValues(horizontal = 10.dp),
                                    modifier = Modifier.height(28.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF007AFF))
                                ) { Text("保存", fontSize = 11.sp) }

                                Button(
                                    onClick = { stopSelf() },
                                    contentPadding = PaddingValues(horizontal = 10.dp),
                                    modifier = Modifier.height(28.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF3B30))
                                ) { Text("收起", fontSize = 11.sp) }
                            }
                        }
                    }

                    // === 📐 四向边缘缩放手势层 ===
                    EdgeResizeLayer(windowParams, floatingView!!)
                }
            }
        }

        windowManager.addView(floatingView, windowParams)
    }

    override fun onDestroy() {
        super.onDestroy()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        floatingView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
    }
}

/**
 * 📐 四向边缘缩放层
 * 在窗口四周叠加不可见的拖拽热区，替代固定的缩放按钮
 */
@Composable
private fun EdgeResizeLayer(params: WindowManager.LayoutParams, view: ComposeView) {
    val edgeSize = 14.dp // 边缘热区宽度
    val wm = view.context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    Box(modifier = Modifier.fillMaxSize()) {
        // 右边缘
        Box(modifier = Modifier
            .align(Alignment.CenterEnd)
            .width(edgeSize)
            .fillMaxHeight()
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    params.width = max(600, params.width + dragAmount.x.toInt())
                    try { wm.updateViewLayout(view, params) } catch (_: Exception) {}
                }
            })
        
        // 下边缘
        Box(modifier = Modifier
            .align(Alignment.BottomCenter)
            .height(edgeSize)
            .fillMaxWidth()
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    params.height = max(400, params.height + dragAmount.y.toInt())
                    try { wm.updateViewLayout(view, params) } catch (_: Exception) {}
                }
            })
        
        // 右下角（同时调整宽高）
        Box(modifier = Modifier
            .align(Alignment.BottomEnd)
            .size(edgeSize)
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    params.width = max(600, params.width + dragAmount.x.toInt())
                    params.height = max(400, params.height + dragAmount.y.toInt())
                    try { wm.updateViewLayout(view, params) } catch (_: Exception) {}
                }
            })
    }
}

/**
 * 📝 统一日志行组件
 * @param defaultColor 该面板的默认文字颜色（手机绿/手表蓝）
 */
@Composable
private fun LogLineItem(line: String, defaultColor: Color) {
    val isError = line.contains(" E/") || line.contains("Error")
    val isTest = line.contains("[TEST]")

    val textColor = when {
        isError -> LogColors.errorText
        isTest -> LogColors.testText
        else -> defaultColor
    }

    Text(
        text = line,
        color = textColor,
        fontSize = 11.sp,
        fontFamily = FontFamily.Monospace,
        lineHeight = 15.sp,
        modifier = Modifier.padding(vertical = 2.dp)
    )
}
