package cn.luke.wearsync

import android.content.Context
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlin.OptIn
import kotlinx.coroutines.delay
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
class PhoneLogActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var logLines by remember { mutableStateOf(listOf<String>()) }
            val listState = rememberLazyListState()

            val sp = remember { getSharedPreferences("dndsync_prefs", Context.MODE_PRIVATE) }

            LaunchedEffect(Unit) {
                while (true) {
                    val showPhoneLog = sp.getBoolean("phone_log_debug_visible", false)
                    val showWearLog = sp.getBoolean("wear_log_debug_visible", false)
                    val rawLogs = PhoneLog.getLogBuffer()

                    logLines = rawLogs.filter { line ->
                        (showPhoneLog && line.contains("[PHONE]")) || (showWearLog && line.contains("[WEAR]"))
                    }
                    delay(500)
                }
            }

            LaunchedEffect(logLines.size) {
                if (logLines.isNotEmpty()) listState.animateScrollToItem(logLines.lastIndex)
            }

            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text("WearSync 全屏大日志舱", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold) },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF151518)),
                        actions = {
                            // 🚀 核心 PiP 按钮：一键切换到悬浮窗
                            Button(
                                onClick = {
                                    val serviceIntent = Intent(this@PhoneLogActivity, PhoneLogFloatingService::class.java)
                                    startService(serviceIntent)
                                    // 完美模拟画中画切出：将 Activity 轻轻推到系统后台而不断开服务
                                    moveTaskToBack(true)
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00B0FF)),
                                contentPadding = PaddingValues(horizontal = 12.dp)
                            ) {
                                Text("↙ 悬浮小窗", color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    )
                },
                bottomBar = {
                    Surface(color = Color(0xFF151518)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            Button(
                                onClick = { PhoneLog.clear(); logLines = emptyList() },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3A3A3C))
                            ) { Text("清空缓冲区", color = Color.White) }

                            Button(
                                onClick = {
                                    val file = PhoneLog.saveToFile(this@PhoneLogActivity)
                                    if (file != null && file.exists()) {
                                        shareLogFile(file)
                                    } else {
                                        Toast.makeText(this@PhoneLogActivity, "文件生成失败", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF007AFF))
                            ) { Text("保存并共享日志", color = Color.White) }
                        }
                    }
                }
            ) { innerPadding ->
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .background(Color(0xFF121212))
                        .padding(8.dp)
                ) {
                    items(logLines) { line ->
                        val isWear = line.contains("[WEAR]")
                        val cleanLine = line.replace("[PHONE] ", "").replace("[PHONE]", "").replace("[WEAR] ", "").replace("[WEAR]", "")
                        
                        val color = when {
                            cleanLine.contains(" E/") || cleanLine.contains("Error") -> Color(0xFFFF5252)
                            isWear -> Color(0xFF00B0FF)
                            else -> Color(0xFF00E676)
                        }
                        Text(
                            text = if (isWear) "⌚ $cleanLine" else "📱 $cleanLine",
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
            val fileUri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
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
