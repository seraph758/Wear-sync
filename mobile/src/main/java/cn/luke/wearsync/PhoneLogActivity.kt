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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PhoneLogActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        PhoneLog.initDirectories()

        setContent {
            val context = LocalContext.current
            var logLines by remember { mutableStateOf(listOf<String>()) }
            val listState = rememberLazyListState()

            // 🎯 核心改动 1：每 400 毫秒刷新时，自动过滤掉 10 分钟之前的日志
            LaunchedEffect(Unit) {
                val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
                while (true) {
                    val rawLogs = PhoneLog.getLogBuffer()
                    val currentTime = System.currentTimeMillis()
                    val tenMinutesAgo = currentTime - 10 * 60 * 1000 // 10分钟前的毫秒数

                    // 过滤出 10 分钟内的日志
                    val filteredLogs = rawLogs.filter { line ->
                        try {
                            // 你的日志格式类似：[PHONE] [2026-07-13 23:45:01.123] D/TAG: msg
                            // 或者手表端：[WEAR] D/TAG: msg (如果没有带时间戳，默认保留)
                            if (line.contains("[") && line.contains("]")) {
                                val firstBracket = line.indexOf('[', 1)
                                val firstCloseBracket = line.indexOf(']', firstBracket)
                                if (firstBracket != -1 && firstCloseBracket != -1) {
                                    val timeStr = line.substring(firstBracket + 1, firstCloseBracket)
                                    val logTime = timeFormat.parse(timeStr)?.time ?: currentTime
                                    return@filter logTime >= tenMinutesAgo
                                }
                            }
                        } catch (_: Exception) {
                            // 解析失败的异常行默认保留，防止误删
                        }
                        true
                    }

                    if (logLines.size != filteredLogs.size) {
                        logLines = filteredLogs
                    }
                    delay(400)
                }
            }

            LaunchedEffect(logLines.size) {
                if (logLines.isNotEmpty()) {
                    listState.animateScrollToItem(logLines.lastIndex)
                }
            }

            Box(modifier = Modifier.fillMaxSize().background(Color(0xFF101012))) {

                // 🎯 核心改动 2：采用 SelectionContainer 整体包裹 LazyColumn
                // 彻底移除了原先消费手势的 combinedClickable，使得用户可以自由地像网页一样跨行滑动抹选
                // 手指抬起时，会自动弹出 Android 系统自带的“复制/全选”小气泡菜单
                androidx.compose.foundation.text.selection.SelectionContainer {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 80.dp)
                    ) {
                        items(logLines.size) { index ->
                            val line = logLines[index]

                            val isWear = line.contains("[WEAR]")
                            val isError = line.contains(" E/") || line.contains("Error")

                            val textColor = when {
                                isError -> Color(0xFFFF5252) // 错误爆红
                                isWear -> Color(0xFF00B0FF)  // 手表科技蓝
                                else -> Color(0xFF00E676)    // 手机荧光绿
                            }

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 10.dp, vertical = 3.dp)
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

                // 3. 右下角控制浮动按钮组（清空、保存备份）
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(16.dp)
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FloatingActionButton(
                            onClick = { PhoneLog.clear(); logLines = emptyList() },
                            containerColor = Color(0xFF2C2C30),
                            contentColor = Color.White,
                            modifier = Modifier.size(42.dp)
                        ) {
                            Icon(Icons.Default.Delete, "清空", modifier = Modifier.size(18.dp))
                        }
                        FloatingActionButton(
                            onClick = {
                                val backup = PhoneLog.exportBackupFile()
                                if (backup != null) {
                                    Toast.makeText(context, "备份成功！", Toast.LENGTH_LONG).show()
                                }
                            },
                            containerColor = Color(0xFF007AFF),
                            contentColor = Color.White,
                            modifier = Modifier.size(42.dp)
                        ) {
                            Icon(Icons.Default.Check, "保存备份", modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
    }
}