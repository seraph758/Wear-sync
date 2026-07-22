package cn.luke.wearsync

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import android.content.Intent

class PhoneLogActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
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

            // 现代化深色主题背景
            Box(modifier = Modifier.fillMaxSize().background(Color(0xFF121212))) {
                androidx.compose.foundation.text.selection.SelectionContainer {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        items(logLines.size) { index ->
                            val line = logLines[index]
                            val isWear = line.contains("[WEAR]")
                            val isError = line.contains(" E/") || line.contains("Error")

                            // 🎨 现代化配色方案
                            val textColor = when {
                                isError -> Color(0xFFFF6B6B) // 错误：亮红色
                                isWear -> Color(0xFF60A5FA)  // 手表：蓝色
                                else -> Color(0xFF4ADE80)    // 手机：绿色
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

                // 底部操作按钮
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(16.dp)
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
Button(
    onClick = {
        startService(Intent(this@PhoneLogActivity, PhoneLogFloatingService::class.java))
        finish()
    },
    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8E44AD)),
    modifier = Modifier.height(42.dp) // 保持和之前 FAB 差不多的高度
) {
    Text("切换悬浮窗", fontSize = 12.sp) // 直接显示文字
}

                        // 清空按钮
Button(
    onClick = { PhoneLog.clear(); logLines = emptyList() },
    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3A3A3C)),
    modifier = Modifier.height(42.dp)
) {
    Text("清空", fontSize = 12.sp)
}

// 保存备份按钮
Button(
    onClick = {
        val backup = PhoneLog.exportBackupFile()
        if (backup != null) {
            Toast.makeText(context, "备份成功！", Toast.LENGTH_LONG).show()
        }
    },
    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF007AFF)),
    modifier = Modifier.height(42.dp)
) {
    Text("保存备份", fontSize = 12.sp)
}

                    }
                }
            }
        }
    }
}
