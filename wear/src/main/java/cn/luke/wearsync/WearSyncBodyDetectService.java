package cn.luke.wearsync;

import android.app.Service;
import android.content.Intent;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.IBinder;

import androidx.annotation.Nullable;

import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;

/**
 * 🧘 离腕检测服务
 * 职责：实时监听佩戴状态，并在状态改变时通知手机。
 */
public class WearSyncBodyDetectService extends Service implements SensorEventListener {
    private static final String TAG = "WearSync_BodyDetect";
    private static final String UNIVERSAL_SYNC_PATH = "/wear-universal-sync";
    
    private SensorManager sensorManager;
    private Sensor offBodySensor;
    private float lastState = -1.0f;

    @Override
    public void onCreate() {
        super.onCreate();
        WearLog.d(TAG, "🟢 离腕检测服务已创建");
        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        if (sensorManager != null) {
            offBodySensor = sensorManager.getDefaultSensor(Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT);
            if (offBodySensor == null) {
                WearLog.w(TAG, "⚠️ 硬件不支持 TYPE_LOW_LATENCY_OFFBODY_DETECT");
            } else {
                WearLog.d(TAG, "✅ 找到离腕传感器：" + offBodySensor.getName());
                sensorManager.registerListener(this, offBodySensor, SensorManager.SENSOR_DELAY_NORMAL);
            }
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        WearLog.d(TAG, "🚀 离腕检测服务正在运行...");
        return START_STICKY;
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT) {
            float currentState = event.values[0];
            if (currentState != lastState) {
                lastState = currentState;
                String status = (currentState == 0.0f) ? "OFF_BODY" : "ON_BODY";
                WearLog.i(TAG, "🔍 佩戴状态变更: " + status);
                notifyPhoneStatus(status);
            }
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    private void notifyPhoneStatus(String status) {
        try {
            JSONObject payload = new JSONObject();
            payload.put("sender", "wear");
            payload.put("type", "body_status");
            payload.put("action", status);
            payload.put("timestamp", System.currentTimeMillis());

            byte[] data = payload.toString().getBytes(StandardCharsets.UTF_8);
            
            Wearable.getNodeClient(this).getConnectedNodes()
                .addOnSuccessListener(nodes -> {
                    for (Node node : nodes) {
                        Wearable.getMessageClient(this)
                            .sendMessage(node.getId(), UNIVERSAL_SYNC_PATH, data)
                            .addOnSuccessListener(v -> WearLog.d(TAG, "✅ 状态同步成功: " + status))
                            .addOnFailureListener(e -> WearLog.e(TAG, "❌ 状态同步失败: " + e.getMessage()));
                    }
                });
        } catch (Exception e) {
            WearLog.e(TAG, "❌ 构建状态 JSON 失败", e);
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        WearLog.d(TAG, "🛑 停止传感器监听并销毁服务");
        if (sensorManager != null && offBodySensor != null) {
            sensorManager.unregisterListener(this);
        }
        super.onDestroy();
    }
}
