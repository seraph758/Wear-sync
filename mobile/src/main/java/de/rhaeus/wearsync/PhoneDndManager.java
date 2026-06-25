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
    public static void syncDndToWear(Context context, int interruptionFilter) {
        SharedPreferences sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        int currentMask = sp.getInt(KEY_MASK, 15); 

        new Thread(() -> {
            try {
                // 🧩 槍響第一聲：先發送純淨的本地持久化掩碼包 (讓手錶先做好配置準備)
                JSONObject maskJson = new JSONObject();
                maskJson.put("sender", "phone");
                maskJson.put("type", "status_mask");  
                maskJson.put("mask_value", currentMask);
                maskJson.put("timestamp", System.currentTimeMillis());
                byte[] maskData = maskJson.toString().getBytes(StandardCharsets.UTF_8);

                // 🧩 槍響第二聲：再發送原汁原味的系統勿擾狀態包 (帶動聯動執行)
                JSONObject dndJson = new JSONObject();
                dndJson.put("sender", "phone");
                dndJson.put("type", "dnd");           
                dndJson.put("dnd_state", interruptionFilter); // ➔ 原生舊代碼底層數值，不作人為轉換
                dndJson.put("timestamp", System.currentTimeMillis());
                byte[] dndData = dndJson.toString().getBytes(StandardCharsets.UTF_8);

                String nodeId = WearSyncState.getNodeId(context);
                if (nodeId != null && !nodeId.isEmpty()) {
                    // 🔥 先 Mask，後 DND
                    Tasks.await(Wearable.getMessageClient(context).sendMessage(nodeId, UNIVERSAL_SYNC_PATH, maskData));
                    Thread.sleep(50); // 微調間隔，確保手錶隊列順序不亂
                    Tasks.await(Wearable.getMessageClient(context).sendMessage(nodeId, UNIVERSAL_SYNC_PATH, dndData));
                    PhoneLog.d(TAG, "🚀 [正向發射成功] 已先發 Mask(" + currentMask + ")，後發 DND 原生Filter(" + interruptionFilter + ")");
                }
            } catch (Exception e) {
                PhoneLog.e(TAG, "🔴 [發信異常] ", e);
            }
        }).start();
    }
}
