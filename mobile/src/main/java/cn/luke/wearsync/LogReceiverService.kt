package cn.luke.wearsync

import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.InputStream
import java.nio.charset.StandardCharsets
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import androidx.lifecycle.lifecycleScope // ✅ 导入 lifecycleScope

class LogReceiverService : WearableListenerService() {

    override fun onChannelOpened(channel: ChannelClient.Channel) {
        super.onChannelOpened(channel)
        Log.d("LogReceiver", "📥 通道已打开: ${channel.path}")

        if (channel.path == "/wear_data_channel/log") {
            // ✅ 使用 lifecycleScope 替代手动创建的 scope
            // 它会在 Service 销毁时自动取消协程，防止内存泄漏
            lifecycleScope.launch(Dispatchers.IO) {
                var inputStream: InputStream? = null
                try {
                    inputStream = getInputStreamSuspend(channel)
                    Log.d("LogReceiver", "🟢 成功获取输入流")

                    val buffer = ByteArray(4096)
                    var readBytes: Int
                    // 循环读取数据块
                    while (isActive && inputStream.read(buffer).also { readBytes = it } != -1) {
                        val logChunk = String(buffer, 0, readBytes, StandardCharsets.UTF_8)
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

    // ✅ onDestroy 方法不再需要，因为 lifecycleScope 会自动处理协程的取消

    // ✅ 新增：一个挂起函数，用于以 Kotlin 协程的方式获取 InputStream
    private suspend fun getInputStreamSuspend(channel: ChannelClient.Channel): InputStream {
        return suspendCancellableCoroutine { continuation ->
            val task = Wearable.getChannelClient(this).getInputStream(channel)
            task.addOnSuccessListener { inputStream ->
                continuation.resume(inputStream)
            }.addOnFailureListener { exception ->
                continuation.resumeWithException(exception)
            }

            // 如果协程被取消，也取消这个任务
            continuation.invokeOnCancellation {
                task.cancel()
            }
        }
    }
}
