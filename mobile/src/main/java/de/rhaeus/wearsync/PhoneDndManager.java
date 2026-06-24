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
     * 🛰️ 正向同步：当手机系统勿扰状态改变时，打包最新的 Mask 推送至手表（对齐在线扫描降级逻辑）
     */
    public static void syncDndToWear(Context context, boolean isPhoneDndOn) {
        SharedPreferences sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        int currentMask = sp.getInt(KEY_MASK, 15);

        boolean isMasterOn = (currentMask & 1) != 0;
        if (!isMasterOn) {
            PhoneLog.w(TAG, "🛑 [勿扰拦截] 勿扰联动总干线已关闭，放弃向手表同步状态掩码。");
            return;
        }

        PhoneLog.d(TAG, "🛰️ [正向同步启动] 准备打包状态掩码。手机勿扰=" + isPhoneDndOn + ", Mask=" + currentMask);

        new Thread(() -> {
            try {
                JSONObject json = new JSONObject();
                json.put("sender", "phone");
                json.put("type", "status_mask");
                json.put("dnd_sync_mask", currentMask); 
                json.put("timestamp", System.currentTimeMillis());

                byte[] data = json.toString().getBytes(StandardCharsets.UTF_8);
                
                // 1. 优先读取缓存
                String nodeId = WearSyncState.getNodeId(context);

                if (nodeId != null && !nodeId.isEmpty()) {
                    // 2. 命中缓存直接发送
                    Tasks.await(Wearable.getMessageClient(context).sendMessage(nodeId, UNIVERSAL_SYNC_PATH, data));
                    PhoneLog.d(TAG, "🚀 [正向同步成功] 状态掩码 [" + currentMask + "] 已推送到缓存节点: " + nodeId);
                } else {
                    // 3. 🔥 🔥 🔥 [降级补偿] 缓存为空，触发在线扫描，对齐 PhoneAlarmManager 逻辑
                    PhoneLog.w(TAG, "⚠️ [正向同步降级] 活跃节点缓存为空，触发在线扫描机制...");
                    java.util.List<com.google.android.gms.wearable.Node> nodes = 
                            Tasks.await(Wearable.getNodeClient(context).getConnectedNodes());

                    if (nodes != null && !nodes.isEmpty()) {
                        PhoneLog.d(TAG, "🔍 [正向同步降级] 在线扫描发现 " + nodes.size() + " 个可通信的手表设备");
                        for (com.google.android.gms.wearable.Node node : nodes) {
                            PhoneLog.d(TAG, "  └─ 正在激活并重写节点缓存: " + node.getId());
                            // 重新写入缓存，供后续模块使用
                            WearSyncState.setNodeId(context, node.getId()); 
                            // 补发勿扰掩码
                            Tasks.await(Wearable.getMessageClient(context).sendMessage(node.getId(), UNIVERSAL_SYNC_PATH, data));
                        }
                        PhoneLog.d(TAG, "🚀 [正向同步成功] 降级扫描同步掩码机制发射完成。");
                    } else {
                        PhoneLog.w(TAG, "❌ [正向同步断联] 传输失败：在线没有发现任何物理手表，放弃本次发射。");
                    }
                }
            } catch (Exception e) {
                PhoneLog.e(TAG, "🔴 [正向同步崩溃] 异步推送当前面板掩码失败: " + e.getMessage(), e);
            }
        }).start();
    }
}
