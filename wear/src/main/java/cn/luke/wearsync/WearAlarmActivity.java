package cn.luke.wearsync;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;
import androidx.annotation.Nullable;
import androidx.activity.ComponentActivity;
import java.lang.ref.WeakReference;
import org.json.JSONObject;

public class WearAlarmActivity extends ComponentActivity {
    private static final String TAG = "WearSync_WearAlarmUI";
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
        
        // --- 检查 FORCE_STOP 指令 ---
        if (getIntent() != null && "FORCE_STOP".equals(getIntent().getStringExtra("alarm_action"))) {
            WearLog.d("WearAlarmActivity", "收到 FORCE_STOP 指令，正在执行 cleanExit...");
            cleanExit();
            finish();
            return;
        }

        // 1. 基础初始化
        instanceRef = new WeakReference<>(this);
        screenManager = new WearSyncScreenManager(this);
        screenManager.bind(this);
        WearLog.d(TAG, "🎬 onCreate: 手表闹钟接管界面启动");
        screenManager.wakeForSync(1 * 60 * 1000L);
        setContentView(R.layout.activity_wear_alarm);
        
        tvAlarmDay = findViewById(R.id.tv_alarm_day);
        tvAlarmTime = findViewById(R.id.tv_alarm_time);
        Button btnDismiss = findViewById(R.id.btn_dismiss);
        Button btnSnooze = findViewById(R.id.btn_snooze);
        handleIncomingTime(getIntent());
        startWatchVibration();

        // ❌ 删除：移除所有与 WearSyncCommManager 通信相关的代码
        // WearSyncCommManager.getInstance(this).setConnectionListener(...);
        // WearSyncCommManager.getInstance(this).connect();

        // 3. 按钮逻辑
        btnDismiss.setOnClickListener(v -> {
            WearLog.d(TAG, "🔘 用户点击 [关闭]");
            // ✅ 调用 CommManager 发送指令
            WearSyncCommManager.getInstance(this).dismissPhoneAlarm();
            cleanExit();
        });

        btnSnooze.setOnClickListener(v -> {
            WearLog.d(TAG, "🔘 用户点击 [延后]");
            // ✅ 调用 CommManager 发送指令
            WearSyncCommManager.getInstance(this).snoozePhoneAlarm();
            cleanExit();
        });
    }

    // ✅ 修改：handleIncomingCommand 变为纯粹的 UI 更新方法
    // 职责：只负责根据指令更新界面，不涉及任何通信逻辑
    public static void handleIncomingCommand(Context context, JSONObject json) {
        WearLog.d(TAG, "收到闹钟控制指令: " + json.toString());
        String action = json.optString("action");
        if ("DISMISS".equals(action)) {
            WearAlarmActivity activity = getInstance();
            if (activity != null) {
                activity.cleanExit();
            }
        }
    }
    private void handleIncomingTime(Intent intent) {
        if (intent == null) return;

        // 1. 从 Intent 中获取 JSON 字符串
        // 注意：这里假设你手表端的 Listener 是用 "raw_alarm_json" 这个 key 传递的
        // 如果你的 Listener 用的是别的 key (比如 "json")，请相应修改这里
        String rawJson = intent.getStringExtra("raw_alarm_json");

        if (rawJson != null) {
            try {
                JSONObject json = new JSONObject(rawJson);
                String time = json.optString("time", "00:00");
                String monthDay = json.optString("month_day", "");
                String week = json.optString("day_tips", "");

                // 2. 更新 UI
                if (tvAlarmTime != null) tvAlarmTime.setText(time);
                if (tvAlarmDay != null) {
                    if (!monthDay.isEmpty()) {
                        tvAlarmDay.setText(monthDay + "  " + week);
                    } else {
                        tvAlarmDay.setText(week);
                    }
                }
                WearLog.d(TAG, "📦 成功解析并显示手机时间: " + time);

            } catch (Exception e) {
                WearLog.e(TAG, "🔴 解析手机发来的时间JSON失败", e);
            }
        }
    }

    private void startWatchVibration() {
        WearVibratorHelper.vibratePattern(this);
    }

    private void cleanExit() {
        try {
            WearVibratorHelper.cancelVibration(this);
        } catch (Exception e) {
            WearLog.e(TAG, "❌ cleanExit 停止震动异常", e);
        }
        if (screenManager != null) {
            screenManager.releaseScreen();
            screenManager.releaseCpu();
        }
        finishAndRemoveTask();
    }

    @Override
    protected void onStop() {
        super.onStop();
        WearVibratorHelper.cancelVibration(this);
    }

    @Override
    protected void onDestroy() {
        if (instanceRef != null) {
            instanceRef.clear();
            instanceRef = null;
        }
        WearVibratorHelper.cancelVibration(this);
        super.onDestroy();
    }
}
