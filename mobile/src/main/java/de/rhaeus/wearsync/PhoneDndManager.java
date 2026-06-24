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
     * 🛰️ 正向同步：当手机系统勿扰状态改变时，由哨兵服务调用此方法打包最新的 Mask 推送至手表
     */
    public static void syncDndToWear(Context context, boolean isPhoneDndOn) {
        // 1. 读取 UI 端存入的最新 Mask 值
        SharedPreferences sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        int currentMask = sp.getInt(KEY_MASK, 15); // 默认 15 (全部开启)

        // 2. 位运算解码检查：Bit 0 (1) 是否为总开关
        boolean isMasterOn = (currentMask & 1) != 0;
        if (!isMasterOn) {
            PhoneLog.w(TAG, "🛑 [勿扰拦截] 勿扰联动总干线已关闭，放弃向手表同步状态掩码。");
            return;
        }

        PhoneLog.d(TAG, "🛰️ [正向同步启动] 准备打包状态掩码并异步发送至手表。当前手机勿扰=" + isPhoneDndOn + ", 当前 Mask=" + currentMask);

        new Thread(() -> {
            try {
                JSONObject json = new JSONObject();
                json.put("sender", "phone");
                json.put("type", "status_mask");
                // 这里的 key 与您在 KT 里的逻辑完全对齐
                json.put("dnd_sync_mask", currentMask); 
                json.put("timestamp", System.currentTimeMillis());

                byte[] data = json.toString().getBytes(StandardCharsets.UTF_8);
                
                // 3. 严格从指定的 WearSyncState 获取活跃节点 ID
                String nodeId = WearSyncState.getNodeId(context);

                if (nodeId != null && !nodeId.isEmpty()) {
                    Tasks.await(Wearable.getMessageClient(context).sendMessage(nodeId, UNIVERSAL_SYNC_PATH, data));
                    PhoneLog.d(TAG, "🚀 [正向同步成功] 状态掩码 [" + currentMask + "] 已成功推送到缓存节点: " + nodeId);
                } else {
                    PhoneLog.w(TAG, "⚠️ [正向同步失败] 活跃手表节点缓存为空，放弃本次状态推送");
                }
            } catch (Exception e) {
                PhoneLog.e(TAG, "🔴 [正向同步崩溃] 异步推送当前面板掩码失败: " + e.getMessage(), e);
            }
        }).start();
    }
}
