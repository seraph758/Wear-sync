package cn.luke.wearsync

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

@OptIn(ExperimentalFoundationApi::class)
class PhoneLogActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        PhoneLog.initDirectories()

        setContent {
            val context = LocalContext.current
            var logLines by remember { mutableStateOf(listOf<String>()) }
            val listState = rememberLazyListState()

            var selectedIndices by remember { mutableStateOf(setOf<Int>()) }
            val isMultiSelectMode by remember { derivedStateOf { selectedIndices.isNotEmpty() } }

            // 0过滤流，定时刷出底层的完整大池子
            LaunchedEffect(Unit) {
                while (true) {
                    val rawLogs = PhoneLog.getLogBuffer()
                    if (logLines.size != rawLogs.size) {
                        logLines = rawLogs
                    }
                    delay(400)
                }
            }

            LaunchedEffect(logLines.size) {
                if (logLines.isNotEmpty() && !isMultiSelectMode) {
                    listState.animateScrollToItem(logLines.lastIndex)
                }
            }

            Box(modifier = Modifier.fillMaxSize().background(Color(0xFF101012))) {
                // 1. 全屏混合日志流大通道
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 80.dp)
                ) {
                    items(logLines.size) { index ->
                        val line = logLines[index]
                        val isSelected = index in selectedIndices
                        
                        val isWear = line.contains("[WEAR]")
                        val isError = line.contains(" E/") || line.contains("Error")
                        
                        val textColor = when {
                            isError -> Color(0xFFFF5252) // 错误爆红
                            isWear -> Color(0xFF00B0FF)  // 手表科技蓝
                            else -> Color(0xFF00E676)    // 手机荧光绿
                        }

                        val rowBgColor = if (isSelected) Color(0xFF1A365D) else Color.Transparent

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(rowBgColor)
                                .combinedClickable(
                                    onClick = {
                                        if (isMultiSelectMode) {
                                            selectedIndices = if (isSelected) selectedIndices - index else selectedIndices + index
                                        } else {
                                            copyToClipboard(context, line)
                                        }
                                    },
                                    onLongClick = {
                                        selectedIndices = if (isSelected) selectedIndices - index else selectedIndices + index
                                    }
                                )
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

                // 2. 控制浮动按钮组
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(16.dp)
                ) {
                    if (isMultiSelectMode) {
                        Surface(
                            shape = ShapeDefaults.Large,
                            color = Color(0xFF1F1F23)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "已选 ${selectedIndices.size} 条", 
                                    color = Color(0xFF00B0FF), 
                                    fontSize = 12.sp,
                                    modifier = Modifier.padding(horizontal = 8.dp)
                                )
                                IconButton(onClick = {
                                    val copiedText = logLines.filterIndexed { idx, _ -> idx in selectedIndices }.joinToString("\n")
                                    copyToClipboard(context, copiedText)
                                    selectedIndices = emptySet()
                                }) {
                                    Icon(Icons.Default.Share, "复制", tint = Color(0xFF00E676))
                                }
                                IconButton(onClick = { selectedIndices = emptySet() }) {
                                    Icon(Icons.Default.Close, "取消", tint = Color.LightGray)
                                }
                            }
                        }
                    } else {
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

    private fun copyToClipboard(context: Context, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("WearSyncLog", text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(context, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
    }
}
