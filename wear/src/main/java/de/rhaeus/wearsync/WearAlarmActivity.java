package de.rhaeus.wearsync;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
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
    public static final String ACTION_INTERNAL_FORCE_STOP = "de.rhaeus.wearsync.ACTION_INTERNAL_FORCE_STOP";

    private Vibrator vibrator;
    private boolean isDestroyedBySystem = false;
    private TextView tvAlarmDay;
    private TextView tvAlarmTime;
    private TextView tvAlarmLabel;

    private final BroadcastReceiver forceStopReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent != null && ACTION_INTERNAL_FORCE_STOP.equals(intent.getAction())) {
                WearLog.d(TAG, "📥 [看门狗脉冲] 收到手机端反向强退广播，立即安全自毁销毁闹钟界面...");
                cleanExit();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WearLog.d(TAG, "🎬 onCreate: 手表闹钟接管界面强行顶屏显示中...");

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_ALLOW_LOCK_WHILE_SCREEN_ON);

        setContentView(R.layout.activity_wear_alarm);

        tvAlarmDay = findViewById(R.id.tv_alarm_day);
        tvAlarmTime = findViewById(R.id.tv_alarm_time);
        tvAlarmLabel = findViewById(R.id.tv_alarm_label);
        Button btnDismiss = findViewById(R.id.btn_dismiss);
        Button btnSnooze = findViewById(R.id.btn_snooze);

        Intent intent = getIntent();
        if (intent != null) {
            String time = intent.getStringExtra("alarm_time");
            String label = intent.getStringExtra("alarm_label");
            String day = intent.getStringExtra("alarm_day");

            if (time != null) tvAlarmTime.setText(time);
            if (label != null) tvAlarmLabel.setText(label);
            if (day != null) tvAlarmDay.setText(day);

            WearLog.d(TAG, "📦 成功解析闹钟元数据 ➔ 时间:[" + time + "], 标签:[" + label + "]");
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            registerReceiver(forceStopReceiver, new IntentFilter(ACTION_INTERNAL_FORCE_STOP), Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(forceStopReceiver, new IntentFilter(ACTION_INTERNAL_FORCE_STOP));
        }

        startWatchVibration();

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

    private void startWatchVibration() {
        vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        if (vibrator != null && vibrator.hasVibrator()) {
            WearLog.d(TAG, "📳 启动手表独立硬件震动器...");
            long[] pattern = {0, 500, 500};
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
        isDestroyedBySystem = true;
        try {
            if (vibrator != null) vibrator.cancel();
            unregisterReceiver(forceStopReceiver);
        } catch (Exception ignored) {}
        finishAndRemoveTask();
    }

    @Override
    protected void onDestroy() {
        if (!isDestroyedBySystem) {
            if (vibrator != null) vibrator.cancel();
            try { unregisterReceiver(forceStopReceiver); } catch (Exception ignored) {}
        }
        WearLog.d(TAG, "🏳️ onDestroy: 闹钟接管界面完全退出并释放上下文");
        super.onDestroy();
    }
}
