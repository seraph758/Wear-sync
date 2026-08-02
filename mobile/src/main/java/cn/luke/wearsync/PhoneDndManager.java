package cn.luke.wearsync;

import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;

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
     * @param wearSystemDndVal 手表当前的 DND 状态值 (目标值)
     */
    // 🔑 类级别变量：记录上一次成功下发的目标状态，弥补系统API异步延迟
private static volatile boolean sLastAppliedDndOn = false;
private static volatile boolean sHasInitialized = false;

public static void handleIncomingAction(Context context, int wearSystemDndVal) {
    PhoneLog.d(TAG, "📥 [逆向同步] 收到手表反向勿扰信令 ➔ 原始值 = " + wearSystemDndVal);
    try {
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (nm == null) {
            PhoneLog.e(TAG, "🔴 NotificationManager 获取失败");
            return;
        }

        // 🔑 Step 1: 布尔归一化（只关心开/关）
        // 手表端：0=关，非0=开
        boolean targetDndOn = (wearSystemDndVal != 0);
        
        // 手机端：INTERRUPTION_FILTER_ALL(1)=关，其余(2,3,4)=开
        int currentFilter = nm.getCurrentInterruptionFilter();
        boolean currentDndOn = (currentFilter != NotificationManager.INTERRUPTION_FILTER_ALL);

        // 🔑 Step 2: 双重比对（系统当前值 + 上次下发值）
        boolean alreadyApplied = sHasInitialized 
                && (targetDndOn == currentDndOn) 
                && (targetDndOn == sLastAppliedDndOn);

        if (alreadyApplied) {
            PhoneLog.d(TAG, "✅ [拦截] DND开关状态一致(on=" + currentDndOn + ")，跳过");
            return;
        }

        // 🔑 Step 3: 权限检查
        if (!nm.isNotificationPolicyAccessGranted()) {
            PhoneLog.w(TAG, "⚠️ [逆向同步失败] 缺少 NotificationPolicyAccess 权限");
            return;
        }

        // 🔑 Step 4: 映射为系统标准值并执行
        int mappedTarget = targetDndOn
                ? NotificationManager.INTERRUPTION_FILTER_NONE      // 开启 → 全静音
                : NotificationManager.INTERRUPTION_FILTER_ALL;      // 关闭 → 允许所有

        sLastAppliedDndOn = targetDndOn;
        sHasInitialized = true;
        
        nm.setInterruptionFilter(mappedTarget);
        PhoneLog.d(TAG, "✨ [逆向同步成功] DND: " + currentDndOn + " → " + targetDndOn 
                + " (filter: " + currentFilter + " → " + mappedTarget + ")");

    } catch (Exception e) {
        PhoneLog.e(TAG, "🔴 [逆向同步异常] " + e.getMessage(), e);
    }
}

    // ==========================================
    // 2. 正向同步入口 (手机 -> 手表)
    // ==========================================

    /**
     * 场景：系统监听器 (PhoneSyncNotificationService) 触发
     * 仅在手机系统勿扰模式发生变化时调用
     */
    
public static void syncDndToWear(Context context) {
    SharedPreferences spPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    int currentMask = spPrefs.getInt(KEY_MASK, 15);

    new Thread(() -> {
        try {
            // 🔑 Step 1: 获取系统原始值并立即归一化为 0/1
            int rawFilter = NotificationManagerCompat.from(context).getCurrentInterruptionFilter();
            // INTERRUPTION_FILTER_ALL(1) = 关 → 0；其余(2,3,4) = 开 → 1
            int dndState = (rawFilter == NotificationManager.INTERRUPTION_FILTER_ALL) ? 0 : 1;

            // 🔑 Step 2: 读取最新延迟值
            int delay = spPrefs.getInt(KEY_PULL_DOWN_DELAY, 500);
            PhoneLog.d("PullDownDelay", "实际读取延迟: " + delay + "ms");

            // 🔑 Step 3: 打包发送（使用归一化后的 dnd_state）
            JSONObject json = new JSONObject();
            json.put("sender", "phone");
            json.put("type", "dnd");
            json.put("dnd_state", dndState);       // ✅ 0=关, 1=开
            json.put("mask", currentMask);
            json.put("pullDownDelayMs", delay);
            json.put("timestamp", System.currentTimeMillis());

            byte[] data = json.toString().getBytes(StandardCharsets.UTF_8);
            String nodeId = WearSyncState.getNodeId(context);

            if (nodeId == null || nodeId.isEmpty()) {
                PhoneLog.w(TAG, "⚠️ [DND发送失败] NodeId为空");
                return;
            }

            PhoneLog.d(TAG, "📤 [正向同步] dnd_state=" + dndState 
                    + "(raw=" + rawFilter + ") delay=" + delay);
            Tasks.await(Wearable.getMessageClient(context)
                    .sendMessage(nodeId, UNIVERSAL_SYNC_PATH, data));

        } catch (Exception e) {
            PhoneLog.e(TAG, "🔴 [DND发送异常]", e);
        }
    }).start();
}
}
