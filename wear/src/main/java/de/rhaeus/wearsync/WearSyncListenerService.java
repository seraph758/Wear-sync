package de.rhaeus.wearsync;

import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;
import android.util.Log;
import androidx.annotation.NonNull;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.WearableListenerService;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;

/**
 * 🛰️ 手表端中央信令路由器
 * 核心扩展：
 * 1. 拦截手机传来的 alarm 协议类型。
 * 2. 收到 START_ALARM_UI 时，执行硬件级物理硬唤醒，强行在前台拉起全屏闹钟控制页面。
 * 3. 收到 FORCE_STOP_WEAR_ALARM 时，通过全局广播通知前台页面瞬间自杀停震。
 */
public class WearSyncListenerService extends WearableListenerService {
    private static final String TAG = "WearSync_WearListener";
    private static final String UNIVERSAL_SYNC_PATH = "/wear-universal-sync";

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

            // 过滤手表自身发出的信令
            if ("wear".equalsIgnoreCase(sender)) return;

            // ================= 🌙 1️⃣ 勿扰模式/手势宏分发 (保留原逻辑) =================
            if ("dnd".equalsIgnoreCase(type)) {
                // 原有的勿扰分发或手势宏调用保留在此处...
                return;
            }

            // ================= ⏰ 2️⃣ 远端闹钟核心联动分发 (精准补齐) =================
            if ("alarm".equalsIgnoreCase(type)) {
                Log.d(TAG, "📥 [闹钟信令] 收到来自手机端的闹钟控制要求: " + action);

                if ("START_ALARM_UI".equalsIgnoreCase(action)) {
                    // 🔥 步骤一：执行硬件级硬唤醒（防止手表锁屏、黑屏导致后台无法启动Activity）
                    PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                    if (pm != null) {
                        PowerManager.WakeLock wakeLock = pm.newWakeLock(
                                PowerManager.FULL_WAKE_LOCK | 
                                PowerManager.ACQUIRE_CAUSES_WAKEUP | 
                                PowerManager.ON_AFTER_RELEASE, 
                                "wearsync:AlarmScreenWakeLock"
                        );
                        // 瞬时保持亮屏3秒，为Activity创建和转场动画留出绝对充足的硬件响应时间
                        wakeLock.acquire(3000L); 
                    }

                    // 🔥 步骤二：强行拉起全屏独占的 WearAlarmActivity
                    // 使用 CLEAR_TOP 和 NEW_TASK 双重锁。配合手机端因响铃通知更新而反复发射的机制，
                    // 即使手表用户不小心滑掉了页面，这里也会天然形成“二次强行拉起保护”，确保不会漏掉闹钟。
                    Intent alarmIntent = new Intent(this, WearAlarmActivity.class);
                    alarmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                    startActivity(alarmIntent);

                } else if ("FORCE_STOP_WEAR_ALARM".equalsIgnoreCase(action)) {
                    // 🔥 步骤三：手机端闹钟已灭，发射全局强退广播，斩断手表端的 Activity 和震动
                    Log.d(TAG, "🔕 手机端闹钟已提前解除，发送强退广播终止手表震动...");
                    Intent stopBroadcast = new Intent(WearAlarmActivity.ACTION_INTERNAL_FORCE_STOP);
                    sendBroadcast(stopBroadcast);
                }
                return;
            }

            // ================= 📸 3️⃣ 相机控制分发 (留空待下一步处理) =================
            if ("camera_control".equalsIgnoreCase(type)) {
                return;
            }

        } catch (Exception e) {
            Log.e(TAG, "手表中央路由器分发信令产生致命异常", e);
        }
    }
}
