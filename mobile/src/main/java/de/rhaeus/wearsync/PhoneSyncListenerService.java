package de.rhaeus.wearsync;

import android.content.Intent;
import android.util.Log;
import androidx.annotation.NonNull;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.WearableListenerService;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;

/**
 * 🚀 极致解耦版：中央信令路由器
 * 职责：只负责接收手表原生信令，并通过标准的显式 Intent 转发给各自独立的业务 Service。
 * 本文件内不包含任何具体的勿扰控制、闹钟点击或相机的核心计算逻辑。
 */
public class PhoneSyncListenerService extends WearableListenerService {
    private static final String TAG = "WearSync_PhoneListener";
    private static final String UNIVERSAL_SYNC_PATH = "/wear-universal-sync";
    
    /**
     * 🎯 核心全局拦截锁：留给独立的 PhoneDndService 等跨文件读写，
     * 维持原项目的防死循环拦截机制，确保编译 100% 通过。
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

            if ("phone".equalsIgnoreCase(sender)) return; // 过滤本地回环

            // ================= 1️⃣ 勿扰同步模块分发 =================
            if ("dnd".equalsIgnoreCase(type)) {
                int dndVal = json.optInt("dnd_profile_value", -1);
                Log.d(TAG, "🌙 [信令路由] 收到手表勿扰信令，底层硬状态值: " + dndVal + " -> 转发至独立 PhoneDndService");
                
                Intent dndIntent = new Intent(this, PhoneDndService.class);
                // 此时作为接收分发，可携带标志通知 Service 这是手表传给手机的，或是直接传递系统值
                dndIntent.putExtra("dnd_profile_value", dndVal); 
                startService(dndIntent);
                return;
            }

            // ================= 2️⃣ 闹钟模块分发（留空，下一步修复） =================
            if ("alarm_action".equalsIgnoreCase(type)) {
                Log.d(TAG, "⏰ [信令路由] 收到手表闹钟动作: " + action + " -> 暂未接入独立模块（待修复）");
                return;
            }

            // ================= 3️⃣ 相机模块分发（留空，后面修复） =================
            if ("camera_control".equalsIgnoreCase(type)) {
                Log.d(TAG, "📸 [信令路由] 收到相机动作: " + action + " -> 暂未接入独立模块（待修复）");
                return;
            }

        } catch (Exception e) {
            Log.e(TAG, "解析手表消息失败", e);
        }
    }
}
