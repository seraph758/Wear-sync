package cn.luke.wearsync;

import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;

import com.google.android.gms.tasks.Task;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;
import com.google.android.gms.wearable.WearableListenerService;

import java.nio.charset.StandardCharsets;
import java.util.List;

public class WearSyncBodyDetectService extends WearableListenerService implements SensorEventListener {
    private static final String TAG = "WearSync_Watch";
    private SensorManager sensorManager;
    private Sensor offBodySensor;
    private float lastState = -1.0f; // 初始为 -1

    @Override
    public void onCreate() {
        super.onCreate();
        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        if (sensorManager != null) {
            // 获取标准的离腕/佩戴检测传感器
           offBodySensor = sensorManager.getDefaultSensor(Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT);

            if (offBodySensor == null) {
                WearLog.d(TAG, "TYPE_LOW_LATENCY_OFFBODY_DETECT 不存在");
            } else {
                WearLog.d(TAG, "找到离腕传感器：" + offBodySensor.getName());
            }
            
            if (offBodySensor != null) {
                sensorManager.registerListener(this, offBodySensor, SensorManager.SENSOR_DELAY_NORMAL);
                WearLog.d(TAG, "离腕检测传感器注册成功");
            }
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT) {
            float currentState = event.values[0];
            WearLog.d(TAG, "离腕状态变化：" + currentState);
            // 只有当状态发生实际改变时，才执行后续的网络发送逻辑
            if (currentState != lastState) {
                lastState = currentState;
                if (currentState == 0.0f) {
                    sendMessageToPhone("off_body");
                } else if (currentState == 1.0f) {
                    sendMessageToPhone("on_body");
                }
            }
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // 无需实现
    }

    private void sendMessageToPhone(String status) {
        WearLog.d(TAG, "准备发送状态：" + status);
        Task<List<Node>> connectedNodesTask = Wearable.getNodeClient(this).getConnectedNodes();
        connectedNodesTask.addOnSuccessListener(nodes -> {
            for (Node node : nodes) {
                WearLog.d(TAG, "发送到节点：" + node.getDisplayName());
                Wearable.getMessageClient(this).sendMessage(
                        node.getId(),
                        "/wearsync-body-status",
                        status.getBytes(StandardCharsets.UTF_8)
                ).addOnSuccessListener(task -> {
                    WearLog.d(TAG, "✅ 离腕消息发送成功: " + status); // 确认消息已提交给 Wear OS
                }).addOnFailureListener(e -> {
                    WearLog.e(TAG, "❌ 离腕消息发送失败: " + e.getMessage()); // 定位是节点问题还是通道问题
                });
            }
        });
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (sensorManager != null && offBodySensor != null) {
            sensorManager.unregisterListener(this);
        }
    }
}
