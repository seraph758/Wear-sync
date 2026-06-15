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
 * 🌙 独立解耦的勿扰/复合模式处理服务（手机端主控发送源）
 * 职责：读取“勿扰总开关”及下层“睡眠模式”、“勿扰震动”、“省电模式”3个子开关的状态，
 * 动态计算出项目原生的二进制复合 Mask 掩码值，并跨端推送到手表。
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

        Log.d(TAG, "☯️ 独立勿扰复合服务启动，准备读取手机端 4 个开关状态...");

        new Thread(() -> {
            try {
                // 1️⃣ 获取手机本地 App 的持久化开关配置 (根据你之前总代码的 Key 进行对齐)
                SharedPreferences prefs = getSharedPreferences("wear_sync_prefs", Context.MODE_PRIVATE);
                
                // 【总开关】：勿扰同步总开关
                boolean isDndSyncMasterOn = prefs.getBoolean("key_dnd_sync_master", true);
                
                // 如果用户把【勿扰同步总开关】给关了，手机端直接摆平，不向手表发送任何勿扰控制掩码
                if (!isDndSyncMasterOn) {
                    Log.d(TAG, "🚫 勿扰同步总开关已关闭，取消本次跨端 Mask 数据推送。");
                    return;
                }

                // 【3个子开关】：睡眠模式、勿扰震动、省电模式
                boolean isSleepModeOn = prefs.getBoolean("key_sleep_mode", false);
                boolean isVibrateOn = prefs.getBoolean("key_dnd_vibrate", false);
                boolean isPowerSaveOn = prefs.getBoolean("key_power_save", false);

                // 2️⃣ 🎯 核心权值计算：根据你原本总代码中的二进制位权协议进行按位或（|）组合
                // 假设：睡眠模式=权值2(第1位), 勿扰震动=权值4(第2位), 省电模式=权值8(第3位)（此处按你原工程协议的物理数值对齐即可）
                int maskValue = 0;
                
                if (isSleepModeOn) {
                    maskValue |= 2;  // 开启睡眠模式对应的位权数字
                }
                if (isVibrateOn) {
                    maskValue |= 4;  // 开启勿扰震动对应的位权数字
                }
                if (isPowerSaveOn) {
                    maskValue |= 8;  // 开启省电模式对应的位权数字
                }

                Log.d(TAG, "🔢 [4开关状态清点完毕] -> 总开关:开 | 睡眠: " + isSleepModeOn 
                        + " | 震动: " + isVibrateOn + " | 省电: " + isPowerSaveOn 
                        + " => 组合生成最终 Mask 权值 = " + maskValue);

                // 3️⃣ 组装符合你项目原生的信令 JSON 协议
                JSONObject json = new JSONObject();
                json.put("sender", "phone");
                json.put("type", "dnd");
                json.put("action", String.valueOf(maskValue));  // 把动态组合后的掩码值作为 action 推送
                json.put("dnd_profile_value", maskValue);       // 完美兼容手表端的多重取值方式

                byte[] dataPayload = json.toString().getBytes(StandardCharsets.UTF_8);

                // 4️⃣ 获取手表的连接节点，将提纯解耦后的 Mask 核心权值发射出去
                List<Node> connectedNodes = Tasks.await(Wearable.getNodeClient(this).getConnectedNodes());
                if (connectedNodes != null && !connectedNodes.isEmpty()) {
                    for (Node node : connectedNodes) {
                        Tasks.await(Wearable.getMessageClient(this).sendMessage(
                                node.getId(), 
                                UNIVERSAL_SYNC_PATH, 
                                dataPayload
                        ));
                        Log.d(TAG, "🚀 [跨端同步成功] 已将最新复合 Mask 掩码值 [" + maskValue + "] 推送至手表: " + node.getDisplayName());
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
