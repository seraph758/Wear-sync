package cn.luke.wearsync;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.activity.ComponentActivity; // ✅ 確保是 androidx.activity 包

import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.MessageClient;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;

public class WearAlarmActivity extends ComponentActivity {

    private static final String TAG = "WearSync_WearAlarmUI";
    private static final String UNIVERSAL_SYNC_PATH = "/wear-universal-sync";

    // 🚀 安全单例：使用 WeakReference 防止内存泄漏
    private static WeakReference<WearAlarmActivity> instanceRef;

    private TextView tvAlarmDay;
    private TextView tvAlarmTime;
    private WearSyncScreenManager screenManager;

    public static WearAlarmActivity getInstance() {
        return (instanceRef != null) ? instanceRef.get() : null;
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 🚀 绑定弱引用
        instanceRef = new WeakReference<>(this);

        // 🚀 初始化屏幕管理器（必须在 setContentView 之前或之后立即绑定）
        screenManager = new WearSyncScreenManager(this);
        screenManager.bind(this);

        WearLog.d(TAG, "🎬 onCreate: 手表闹钟接管界面启动");

        // 🚀 使用新 API 唤醒屏幕 + 保持常亮 + CPU 锁
        // 5分钟安全上限，防止意外卡死导致电量耗尽
        screenManager.wakeForSync(5 * 60 * 1000L);

        setContentView(R.layout.activity_wear_alarm);

        tvAlarmDay = findViewById(R.id.tv_alarm_day);
        tvAlarmTime = findViewById(R.id.tv_alarm_time);
        Button btnDismiss = findViewById(R.id.btn_dismiss);
        Button btnSnooze = findViewById(R.id.btn_snooze);

        handleIncomingIntent(getIntent());
        startWatchVibration();

        btnDismiss.setOnClickListener(v -> {
            WearLog.d(TAG, "🔘 用户点击 [关闭]");
            sendControlSignalToPhone("DISMISS");
            cleanExit();
        });

        btnSnooze.setOnClickListener(v -> {
            WearLog.d(TAG, "🔘 用户点击 [延后]");
            sendControlSignalToPhone("SNOOZE");
            cleanExit();
        });
    }

    @Override
    protected void onNewIntent(@NonNull Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        WearLog.d(TAG, "⚡ onNewIntent: 收到热流转信令");
        
        // 🚀 新意图到来时重新唤醒屏幕和震动
        if (screenManager != null) {
            screenManager.wakeForSync(5 * 60 * 1000L);
        }
        handleIncomingIntent(intent);
    }

    private void handleIncomingIntent(Intent intent) {
        if (intent == null) return;

        String action = intent.getStringExtra("alarm_action");
        if ("FORCE_STOP_WEAR_ALARM".equalsIgnoreCase(action)) {
            WearLog.d(TAG, "📥 捕获手机端强退信号，安全退出");
            cleanExit();
            return;
        }

        String rawJson = intent.getStringExtra("raw_alarm_json");
        if (rawJson != null) {
            try {
                JSONObject json = new JSONObject(rawJson);
                String time = json.optString("time", "00:00");
                String monthDay = json.optString("month_day", "");
                String week = json.optString("day_tips", "");

                if (tvAlarmTime != null) tvAlarmTime.setText(time);
                if (tvAlarmDay != null) {
                    if (!monthDay.isEmpty()) {
                        tvAlarmDay.setText(getString(R.string.alarm_date_format, monthDay, week));
                    } else {
                        tvAlarmDay.setText(week);
                    }
                }
                WearLog.d(TAG, "📦 解包成功 ➔ 时间:[" + time + "]");
                startWatchVibration();
            } catch (Exception e) {
                WearLog.e(TAG, "🔴 JSON 解包失败", e);
            }
        }
    }

    private void startWatchVibration() {
        // 🚀 统一入口，Helper 内部自动读取最新参数
        WearVibratorHelper.vibratePattern(this);
    }

    /**
     * 🚀 核心修复：无条件停止震动 + 释放屏幕资源
     */
    private void cleanExit() {
        try {
            WearVibratorHelper.cancelVibration(this);
        } catch (Exception e) {
            WearLog.e(TAG, "❌ cleanExit 停止震动异常", e);
        }

        // 主动释放屏幕控制（LifecycleObserver 也会兜底释放）
        if (screenManager != null) {
            screenManager.releaseScreen();
            screenManager.releaseCpu();
        }

        finishAndRemoveTask();
    }

    /**
     * 🚀 关键兜底：防止非按钮退出（侧滑/电源键/系统回收）导致震动残留
     */
    @Override
    protected void onStop() {
        super.onStop();
        WearVibratorHelper.cancelVibration(this);
        WearLog.d(TAG, "🛡️ onStop: 界面不可见，兜底停止震动");
    }

    @Override
    protected void onDestroy() {
        if (instanceRef != null) {
            instanceRef.clear();
            instanceRef = null;
        }
        // 双重保险停止震动
        WearVibratorHelper.cancelVibration(this);
        WearLog.d(TAG, "🏳️ onDestroy: 闹钟界面完全释放");
        super.onDestroy();
    }

    private void sendControlSignalToPhone(String actionCommand) {
        new Thread(() -> {
            try {
                JSONObject json = new JSONObject();
                json.put("sender", "wear");
                json.put("type", "alarm_action");
                json.put("action", actionCommand);
                json.put("timestamp", System.currentTimeMillis());

                byte[] payload = json.toString().getBytes(StandardCharsets.UTF_8);
                List<Node> nodes = Tasks.await(Wearable.getNodeClient(this).getConnectedNodes());
                if (nodes != null) {
                    for (Node n : nodes) {
                        Tasks.await(Wearable.getMessageClient(this)
                                .sendMessage(n.getId(), UNIVERSAL_SYNC_PATH, payload));
                        WearLog.d(TAG, "🚀 反向推送成功: " + actionCommand);
                    }
                }
            } catch (Exception e) {
                WearLog.e(TAG, "❌ 反向推送失败", e);
            }
        }).start();
    }
}
