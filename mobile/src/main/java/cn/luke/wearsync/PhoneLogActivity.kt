package cn.luke.wearsync

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

class PhoneLogActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            // ✅ 移到 setContent 外部，避免每次重组都重新获取 Context
            val context = LocalContext.current
            var logLines by remember { mutableStateOf(listOf<String>()) }
            val listState = rememberLazyListState()

            // 定时同步日志
            LaunchedEffect(Unit) {
                while (true) {
                    val freshLogs = PhoneLog.getLatestTenMinutesLogs()
                    if (logLines.size != freshLogs.size) {
                        logLines = freshLogs
                    }
                    delay(400)
                }
            }

            // 自动滚动到底部
            LaunchedEffect(logLines.size) {
                if (logLines.isNotEmpty()) {
                    listState.animateScrollToItem(logLines.lastIndex)
                }
            }

            // ✅ 使用 Scaffold 统一管理顶部/底部栏与内容区域的关系
            Scaffold(
                containerColor = Color(0xFF121212),
                bottomBar = {
                    // ✅ 关键：为底部按钮区域添加导航栏内边距，避免与系统导航栏融合
                    Surface(
                        color = Color(0xFF121212),
                        tonalElevation = 3.dp
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .navigationBarsPadding() // 避开系统导航栏
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    startService(Intent(this@PhoneLogActivity, PhoneLogFloatingService::class.java))
                                    finish()
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8E44AD)),
                                modifier = Modifier.height(42.dp).weight(1f)
                            ) {
                                Text("切换悬浮窗", fontSize = 12.sp)
                            }
                            Button(
                                onClick = { PhoneLog.clear(); logLines = emptyList() },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3A3A3C)),
                                modifier = Modifier.height(42.dp).weight(1f)
                            ) {
                                Text("清空", fontSize = 12.sp)
                            }
                            Button(
                                onClick = {
                                    val backup = PhoneLog.exportBackupFile()
                                    if (backup != null) {
                                        Toast.makeText(context, "备份成功！", Toast.LENGTH_LONG).show()
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF007AFF)),
                                modifier = Modifier.height(42.dp).weight(1f)
                            ) {
                                Text("保存备份", fontSize = 12.sp)
                            }
                        }
                    }
                }
            ) { innerPadding ->
                // ✅ 内容区域应用 Scaffold 提供的 padding，自动避开 bottomBar + 导航栏
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF121212))
                        .padding(innerPadding)
                ) {
                    androidx.compose.foundation.text.selection.SelectionContainer {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            items(logLines.size) { index ->
                                val line = logLines[index]
                                val isWear = PhoneLog.isWearLog(line)
                                val isError = line.contains(" E/") || line.contains("Error")

                                val textColor = when {
                                    isError -> Color(0xFFFF6B6B)
                                    isWear -> Color(0xFF60A5FA)
                                    else -> Color(0xFF4ADE80)
                                }
                                val bgColor = if (isWear) Color(0xFF0A1628).copy(alpha = 0.4f) else Color.Transparent

                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 2.dp)
                                        .background(bgColor, shape = MaterialTheme.shapes.small)
                                        .padding(horizontal = 8.dp, vertical = 6.dp)
                                ) {
                                    Text(
                                        text = line,
                                        color = textColor,
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace,
                                        lineHeight = 14.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
