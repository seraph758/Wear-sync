package de.rhaeus.wearsync;

import android.app.NotificationManager;
import android.content.Context;
import android.util.Log;
import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class PhoneDndManager {
    private static final String TAG = "WearSync_PhoneDnd";

    // 🎯 核心保留：由 UI 界面即时计算并写入的静态变量。
    // 位权定义：1 = 睡眠模式手势宏，2 = 短震动开关，4 = 省电模式
    public static int cachedMaskValue = 0;

    // 处理来自手表的反向同步请求
    public static void handleIncomingAction(Context context, int wearSystemDndVal) {
        Log.d(TAG, "📥 收到来自手表的反向硬勿扰同步，目标系统值: " + wearSystemDndVal);
        try {
            NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null && nm.isNotificationPolicyAccessGranted()) {
                nm.setInterruptionFilter(wearSystemDndVal);
                Log.d(TAG, "☯️ [反向同步成功] 已将手机系统硬勿扰过滤器设置为: " + wearSystemDndVal);
            }
        } catch (Exception e) {
            Log.e(TAG, "应用手表反向勿扰状态失败", e);
        }
    }

    // 手机状态或配置变更时，向手表正向同步
    public static void syncDndToWear(Context context, int currentFilter) {
        new Thread(() -> {
            try {
                JSONObject json = new JSONObject();
                json.put("sender", "phone");
                json.put("type", "dnd");
                json.put("action", "SYNC_DND_STATUS");
                json.put("dnd_state", currentFilter);
                // 🌟 重新补回：将核心 cachedMaskValue 放入信令发给手表
                json.put("mask_value", cachedMaskValue);
                json.put("timestamp", System.currentTimeMillis());
                
                Log.d(TAG, "🔢 [高性能发信准备] 当前缓存的开关 Mask = " + cachedMaskValue + " | 手机当前状态 = " + currentFilter);
                
                byte[] data = json.toString().getBytes(StandardCharsets.UTF_8);
                List<Node> nodes = Tasks.await(Wearable.getNodeClient(context).getConnectedNodes());
                for (Node node : nodes) {
                    Wearable.getMessageClient(context).sendMessage(node.getId(), "/wear-universal-sync", data);
                }
                Log.d(TAG, "🚀 [同步成功] 已将最新同步信令推送到手表");
            } catch (Exception e) {
                Log.e(TAG, "DND正向同步失败", e);
            }
        }).start();
    }
}
