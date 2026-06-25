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
 * 🌓 勿扰与配置掩码核心业务专属管理器 (单包精简优化版)
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
     * 🛰️ 正向同步：将全量状态通过单包 Mask 推送至手表（极致省电与带宽优化）
     */
    /**
     * 🛰️ 正向同步：将手机勿扰状态与掩码推送至手表（双包标准版，业务语义清晰）
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
                // 🧩 包一：核心 DND 物理切换信令
                JSONObject dndJson = new JSONObject();
                dndJson.put("sender", "phone");
                dndJson.put("type", "dnd");           
                dndJson.put("dnd_state", systemDndValue); 
                dndJson.put("timestamp", System.currentTimeMillis());
                byte[] dndData = dndJson.toString().getBytes(StandardCharsets.UTF_8);

                // 🧩 包二：纯净的掩码配置值（保持原汁原味，不污染配置）
                JSONObject maskJson = new JSONObject();
                maskJson.put("sender", "phone");
                maskJson.put("type", "status_mask");  
                maskJson.put("mask_value", currentMask);
                maskJson.put("timestamp", System.currentTimeMillis());
                byte[] maskData = maskJson.toString().getBytes(StandardCharsets.UTF_8);

                // 3. 🎯 优先从 WearSyncState 缓存获取活跃的手表节点 ID
                String cachedNodeId = WearSyncState.getNodeId(context);

                if (cachedNodeId != null && !cachedNodeId.isEmpty()) {
                    PhoneLog.d(TAG, "⚡ [勿扰发信] 正在向缓存节点 [" + cachedNodeId + "] 连发 DND + Mask 套餐...");
                    
                    // 🔥 恢复双发射击
                    Tasks.await(Wearable.getMessageClient(context).sendMessage(cachedNodeId, UNIVERSAL_SYNC_PATH, dndData));
                    Tasks.await(Wearable.getMessageClient(context).sendMessage(cachedNodeId, UNIVERSAL_SYNC_PATH, maskData));
                    
                    PhoneLog.d(TAG, "🚀 [正向同步成功] 双包已通过缓存通道送达。");
                } else {
                    // 4. 降级方案：物理扫描
                    PhoneLog.w(TAG, "⚠️ [勿扰发信降级] 缓存为空，启动物理实时扫描...");
                    java.util.List<com.google.android.gms.wearable.Node> nodes = 
                            Tasks.await(Wearable.getNodeClient(context).getConnectedNodes());

                    if (nodes != null && !nodes.isEmpty()) {
                        for (com.google.android.gms.wearable.Node node : nodes) {
                            PhoneLog.d(TAG, "  └─ 🚀 发现触点: " + node.getId() + "，正在双发信令...");
                            WearSyncState.setNodeId(context, node.getId());
                            
                            // 🔥 恢复双发射击
                            Tasks.await(Wearable.getMessageClient(context).sendMessage(node.getId(), UNIVERSAL_SYNC_PATH, dndData));
                            Tasks.await(Wearable.getMessageClient(context).sendMessage(node.getId(), UNIVERSAL_SYNC_PATH, maskData));
                        }
                        PhoneLog.d(TAG, "🚀 [正向同步成功] 降级物理双包发送完成。");
                    }
                }
            } catch (Exception e) {
                PhoneLog.e(TAG, "🔴 [勿扰同步崩溃] " + e.getMessage(), e);
            }
        }).start();
    }
}
