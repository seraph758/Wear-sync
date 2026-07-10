package cn.luke.wearsync

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.view.Gravity
import android.view.WindowManager
import android.widget.Toast
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
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.max

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

        windowParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            750, 
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 250
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

                var widthPx by remember { mutableStateOf(windowParams.width) }
                var heightPx by remember { mutableStateOf(windowParams.height) }

                val sp = remember { getSharedPreferences("dndsync_prefs", Context.MODE_PRIVATE) }

                // 🔄 核心精准双舱分流
                LaunchedEffect(Unit) {
                    while (true) {
                        val showPhoneLog = sp.getBoolean("phone_log_debug_visible", true)
                        val showWearLog = sp.getBoolean("wear_log_debug_visible", true)
                        val rawLogs = PhoneLog.getLogBuffer()

                        // 🔍 依靠硬标签 [PHONE] 和 [WEAR] 或者包含特定关键字来进行百分百归位
                        val pList = if (showPhoneLog) {
                            rawLogs.filter { line -> 
                                line.contains("[PHONE]") || (!line.contains("[WEAR]") && !line.contains("WearLog") && !line.contains("🎬"))
                            }
                        } else emptyList()

                        val wList = if (showWearLog) {
                            rawLogs.filter { line -> 
                                line.contains("[WEAR]") || line.contains("WearLog") || line.contains("🎬")
                            }
                        } else emptyList()

                        if (phoneLogs.size != pList.size) phoneLogs = pList
                        if (wearLogs.size != wList.size) wearLogs = wList

                        delay(400)
                    }
                }

                LaunchedEffect(phoneLogs.size) {
                    if (phoneLogs.isNotEmpty()) phoneListState.animateScrollToItem(phoneLogs.lastIndex)
                }
                LaunchedEffect(wearLogs.size) {
                    if (wearLogs.isNotEmpty()) wearListState.animateScrollToItem(wearLogs.lastIndex)
                }

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xF2121212)) 
                ) {
                    // ================= 舱位上层：手机本地日志舱 =================
                    Box(modifier = Modifier.weight(1f).fillMaxWidth().padding(4.dp)) {
                        if (phoneLogs.isEmpty()) {
                            Text("手机日志舱 (暂无内容或主页开关已关)", color = Color.DarkGray, fontSize = 11.sp, modifier = Modifier.align(Alignment.Center))
                        } else {
                            LazyColumn(state = phoneListState, modifier = Modifier.fillMaxSize()) {
                                items(phoneLogs) { line ->
                                    val color = if (line.contains(" E/") || line.contains("Error")) Color(0xFFFF5252) else Color(0xFF00E676)
                                    Text(text = line, color = color, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                                }
                            }
                        }
                        Text("📱 手机本地通道", color = Color.White.copy(alpha = 0.25f), fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.TopEnd).padding(4.dp))
                    }

                    Row(modifier = Modifier.fillMaxWidth().height(2.dp).background(Color(0xFF00B0FF))) {}

                    // ================= 舱位下层：手表专属日志舱 =================
                    Box(modifier = Modifier.weight(1f).fillMaxWidth().padding(4.dp)) {
                        if (wearLogs.isEmpty()) {
                            Text("手表日志舱 (等待无线网络报文或主页开关已关)", color = Color.DarkGray, fontSize = 11.sp, modifier = Modifier.align(Alignment.Center))
                        } else {
                            LazyColumn(state = wearListState, modifier = Modifier.fillMaxSize()) {
                                items(wearLogs) { line ->
                                    val color = if (line.contains(" E/") || line.contains("Error")) Color(0xFFFF5252) else Color(0xFF00B0FF)
                                    Text(text = line, color = color, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                                }
                            }
                        }
                        Text("⌚ 手表无线网络", color = Color.White.copy(alpha = 0.25f), fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.TopEnd).padding(4.dp))
                    }

                    // ================= 底部第 1 行：全手势纯净拖动滑行带 =================
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(36.dp)
                            .background(Color(0xFF1F1F23))
                            .pointerInput(Unit) {
                                detectDragGestures { change, dragAmount ->
                                    change.consume()
                                    windowParams.x += dragAmount.x.toInt()
                                    windowParams.y += dragAmount.y.toInt()
                                    try { windowManager.updateViewLayout(floatingView, windowParams) } catch (_: Exception) {}
                                }
                            }
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("↕ 按住此行可在屏幕任意拖放位置", color = Color(0xFF00B0FF), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        
                        Text(
                            text = "📐 拖拽此边缘调大小",
                            color = Color.LightGray,
                            fontSize = 11.sp,
                            modifier = Modifier
                                .background(Color(0xFF2D2D34))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                .pointerInput(Unit) {
                                    detectDragGestures { change, dragAmount ->
                                        change.consume()
                                        if (windowParams.width == WindowManager.LayoutParams.MATCH_PARENT) {
                                            windowParams.width = floatingView?.width ?: 1000
                                        }
                                        windowParams.width = max(500, windowParams.width + dragAmount.x.toInt())
                                        windowParams.height = max(400, windowParams.height + dragAmount.y.toInt())
                                        
                                        widthPx = windowParams.width
                                        heightPx = windowParams.height
                                        try { windowManager.updateViewLayout(floatingView, windowParams) } catch (_: Exception) {}
                                    }
                                }
                        )
                    }

                    // ================= 底部第 2 行：核心控制台 =================
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                            .background(Color(0xFF151518))
                            .padding(horizontal = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("WearSync 核心监视器", color = Color.Gray, fontSize = 10.sp)
                        
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { 
                                    PhoneLog.clear()
                                    phoneLogs = emptyList()
                                    wearLogs = emptyList()
                                },
                                contentPadding = PaddingValues(horizontal = 10.dp),
                                modifier = Modifier.height(28.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3A3A3C))
                            ) { Text("清空", fontSize = 11.sp, color = Color.White) }

                            Button(
                                onClick = {
                                    try {
                                        val allLogs = PhoneLog.getLogBuffer()
                                        if (allLogs.isEmpty()) {
                                            Toast.makeText(this@PhoneLogFloatingService, "当前缓冲区无日志，无需导出", Toast.LENGTH_SHORT).show()
                                            return@Button
                                        }
                                        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(System.currentTimeMillis())
                                        val fileName = "WearSync_Log_$timeStamp.txt"
                                        
                                        val docDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
                                        if (!docDir.exists()) docDir.mkdirs()
                                        
                                        val file = File(docDir, fileName)
                                        FileOutputStream(file).use { os ->
                                            allLogs.forEach { line ->
                                                os.write((line + "\n").toByteArray())
                                            }
                                        }
                                        Toast.makeText(this@PhoneLogFloatingService, "日志已成功保存至 Documents/$fileName", Toast.LENGTH_LONG).show()
                                    } catch (e: Exception) {
                                        Toast.makeText(this@PhoneLogFloatingService, "保存失败: ${e.message}", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                contentPadding = PaddingValues(horizontal = 10.dp),
                                modifier = Modifier.height(28.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF007AFF)) 
                            ) { Text("保存日志", fontSize = 11.sp, color = Color.White) }

                            Button(
                                onClick = { stopSelf() },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF3B30)), 
                                contentPadding = PaddingValues(horizontal = 10.dp),
                                modifier = Modifier.height(28.dp)
                            ) { Text("收起", fontSize = 11.sp, color = Color.White) }
                        }
                    }
                }
            }
        }

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
