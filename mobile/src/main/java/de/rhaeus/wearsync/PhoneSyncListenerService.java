package de.rhaeus.wearsync;

import android.content.Intent;
import android.util.Log;
import androidx.annotation.NonNull;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.WearableListenerService;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;

/**
 * 🚀 极致纯净版：中央信令路由器
 * 职责：作为手机端的交通枢纽，收到手表的原生信令后，只负责拉起核心拦截锁并转发显式 Intent，
 * 本文件绝不包含任何具体勿扰、闹钟或相机的核心计算。
 */
public class PhoneSyncListenerService extends WearableListenerService {
    private static final String TAG = "WearSync_PhoneListener";
    private static final String UNIVERSAL_SYNC_PATH = "/wear-universal-sync";
    
    /**
     * 🛡️ 核心全局反回环死锁闸门
     * 当收到手表反向同步，需要修改手机系统底层勿扰状态时，在最前线拉起此锁（设为 true）。
     * 这样手机系统的勿扰监听回调（PhoneSyncNotificationService）看到它就会直接拦截，彻底打破无限回环死循环！
     */
    public static boolean isInternalUpdate = false;

    @Override
    public void onMessageReceived(@NonNull MessageEvent messageEvent) {
        if (!UNIVERSAL_SYNC_PATH.equalsIgnoreCase(messageEvent.getPath())) return;
        byte[] data = messageEvent.getData();
        if (data == null) return;

        try {
            String jsonStr = new String(data, StandardCharsets.UTF_8);
            JSONObject json = new JSONObject(jsonStr);
            String sender = json.optString("sender", "");
            String type = json.optString("type", "");
            String action = json.optString("action", "");

            // 过滤掉手机自身发出的回环信令
            if ("phone".equalsIgnoreCase(sender)) return; 

            // ================= 🌙 1️⃣ 勿扰同步模块完美分发 =================
            if ("dnd".equalsIgnoreCase(type)) {
                // 手表反向同步过来的是手表的系统硬勿扰 Filter 值 (1-全部放行, 2-优先阻断)
                int dndVal = json.optInt("dnd_profile_value", -1);
                Log.d(TAG, "📥 [信令分发] 收到手表反向勿扰信令，目标系统状态: " + dndVal);
                
                if (dndVal != -1) {
                    // 🎯 【全网最前线锁定】：在手机系统发生改变前，第一时间把回环防御闸门关上
                    isInternalUpdate = true;
                    Log.d(TAG, "🔒 核心防御：已在最前线将 isInternalUpdate 闸门拉起，拦截反弹。");

                    // 显式 Intent 干净转发给专门的独立勿扰服务承接
                    Intent dndIntent = new Intent(this, PhoneDndService.class);
                    dndIntent.putExtra("dnd_profile_value", dndVal); 
                    startService(dndIntent);
                }
                return;
            }

            // ================= ⏰ 2️⃣ 闹钟控制模块分发（框架已搭好） =================
            if ("alarm_action".equalsIgnoreCase(type)) {
                Log.d(TAG, "⏰ [信令分发] 收到手表闹钟动作指令: " + action + " -> 准备转发至独立闹钟模块...");
                // 待后续修复闹钟模块时，在此处直接启动独立的 PhoneAlarmService 或发广播
                return;
            }

            // ================= 📸 3️⃣ 相机控制模块分发（框架已搭好） =================
            if ("camera_control".equalsIgnoreCase(type)) {
                Log.d(TAG, "📸 [信令分发] 收到手表相机控制指令: " + action + " -> 准备转发至独立相机模块...");
                // 待后续修复相机模块时，在此处直接提权拉起前台
                return;
            }

        } catch (Exception e) {
            Log.e(TAG, "中央路由器解析手表消息失败", e);
        }
    }
}
