package de.rhaeus.wearsync;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 手表全屏响铃 Activity
 * 完美修正：精准绑定你提供的 activity_wear_alarm.xml 及其按钮 ID。
 */
public class WearAlarmActivity extends Activity {
    private static final String TAG = "WearSync_WearAlarm";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // 🎯 1. 核心修正：精准对齐你本地存在的 activity_wear_alarm.xml 文件名
        try {
            setContentView(R.layout.activity_wear_alarm); 
        } catch (Exception e) {
            Log.e(TAG, "❌ 严重错误：找不到 R.layout.activity_wear_alarm，请检查文件名！", e);
            // 兜底防崩溃：如果依然找不到，加载一个空白视图，防止崩溃
            setContentView(new View(this));
        }

        // 🎯 2. 精准绑定 XML 里的延后按钮：btn_snooze_alarm
        Button btnSnooze = findViewById(R.id.btn_snooze_alarm);
        if (btnSnooze != null) {
            btnSnooze.setOnClickListener(v -> {
                Log.d(TAG, "⏰ 用户点击了[延后]按钮，正在向手机投递延迟信号...");
                sendAlarmActionToPhone("SNOOZE_ALARM");
                finish();
            });
        } else {
            Log.e(TAG, "⚠️ 警告：在布局中未找到 R.id.btn_snooze_alarm，执行盲操全屏兜底");
            // 兜底：如果找不到按钮，点屏幕任意地方也触发延后
            View container = findViewById(R.id.alarm_container);
            if (container != null) {
                container.setOnClickListener(v -> {
                    sendAlarmActionToPhone("SNOOZE_ALARM");
                    finish();
                });
            }
        }

        // 🎯 3. 精准绑定 XML 里的停止按钮：btn_dismiss_alarm
        Button btnDismiss = findViewById(R.id.btn_dismiss_alarm);
        if (btnDismiss != null) {
            btnDismiss.setOnClickListener(v -> {
                Log.d(TAG, "🛑 用户点击了[停止]按钮，正在向手机投递关闭信号...");
                sendAlarmActionToPhone("DISMISS_ALARM");
                finish();
            });
        }
    }

    private void sendAlarmActionToPhone(String actionValue) {
        new Thread(() -> {
            try {
                JSONObject json = new JSONObject();
                json.put("sender", "wear");
                json.put("type", "alarm");
                json.put("action", actionValue);
                json.put("timestamp", System.currentTimeMillis());

                byte[] data = json.toString().getBytes(StandardCharsets.UTF_8);
                List<Node> nodes = Tasks.await(Wearable.getNodeClient(this).getConnectedNodes());
                for (Node node : nodes) {
                    Wearable.getMessageClient(this).sendMessage(node.getId(), "/wear-universal-sync", data);
                }
                Log.d(TAG, "📤 手表成功投递闹钟响应动作至手机: " + actionValue);
            } catch (Exception e) {
                Log.e(TAG, "手表投递闹钟信令失败", e);
            }
        }).start();
    }
}
