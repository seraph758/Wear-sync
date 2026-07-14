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

            // 定时去底层统一池子里同步 10 分钟数据即可，不需要自己再算逻辑
            LaunchedEffect(Unit) {
                while (true) {
                    val freshLogs = PhoneLog.getLatestTenMinutesLogs()
                    if (logLines.size != freshLogs.size) {
                        logLines = freshLogs
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
                                isError -> Color(0xFFFF5252)
                                isWear -> Color(0xFF00B0FF)
                                else -> Color(0xFF00E676)
                            }
                            Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 3.dp)) {
                                Text(text = line, color = textColor, fontSize = 11.sp, fontFamily = FontFamily.Monospace, lineHeight = 14.sp)
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
