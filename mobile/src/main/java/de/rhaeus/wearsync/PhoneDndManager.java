package de.rhaeus.wearsync;

import android.content.Context;
import android.app.NotificationManager;
import android.content.SharedPreferences;
import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.Wearable;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;

/**
 * 🌓 勿扰与配置掩码核心业务专属管理器 (解耦重构版)
 */
public class PhoneDndManager {
    private static final String TAG = "WearSync_PhoneDnd";
    private static final String PREFS_NAME = "wearsync_prefs";
    private static final String KEY_MASK = "dnd_sync_mask";
    private static final String UNIVERSAL_SYNC_PATH = "/wear-universal-sync";

    /**
     * 📥 处理从 PhoneSyncListenerService 转发过来的手表反向勿扰同步指令
     */
    public static void handleIncomingAction(Context context, int wearSystemDndVal) {
        PhoneLog.d(TAG, "📥 [逆向同步] 收到手表反向勿扰信令 ➔ 目标值 = " + wearSystemDndVal);
        try {
            NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                boolean hasPermission = nm.isNotificationPolicyAccessGranted();
                if (hasPermission) {
                    nm.setInterruptionFilter(wearSystemDndVal);
                    PhoneLog.d(TAG, "✨ [逆向同步成功] 手机系统勿扰模式已成功设置为 = " + wearSystemDndVal);
                } else {
                    PhoneLog.w(TAG, "⚠️ [逆向同步失败] 手机端缺乏 NotificationPolicyAccess 权限！");
                }
            }
        } catch (Exception e) {
            PhoneLog.e(TAG, "🔴 [逆向同步异常] 修改手机勿扰状态失败: " + e.getMessage(), e);
        }
    }

   /**
     * 🛰️ 正向同步：依照手表端解析协议，拆解 Mask 并打包发送
     */
    public static void syncDndToWear(Context context, boolean isPhoneDndOn) {
        SharedPreferences sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        int currentMask = sp.getInt(KEY_MASK, 15);

        boolean isMasterOn = (currentMask & 1) != 0;
        if (!isMasterOn) {
            PhoneLog.w(TAG, "🛑 [勿扰拦截] 勿扰联动总干线已关闭，放弃向手表同步状态掩码。");
            return;
        }

        // 拆解出具体的子开关状态，用于适配手表端的 optBoolean
        boolean vibrateOn = (currentMask & 2) != 0;
        boolean sleepOn = (currentMask & 4) != 0;
        boolean powerOn = (currentMask & 8) != 0;

        PhoneLog.d(TAG, "🛰️ [正向同步启动] 对齐手表协议打包中...");

        new Thread(() -> {
            try {
                JSONObject json = new JSONObject();
                json.put("sender", "phone");
                json.put("type", "status_mask");
                
                // 🔥 🔥 🔥 严格对齐手表 WearSyncListenerService 的字段读取
                json.put("dnd_sync_mask", isPhoneDndOn); // 手表用它当做是否开启勿扰的布尔值
                json.put("vibrate_feedback", vibrateOn);
                json.put("sleep_mode_sync", sleepOn);
                json.put("power_saving_sync", powerOn);
                json.put("timestamp", System.currentTimeMillis());

                byte[] data = json.toString().getBytes(StandardCharsets.UTF_8);
                String nodeId = WearSyncState.getNodeId(context);

                if (nodeId != null && !nodeId.isEmpty()) {
                    Tasks.await(Wearable.getMessageClient(context).sendMessage(nodeId, UNIVERSAL_SYNC_PATH, data));
                    PhoneLog.d(TAG, "🚀 [正向同步成功] 协议对齐数据已送达缓存节点: " + nodeId);
                } else {
                    // 降级扫描
                    List<Node> nodes = Tasks.await(Wearable.getNodeClient(context).getConnectedNodes());
                    if (nodes != null && !nodes.isEmpty()) {
                        for (Node node : nodes) {
                            WearSyncState.setNodeId(context, node.getId());
                            Tasks.await(Wearable.getMessageClient(context).sendMessage(node.getId(), UNIVERSAL_SYNC_PATH, data));
                        }
                    }
                }
            } catch (Exception e) {
                PhoneLog.e(TAG, "🔴 [正向同步崩溃] 发送失败: " + e.getMessage(), e);
            }
        }).start();
    }
}
