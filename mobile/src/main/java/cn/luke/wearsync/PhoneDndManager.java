package cn.luke.wearsync;

import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;
import androidx.core.app.NotificationManagerCompat;
import androidx.preference.PreferenceManager;
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
     * @param wearSystemDndVal 手表当前的 DND 状态值 (目标值)
     */
    public static void handleIncomingAction(Context context, int wearSystemDndVal) {
        PhoneLog.d(TAG, "📥 [逆向同步] 收到手表反向勿扰信令 ➔ 目标值 = " + wearSystemDndVal);
        try {
            NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                // 🎯 核心安全校验：先获取手机当前的 filter 状态
                int currentPhoneFilter = nm.getCurrentInterruptionFilter();
                
                // 🎯 只有当手表传过来的状态与手机当前状态不相等时，才允许修改，原地拦截重复信号
                if (currentPhoneFilter != wearSystemDndVal) {
                    boolean hasPermission = nm.isNotificationPolicyAccessGranted();
                    if (hasPermission) {
                        // 🔒 关闭正向发射开关，告诉手机监听器：「这是我自己的改的，不要再发回给手表了！」
                        // 注意：请确保 PhoneSyncNotificationService 中有对应的 isInternalUpdate 变量逻辑
                        // PhoneSyncNotificationService.isInternalUpdate = true; 
                        
                        nm.setInterruptionFilter(wearSystemDndVal);
                        PhoneLog.d(TAG, "✨ [逆向同步成功] 手机系统勿扰模式已成功设置为 = " + wearSystemDndVal);
                    } else {
                        PhoneLog.w(TAG, "⚠️ [逆向同步失败] 手机端缺乏 NotificationPolicyAccess 权限！");
                    }
                } else {
                    PhoneLog.d(TAG, "✅ [逆向同步拦截] 手机当前勿扰 Filter 已经是 " + wearSystemDndVal + "，判定为回流或重复信号，不作处理。");
                }
            }
        } catch (Exception e) {
            PhoneLog.e(TAG, "🔴 [逆向同步异常] 修改手机勿扰状态失败: " + e.getMessage(), e);
        }
    }

    // ==========================================
    // 2. 正向同步入口 (手机 -> 手表)
    // ==========================================

    /**
     * 场景 A：UI (MainFragment) 触发
     * 用户在设置界面拖动滑块时调用。
     * 逻辑：实时获取当前 DND 状态 + 使用 UI 传入的新间隔值 -> 发送
     *
     * @param context 上下文
     * @param pullDownDelayMs UI 传入的最新间隔值
     */

    /**
     * 场景 B：系统监听器 (PhoneSyncNotificationService) 触发
     * 当手机系统勿扰模式发生变化时调用。
     * 逻辑：实时获取当前 DND 状态 + 读取本地保存的间隔配置 -> 发送
     *
     * @param context 上下文
     */
    /**
     * 场景：系统监听器 (PhoneSyncNotificationService) 触发
     * 仅在手机系统勿扰模式发生变化时调用
     */
    public static void syncDndToWear(Context context) {
                // ✅ 初始化SP实例（整个方法复用）
                SharedPreferences spPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                int currentMask = spPrefs.getInt(KEY_MASK, 15);
        new Thread(() -> {
            try {
                // 1. 实时获取当前 DND 状态
                int interruptionFilter = NotificationManagerCompat.from(context).getCurrentInterruptionFilter();

                // 2. ✅ 实时读取用户刚滑动保存的最新延迟值
                int delay = spPrefs.getInt(KEY_PULL_DOWN_DELAY, 500);
PhoneLog.d("PullDownDelay", "实际读取延迟: " + delay + "ms");             

                // 3. 打包发送（状态+最新延迟作为一个完整载荷）
                JSONObject json = new JSONObject();
                json.put("sender", "phone");
                json.put("type", "dnd");
                json.put("dnd_state", interruptionFilter);
                json.put("mask", currentMask);
                json.put("pullDownDelayMs", delay); // ✅ 新鲜数值
                json.put("timestamp", System.currentTimeMillis());

                byte[] data = json.toString().getBytes(StandardCharsets.UTF_8);
                String nodeId = WearSyncState.getNodeId(context);

                if (nodeId == null || nodeId.isEmpty()) {
                    PhoneLog.w(TAG, "⚠️ [DND发送失败] NodeId为空");
                    return;
                }

                PhoneLog.d(TAG, "📤 [系统触发发送] dnd_state=" + interruptionFilter + " delay=" + delay);
                Tasks.await(Wearable.getMessageClient(context).sendMessage(nodeId, UNIVERSAL_SYNC_PATH, data));

            } catch (Exception e) {
                PhoneLog.e(TAG, "🔴 [DND发送异常]", e);
            }
        }).start();
    }
}
