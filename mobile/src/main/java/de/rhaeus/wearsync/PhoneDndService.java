package de.rhaeus.wearsync;

import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.IBinder;
import android.util.Log;
import androidx.annotation.Nullable;

import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;

import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 🌙 独立解耦的勿扰处理服务（手机端主控发送源）
 * 核心设计：
 * 1. 动态接收手机 UI 变动时即时锁定并缓存在静态变量中的 maskValue。
 * 2. 剥离数字对系统勿扰常量的污染，将【纯净开关掩码】与【手机当前硬勿扰状态】打包并推。
 */
public class PhoneDndService extends Service {
    private static final String TAG = "WearSync_PhoneDnd";
    private static final String UNIVERSAL_SYNC_PATH = "/wear-universal-sync";

    /**
     * 🎯 核心优化：由 UI 界面（Activity/Fragment）在切换开关状态时即时计算并写入该静态变量。
     * 默认值为 0（代表 3 个特殊联动动作全关）。
     * 位权定义：1 = 睡眠模式手势宏，2 = 短震动开关，4 = 省电模式
     */
    public static int cachedMaskValue = 0;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        // 📥 分支一：承接来自手表反向同步手机系统硬勿扰的请求（此流向绝对不触发震动，也不动手机开关）
        if (intent.hasExtra("dnd_profile_value") && PhoneSyncListenerService.isInternalUpdate) {
            int wearSystemDndVal = intent.getIntExtra("dnd_profile_value", -1);
            Log.d(TAG, "📥 收到来自手表的反向硬勿扰同步，目标系统值: " + wearSystemDndVal);
            if (wearSystemDndVal != -1) {
                try {
                    NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                    if (nm != null && nm.isNotificationPolicyAccessGranted()) {
                        nm.setInterruptionFilter(wearSystemDndVal);
                        Log.d(TAG, "☯️ [反向同步成功] 已将手机系统硬勿扰过滤器设置为: " + wearSystemDndVal);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "应用手表反向勿扰状态失败", e);
                } finally {
                    // 留足系统反应缓冲时间，1秒后安全释放拦截锁
                    new Thread(() -> {
                        try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
                        PhoneSyncListenerService.isInternalUpdate = false;
                        Log.d(TAG, "🔓 内部更新拦截锁已安全释放。");
                    }).start();
                }
            }
            stopSelf();
            return START_NOT_STICKY;
        }

        // 🛡️ 发送侧拦截锁：如果是由于手机本地收到手表反向通知导致的内部状态回调，直接拦截，防止自循环
        if (PhoneSyncListenerService.isInternalUpdate) {
            Log.d(TAG, "🔒 [防回环] 处于内部更新锁状态，拦截本次手机向手表的同步推流。");
            stopSelf();
            return START_NOT_STICKY;
        }

        // 📤 分支二：手机端主动触发同步（不管是拨动了子开关，还是手机自身勿扰变了，都要强制对齐）
        Log.d(TAG, "☯️ 手机勿扰状态或配置变更，准备向手表同步...");
        
        new Thread(() -> {
            try {
                // 1. 验证总开关是否开启
                SharedPreferences prefs = getSharedPreferences("wear_sync_prefs", Context.MODE_PRIVATE);
                boolean isDndSyncMasterOn = prefs.getBoolean("key_dnd_sync_master", true);
                if (!isDndSyncMasterOn) {
                    Log.d(TAG, "🚫 勿扰同步总开关已关闭，终止跨端数据同步。");
                    return;
                }

                // 2. 获取手机系统当前【真实的硬勿扰状态级别】
                int currentPhoneDndStatus = 1; // 默认放行全部（INTERRUPTION_FILTER_ALL = 1）
                NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                if (nm != null) {
                    currentPhoneDndStatus = nm.getCurrentInterruptionFilter();
                }

                // 3. 直接提取 UI 层在变动时提早存入静态变量的计算结果
                int finalMask = cachedMaskValue;
                Log.d(TAG, "🔢 [高性能发信准备] 当前缓存的开关 Mask = " + finalMask + " | 手机当前硬勿扰系统状态 = " + currentPhoneDndStatus);

                // 4. 组装极度纯净的 JSON 协议大对齐
                JSONObject json = new JSONObject();
                json.put("sender", "phone");
                json.put("type", "dnd");
                json.put("switches_mask", finalMask);               // 👈 纯净开关掩码（仅代表 1-睡眠、2-震动、4-省电的动作命令）
                json.put("dnd_profile_value", currentPhoneDndStatus); // 👈 手机当前真实的硬勿扰系统值（哪怕 Mask 是 0，它也负责让手表强制跟变）
                json.put("action", String.valueOf(finalMask));

                byte[] dataPayload = json.toString().getBytes(StandardCharsets.UTF_8);

                // 5. 跨端发射
                List<Node> connectedNodes = Tasks.await(Wearable.getNodeClient(this).getConnectedNodes());
                if (connectedNodes != null && !connectedNodes.isEmpty()) {
                    for (Node node : connectedNodes) {
                        Tasks.await(Wearable.getMessageClient(this).sendMessage(node.getId(), UNIVERSAL_SYNC_PATH, dataPayload));
                        Log.d(TAG, "🚀 [同步成功] 已将最新同步信令推送到手表: " + node.getDisplayName());
                    }
                } else {
                    Log.w(TAG, "⚠️ 未检测到任何可用的手表节点。");
                }

            } catch (Exception e) {
                Log.e(TAG, "手机向手表发送同步信令灾难性失败", e);
            } finally {
                stopSelf();
            }
        }).start();

        return START_NOT_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }
}
