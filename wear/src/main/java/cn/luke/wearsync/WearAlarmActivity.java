package cn.luke.wearsync;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;
import androidx.annotation.Nullable;
import androidx.activity.ComponentActivity;
// ❌ 删除 import androidx.localbroadcastmanager.content.LocalBroadcastManager; (未使用)
// ❌ 删除 import com.google.android.gms.tasks.Tasks; (未使用)
// ❌ 删除 import com.google.android.gms.wearable.MessageClient; (未使用)
// ❌ 删除 import com.google.android.gms.wearable.Node; (未使用)
// ❌ 删除 import com.google.android.gms.wearable.Wearable; (未使用)
import java.lang.ref.WeakReference;
// ❌ 删除 import java.nio.charset.StandardCharsets; (未使用)
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
