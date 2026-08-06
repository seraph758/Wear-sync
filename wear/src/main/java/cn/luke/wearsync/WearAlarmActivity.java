package cn.luke.wearsync;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.widget.Button;
import android.widget.TextView;
import androidx.annotation.Nullable;
import androidx.activity.ComponentActivity;
import org.json.JSONObject;

public class WearAlarmActivity extends ComponentActivity {

    private static final String TAG = "WearSync_WearAlarmUI";

    // ✅ P0: 新增静态实例，用于外部服务获取当前 Activity
    private static WearAlarmActivity instance;

    private TextView tvAlarmDay;
    private TextView tvAlarmTime;
    private WearSyncScreenManager screenManager;

    // 震动循环控制
    private Handler vibrationHandler;
    private Runnable vibrationRunnable;
    private volatile boolean isVibrating = false;
    private long nextVibrateTime = 0;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // ✅ P0: 注册当前实例
        instance = this;

        // ✅ 最先检查 FORCE_STOP，避免任何不必要的初始化
        if (getIntent() != null && "FORCE_STOP".equals(getIntent().getStringExtra("alarm_action"))) {
            WearLog.d(TAG, "收到 FORCE_STOP 指令，直接退出");
            finishAndRemoveTask();
            return;
        }

        // 正常初始化流程
        screenManager = new WearSyncScreenManager(this);
        screenManager.bind(this);
        WearLog.d(TAG, "🎬 onCreate: 手表闹钟接管界面启动");

        // 保持屏幕常亮 1 分钟
        screenManager.wakeForSync(60 * 1000L);

        setContentView(R.layout.activity_wear_alarm);
        tvAlarmDay = findViewById(R.id.tv_alarm_day);
        tvAlarmTime = findViewById(R.id.tv_alarm_time);
        Button btnDismiss = findViewById(R.id.btn_dismiss);
        Button btnSnooze = findViewById(R.id.btn_snooze);

        handleIncomingTime(getIntent());

        // 启动应用层循环震动
        startWatchVibration();

        // 按钮逻辑
        btnDismiss.setOnClickListener(v -> {
            WearLog.d(TAG, "🔘 用户点击 [关闭]");
            WearSyncCommManager.getInstance(this).dismissPhoneAlarm();
            cleanExit();
        });

        btnSnooze.setOnClickListener(v -> {
            WearLog.d(TAG, "🔘 用户点击 [延后]");
            WearSyncCommManager.getInstance(this).snoozePhoneAlarm();
            cleanExit();
        });
    }

    // ✅ P0: 新增公共静态方法，供外部安全获取实例
    public static WearAlarmActivity getInstance() {
        return instance;
    }

    private void handleIncomingTime(Intent intent) {
        if (intent == null) return;
        String rawJson = intent.getStringExtra("raw_alarm_json");
        if (rawJson != null) {
            try {
                JSONObject json = new JSONObject(rawJson);
                String time = json.optString("time", "00:00");
                String monthDay = json.optString("month_day", "");
                String week = json.optString("day_tips", "");

                // ✅ P2: 增加空值防御
                if (time == null || time.trim().isEmpty()) time = "00:00";

                if (tvAlarmTime != null) tvAlarmTime.setText(time);
                if (tvAlarmDay != null) {
                    if (!monthDay.isEmpty()) {
                        tvAlarmDay.setText(monthDay + " " + week);
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
        // 从本地 SharedPreferences 读取震动参数
        WearVibratorHelper.initFromPhone(this);
        int rawOnDuration = WearVibratorHelper.getOnDuration();
        int rawOffDuration = WearVibratorHelper.getOffDuration();

        // 防御性检查
        if (rawOnDuration <= 0) return;
        if (rawOffDuration < 0) rawOffDuration = 200;

        final int onDuration = rawOnDuration;
        final int offDuration = rawOffDuration;

        vibrationHandler = new Handler(Looper.getMainLooper());
        nextVibrateTime = SystemClock.elapsedRealtime();

        vibrationRunnable = new Runnable() {
            @Override
            public void run() {
                if (!isVibrating) return;

                WearVibratorHelper.vibrateOnce(WearAlarmActivity.this, onDuration);

                // ✅ P1: 绝对时间调度，消除累积误差
                nextVibrateTime += (onDuration + offDuration);
                long delay = Math.max(0, nextVibrateTime - SystemClock.elapsedRealtime());
                vibrationHandler.postDelayed(this, delay);
            }
        };

        isVibrating = true;
        vibrationRunnable.run();
        WearLog.d(TAG, "🔊 闹钟震动循环已启动 (On: " + onDuration + ", Off: " + offDuration + ")");
    }

    /**
     * 停止震动循环（幂等、线程安全）
     */
    private void stopWatchVibration() {
        // 快速退出：避免无效操作和日志噪声
        if (!isVibrating && (vibrationHandler == null || !vibrationHandler.hasCallbacks(vibrationRunnable))) {
            return;
        }

        // 先移除未来计划中的震动任务
        if (vibrationHandler != null && vibrationRunnable != null) {
            vibrationHandler.removeCallbacks(vibrationRunnable);
        }

        // 再取消当前正在进行的震动
        try {
            WearVibratorHelper.cancelVibration(this);
        } catch (Exception e) {
            WearLog.e(TAG, "❌ 取消震动失败", e);
        }

        // 最后更新状态标记 + 日志
        isVibrating = false;
        WearLog.d(TAG, "🔇 闹钟震动已停止");
    }

    /**
     * 清理资源并彻底退出
     */
    public void cleanExit() {
        try {
            stopWatchVibration();
            if (screenManager != null) {
                screenManager.releaseScreen();
                screenManager.releaseCpu();
            }
        } catch (Exception e) {
            WearLog.e(TAG, "❌ cleanExit 资源释放异常", e);
        } finally {
            // ✅ P2: 确保页面一定关闭，即使资源释放异常
            finishAndRemoveTask();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        // ✅ P2: 不可见时同时停止震动 + 释放屏幕/CPU
        stopWatchVibration();
        if (screenManager != null) {
            screenManager.releaseScreen();
            screenManager.releaseCpu();
        }
    }

    @Override
    protected void onDestroy() {
        // ✅ P0: 销毁时清空实例，防止内存泄漏
        instance = null;
        // 最终兜底：确保震动停止
        stopWatchVibration();
        super.onDestroy();
    }
}
