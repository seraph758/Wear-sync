package cn.luke.wearsync

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.delay
import java.io.File
import androidx.compose.material3.TopAppBar

class PhoneLogActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var logLines by remember { mutableStateOf(listOf<String>()) }
            val listState = rememberLazyListState()

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
        // 💡 修正 1：改用正式版的 TopAppBar（需要 import androidx.compose.material3.TopAppBar）
        TopAppBar(
            title = { Text("实时通信日志看板", color = Color.White, fontSize = 16.sp) },
            // 💡 修正 2：改用正式版的 topAppBarColors
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
                    modifier = Modifier.padding(end = 4.dp)
                ) {
                    Text("保存并分享", fontSize = 12.sp)
                }

                Button(onClick = { PhoneLog.clear(); logLines = emptyList() }) {
                    Text("清空", fontSize = 12.sp)
                }
            }
        )
    }
)
{ paddingValues ->
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

    // 🔥 新增：调用系统原生分享，把 txt 文件发给微信/QQ
    private fun shareLogFile(file: File) {
        try {
            // 通过 FileProvider 安全地生成一个外部可读的 URI
            val fileUri = FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider", // 必须匹配 Manifest 里的配置
                file
            )

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, fileUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) // 授权接收方读取该文件
            }
            
            startActivity(Intent.createChooser(shareIntent, "导出 WearSync 核心日志"))
        } catch (e: Exception) {
            Toast.makeText(this, "分享失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}

