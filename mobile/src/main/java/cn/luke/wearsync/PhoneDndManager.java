package cn.luke.wearsync;

import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import androidx.core.app.NotificationManagerCompat;

import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.Wearable;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;

/**
 * 🌓 勿扰与配置掩码核心业务专属管理器
 * 职责：
 * 1. 处理手表发来的逆向同步请求 (handleIncomingAction)
 * 2. 处理手机 UI 或系统触发的正向同步请求 (syncDndToWear)
 */
public class PhoneDndManager {
    private static final String TAG = "WearSync_PhoneDnd";
    private static final String PREFS_NAME = "dndsync_prefs";
    private static final String KEY_MASK = "dnd_sync_mask";
    private static final String KEY_PULL_DOWN_DELAY = "screen_pull_down_interval";
    private static final String UNIVERSAL_SYNC_PATH = "/wear-universal-sync";

    // ==========================================
    // 1. 逆向同步入口 (手表 -> 手机)
    // ==========================================

    /**
     * 处理手表发来的 DND 变更指令
     * @param context 上下文
     * @param targetFilter 手表当前的 DND 系统原始值 (1/2/3/4)
     */
    public static void handleIncomingAction(Context context, int targetFilter) {
        PhoneLog.d(TAG, "📥 [逆向同步] 收到手表反向勿扰信令 ➔ 目标值 = " + targetFilter);
        try {
            NotificationManager nm = context.getSystemService(NotificationManager.class);
            if (nm == null) {
                PhoneLog.e(TAG, "🔴 NotificationManager 获取失败");
                return;
            }

            // 🔑 Step 1: 直接用系统原始枚举值比对
            int currentFilter = nm.getCurrentInterruptionFilter();
            if (currentFilter == targetFilter) {
                PhoneLog.d(TAG, "✅ [拦截] DND状态一致(filter=" + currentFilter + ")，跳过");
                return;
            }

            // 🔑 Step 2: 权限检查
            if (!nm.isNotificationPolicyAccessGranted()) {
                PhoneLog.w(TAG, "⚠️ [逆向同步失败] 缺少 NotificationPolicyAccess 权限");
                return;
            }

            // 🔑 Step 3: 标记内部更新，防止回环
            PhoneSyncListenerService.isInternalUpdate = true;

            // 🔑 Step 4: 直接设置目标值（无需任何映射）
            nm.setInterruptionFilter(targetFilter);
            PhoneLog.d(TAG, "✨ [逆向同步成功] filter: " + currentFilter + " → " + targetFilter);

            // 🔑 Step 5: 延迟重置标记
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                PhoneSyncListenerService.isInternalUpdate = false;
                PhoneLog.d(TAG, "🔓 逆向同步标记已重置");
            }, 5000);

        } catch (Exception e) {
            PhoneLog.e(TAG, "🔴 [逆向同步异常] " + e.getMessage(), e);
        }
    }

    // ==========================================
    // 2. 正向同步入口 (手机 -> 手表)
    // ==========================================

    /**
     * 兼容入口：非回调触发场景使用（如手动同步、定时同步）
     * 内部重新读取系统当前值，再委托给精确版本
     */
    public static void syncDndToWear(Context context) {
        int rawFilter = NotificationManagerCompat.from(context).getCurrentInterruptionFilter();
        syncDndToWear(context, rawFilter);
    }

    /**
     * 精确入口：由 onInterruptionFilterChanged 回调触发
     * @param context 上下文
     * @param currentFilter 回调传入的系统原始 DND 值 (1/2/3/4)，避免异步时序偏差
     */
    public static void syncDndToWear(Context context, int currentFilter) {
        SharedPreferences spPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        int currentMask = spPrefs.getInt(KEY_MASK, 15);

        new Thread(() -> {
            try {
                // 🔑 直接使用原始值，不做任何归一化
                int delay = spPrefs.getInt(KEY_PULL_DOWN_DELAY, 500);
                PhoneLog.d("PullDownDelay", "实际读取延迟: " + delay + "ms");

                JSONObject json = new JSONObject();
                json.put("sender", "phone");
                json.put("type", "dnd");
                json.put("dnd_state", currentFilter);   // ✅ 原始值 1/2/3/4 直通
                json.put("mask", currentMask);
                json.put("pullDownDelayMs", delay);
                json.put("timestamp", System.currentTimeMillis());
                json.put("source", "phone_dnd_change");

                byte[] data = json.toString().getBytes(StandardCharsets.UTF_8);
                String nodeId = WearSyncState.getNodeId(context);

                if (nodeId == null || nodeId.isEmpty()) {
                    PhoneLog.w(TAG, "⚠️ [DND发送失败] NodeId为空");
                    return;
                }

                PhoneLog.d(TAG, "📤 [正向同步] dnd_state=" + currentFilter + " delay=" + delay);
                Tasks.await(Wearable.getMessageClient(context)
                        .sendMessage(nodeId, UNIVERSAL_SYNC_PATH, data));

            } catch (Exception e) {
                PhoneLog.e(TAG, "🔴 [DND发送异常]", e);
            }
        }).start();
    }
}