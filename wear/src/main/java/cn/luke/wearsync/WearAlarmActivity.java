package cn.luke.wearsync;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;
import androidx.annotation.Nullable;
import androidx.activity.ComponentActivity;
import androidx.localbroadcastmanager.content.LocalBroadcastManager; // ✅ 确保导入
import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.MessageClient;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.util.List;
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
        
        // 1. 基础初始化
        instanceRef = new WeakReference<>(this);
        screenManager = new WearSyncScreenManager(this);
        screenManager.bind(this);
        WearLog.d(TAG, "🎬 onCreate: 手表闹钟接管界面启动");
        screenManager.wakeForSync(5 * 60 * 1000L);
        setContentView(R.layout.activity_wear_alarm);
        
        tvAlarmDay = findViewById(R.id.tv_alarm_day);
        tvAlarmTime = findViewById(R.id.tv_alarm_time);
        Button btnDismiss = findViewById(R.id.btn_dismiss);
        Button btnSnooze = findViewById(R.id.btn_snooze);

        handleIncomingIntent(getIntent());
        startWatchVibration();

        // 2. ✅ 修复：将通信逻辑包裹在 onCreate 方法内
        WearSyncCommManager.getInstance(this).setConnectionListener(new WearSyncCommManager.ConnectionListener() {
            @Override
            public void onConnected(Node node) {
                WearLog.d(TAG, "通信链路已就绪，可以发送指令");
            }

            @Override
            public void onDisconnected() {
                WearLog.w(TAG, "通信链路断开");
            }
        });
        WearSyncCommManager.getInstance(this).connect();

        // 3. 按钮逻辑
        btnDismiss.setOnClickListener(v -> {
            WearLog.d(TAG, "🔘 用户点击 [关闭]");
            WearSyncCommManager.getInstance(this).sendBusinessCommand("alarm_action", "DISMISS");
            cleanExit();
        });

        btnSnooze.setOnClickListener(v -> {
            WearLog.d(TAG, "🔘 用户点击 [延后]");
            WearSyncCommManager.getInstance(this).sendBusinessCommand("alarm_action", "SNOOZE");
            cleanExit();
        });
    }

    // ✅ 新增：处理来自 CommManager 的指令
    public static void handleIncomingCommand(Context context, JSONObject json) {
        WearLog.d(TAG, "收到闹钟控制指令: " + json.toString());
        String action = json.optString("action");
        if ("DISMISS".equals(action)) {
            if (getInstance() != null) {
                getInstance().cleanExit();
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
