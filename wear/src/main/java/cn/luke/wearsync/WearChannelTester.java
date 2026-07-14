package cn.luke.wearsync;

import android.content.Context;
import android.util.Log;
import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.ChannelClient;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class WearChannelTester {
    private static final String TAG = "WearChannelTester";
    private static final String TEST_CHANNEL_PATH = "/channel_test_path";

    public static void sendLargeChannelTestSignal(Context context) {
        // 🚀 啟動異步背景線程，先尋找配對的手機節點
        new Thread(() -> {
            try {
                Log.d(TAG, "🛰️ [測試端] 開始掃描配對的手機節點...");
                List<Node> nodes = Tasks.await(Wearable.getNodeClient(context).getConnectedNodes());
                
                if (nodes == null || nodes.isEmpty()) {
                    Log.e(TAG, "❌ [測試端] 失敗：未找到任何在線的手機配對節點！請檢查藍牙連線。");
                    return;
                }

                // 獲取當前相連的手機節點 ID
                String targetNodeId = nodes.get(0).getId();
                Log.d(TAG, "🎯 [測試端] 成功鎖定手機目標節點 ID: " + targetNodeId);

                // 2. 正式向該手機節點發起 Channel 管道握手
                ChannelClient channelClient = Wearable.getChannelClient(context);
                ChannelClient.Channel channel = Tasks.await(
                        channelClient.openChannel(targetNodeId, TEST_CHANNEL_PATH)
                );

                Log.d(TAG, "🟢 [測試端] 管道開闢成功！準備獲取 OutputStream 寫入高壓數據流...");
                OutputStream outputStream = Tasks.await(channelClient.getOutputStream(channel));

                // 3. 開始灌入大包測試數據
                String payload1 = "[PAYLOAD_START_PACKET_1]" + "A".repeat(50 * 1024) + "\n"; // 50KB 數據
                String payload2 = "[PAYLOAD_START_PACKET_2]" + "B".repeat(50 * 1024) + "\n"; // 50KB 數據
                String payload3 = "[PAYLOAD_START_PACKET_3]" + "C".repeat(50 * 1024) + "\n"; // 50KB 數據

                Log.d(TAG, "🚀 [測試端] 正在發送第 1 個大包...");
                outputStream.write(payload1.getBytes(StandardCharsets.UTF_8));
                outputStream.flush();

                Log.d(TAG, "🚀 [測試端] 正在發送第 2 個大包...");
                outputStream.write(payload2.getBytes(StandardCharsets.UTF_8));
                outputStream.flush();

                Log.d(TAG, "🚀 [測試端] 正在發送第 3 個大包...");
                outputStream.write(payload3.getBytes(StandardCharsets.UTF_8));
                outputStream.flush();

                // 關閉流與通道，觸發手機端的測試完成報告
                outputStream.close();
                Tasks.await(channelClient.close(channel));
                Log.d(TAG, "🏆 [測試端] 所有數據包發送完畢，通道已安全關閉。");

            } catch (Exception e) {
                Log.e(TAG, "❌ [測試端] 發送高壓大包流時發生異常崩潰: " + e.getMessage(), e);
            }
        }).start();
    }
}
