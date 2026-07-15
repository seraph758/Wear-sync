package cn.luke.wearsync;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.ChannelClient;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class WearChannelTester {
    private static final String TAG = "WearChannelTester";
    private static final String TEST_CHANNEL_PATH = "/channel_test_path";
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    private static void showToast(Context context, String msg) {
        mainHandler.post(() -> Toast.makeText(context.getApplicationContext(), msg, Toast.LENGTH_SHORT).show());
    }

    public static void sendLargeChannelTestSignal(Context context) {
        Context appContext = context.getApplicationContext();
        showToast(appContext, "🛰️ 正在掃描手機配對節點...");

        new Thread(() -> {
            ChannelClient.Channel channel = null;
            OutputStream outputStream = null;
            ChannelClient channelClient = Wearable.getChannelClient(appContext);

            try {
                Log.d(TAG, "🛰️ [測試端] 開始異步獲取在線節點...");
                // 設置 5 秒超時，防止因為藍牙 Sniff 狀態導致 Tasks.await 永久阻塞
                List<Node> nodes = Tasks.await(
                        Wearable.getNodeClient(appContext).getConnectedNodes(),
                        5,
                        TimeUnit.SECONDS
                );

                if (nodes == null || nodes.isEmpty()) {
                    Log.e(TAG, "❌ [測試端] 失敗：未找到任何配對的手機。");
                    showToast(appContext, "❌ 失敗：未找到配對的手機節點！");
                    return;
                }

                // 尋找並鎖定手機節點
                String targetNodeId = null;
                for (Node node : nodes) {
                    if (node.isNearby()) { // 優先選擇物理鄰近（藍牙/近距離Wi-Fi）節點
                        targetNodeId = node.getId();
                        break;
                    }
                }
                if (targetNodeId == null) {
                    targetNodeId = nodes.get(0).getId();
                }

                Log.d(TAG, "🎯 [測試端] 成功鎖定手機目標節點 ID: " + targetNodeId);
                showToast(appContext, "🎯 已鎖定手機，建立數據管道中...");

                // 2. 開闢通道（設置 8 秒超時）
                channel = Tasks.await(
                        channelClient.openChannel(targetNodeId, TEST_CHANNEL_PATH),
                        8,
                        TimeUnit.SECONDS
                );

                Log.d(TAG, "🟢 [測試端] 管道握手成功，獲取 OutputStream...");
                outputStream = Tasks.await(
                        channelClient.getOutputStream(channel),
                        5,
                        TimeUnit.SECONDS
                );

                showToast(appContext, "🚀 管道建立成功！開始傳送高壓數據...");

                // 3. 灌入 3 個大包（共約 150KB）
                String payload1 = "[PAYLOAD_START_PACKET_1]" + "A".repeat(50 * 1024) + "\n";
                String payload2 = "[PAYLOAD_START_PACKET_2]" + "B".repeat(50 * 1024) + "\n";
                String payload3 = "[PAYLOAD_START_PACKET_3]" + "C".repeat(50 * 1024) + "\n";

                Log.d(TAG, "🚀 [測試端] 正在寫入第 1 包...");
                outputStream.write(payload1.getBytes(StandardCharsets.UTF_8));
                outputStream.flush();

                Log.d(TAG, "🚀 [測試端] 正在寫入第 2 包...");
                outputStream.write(payload2.getBytes(StandardCharsets.UTF_8));
                outputStream.flush();

                Log.d(TAG, "🚀 [測試端] 正在寫入第 3 包...");
                outputStream.write(payload3.getBytes(StandardCharsets.UTF_8));
                outputStream.flush();

                // 4. 安全關閉（🟢 完美修復：已將 closeChannel 改為 close）
                outputStream.close();
                Tasks.await(channelClient.close(channel), 5, TimeUnit.SECONDS);

                Log.d(TAG, "🏆 [測試端] 發送完畢，通道關閉。");
                showToast(appContext, "🏆 壓力數據發送完畢！請查看手機 Logfox。");

            } catch (Exception e) {
                Log.e(TAG, "❌ [測試端] 發生異常崩潰: " + e.getMessage(), e);
                showToast(appContext, "❌ 傳輸中斷: " + e.getMessage());
                
                // 發生異常時，嘗試進行物理釋放，防止通道殘留鎖死（🟢 完美修復：已將 closeChannel 改為 close）
                try {
                    if (outputStream != null) outputStream.close();
                    if (channel != null) channelClient.close(channel);
                } catch (Exception ignored) {}
            }
        }).start();
    }
}
