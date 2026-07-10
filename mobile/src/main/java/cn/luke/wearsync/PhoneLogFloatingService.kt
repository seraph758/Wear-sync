package cn.luke.wearsync

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
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
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.delay

class PhoneLogFloatingService : Service(), SavedStateRegistryOwner {

    private lateinit var windowManager: WindowManager
    private var floatingView: ComposeView? = null

    // 基础设施基础设施与生命周期公共访问机制
    private val controller = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry = controller.savedStateRegistry

    // 绑定该 Service 自身提供给 Compose 底层环境的生命周期持有者
    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        controller.performRestore(null)
        super.onCreate()

        // 标记基础设施生命周期走向激活状态
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        // 设置系统窗口参数：小窗、飘在最上层、默认不抢占焦点
        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            650, // 💡 悬浮窗高度：微调到 650 像素，留出更宽裕的日志展示行数
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START // 默认靠屏幕顶部显示
            x = 0
            y = 150 // 距离顶部 150 像素，防止挡住系统状态栏和操作区
        }

        // 创建用来承载 Compose 布局的 View 容器
        floatingView = ComposeView(this).apply {
            val viewModelStore = ViewModelStore()

            setViewTreeLifecycleOwner(this@PhoneLogFloatingService)
            setViewTreeViewModelStoreOwner(object : ViewModelStoreOwner { 
                override val viewModelStore: ViewModelStore = viewModelStore 
            })
            setViewTreeSavedStateRegistryOwner(this@PhoneLogFloatingService)

            setContent {
                var logLines by remember { mutableStateOf(listOf<String>()) }
                val listState = rememberLazyListState()

                // ✅ 改善点：替换掉不稳定的 Timer，使用 Compose 宿主协程进行绝对安全的、秒级实时日志同步
                LaunchedEffect(Unit) {
                    while (true) {
                        val currentLogs = PhoneLog.getLogBuffer()
                        // 只有在日志有变化时才更新状态，避免无意义的重绘
                        if (logLines.size != currentLogs.size) {
                            logLines = currentLogs
                        }
                        delay(400) // ⚡ 每 400 毫秒高刷一次，操作完立马就能在悬浮窗里看到！
                    }
                }

                // 每次有新日志进来，自动平滑滚动到底部
                LaunchedEffect(logLines.size) {
                    if (logLines.isNotEmpty()) {
                        listState.animateScrollToItem(logLines.lastIndex)
                    }
                }

                // 悬浮窗半透明黑客面板
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xD9151515)) // 0xD9 约 85% 不透明，背景更深、确保强光下日志也清晰可见
                        .padding(6.dp)
                ) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize().padding(bottom = 38.dp)
                    ) {
                        items(logLines) { line ->
                            val color = when {
                                line.contains(" E/") || line.contains("Error") -> Color(0xFFFF5252)
                                line.contains(" W/") || line.contains("Warn") -> Color(0xFFFFD740)
                                else -> Color(0xFF00E676)
                            }
                            Text(
                                text = line,
                                color = color,
                                fontSize = 11.sp, // 微调字体，保证小屏幕阅读体验
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(vertical = 1.dp)
                            )
                        }
                    }

                    // 🛠️ 底部迷你控制栏
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.BottomCenter)
                            .background(Color(0xFF222222))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("WearSync 实时悬浮监视器", color = Color.LightGray, fontSize = 10.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            // 清空按钮
                            Button(
                                onClick = { 
                                    PhoneLog.clear()
                                    logLines = emptyList() 
                                },
                                contentPadding = PaddingValues(horizontal = 10.dp),
                                modifier = Modifier.height(26.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF424242))
                            ) {
                                Text("清空", fontSize = 11.sp, color = Color.White)
                            }
                            // 收起按钮（直接销毁并退出悬浮窗服务）
                            Button(
                                onClick = { stopSelf() },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F)),
                                contentPadding = PaddingValues(horizontal = 10.dp),
                                modifier = Modifier.height(26.dp)
                            ) {
                                Text("收起", fontSize = 11.sp, color = Color.White)
                            }
                        }
                    }
                }
            }
        }

        // 把这个小悬浮窗真正塞进 WindowManager
        windowManager.addView(floatingView, params)
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
