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
import android.widget.Button; // 实际编写UI时可替换为对应的圆圈样式或ImageButton

import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;

import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * ⏰ 手表端远端闹钟全屏交互控制舱
 * 核心职责：
 * 1. 顶层显示并强制维持屏幕常亮。
 * 2. 启动时启动无限循环的高频强力震动链条。
 * 3. 监听手机端随时传来的解脱强退广播。
 * 4. 用户点击 停止(DISMISS) 或 延后(SNOOZE) 时，反向对手机投递精准代点信令并利落自杀。
 */
public class WearAlarmActivity extends Activity {
    private static final String TAG = "WearSync_WearAlarmUI";
    private static final String UNIVERSAL_SYNC_PATH = "/wear-universal-sync";
    public static final String ACTION_INTERNAL_FORCE_STOP = "de.rhaeus.wearsync.ACTION_FORCE_STOP_ALARM";

    private Vibrator vibrator;
    private boolean isDestroyedBySystem = false;

    /**
     * 🛰️ 核心看门狗：随时准备接收由于手机端点灭闹钟而下发的强退广播
     */
    private final BroadcastReceiver forceStopReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (ACTION_INTERNAL_FORCE_STOP.equalsIgnoreCase(intent.getAction())) {
                Log.d(TAG, "🛑 收到广播：手机端已关闭闹钟。手表端准备无条件清净退出。");
                isDestroyedBySystem = true;
                cleanExit();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // 🔒 1. 窗口控场锁：强制点亮屏幕并保持常亮，允许在锁屏界面上方直接无阻碍全屏弹窗
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // 🌟 2. 动态加载一个简单的全屏交互布局（包含两个控制按钮：btn_dismiss 和 btn_snooze）
        setContentView(R.layout.activity_wear_alarm); 

        // 🛰️ 3. 注册强退看门狗广播
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(forceStopReceiver, new IntentFilter(ACTION_INTERNAL_FORCE_STOP), Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(forceStopReceiver, new IntentFilter(ACTION_INTERNAL_FORCE_STOP));
        }

        // ⚡ 4. 激活无限高频强震动链条
        vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        startInfiniteVibration();

        // 🎯 5. 绑定“停止（DISMISS）”按钮动作
        Button btnDismiss = findViewById(R.id.btn_dismiss);
        if (btnDismiss != null) {
            btnDismiss.setOnClickListener(v -> {
                Log.d(TAG, "👉 用户在手表端点击了【停止】");
                sendActionToPhone("DISMISS");
                cleanExit();
            });
        }

        // 🎯 6. 绑定“延后（SNOOZE）”按钮动作
        Button btnSnooze = findViewById(R.id.btn_snooze);
        if (btnSnooze != null) {
            btnSnooze.setOnClickListener(v -> {
                Log.d(TAG, "👉 用户在手表端点击了【延后】");
                sendActionToPhone("SNOOZE");
                cleanExit();
            });
        }
    }

    /**
     * ⚡ 高频震动编排器：制造强烈的规律性同步手腕震感
     */
    private void startInfiniteVibration() {
        if (vibrator == null || !vibrator.hasVibrator()) return;
        try {
            // 编排一个典型的闹钟震动模式：震动500毫秒，停顿300毫秒，以此循环
            long[] pattern = {0, 500, 300};
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // 最后一个参数 0 代表从数组索引0开始无限循环震动
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0));
            } else {
                vibrator.vibrate(pattern, 0);
            }
        } catch (Exception e) {
            Log.e(TAG, "启动手表核心震动失败", e);
        }
    }

    /**
     * 🚀 跨端反向发射：通知手机中央路由器执行通知栏高精准虚拟代点
     */
    private void sendActionToPhone(String actionCommand) {
        new Thread(() -> {
            try {
                JSONObject json = new JSONObject();
                json.put("sender", "wear");
                json.put("type", "alarm_action");
                json.put("action", actionCommand); // 发送 "DISMISS" 或 "SNOOZE"
                json.put("timestamp", System.currentTimeMillis());

                byte[] payload = json.toString().getBytes(StandardCharsets.UTF_8);
                List<Node> nodes = Tasks.await(Wearable.getNodeClient(this).getConnectedNodes());
                if (nodes != null) {
                    for (Node n : nodes) {
                        Tasks.await(Wearable.getMessageClient(this).sendMessage(n.getId(), UNIVERSAL_SYNC_PATH, payload));
                        Log.d(TAG, "🚀 [闹钟反向代点] 成功向手机投递口令: " + actionCommand);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "手表向手机反向推送代点指令失败", e);
            }
        }).start();
    }

    /**
     * 🧹 干净撤退协议：彻底掐断震动、注销看门狗、抹去历史任务残余
     */
    private void cleanExit() {
        try {
            if (vibrator != null) {
                vibrator.cancel(); // 🛑 瞬间停震，还用户清净
            }
            unregisterReceiver(forceStopReceiver);
        } catch (Exception ignored) {}

        // 调用 finishAndRemoveTask()。配合 CLEAR_TOP 标志，
        // 能将本页面从系统的最近任务（Recent Tasks）列表中斩草除根，绝不留任何后台残影。
        finishAndRemoveTask();
    }

    @Override
    protected void onDestroy() {
        // 二次兜底安全阀：防止用户通过左滑手势强行划走页面时，震动依然残留在后台压榨电池
        if (!isDestroyedBySystem) {
            if (vibrator != null) vibrator.cancel();
            try { unregisterReceiver(forceStopReceiver); } catch (Exception ignored) {}
        }
        super.onDestroy();
    }
}
