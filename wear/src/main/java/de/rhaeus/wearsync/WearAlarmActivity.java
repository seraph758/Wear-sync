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
import android.widget.TextView; // 🎯 引入 TextView 用於全屏提示

import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;

import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class WearAlarmActivity extends Activity {
    private static final String TAG = "WearSync_WearAlarmUI";
    private static final String UNIVERSAL_SYNC_PATH = "/wear-universal-sync";
    public static final String ACTION_INTERNAL_FORCE_STOP = "de.rhaeus.wearsync.ACTION_FORCE_STOP_ALARM";

    private Vibrator vibrator;
    private boolean isDestroyedBySystem = false;

    // 🎯 UI 提示控件定義
    private TextView tvAlarmTime;
    private TextView tvAlarmLabel;

    private final BroadcastReceiver forceStopReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (ACTION_INTERNAL_FORCE_STOP.equalsIgnoreCase(intent.getAction())) {
                Log.d(TAG, "🛑 收到廣播：手機端已成功關閉鬧鐘。手錶端清淨退出。");
                isDestroyedBySystem = true;
                cleanExit();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 🔒 窗口控場鎖
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        setContentView(R.layout.activity_wear_alarm); 

        // 🎯 1. 綁定全屏提示的 TextView（請確保您的 activity_wear_alarm.xml 裡有這兩個 ID）
        tvAlarmTime = findViewById(R.id.tv_alarm_time);   // 用於顯示 07:30 
        tvAlarmLabel = findViewById(R.id.tv_alarm_label); // 用於顯示 "起床鬧鐘"

        // 🎯 2. 解析並渲染全屏提示內容
        parseAndRenderIntentData(getIntent());

        // 🛰️ 註冊強退看門狗廣播
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(forceStopReceiver, new IntentFilter(ACTION_INTERNAL_FORCE_STOP), Context.RECEIVER_NOT_EXPORTED);
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
                cleanExit(); // 乾淨撤退，把命運交給「核查機制」：如果手機沒滅，手機會再次將我拉起！
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

    /**
     * 🔥【核心核查防漏機制】：如果手錶點了關閉，但手機代點失敗還在響，
     * 手機端再次發送 START_ALARM_UI 拽起手錶時，如果本頁面還沒退乾淨，會直接觸發這裡！
     */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        Log.w(TAG, "⚠️ [再次拉起核查警告]：手機端依舊在轟鳴！說明代點可能失敗，重新刷新全屏提示並加強震動！");
        
        // 1. 重新解析可能變更的鬧鐘提示
        parseAndRenderIntentData(intent);
        
        // 2. 重新激活震動，防止代點期間震動中斷
        startInfiniteVibration();
    }

    /**
     * 🎯 萃取並渲染全屏提示文字的方法
     */
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
            vibrator.cancel(); // 先取消，確保重新編排
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
                json.put("type", "alarm");
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
        isDestroyedBySystem = true; // 🎯 防禦重置，告訴 onDestroy 不要重複注銷 Receiver
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
