package de.rhaeus.wearsync;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Vibrator;
import android.os.VibrationEffect;
import android.view.WindowManager;
import android.widget.Button;
import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class WearAlarmActivity extends Activity {
    private Vibrator vibrator;
    private boolean isVibrating = false;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final BroadcastReceiver stopReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if ("de.rhaeus.wearsync.FORCE_STOP_ALARM_UI".equals(intent.getAction())) {
                cleanUpAndDestroy();
            }
        }
    };

    private final Runnable vibrateRunnable = new Runnable() {
        @Override
        public void run() {
            if (isVibrating && vibrator != null) {
                vibrator.vibrate(VibrationEffect.createOneShot(600, VibrationEffect.DEFAULT_AMPLITUDE));
                handler.postDelayed(this, 1200);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // 🎯 核心手术：在渲染视图前，强制注入最高级别破锁屏和点亮屏幕 Flag，攻克系统静默拦截
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED 
                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        super.onCreate(savedInstanceState);
        setContentView(getResources().getIdentifier("activity_wear_alarm", "layout", getPackageName()));

        vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);

        Button btnDismiss = findViewById(getResources().getIdentifier("btn_dismiss", "id", getPackageName()));
        Button btnSnooze = findViewById(getResources().getIdentifier("btn_snooze", "id", getPackageName()));

        if (btnDismiss != null) btnDismiss.setOnClickListener(v -> sendActionToPhone("DISMISS"));
        if (btnSnooze != null) btnSnooze.setOnClickListener(v -> sendActionToPhone("SNOOZE"));

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stopReceiver, new IntentFilter("de.rhaeus.wearsync.FORCE_STOP_ALARM_UI"), Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(stopReceiver, new IntentFilter("de.rhaeus.wearsync.FORCE_STOP_ALARM_UI"));
        }

        isVibrating = true;
        handler.post(vibrateRunnable);
    }

    private void sendActionToPhone(String action) {
        new Thread(() -> {
            try {
                JSONObject json = new JSONObject();
                json.put("sender", "wear");
                json.put("type", "alarm_control"); // 匹配手机端新增的协议类型
                json.put("action", action);

                byte[] data = json.toString().getBytes(StandardCharsets.UTF_8);
                List<Node> nodes = Tasks.await(Wearable.getNodeClient(this).getConnectedNodes());
                for (Node n : nodes) {
                    Wearable.getMessageClient(this).sendMessage(n.getId(), "/wear-universal-sync", data);
                }
            } catch (Exception ignored) {}
            runOnUiThread(this::cleanUpAndDestroy);
        }).start();
    }

    private void cleanUpAndDestroy() {
        isVibrating = false;
        handler.removeCallbacks(vibrateRunnable);
        if (vibrator != null) vibrator.cancel();
        try { unregisterReceiver(stopReceiver); } catch (Exception ignored) {}
        finishAndRemoveTask();
    }

    @Override
    protected void onDestroy() {
        cleanUpAndDestroy();
        super.onDestroy();
    }
}