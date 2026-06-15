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
 * 手表全屏响铃 Activity
 * 完美修复：精准绑定你之前在 XML 中亲手修改并对齐的 btn_dismiss_alarm 和 btn_snooze_alarm！
 */
public class WearAlarmActivity extends Activity {
    private static final String TAG = "WearSync_WearAlarm";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // 🎯 绑定包含你新修改 ID 的闹钟布局文件
        setContentView(R.layout.activity_alarm); 

        // 🎯 精准匹配：关闭/停止闹钟按钮
        Button btnDismiss = findViewById(R.id.btn_dismiss_alarm);
        if (btnDismiss != null) {
            btnDismiss.setOnClickListener(v -> {
                Log.d(TAG, "🛎️ 用户点击[停止]按钮，正在向手机投递关闭信号...");
                sendAlarmActionToPhone("DISMISS_ALARM");
                finish();
            });
        } else {
            Log.e(TAG, "❌ 警告：在布局中未找到 R.id.btn_dismiss_alarm，请核对 XML！");
        }

        // 🎯 精准匹配：稍后提醒/延后按钮
        Button btnSnooze = findViewById(R.id.btn_snooze_alarm);
        if (btnSnooze != null) {
            btnSnooze.setOnClickListener(v -> {
                Log.d(TAG, "🛎️ 用户点击[延后]按钮，正在向手机投递延时信号...");
                sendAlarmActionToPhone("SNOOZE_ALARM");
                finish();
            });
        } else {
            Log.e(TAG, "❌ 警告：在布局中未找到 R.id.btn_snooze_alarm，请核对 XML！");
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
