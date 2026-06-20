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
import android.util.Log;
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
    
    // 🎯 協議精準拉齊：這裡必須與手錶骨幹網發射的廣播路徑完全等值一致！
    public static final String ACTION_INTERNAL_FORCE_STOP = "de.rhaeus.wearsync.ACTION_INTERNAL_FORCE_STOP";

    private Vibrator vibrator;
    private boolean isDestroyedBySystem = false;

    private TextView tvAlarmTime;
    private TextView tvAlarmLabel;

    // 📥 核心強退看門狗監聽器：一旦收到手機的 FORCE_STOP_WEAR_ALARM 觸發的本地廣播，手錶立刻停震自毀退出！
    private final BroadcastReceiver forceStopReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (ACTION_INTERNAL_FORCE_STOP.equalsIgnoreCase(intent.getAction())) {
                Log.d(TAG, "📥 [強退命令命中] 收到手機端下發的強退信號！手錶端UI立刻無條件平穩自毀退出...");
                isDestroyedBySystem = true;
                cleanExit();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 🔒 窗口控場鎖（高版本鎖屏彈窗前台特權）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        setContentView(R.layout.activity_wear_alarm); 

        tvAlarmTime = findViewById(R.id.tv_alarm_time);   
        tvAlarmLabel = findViewById(R.id.tv_alarm_label); 

        parseAndRenderIntentData(getIntent());

        // 🛰️ 安全註冊強退看門狗廣播（解決你原本代碼錯位放置在方法外的致命語法錯誤）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(forceStopReceiver, new IntentFilter(ACTION_INTERNAL_FORCE_STOP), Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(forceStopReceiver, new IntentFilter(ACTION_INTERNAL_FORCE_STOP));
        }

        vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        startInfiniteVibration();

        // 停止按鈕
        Button btnDismiss = findViewById(R.id.btn_dismiss);
        if (btnDismiss != null) {
            btnDismiss.setOnClickListener(v -> {
                Log.d(TAG, "👉 用戶在手錶端點擊了【停止】");
                sendActionToPhone("DISMISS");
                cleanExit(); // 把命運交給手機端看門狗：如果手機沒被掐滅，看門狗4秒內會再次把我拽起
            });
        }

        // 延後按鈕
        Button btnSnooze = findViewById(R.id.btn_snooze);
        if (btnSnooze != null) {
            btnSnooze.setOnClickListener(v -> {
                Log.d(TAG, "👉 用戶在手錶端點擊了【延後】");
                sendActionToPhone("SNOOZE");
                cleanExit();
            });
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        Log.w(TAG, "⚠️ [再次拉起核查警告]：手機端依舊在轟鳴！說明代點未成功，重新刷新全屏提示並加強震動！");
        parseAndRenderIntentData(intent);
        startInfiniteVibration();
    }

    private void parseAndRenderIntentData(Intent intent) {
        if (intent == null) return;
        String label = intent.getStringExtra("EXTRA_ALARM_LABEL");
        String time = intent.getStringExtra("EXTRA_ALARM_TIME");

        if (tvAlarmLabel != null && label != null) {
            tvAlarmLabel.setText(label);
        }
        if (tvAlarmTime != null && time != null) {
            tvAlarmTime.setText(time);
        }
    }

    private void startInfiniteVibration() {
        if (vibrator == null || !vibrator.hasVibrator()) return;
        try {
            vibrator.cancel(); 
            long[] pattern = {0, 500, 300};
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0));
            } else {
                vibrator.vibrate(pattern, 0);
            }
        } catch (Exception e) {
            Log.e(TAG, "啟動手錶核心震動失敗", e);
        }
    }

    private void sendActionToPhone(String actionCommand) {
        new Thread(() -> {
            try {
                JSONObject json = new JSONObject();
                json.put("sender", "wear");
                json.put("type", "alarm_action"); // 🎯 修正：與手機端骨幹網等待接收的 type="alarm_action" 完美對齊！
                json.put("action", actionCommand);
                json.put("timestamp", System.currentTimeMillis());

                byte[] payload = json.toString().getBytes(StandardCharsets.UTF_8);
                List<Node> nodes = Tasks.await(Wearable.getNodeClient(this).getConnectedNodes());
                if (nodes != null) {
                    for (Node n : nodes) {
                        Tasks.await(Wearable.getMessageClient(this).sendMessage(n.getId(), UNIVERSAL_SYNC_PATH, payload));
                        Log.d(TAG, "🚀 [鬧鐘反向代點] 成功向手機投遞口令: " + actionCommand);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "手錶向手機反向推送代點指令失敗", e);
            }
        }).start();
    }

    private void cleanExit() {
        isDestroyedBySystem = true; 
        try {
            if (vibrator != null) { vibrator.cancel(); }
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
        super.onDestroy();
    }
}
