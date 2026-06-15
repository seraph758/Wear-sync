package de.rhaeus.wearsync;

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
 * 🌙 独立解耦的勿扰处理服务（手机主控发送端）
 * 核心职责：严格遵循原生 1, 2, 4 位权协议。当手机状态改变时，
 * 读取 3 个子开关组合状态，计算复合 Mask 值并跨端同步给手表。
 */
public class PhoneDndService extends Service {
    private static final String TAG = "WearSync_PhoneDnd";
    private static final String UNIVERSAL_SYNC_PATH = "/wear-universal-sync";

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        // 🛡️ 拦截锁：如果是由于接收手表信令导致手机系统勿扰变化而回调触发的，直接拦截，防止无限双端死循环
        if (PhoneSyncListenerService.isInternalUpdate) {
            Log.d(TAG, "🔒 [防回环] 检测到属于内部接收变更引起的系统回调，拦截发信，防止双端死锁。");
            stopSelf();
            return START_NOT_STICKY;
        }

        Log.d(TAG, "☯️ 独立勿扰服务启动，准备依据 [1, 2, 4] 协议计算 Mask 并推送至手表...");

        new Thread(() -> {
            try {
                // 1️⃣ 从本地持久化存储中获取 4 个开关的最新物理状态
                SharedPreferences prefs = getSharedPreferences("wear_sync_prefs", Context.MODE_PRIVATE);
                
                // 【总开关】：勿扰同步总开关
                boolean isDndSyncMasterOn = prefs.getBoolean("key_dnd_sync_master", true);
                if (!isDndSyncMasterOn) {
                    Log.d(TAG, "🚫 勿扰同步总开关已关闭，放弃本次跨端数据同步。");
                    return;
                }

                // 【3个子开关】
                boolean isSleepModeOn = prefs.getBoolean("key_sleep_mode", false); // 对应权值 1
                boolean isVibrateOn = prefs.getBoolean("key_dnd_vibrate", false);   // 对应权值 2
                boolean isPowerSaveOn = prefs.getBoolean("key_power_save", false); // 对应权值 4

                // 2️⃣ 🎯 严格尊照原版二进制位权协议计算 Mask 值
                int maskValue = 0;
                
                if (isSleepModeOn) {
                    maskValue |= 1;  // 睡眠模式 = 1
                }
                if (isVibrateOn) {
                    maskValue |= 2;  // 勿扰震动 = 2
                }
                if (isPowerSaveOn) {
                    maskValue |= 4;  // 省电模式 = 4
                }

                Log.d(TAG, "🔢 [4开关状态清点] -> 睡眠: " + isSleepModeOn 
                        + " | 震动: " + isVibrateOn + " | 省电: " + isPowerSaveOn 
                        + " => 最终组合计算结果 Mask = " + maskValue);

                // 3️⃣ 组装符合你项目原生的信令 JSON 协议
                JSONObject json = new JSONObject();
                json.put("sender", "phone");
                json.put("type", "dnd");
                json.put("action", String.valueOf(maskValue));  // 把动态组合后的计算结果作为 action 推送
                json.put("dnd_profile_value", maskValue);       // 完美兼容手表端的多重取值方式

                byte[] dataPayload = json.toString().getBytes(StandardCharsets.UTF_8);

                // 4️⃣ 发射信令给手表
                List<Node> connectedNodes = Tasks.await(Wearable.getNodeClient(this).getConnectedNodes());
                if (connectedNodes != null && !connectedNodes.isEmpty()) {
                    for (Node node : connectedNodes) {
                        Tasks.await(Wearable.getMessageClient(this).sendMessage(
                                node.getId(), 
                                UNIVERSAL_SYNC_PATH, 
                                dataPayload
                        ));
                        Log.d(TAG, "🚀 [跨端同步成功] 已将最新复合计算结果 [" + maskValue + "] 推送至手表: " + node.getDisplayName());
                    }
                } else {
                    Log.w(TAG, "⚠️ 未检测到任何已连接的手表节点，放弃发送。");
                }

            } catch (Exception e) {
                Log.e(TAG, "跨端推送 Mask 状态信令发生异常", e);
            } finally {
                // 执行完毕后，清空内存自杀，符合极致纯净解耦规范
                stopSelf();
            }
        }).start();

        return START_NOT_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }
}
