package cn.luke.wearsync

import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.InputStream
import java.nio.charset.StandardCharsets
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class LogReceiverService : WearableListenerService() {

    // ✅ 修复1: WearableListenerService 没有 lifecycleScope，必须手动创建 Job 和 Scope
    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    override fun onChannelOpened(channel: ChannelClient.Channel) {
        super.onChannelOpened(channel)
        PhoneLog.d("LogReceiver", "📥 通道已打开: ${channel.path}")

        if (channel.path == "/wear_data_channel/log") {
            // ✅ 修复2: 使用手动创建的 serviceScope 启动协程
            serviceScope.launch {
                var inputStream: InputStream? = null
                try {
                    // 在协程内部调用挂起函数，解决 "should be called only from a coroutine" 错误
                    inputStream = getInputStreamSuspend(channel)
                    PhoneLog.d("LogReceiver", "🟢 成功获取输入流")

                    val buffer = ByteArray(32768)
                    // ✅ 修复3: Kotlin 要求变量必须初始化
                    var readBytes: Int = -1

                    // ✅ 修复4: 在协程作用域内，isActive 可以被正确解析
                    while (isActive && inputStream.read(buffer).also { readBytes = it } != -1) {
                        val logChunk = String(buffer, 0, readBytes, StandardCharsets.UTF_8)
                        PhoneLog.appendFromRemote(logChunk)
                    }
                    PhoneLog.d("LogReceiver", "🔌 输入流已关闭")
                } catch (e: CancellationException) {
                    // 协程被正常取消时，不需要打印错误日志
                    PhoneLog.d("LogReceiver", "⚠️ 日志读取协程已被取消")
                } catch (e: Exception) {
                    PhoneLog.e("LogReceiver", "❌ 读取日志流时出错", e)
                } finally {
                    try {
                        inputStream?.close()
                    } catch (e: Exception) {
                        PhoneLog.e("LogReceiver", "关闭流失败", e)
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // ✅ 修复5: Job.cancel() 需要传入 CancellationException 参数
        serviceJob.cancel(CancellationException("LogReceiverService is being destroyed"))
    }

    /**
     * 将基于回调的 getInputStream 转换为 Kotlin 挂起函数
     */
    /**
     * 将基于回调的 getInputStream 转换为 Kotlin 挂起函数
     */
    private suspend fun getInputStreamSuspend(channel: ChannelClient.Channel): InputStream {
        return suspendCancellableCoroutine { continuation ->
            val task = Wearable.getChannelClient(this@LogReceiverService).getInputStream(channel)

            task.addOnSuccessListener { inputStream ->
                // 仅在协程未被取消时恢复结果，避免向已取消的协程发送数据
                if (continuation.isActive) {
                    continuation.resume(inputStream)
                }
            }.addOnFailureListener { exception ->
                if (continuation.isActive) {
                    continuation.resumeWithException(exception)
                }
            }

            // ✅ 修复：GMS Task 没有 cancel() 方法
            // 当协程被取消时，我们只需忽略后续回调即可（上面已通过 isActive 判断）
            // 不需要也不应该尝试取消底层 Task
            continuation.invokeOnCancellation {
                // 留空或仅记录日志，不调用 task.cancel()
                PhoneLog.d("LogReceiver", "⚠️ 获取输入流的协程已被取消，忽略后续回调")
            }
        }
    }
}
