package de.rhaeus.wearsync;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;
import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class WearAlarmActivity extends Activity {
    private static final String TAG = "WearSync_WearAlarmUI";
    private static final String UNIVERSAL_SYNC_PATH = "/wear-universal-sync";

    private Vibrator vibrator;
    private TextView tvAlarmDay;
    private TextView tvAlarmTime;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WearLog.d(TAG, "🎬 onCreate: 手表闹钟接管界面全屏顶屏中...");

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_ALLOW_LOCK_WHILE_SCREEN_ON);

        setContentView(R.layout.activity_wear_alarm);

        tvAlarmDay = findViewById(R.id.tv_alarm_day);
        tvAlarmTime = findViewById(R.id.tv_alarm_time);
        Button btnDismiss = findViewById(R.id.btn_dismiss);
        Button btnSnooze = findViewById(R.id.btn_snooze);

        // 🎯 1. 现场解析逻辑整合
        handleIncomingIntent(getIntent());

        btnDismiss.setOnClickListener(v -> {
            WearLog.d(TAG, "🔘 用户点击 [关闭] 按钮");
            sendControlSignalToPhone("DISMISS");
            cleanExit();
        });

        btnSnooze.setOnClickListener(v -> {
            WearLog.d(TAG, "🔘 用户点击 [延后] 按钮");
            sendControlSignalToPhone("SNOOZE");
            cleanExit();
        });
    }

    /**
     * 🎯 核心机制：当 Activity 已经在前台鸣叫时，手机如果下发强退或刷新信令，会直接撞击这里！
     */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        WearLog.d(TAG, "⚡ onNewIntent: 收到热流转信令快件，立即执行现场判决...");
        handleIncomingIntent(intent);
    }

    /**
     * 🧠 纯净的闹钟信令专属私有解包业务站
     */
    private void handleIncomingIntent(Intent intent) {
        if (intent == null) return;
        
        String action = intent.getStringExtra("alarm_action");
        
        // 🚨 自毁拦截：如果判定手机要求强退，直接当场自毁灭活，不需要借助任何外部广播接收器！
        if ("FORCE_STOP_WEAR_ALARM".equalsIgnoreCase(action)) {
            WearLog.d(TAG, "📥 [单包自毁成功] 现场捕获手机端强退信号，立即闭环安全退出界面...");
            cleanExit();
            return;
        }

        // ⏰ 响铃解析：如果是拉起/刷新闹钟，现场解包
        String rawJson = intent.getStringExtra("raw_alarm_json");
        if (rawJson != null) {
            try {
                JSONObject json = new JSONObject(rawJson);
                String time = json.optString("time", "00:00");
                String day = json.optString("day_tips", "");

                if (tvAlarmTime != null) tvAlarmTime.setText(time);
                if (tvAlarmDay != null) tvAlarmDay.setText(day);

                WearLog.d(TAG, "📦 业务现场解包成功 ➔ 时间:[" + time + "], 标签:");
                
                // 确保震动在持续轰鸣
                startWatchVibration();
            } catch (Exception e) {
                WearLog.e(TAG, "🔴 现场解包 JSON 失败", e);
            }
        }
    }

    private void startWatchVibration() {
        if (vibrator == null) {
            vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        }
        if (vibrator != null && vibrator.hasVibrator()) {
            long[] pattern = {0, 500, 500};
            // 每次拉起或看门狗刷新时，如果没在震，就确保它在震动
            vibrator.cancel(); 
            WearLog.d(TAG, "📳 激活/刷新手表硬件独立震动环...");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0));
            } else {
                vibrator.vibrate(pattern, 0);
            }
        }
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
                        Tasks.await(Wearable.getMessageClient(this).sendMessage(n.getId(), UNIVERSAL_SYNC_PATH, payload));
                        WearLog.d(TAG, "🚀 [闹钟反向代点] 成功向手机投递口令: " + actionCommand);
                    }
                }
            } catch (Exception e) {
                WearLog.e(TAG, "❌ 手表向手机反向推送代点指令失败", e);
            }
        }).start();
    }

    private void cleanExit() {
        try {
            if (vibrator != null) vibrator.cancel();
        } catch (Exception ignored) {}
        finishAndRemoveTask();
    }

    @Override
    protected void onDestroy() {
        if (vibrator != null) vibrator.cancel();
        WearLog.d(TAG, "🏳️ onDestroy: 闹钟接管界面完全释放、优雅退出");
        super.onDestroy();
    }
}
