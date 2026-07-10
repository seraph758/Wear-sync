package cn.luke.wearsync

import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import android.view.Gravity
import android.view.WindowManager
import android.widget.Toast
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
import androidx.core.content.FileProvider
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
            650, // 💡 悬浮窗高度
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 150
        }

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

                // 使用 Compose 宿主协程进行秒级实时日志同步
                LaunchedEffect(Unit) {
                    while (true) {
                        val currentLogs = PhoneLog.getLogBuffer()
                        if (logLines.size != currentLogs.size) {
                            logLines = currentLogs
                        }
                        delay(400)
                    }
                }

                // 自动平滑滚动到底部
                LaunchedEffect(logLines.size) {
                    if (logLines.isNotEmpty()) {
                        listState.animateScrollToItem(logLines.lastIndex)
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xD9151515))
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
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(vertical = 1.dp)
                            )
                        }
                    }

                    // 🛠️ 底部控制栏：包含清空、保存、分享、收起
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.BottomCenter)
                            .background(Color(0xFF222222))
                            .padding(horizontal = 4.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("实时悬浮监视", color = Color.Gray, fontSize = 9.sp, modifier = Modifier.weight(1f))
                        
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // 1. 清空按钮
                            Button(
                                onClick = { 
                                    PhoneLog.clear()
                                    logLines = emptyList() 
                                },
                                contentPadding = PaddingValues(horizontal = 6.dp),
                                modifier = Modifier.height(26.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF424242))
                            ) {
                                Text("清空", fontSize = 10.sp, color = Color.White)
                            }

                            // 2. 保存按钮：默认保存在 /Download/WearSync/WearSync.log 并在不存在时自动创建文件夹
                            Button(
                                onClick = {
                                    val textToSave = PhoneLog.getLogBuffer().joinToString("\n")
                                    val success = saveLogToDownload(this@PhoneLogFloatingService, textToSave)
                                    Toast.makeText(this@PhoneLogFloatingService, if(success) "已保存至 Download/WearSync/" else "保存失败", Toast.LENGTH_SHORT).show()
                                },
                                contentPadding = PaddingValues(horizontal = 6.dp),
                                modifier = Modifier.height(26.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00897B))
                            ) {
                                Text("保存", fontSize = 10.sp, color = Color.White)
                            }

                            // 3. 分享按钮：单独拉起系统分享面板
                            Button(
                                onClick = {
                                    val textToShare = PhoneLog.getLogBuffer().joinToString("\n")
                                    shareLogFile(this@PhoneLogFloatingService, textToShare)
                                },
                                contentPadding = PaddingValues(horizontal = 6.dp),
                                modifier = Modifier.height(26.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E88E5))
                            ) {
                                Text("分享", fontSize = 10.sp, color = Color.White)
                            }

                            // 4. 收起按钮
                            Button(
                                onClick = { stopSelf() },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F)),
                                contentPadding = PaddingValues(horizontal = 6.dp),
                                modifier = Modifier.height(26.dp)
                            ) {
                                Text("收起", fontSize = 10.sp, color = Color.White)
                            }
                        }
                    }
                }
            }
        }

        windowManager.addView(floatingView, params)
    }

    /**
     * 将日志保存在公共 Download/WearSync/ 目录下，自动创建文件夹并兼容 Android 高版本
     */
    private fun saveLogToDownload(context: Context, content: String): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, "WearSync.log")
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    // 在公共 Download 文件夹下自动追加 WearSync 文件夹
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/WearSync")
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                if (uri != null) {
                    resolver.openOutputStream(uri).use { outputStream ->
                        outputStream?.write(content.toByteArray())
                    }
                    true
                } else false
            } else {
                // 兼容 Android 9 及以下旧版本的老式写法
                @Suppress("DEPRECATION")
                val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val wearSyncFolder = File(downloadDir, "WearSync")
                if (!wearSyncFolder.exists()) {
                    wearSyncFolder.mkdirs() // 自动创建 WearSync 文件夹
                }
                val logFile = File(wearSyncFolder, "WearSync.log")
                FileOutputStream(logFile).use { outputStream ->
                    outputStream.write(content.toByteArray())
                }
                true
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * 单独的分享逻辑：利用应用内私有缓存生成临时分享文件，并拉起系统原生分享器
     */
    private fun shareLogFile(context: Context, content: String) {
        try {
            val cacheDir = context.cacheDir
            val shareFile = File(cacheDir, "WearSync_Share_Log.txt")
            FileOutputStream(shareFile).use { it.write(content.toByteArray()) }

            val authority = "${context.packageName}.fileprovider"
            val uri: Uri = FileProvider.getUriForFile(context, authority, shareFile)

            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            
            val chooser = Intent.createChooser(intent, "分享 WearSync 日志").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(context, "分享失败", Toast.LENGTH_SHORT).show()
        }
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
