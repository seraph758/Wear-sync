package de.rhaeus.wearsync;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 手表全屏响铃 Activity。
 * 完美修正：将以前硬编码错位的 "DISMISS" 协议升级对齐为规范的 "DISMISS_ALARM"，
 * 并完美补齐“延后”按钮，投递 "SNOOZE_ALARM" 协议。
 */
public class WearAlarmActivity extends Activity {
    private static final String TAG = "WearSync_WearAlarm";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_alarm); // 绑定手表全屏响铃布局

        // 1. 绑定并对齐：关闭/停止闹钟按钮
        Button btnDismiss = findViewById(R.id.btn_dismiss_alarm);
        if (btnDismiss != null) {
            btnDismiss.setOnClickListener(v -> {
                sendAlarmActionToPhone("DISMISS_ALARM"); // 协议完美对齐
                finish();
            });
        }

        // 2. 绑定并对齐：稍后提醒/延后按钮
        Button btnSnooze = findViewById(R.id.btn_snooze_alarm);
        if (btnSnooze != null) {
            btnSnooze.setOnClickListener(v -> {
                sendAlarmActionToPhone("SNOOZE_ALARM"); // 协议完美对齐
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
                json.put("action", actionValue); // 发送精准匹配信令
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