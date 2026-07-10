package cn.luke.wearsync

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import kotlin.math.max
import kotlin.math.min

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

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        }

        // 🌟 初始参数配置
        windowParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            700, // 默认初始总高度为 700 像素
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 200 // 距离顶部留出空间
        }

        floatingView = ComposeView(this).apply {
            val viewModelStore = ViewModelStore()

            setViewTreeLifecycleOwner(this@PhoneLogFloatingService)
            setViewTreeViewModelStoreOwner(object : ViewModelStoreOwner { 
                override val viewModelStore: ViewModelStore = viewModelStore 
            })
            setViewTreeSavedStateRegistryOwner(this@PhoneLogFloatingService)

            setContent {
                // 🚀 双舱独立数据源
                var phoneLogs by remember { mutableStateOf(listOf<String>()) }
                var wearLogs by remember { mutableStateOf(listOf<String>()) }

                // 🚀 双舱独立滚动状态机
                val phoneListState = rememberLazyListState()
                val wearListState = rememberLazyListState()

                // 高度动态调整状态（本地 Compose 维护，并同步反馈给物理窗口）
                var currentHeight by remember { mutableStateOf(700) }

                val sp = remember { getSharedPreferences("dndsync_prefs", Context.MODE_PRIVATE) }

                // 🔄 核心双通道高刷轮询机制
                LaunchedEffect(Unit) {
                    while (true) {
                        val showPhoneLog = sp.getBoolean("phone_log_debug_visible", true)
                        val showWearLog = sp.getBoolean("wear_log_debug_visible", true)
                        val rawLogs = PhoneLog.getLogBuffer()

                        // 独立过滤出手机日志舱
                        val pList = if (showPhoneLog) {
                            rawLogs.filter { !it.contains("Wear", ignoreCase = true) && !it.contains("Watch", ignoreCase = true) }
                        } else emptyList()

                        // 独立过滤出手表日志舱
                        val wList = if (showWearLog) {
                            rawLogs.filter { it.contains("Wear", ignoreCase = true) || it.contains("Watch", ignoreCase = true) }
                        } else emptyList()

                        if (phoneLogs.size != pList.size) phoneLogs = pList
                        if (wearLogs.size != wList.size) wearLogs = wList

                        delay(400) // 400ms 高刷
                    }
                }

                // 📱 手机舱自动触底
                LaunchedEffect(phoneLogs.size) {
                    if (phoneLogs.isNotEmpty()) phoneListState.animateScrollToItem(phoneLogs.lastIndex)
                }

                // ⌚ 手表舱自动触底
                LaunchedEffect(wearLogs.size) {
                    if (wearLogs.isNotEmpty()) wearListState.animateScrollToItem(wearLogs.lastIndex)
                }

                // 全局大外壳容器
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xF2121212)) // 极具质感的近纯黑底色
                ) {
                    
                    // ================= 舱位上层：手机本地日志舱 =================
                    Box(modifier = Modifier.weight(1f).fillMaxWidth().padding(4.dp)) {
                        if (phoneLogs.isEmpty()) {
                            Text("手机日志舱 (已清空或主页开关已关闭)", color = Color.DarkGray, fontSize = 11.sp, modifier = Modifier.align(Alignment.Center))
                        } else {
                            LazyColumn(state = phoneListState, modifier = Modifier.fillMaxSize()) {
                                items(phoneLogs) { line ->
                                    val color = if (line.contains(" E/") || line.contains("Error")) Color(0xFFFF5252) else Color(0xFF00E676)
                                    Text(text = line, color = color, fontSize = 10.5.sp, fontFamily = FontFamily.Monospace)
                                }
                            }
                        }
                        Text("📱 手机本地通道", color = Color.White.copy(alpha = 0.3f), fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.apply { align(Alignment.TopEnd).padding(4.dp) })
                    }

                    // 🛠️ 贯穿中轴：完美的双端分界线
                    Row(
                        modifier = Modifier.fillMaxWidth().height(2.dp).background(Color(0xFF00B0FF)) // 冰蓝色隔离带
                    ) {}

                    // ================= 舱位下层：手表专属日志舱 =================
                    Box(modifier = Modifier.weight(1f).fillMaxWidth().padding(4.dp)) {
                        if (wearLogs.isEmpty()) {
                            Text("手表日志舱 (等待上报或主页开关已关闭)", color = Color.DarkGray, fontSize = 11.sp, modifier = Modifier.align(Alignment.Center))
                        } else {
                            LazyColumn(state = wearListState, modifier = Modifier.fillMaxSize()) {
                                items(wearLogs) { line ->
                                    val color = if (line.contains(" E/") || line.contains("Error")) Color(0xFFFF5252) else Color(0xFF00B0FF)
                                    Text(text = line, color = color, fontSize = 10.5.sp, fontFamily = FontFamily.Monospace)
                                }
                            }
                        }
                        Text("⌚ 手表无线网络", color = Color.White.copy(alpha = 0.3f), fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.apply { align(Alignment.TopEnd).padding(4.dp) })
                    }

                    // ================= 底部：一体化多维控制台 (集成拖动与高度缩放) =================
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(42.dp)
                            .background(Color(0xFF1A1A1A))
                            .pointerInput(Unit) {
                                // 🌟 核心手势：允许用户在控制台区域按住任意上下拖动悬浮窗位置
                                detectDragGestures { change, dragAmount ->
                                    change.consume()
                                    windowParams.y += dragAmount.y.toInt()
                                    try {
                                        windowManager.updateViewLayout(floatingView, windowParams)
                                    } catch (_: Exception) {}
                                }
                            }
                            .padding(horizontal = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 左侧状态文字提示拖拽
                        Text("↕ 拖动此行改变位置", color = Color.Gray, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                        
                        // 右侧动作按钮方阵
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // 高度 - 缩小按钮
                            Button(
                                onClick = {
                                    currentHeight = max(400, currentHeight - 120)
                                    windowParams.height = currentHeight
                                    try { windowManager.updateViewLayout(floatingView, windowParams) } catch (_: Exception) {}
                                },
                                contentPadding = PaddingValues(horizontal = 6.dp),
                                modifier = Modifier.height(26.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF333333))
                            ) { Text("矮 ➖", fontSize = 11.sp, color = Color.LightGray) }

                            // 高度 + 放大按钮
                            Button(
                                onClick = {
                                    currentHeight = min(1500, currentHeight + 120)
                                    windowParams.height = currentHeight
                                    try { windowManager.updateViewLayout(floatingView, windowParams) } catch (_: Exception) {}
                                },
                                contentPadding = PaddingValues(horizontal = 6.dp),
                                modifier = Modifier.height(26.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF333333))
                            ) { Text("高 ➕", fontSize = 11.sp, color = Color.LightGray) }

                            // 一键清空核心缓冲区
                            Button(
                                onClick = { 
                                    PhoneLog.clear()
                                    phoneLogs = emptyList()
                                    wearLogs = emptyList()
                                },
                                contentPadding = PaddingValues(horizontal = 8.dp),
                                modifier = Modifier.height(26.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF424242))
                            ) { Text("清空", fontSize = 11.sp, color = Color.White) }

                            // 完美收起卸载服务
                            Button(
                                onClick = { stopSelf() },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F)),
                                contentPadding = PaddingValues(horizontal = 8.dp),
                                modifier = Modifier.height(26.dp)
                            ) { Text("收起", fontSize = 11.sp, color = Color.White) }
                        }
                    }
                }
            }
        }

        // 正式挂载
        windowManager.addView(floatingView, windowParams)
    }

    override fun onDestroy() {
        super.onDestroy()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        floatingView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {}
        }
    }
}
