package cn.luke.wearsync

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.*
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import java.util.Timer
import kotlin.concurrent.fixedRateTimer

class PhoneLogFloatingService : Service() {

    private lateinit var windowManager: WindowManager
    private var floatingView: ComposeView? = null
    private var timer: Timer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        controller.performRestore(null)
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        // 🌟 设置系统窗口参数：让其飘在最上层，默认不抢占焦点（FLAG_NOT_FOCUSABLE）从而可以操作下方的主界面
        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            500, // 💡 悬浮窗高度：固定 500 像素，占满宽度。你可以根据需要调整。
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START // 默认靠屏幕顶部显示
            x = 0
            y = 100 // 距离顶部 100 像素，防止挡住系统状态栏
        }

        // 创建用来承载 Compose 布局的 
        floatingView = ComposeView(this).apply {
            // 必须绑定的 Android Lifecycle 基础设施，否则 Compose 在 Service 内部会报错
            val viewModelStore = ViewModelStore()
            val lifecycleOwner = object : LifecycleOwner {
                private val registry = LifecycleRegistry(this)
                override val lifecycle: Lifecycle get() = registry
                init { registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE); registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME) }
            }
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeViewModelStoreOwner(object : ViewModelStoreOwner { override val viewModelStore: ViewModelStore = viewModelStore })
            setViewTreeSavedStateRegistryOwner(object : androidx.savedstate.SavedStateRegistryOwner {
                private val controller = androidx.savedstate.SavedStateRegistryController.create(this)
                private val registry = controller.savedstateRegistry                
                override val savedStateRegistry: androidx.savedstate.SavedStateRegistry = registry
                override val lifecycle: Lifecycle get() = lifecycleOwner.lifecycle
            })

            setContent {
                var logLines by remember { mutableStateOf(listOf<String>()) }
                val listState = rememberLazyListState()

                // 监听内存日志流
                LaunchedEffect(Unit) {
                    // 每 800ms 刷新一次日志
                    timer = fixedRateTimer(period = 800) {
                        Handler(Looper.getMainLooper()).post {
                            logLines = PhoneLog.getLogBuffer()
                        }
                    }
                }

                // 每次有新日志进来，自动滚动到底部
                LaunchedEffect(logLines.size) {
                    if (logLines.isNotEmpty()) {
                        listState.animateScrollToItem(logLines.lastIndex)
                    }
                }

                // 悬浮窗半透明黑客面板
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xCC1E1E1E)) // 0xCC 代表 80% 透明度，能隐约看到底下的主界面
                        .padding(4.dp)
                ) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize().padding(bottom = 36.dp)
                    ) {
                        items(logLines) { line ->
                            val color = when {
                                line.contains(" E/") -> Color(0xFFFF5252)
                                line.contains(" W/") -> Color(0xFFFFD740)
                                else -> Color(0xFF00E676)
                            }
                            Text(
                                text = line,
                                color = color,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }

                    // 🛠️ 底部迷你控制栏（只有当你点击悬浮窗区域时，它们才临时获得交互响应）
                    Row(
                        modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter).background(Color(0xEE121212)).padding(4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("WearSync 实时悬浮监视器", color = Color.Gray, fontSize = 10.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            // 清空按钮
                            Button(
                                onClick = { PhoneLog.clear(); logLines = emptyList() },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                modifier = Modifier.height(24.dp)
                            ) {
                                Text("清空", fontSize = 10.sp)
                            }
                            // 关闭按钮（退出悬浮窗）
                            Button(
                                onClick = { stopSelf() },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF5252)),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                modifier = Modifier.height(24.dp)
                            ) {
                                Text("收起", fontSize = 10.sp)
                            }
                        }
                    }
                }
            }
        }

        windowManager.addView(floatingView, params)
    }

    override fun onDestroy() {
        super.onDestroy()
        timer?.cancel()
        floatingView?.let {
            windowManager.removeView(it)
        }
    }
}
