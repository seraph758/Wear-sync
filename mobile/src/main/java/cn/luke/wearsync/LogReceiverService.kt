package cn.luke.wearsync

import android.util.Log
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.InputStream
import java.nio.charset.StandardCharsets

// ... 其他导入保持不变

class LogReceiverService : WearableListenerService() {

    private val job = Job()
    private val scope = CoroutineScope(Dispatchers.IO + job)

    override fun onChannelOpened(channel: ChannelClient.Channel) {
        super.onChannelOpened(channel)
        Log.d("LogReceiver", "📥 通道已打开: ${channel.path}")

        if (channel.path == "/wear_data_channel/log") {
            scope.launch {
                var inputStream: InputStream? = null
                try {
                    val inputStreamTask = Wearable.getChannelClient(this@LogReceiverService).getInputStream(channel)
                    inputStream = Tasks.await(inputStreamTask)
                    Log.d("LogReceiver", "🟢 成功获取输入流")

                    val buffer = ByteArray(4096)
                    var readBytes: Int
                    // 循环读取数据块
                    while (isActive && inputStream.read(buffer).also { readBytes = it } != -1) {
                        val logChunk = String(buffer, 0, readBytes, StandardCharsets.UTF_8)
                        
                        // ✅ 关键修改：将接收到的数据块交给 PhoneLog 处理
                        // 注意：网络流读取是按数据块（chunk）进行的，一行日志可能会被分在两个块里。
                        // 这里的实现假设 appendFromRemote 能处理这种情况，或者手表端是按行发送的。
                        PhoneLog.appendFromRemote(logChunk)
                    }
                    Log.d("LogReceiver", "🔌 输入流已关闭")
                } catch (e: Exception) {
                    Log.e("LogReceiver", "❌ 读取日志流时出错", e)
                } finally {
                    try {
                        inputStream?.close()
                    } catch (e: Exception) {
                        Log.e("LogReceiver", "关闭流失败", e)
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        job.cancel()
    }
}
