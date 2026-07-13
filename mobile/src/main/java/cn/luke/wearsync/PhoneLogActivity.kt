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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Save
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



@OptIn(ExperimentalFoundationApi::class)
class PhoneLogActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 每次打开界面，确保目录被强制检查和建立
        PhoneLog.initDirectories()

        setContent {
            val context = LocalContext.current
            var logLines by remember { mutableStateOf(listOf<String>()) }
            val listState = rememberLazyListState()

            // 选中的日志行索引
            var selectedIndices by remember { mutableStateOf(setOf<Int>()) }
            val isMultiSelectMode by remember { derivedStateOf { selectedIndices.isNotEmpty() } }

            // 0阻拦定时刷新：手机、手表所有日志混在一起
            LaunchedEffect(Unit) {
                while (true) {
                    logLines = PhoneLog.getLogBuffer()
                    delay(400) // 略微调快刷新率
                }
            }

            // 自动滚动到最新日志
            LaunchedEffect(logLines.size) {
                if (logLines.isNotEmpty() && !isMultiSelectMode) {
                    listState.animateScrollToItem(logLines.lastIndex)
                }
            }

            Box(modifier = Modifier.fillMaxSize().background(Color(0xFF101012))) {
                // 1. 占满全屏的日志流
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 80.dp) // 给悬浮控制按钮留出底部空间
                ) {
                    items(logLines.size) { index ->
                        val line = logLines[index]
                        val isSelected = index in selectedIndices
                        
                        // 判定前缀决定颜色（不裁剪任何文本，保证前缀分明）
                        val isWear = line.uppercase().contains("WEAR")
                        val isError = line.uppercase().contains(" E/") || line.uppercase().contains("ERROR")
                        
                        val textColor = when {
                            isError -> Color(0xFFFF5252) // 报错依然用红色高亮
                            isWear -> Color(0xFF00B0FF)  // 手表用科技蓝
                            else -> Color(0xFF00E676)    // 手机用荧光绿
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
                                            // 正常模式下点击单条直接复制
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

                // 2. 右下角极简悬浮工具箱（不遮挡视觉，不占用系统上下栏栏位）
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(16.dp)
                ) {
                    if (isMultiSelectMode) {
                        // 多选状态下的控制条
                        Surface(
                            shape = ShapeDefaults.Large,
                            color = Color(0xFF1F1F23),
                            tonalElevation = 6.dp
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
                        // 正常状态下的极简快捷圆形按钮组
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            // 清空按钮
                            FloatingActionButton(
                                onClick = { PhoneLog.clear(); logLines = emptyList() },
                                containerColor = Color(0xFF2C2C30),
                                contentColor = Color.White,
                                modifier = Modifier.size(42.dp)
                            ) {
                                Icon(Icons.Default.Delete, "清空", modifier = Modifier.size(18.dp))
                            }
                            // 备份导出按钮
                            FloatingActionButton(
                                onClick = {
                                    val backup = PhoneLog.exportBackupFile()
                                    if (backup != null) {
                                        Toast.makeText(context, "备份已存至 Download/WearSync/Log", Toast.LENGTH_LONG).show()
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
