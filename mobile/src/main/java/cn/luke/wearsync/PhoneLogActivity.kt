package cn.luke.wearsync

import android.content.Intent // 💡 补上了：Intent 的导包
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background // 💡 补上了：背景色导包
import androidx.compose.foundation.layout.* // 💡 确保 layout 包下的 padding、fillMaxSize 被正确引入
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState // 💡 补上了：LazyColumn 滚动状态的导包
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlin.OptIn // 💡 修正了：OptIn 正确的导包路径是这个！
import kotlinx.coroutines.delay
import java.io.File

@OptIn(ExperimentalMaterial3Api::class) 
class PhoneLogActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var logLines by remember { mutableStateOf(listOf<String>()) }
            val listState = rememberLazyListState() // 🌟 导包正确后，这里不会再报错

            LaunchedEffect(Unit) {
                while (true) {
                    logLines = PhoneLog.getLogBuffer()
                    if (logLines.isNotEmpty()) {
                        listState.animateScrollToItem(logLines.lastIndex)
                    }
                    delay(1000)
                }
            }

            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text("实时通信日志看板", color = Color.White, fontSize = 16.sp) },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF121212)),
                        actions = {
                            Button(
                                onClick = {
                                    val logFile = PhoneLog.saveToFile(this@PhoneLogActivity)
                                    if (logFile != null && logFile.exists()) {
                                        shareLogFile(logFile)
                                    } else {
                                        Toast.makeText(this@PhoneLogActivity, "日志文件生成失败", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                modifier = Modifier.padding(end = 4.dp) // 🌟 导包正确后，padding 正常工作
                            ) {
                                Text("保存并分享", fontSize = 12.sp)
                            }

                            Button(onClick = { PhoneLog.clear(); logLines = emptyList() }) {
                                Text("清空", fontSize = 12.sp)
                            }
                        }
                    )
                }
            ) { paddingValues ->
                // 🌟 这里补充完善了 LazyColumn，让其直接消耗 paddingValues 并正确绘制
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                        .background(Color(0xFF1E1E1E))
                        .padding(8.dp)
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
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(vertical = 2.dp)
                        )
                    }
                }
            }
        }
    }

    private fun shareLogFile(file: File) {
        try {
            val fileUri = FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                file
            )

            // 🌟 导包正确后，下面的 Intent 操作都能完美闭环
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, fileUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            startActivity(Intent.createChooser(shareIntent, "导出 WearSync 核心日志"))
        } catch (e: Exception) {
            Toast.makeText(this, "分享失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
