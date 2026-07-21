package cn.luke.wearsync;

import android.content.Context;
import android.app.NotificationManager;
import android.content.SharedPreferences;
import androidx.core.app.NotificationManagerCompat; // 确保导入这个
import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.Wearable;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;

/**
 * 🌓 勿扰与配置掩码核心业务专属管理器 (单包精简优化版)
 */
public class PhoneDndManager {
    private static final String TAG = "WearSync_PhoneDnd";
    private static final String PREFS_NAME = "dndsync_prefs";
    private static final String KEY_MASK = "dnd_sync_mask";
    // ✅ 新增：定义读取间隔配置的键名
    private static final String KEY_PULL_DOWN_DELAY = "screen_pull_down_interval"; 
    private static final String UNIVERSAL_SYNC_PATH = "/wear-universal-sync";

    public static void handleIncomingAction(Context context, int wearSystemDndVal) {
        PhoneLog.d(TAG, "📥 [逆向同步] 收到手表反向勿扰信令 ➔ 目标值 = " + wearSystemDndVal);
        try {
            NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                // 🎯 核心安全校驗：先獲取手機當前的 filter 狀態
                int currentPhoneFilter = nm.getCurrentInterruptionFilter();
                // 🎯 只有當手錶傳過來的狀態與手機當前狀態不相等時，才允許修改，原地攔截重複信號
                if (currentPhoneFilter != wearSystemDndVal) {
                    boolean hasPermission = nm.isNotificationPolicyAccessGranted();
                    if (hasPermission) {
                        // 🔒 關閉正向發射開關，告訴手機監聽器：「這是我自己改的，不要再發回給手錶了！」
                        // （請確保您的 PhoneSyncNotificationService 裡有對齊這個 isInternalUpdate 變量）
                        // PhoneSyncNotificationService.isInternalUpdate = true;
                        nm.setInterruptionFilter(wearSystemDndVal);
                        PhoneLog.d(TAG, "✨ [逆向同步成功] 手机系统勿扰模式已成功设置为 = " + wearSystemDndVal);
                    } else {
                        PhoneLog.w(TAG, "⚠️ [逆向同步失败] 手机端缺乏 NotificationPolicyAccess 权限！");
                    }
                } else {
                    PhoneLog.d(TAG, "✅ [逆向同步攔截] 手機當前勿擾 Filter 已經是 " + wearSystemDndVal + "，判定為回流或重複信號，不作處理。");
                }
            }
        } catch (Exception e) {
            PhoneLog.e(TAG, "🔴 [逆向同步异常] 修改手机勿扰状态失败: " + e.getMessage(), e);
        }
    }

    /**
     * 🛰️ 正向同步：
     * 手机勿扰状态 + 联动配置一次性发送给手表
     * <p>
     * 数据格式：
     * <p>
     * {
     * sender:"phone",
     * type:"dnd",
     * <p>
     * dnd_state:2,
     * <p>
     * mask:15
     * }
     * <p>
     * mask:
     * <p>
     * Bit0 = 总同步开关
     * Bit1 = 勿扰震动
     * Bit2 = 睡眠模式联动
     * Bit3 = 省电模式联动
     */
    // ✅ 修改：方法签名只接收 Context
     /**
     * 🛰️ 正向同步：
     * 手机勿扰状态 + 联动配置一次性发送给手表
     * <p>
     * 注意：此方法由 UI (MainFragment) 调用，只传入了新的间隔值。
     * DND 状态会在方法内部实时获取。
     */
    // ✅ 修改后：接收 Context 和 间隔值
    public static void syncDndToWear(Context context, int pullDownDelayMs) {
        new Thread(() -> {
            try {
                // 1. 在内部实时获取勿扰状态 (不要依赖外部传进来的值)
                int interruptionFilter = NotificationManagerCompat.from(context).getCurrentInterruptionFilter();
                
                // 2. 获取配置掩码
                SharedPreferences sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                int currentMask = sp.getInt(KEY_MASK, 15);
                
                // 3. 打包数据
                JSONObject json = new JSONObject();
                json.put("sender", "phone");
                json.put("type", "dnd");
                json.put("dnd_state", interruptionFilter); // ✅ 使用内部获取的值
                json.put("mask", currentMask);
                json.put("pullDownDelayMs", pullDownDelayMs); // ✅ 使用 UI 传进来的新值
                json.put("timestamp", System.currentTimeMillis());

                byte[] data = json.toString().getBytes(StandardCharsets.UTF_8);
                String nodeId = WearSyncState.getNodeId(context);
                
                if (nodeId == null || nodeId.isEmpty()) {
                    PhoneLog.w(TAG, "⚠️ [DND发送失败] NodeId为空");
                    return;
                }
                
                PhoneLog.d(TAG, "📤 [UI触发发送] dnd_state=" + interruptionFilter + " delay=" + pullDownDelayMs);
                Tasks.await(Wearable.getMessageClient(context).sendMessage(nodeId, UNIVERSAL_SYNC_PATH, data));
                
            } catch (Exception e) {
                PhoneLog.e(TAG, "🔴 [DND发送异常]", e);
            }
        }).start();
    }

}
