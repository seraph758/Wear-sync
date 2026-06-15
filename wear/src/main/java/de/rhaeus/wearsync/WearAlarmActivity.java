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
 * 完美修正：对齐手錶端原版现有的 activity_main 布局资源（原项目没有 activity_alarm），
 * 并精准绑定原版自带的 bt_dismiss 按钮！
 */
public class WearAlarmActivity extends Activity {
    private static final String TAG = "WearSync_WearAlarm";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // 🎯 核心修复：直接绑定手錶端原本自带的 activity_main 布局
        setContentView(R.layout.activity_main); 

        // 🎯 核心修复：对齐绑定你原本 XML 里现成的关闭闹钟按钮 ID (bt_dismiss)
        Button btnDismiss = findViewById(R.id.bt_dismiss);
        if (btnDismiss != null) {
            btnDismiss.setOnClickListener(v -> {
                sendAlarmActionToPhone("DISMISS_ALARM"); // 协议完美对齐手机端关键字
                finish();
            });
        }

        // 🎯 核心修复：对齐绑定你原本 XML 里现成的延后/稍后提醒按钮 ID (bt_snooze)
        Button btnSnooze = findViewById(R.id.bt_snooze);
        if (btnSnooze != null) {
            btnSnooze.setOnClickListener(v -> {
                sendAlarmActionToPhone("SNOOZE_ALARM"); // 协议完美对齐手机端关键字
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
