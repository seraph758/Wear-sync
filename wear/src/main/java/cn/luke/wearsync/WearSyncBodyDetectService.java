package cn.luke.wearsync;

import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.util.Log;

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
            if (offBodySensor != null) {
                // 注册监听器
                sensorManager.registerListener(this, offBodySensor, SensorManager.SENSOR_DELAY_NORMAL);
                Log.d(TAG, "🟢 WearSyncBodyDetectService: 离腕检测传感器注册成功");
            }
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT) {
            float currentState = event.values[0];
            
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
        Task<List<Node>> connectedNodesTask = Wearable.getNodeClient(this).getConnectedNodes();
        connectedNodesTask.addOnSuccessListener(nodes -> {
            for (Node node : nodes) {
                Wearable.getMessageClient(this).sendMessage(
                        node.getId(),
                        "/wearsync-body-status", 
                        status.getBytes(StandardCharsets.UTF_8)
                ).addOnFailureListener(e -> {
                    Log.e(TAG, "发送状态到手机失败: " + e.getMessage());
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
