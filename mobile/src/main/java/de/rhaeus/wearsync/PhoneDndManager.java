package de.rhaeus.wearsync;

import android.content.Context;
import android.app.NotificationManager;
import android.content.SharedPreferences;
import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.Wearable;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import com.google.android.gms.wearable.Node;
import java.util.List;

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
     * 🛰️ 正向同步：将手机勿扰状态与掩码推送至手表（融合 WearSyncState 快取优化版）
     */
    public static void syncDndToWear(Context context, boolean isPhoneDndOn) {
        SharedPreferences sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        int currentMask = sp.getInt(KEY_MASK, 15); 

        // 1. 检查总开关（Bit 0）是否放行
        boolean isMasterOn = (currentMask & 1) != 0;
        if (!isMasterOn) {
            PhoneLog.w(TAG, "🛑 [勿扰拦截] 勿扰联动总干线已关闭，放弃同步。");
            return;
        }

        // 2. 映射系统标准的 InterruptionFilter 值
        int systemDndValue = isPhoneDndOn ? 2 : 1; 

        new Thread(() -> {
            try {
                // 🧩 协议一：核心 DND 物理切换信令
                JSONObject dndJson = new JSONObject();
                dndJson.put("sender", "phone");
                dndJson.put("type", "dnd");           
                dndJson.put("dnd_state", systemDndValue); 
                dndJson.put("timestamp", System.currentTimeMillis());
                byte[] dndData = dndJson.toString().getBytes(StandardCharsets.UTF_8);

                // 🧩 协议二：最新的掩码状态值
                JSONObject maskJson = new JSONObject();
                maskJson.put("sender", "phone");
                maskJson.put("type", "status_mask");  
                maskJson.put("mask_value", currentMask);
                maskJson.put("timestamp", System.currentTimeMillis());
                byte[] maskData = maskJson.toString().getBytes(StandardCharsets.UTF_8);

                // 3. 🎯 优先从 WearSyncState 缓存获取活跃的手表节点 ID
                String cachedNodeId = WearSyncState.getNodeId(context);

                if (cachedNodeId != null && !cachedNodeId.isEmpty()) {
                    PhoneLog.d(TAG, "⚡ [勿扰发信] 命中 WearSyncState 缓存节点: " + cachedNodeId + "，正在双发信令套餐...");
                    
                    // 连发射击：先发切换指令，再发状态掩码
                    Tasks.await(Wearable.getMessageClient(context).sendMessage(cachedNodeId, UNIVERSAL_SYNC_PATH, dndData));
                    Tasks.await(Wearable.getMessageClient(context).sendMessage(cachedNodeId, UNIVERSAL_SYNC_PATH, maskData));
                    
                    PhoneLog.d(TAG, "🚀 [正向同步成功] 已通过缓存快车道送达目标手表。");
                } else {
                    // 4. 降级方案：快取失效时，执行物理实时扫描，并顺便踢醒处于 sniff 状态的蓝牙
                    PhoneLog.w(TAG, "⚠️ [勿扰发信降级] WearSyncState 缓存为空，启动物理实时扫描...");
                    java.util.List<com.google.android.gms.wearable.Node> nodes = 
                            Tasks.await(Wearable.getNodeClient(context).getConnectedNodes());

                    if (nodes != null && !nodes.isEmpty()) {
                        for (com.google.android.gms.wearable.Node node : nodes) {
                            PhoneLog.d(TAG, "  └─ 🚀 扫描发现活动触点: " + node.getId() + "，正在刷新持久化快取并灌入信令...");
                            // 刷新缓存记录
                            WearSyncState.setNodeId(context, node.getId());
                            
                            // 连发射击
                            Tasks.await(Wearable.getMessageClient(context).sendMessage(node.getId(), UNIVERSAL_SYNC_PATH, dndData));
                            Tasks.await(Wearable.getMessageClient(context).sendMessage(node.getId(), UNIVERSAL_SYNC_PATH, maskData));
                        }
                        PhoneLog.d(TAG, "🚀 [正向同步成功] 降级物理扫描流发送完成。");
                    } else {
                        PhoneLog.w(TAG, "❌ [勿扰同步失败] 实时物理扫描结果仍为空，手表可能彻底断联。");
                    }
                }
            } catch (Exception e) {
                PhoneLog.e(TAG, "🔴 [勿扰同步崩溃] " + e.getMessage(), e);
            }
        }).start();
    }

}
