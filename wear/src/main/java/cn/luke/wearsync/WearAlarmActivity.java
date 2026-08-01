package cn.luke.wearsync;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;
import androidx.annotation.Nullable;
import androidx.activity.ComponentActivity;
import org.json.JSONObject;
import java.lang.ref.WeakReference;

public class WearAlarmActivity extends ComponentActivity {

    private static final String TAG = "WearSync_WearAlarmUI";
    private static WeakReference<WearAlarmActivity> instanceRef;

    private TextView tvAlarmDay;
    private TextView tvAlarmTime;
    private WearSyncScreenManager screenManager;

    // 震动循环控制
    private Handler vibrationHandler;
    private Runnable vibrationRunnable;
    private boolean isVibrating = false;

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
        
        // 保持屏幕常亮 1 分钟
        screenManager.wakeForSync(1 * 60 * 1000L);
        
        setContentView(R.layout.activity_wear_alarm);
        tvAlarmDay = findViewById(R.id.tv_alarm_day);
        tvAlarmTime = findViewById(R.id.tv_alarm_time);
        Button btnDismiss = findViewById(R.id.btn_dismiss);
        Button btnSnooze = findViewById(R.id.btn_snooze);

        handleIncomingTime(getIntent());
        
        // ✅ 启动应用层循环震动
        startWatchVibration();

        // 3. 按钮逻辑
        btnDismiss.setOnClickListener(v -> {
            WearLog.d(TAG, "🔘 用户点击 [关闭]");
            // 发送关闭指令给手机
            WearSyncCommManager.getInstance(this).dismissPhoneAlarm();
            cleanExit();
        });

        btnSnooze.setOnClickListener(v -> {
            WearLog.d(TAG, "🔘 用户点击 [延后]");
            // 发送延后指令给手机
            WearSyncCommManager.getInstance(this).snoozePhoneAlarm();
            cleanExit();
        });
    }

    // ✅ 修改：handleIncomingCommand 变为纯粹的 UI 更新方法
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
    // ✅ 从本地 SharedPreferences 读取（保存按钮已确保这里是最新的）
        WearVibratorHelper.initFromPhone(this);
            // ✅ 先读取原始值
        int rawOnDuration = WearVibratorHelper.getOnDuration();
        int rawOffDuration = WearVibratorHelper.getOffDuration();
        
        // ✅ 防御性检查在 final 赋值之前完成
        if (rawOnDuration <= 0) return; 
        if (rawOffDuration < 0) rawOffDuration = 200;
        
        // ✅ final 变量只赋值一次，之后不再修改
        final int onDuration = rawOnDuration;
        final int offDuration = rawOffDuration;
        
        
        // 防止配置错误导致不震动
        if (onDuration <= 0) return; 
        if (offDuration < 0) offDuration = 200;
        
        vibrationHandler = new Handler(Looper.getMainLooper());
        vibrationRunnable = new Runnable() {
            @Override
            public void run() {
                if (!isVibrating) return;
                WearVibratorHelper.vibrateOnce(WearAlarmActivity.this, onDuration);
                vibrationHandler.postDelayed(this, onDuration + offDuration);
            }
        };
        isVibrating = true;
        vibrationRunnable.run();
        WearLog.d(TAG, "🔊 闹钟震动循环已启动 (On: " + onDuration + ", Off: " + offDuration + ")");
    }
    
   
    /**
     * ✅ 停止震动循环
     */
    private void stopWatchVibration() {
        isVibrating = false;
        if (vibrationHandler != null) {
            vibrationHandler.removeCallbacks(vibrationRunnable);
        }
        // 立即取消当前正在进行的震动
        WearVibratorHelper.cancelVibration(this);
        WearLog.d(TAG, "🔇 闹钟震动已停止");
    }

    private void cleanExit() {
        try {
            stopWatchVibration(); // ✅ 确保退出前停止震动
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
        // ✅ 界面不可见时也停止震动，节省电量
        stopWatchVibration();
    }

    @Override
    protected void onDestroy() {
        // ✅ 最终保险：Activity 销毁时必须停止震动
        stopWatchVibration();
        
        if (instanceRef != null) {
            instanceRef.clear();
            instanceRef = null;
        }
        super.onDestroy();
    }
}
