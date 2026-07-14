package cn.luke.wearsync;

import android.content.Context;
import android.util.Log;
import com.google.android.gms.wearable.ChannelClient;
import com.google.android.gms.wearable.Wearable;
import java.io.BufferedWriter;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public class WearChannelTester {
    private static final String TAG = "Channel_Test_Trace";
    private static final String TEST_CHANNEL_PATH = "/channel_test_path";

    public static void sendLargeChannelTestSignal(Context context) {
        Log.d(TAG, "🚀 [手表测试端] 开始寻找手机配对节点...");

        Wearable.getNodeClient(context)
            .getConnectedNodes()
            .addOnSuccessListener(nodes -> {
                if (nodes == null || nodes.isEmpty()) {
                    Log.e(TAG, "❌ [手表测试端] 未找到配对手机！请确认蓝牙已连接。");
                    return;
                }

                String phoneNodeId = nodes.get(0).getId();
                String phoneNodeName = nodes.get(0).getDisplayName();
                Log.d(TAG, "📱 [手表测试端] 已定位手机: " + phoneNodeName + " (ID: " + phoneNodeId + ")");

                // 打开测试管道
                Wearable.getChannelClient(context)
                    .openChannel(phoneNodeId, TEST_CHANNEL_PATH)
                    .addOnSuccessListener(channel -> {
                        Log.d(TAG, "🤝 [手表测试端] Channel 管道已成功创建，正在获取 OutputStream...");

                        Wearable.getChannelClient(context)
                            .getOutputStream(channel)
                            .addOnSuccessListener(outputStream -> {
                                // 开启后台线程进行高压传输，避免阻塞手表的 UI 线程
                                new Thread(() -> doHighLoadTransmission(outputStream)).start();
                            })
                            .addOnFailureListener(e -> Log.e(TAG, "❌ [手表测试端] 获取 OutputStream 失败", e));
                    })
                    .addOnFailureListener(e -> Log.e(TAG, "❌ [手表测试端] openChannel 失败", e));
            })
            .addOnFailureListener(e -> Log.e(TAG, "❌ [手表测试端] 获取连接节点失败", e));
    }

    private static void doHighLoadTransmission(OutputStream outputStream) {
        Log.d(TAG, "🟢 [手表测试端] 线程启动，准备生成 3 个 512KB 的媒体级大包...");

        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(outputStream, StandardCharsets.UTF_8))) {
            // 1. 生成 512 KB (约 524,288 字符) 的模拟数据缓冲区
            char[] mockChunk = new char[512 * 1024];
            Arrays.fill(mockChunk, 'A'); // 填充测试字符 'A'
            String mockPayload = new String(mockChunk);

            for (int i = 1; i <= 3; i++) {
                Log.d(TAG, "📤 [手表测试端] 正在强力推送第 " + i + " 个大包 (512 KB)...");
                long startTime = System.currentTimeMillis();

                // 写入数据头，方便手机识别大包序列
                writer.write("[PAYLOAD_START_PACKET_" + i + "]");
                // 写入 512KB 载荷
                writer.write(mockPayload);
                // 写入结束换行符（让手机端的 readLine 顺利截断）
                writer.write("\n");
                // 强行冲刷缓冲区，把数据推入蓝牙/Wi-Fi物理管道
                writer.flush();

                long duration = System.currentTimeMillis() - startTime;
                Log.d(TAG, "✨ [手表测试端] 第 " + i + " 个大包发送完毕，耗时: " + duration + " ms");
                
                // 间隔 1.5 秒再发下一个，给蓝牙硬件预留喘息时间
                Thread.sleep(1500);
            }

            Log.d(TAG, "🎉 [手表测试端] 1.5 MB 压力测试数据全部投递完毕！");
        } catch (Exception e) {
            Log.e(TAG, "❌ [手表测试端] 高压传输期间发生崩溃: " + e.getMessage());
        }
    }
}

