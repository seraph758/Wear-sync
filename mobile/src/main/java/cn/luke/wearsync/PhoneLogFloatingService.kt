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
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
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
                val context = LocalContext.current
                var logLines by remember { mutableStateOf(listOf<String>()) }
                val listState = rememberLazyListState()

                LaunchedEffect(Unit) {
                    while (true) {
                        val freshLogs = PhoneLog.getLatestTenMinutesLogs()
                        if (logLines.size != freshLogs.size) {
                            logLines = freshLogs
                        }
                        delay(400)
                    }
                }

                LaunchedEffect(logLines.size) {
                    if (logLines.isNotEmpty()) {
                        listState.animateScrollToItem(logLines.lastIndex)
                    }
                }

                Column(modifier = Modifier.fillMaxSize()) {
                    // 拖动栏
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(34.dp)
                            .background(Color(0xFF1F1F23))
                            .pointerInput(Unit) {
                                detectDragGestures { change, dragAmount ->
                                    change.consume()
                                    windowParams.x += dragAmount.x.toInt()
                                    windowParams.y += dragAmount.y.toInt()
                                    try { windowManager.updateViewLayout(this@apply, windowParams) } catch (_: Exception) {}
                                }
                            }
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("↕ 按住此处拖动监视器", color = Color(0xFF00B0FF), fontSize = 11.sp, fontWeight = FontWeight.Bold)

                        Text(
                            text = "📐 拖拽边缘调大小",
                            color = Color.LightGray,
                            fontSize = 11.sp,
                            modifier = Modifier
                                .background(Color(0xFF2D2D34))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                .pointerInput(Unit) {
                                    detectDragGestures { change, dragAmount ->
                                        change.consume()
                                        if (windowParams.width == WindowManager.LayoutParams.MATCH_PARENT) {
                                            windowParams.width = this@apply.width
                                        }
                                        windowParams.width = max(500, windowParams.width + dragAmount.x.toInt())
                                        windowParams.height = max(400, windowParams.height + dragAmount.y.toInt())
                                        try { windowManager.updateViewLayout(this@apply, windowParams) } catch (_: Exception) {}
                                    }
                                }
                        )
                    }

                    // 日志内容区
                    Box(modifier = Modifier.weight(1f).background(Color(0xFF101012))) {
                        androidx.compose.foundation.text.selection.SelectionContainer {
                            LazyColumn(
                                state = listState,
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(bottom = 10.dp)
                            ) {
                                items(logLines.size) { index ->
                                    val line = logLines[index]
                                    val isWear = line.contains("[WEAR]")
                                    val isTest = line.contains("[TEST]") 
                                    val isError = line.contains(" E/") || line.contains("Error")
                                    
                                    val textColor = when {
                                        isError -> Color(0xFFFF5252) 
                                        isTest -> Color(0xFFE040FB)  
                                        isWear -> Color(0xFF00B0FF)  
                                        else -> Color(0xFF00E676)    
                                    }
                                    Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 3.dp)) {
                                        Text(text = line, color = textColor, fontSize = 11.sp, fontFamily = FontFamily.Monospace, lineHeight = 14.sp)
                                    }
                                }
                            }
                        }
                        Text("📱⌚ 混合时间流", color = Color.White.copy(alpha = 0.2f), fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.TopEnd).padding(4.dp))
                    }

                    // 底部面板
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(40.dp)
                            .background(Color(0xFF1F1F23))
                            .padding(horizontal = 4.dp),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Button(
                                onClick = { 
                                    PhoneLog.clear()
                                    logLines = emptyList()
                                },
                                contentPadding = PaddingValues(horizontal = 8.dp),
                                modifier = Modifier.height(28.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3A3A3C))
                            ) { Text("清空", fontSize = 11.sp) }

                            Button(
                                onClick = {
                                    val activityIntent = Intent(this@PhoneLogFloatingService, PhoneLogActivity::class.java).apply {
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                                    }
                                    startActivity(activityIntent)
                                    stopSelf()
                                },
                                contentPadding = PaddingValues(horizontal = 8.dp),
                                modifier = Modifier.height(28.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00B0FF))
                            ) { Text("返回全屏", fontSize = 11.sp, color = Color.Black, fontWeight = FontWeight.Bold) }

                            Button(
                                onClick = {
                                    // 🟢 完美修復：移除了錯誤傳入的 this@PhoneLogFloatingService 參數
                                    val file: File? = PhoneLog.exportBackupFile()
                                    if (file != null && file.exists()) {
                                        Toast.makeText(this@PhoneLogFloatingService, "保存成功！", Toast.LENGTH_LONG).show()
                                    }
                                },
                                contentPadding = PaddingValues(horizontal = 8.dp),
                                modifier = Modifier.height(28.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF007AFF)) 
                            ) { Text("保存", fontSize = 11.sp) }

                            Button(
                                onClick = { stopSelf() },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF3B30)), 
                                contentPadding = PaddingValues(horizontal = 8.dp),
                                modifier = Modifier.height(28.dp)
                            ) { Text("收起", fontSize = 11.sp) }
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
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
    }
}
