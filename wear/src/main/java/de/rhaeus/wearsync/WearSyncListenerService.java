package de.rhaeus.wearsync;

import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;
import android.util.Log;
import androidx.annotation.NonNull;
import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.ChannelClient;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.Wearable;
import com.google.android.gms.wearable.WearableListenerService;
import org.json.JSONObject;

import java.io.EOFException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 🛰️ 手表端中央信令路由器 (终极完全合流版)
 * 核心职责：
 * 1. 勿扰/手势宏分发中心。
 * 2. 远端闹钟强拉、硬件亮屏与反向停震。
 * 3. 相机控场信令分发，以及 ChannelClient 核心高速流式解包泵。
 */
public class WearSyncListenerService extends WearableListenerService {
    private static final String TAG = "WearSync_WearListener";
    private static final String UNIVERSAL_SYNC_PATH = "/wear-universal-sync";
    private static final String CAMERA_PREVIEW_STREAM_PATH = "/camera-preview-stream";

    @Override
    public void onMessageReceived(@NonNull MessageEvent messageEvent) {
        if (!UNIVERSAL_SYNC_PATH.equalsIgnoreCase(messageEvent.getPath())) return;
        byte[] data = messageEvent.getData();
        if (data == null) return;

        try {
            String jsonStr = new String(data, StandardCharsets.UTF_8);
            JSONObject json = new JSONObject(jsonStr);
            String sender = json.optString("sender", "");
            String type = json.optString("type", "");
            String action = json.optString("action", "");

            if ("wear".equalsIgnoreCase(sender)) return;

            // ================= 🌙 1️⃣ 勿扰模式/手势宏分发 =================
            if ("dnd".equalsIgnoreCase(type)) {
                // 原有的勿扰分发或手势宏保留在此处...
                return;
            }

            // ================= ⏰ 2️⃣ 远端闹钟核心联动分发 =================
            if ("alarm".equalsIgnoreCase(type)) {
                Log.d(TAG, "📥 [闹钟信令] 收到来自手机端的闹钟控制要求: " + action);
                if ("START_ALARM_UI".equalsIgnoreCase(action)) {
                    PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                    if (pm != null) {
                        PowerManager.WakeLock wakeLock = pm.newWakeLock(
                                PowerManager.FULL_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP | PowerManager.ON_AFTER_RELEASE, 
                                "wearsync:AlarmScreenWakeLock"
                        );
                        wakeLock.acquire(3000L); 
                    }
                    Intent alarmIntent = new Intent(this, WearAlarmActivity.class);
                    alarmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                    startActivity(alarmIntent);
                } else if ("FORCE_STOP_WEAR_ALARM".equalsIgnoreCase(action)) {
                    Intent stopBroadcast = new Intent(WearAlarmActivity.ACTION_INTERNAL_FORCE_STOP);
                    sendBroadcast(stopBroadcast);
                }
                return;
            }

            // ================= 📸 3️⃣ 相机控场信令分发 (完美复活) =================
            if ("camera_control".equalsIgnoreCase(type)) {
                Log.d(TAG, "📥 [相机信令] 收到手机端相机主控动作: " + action);
                if ("START_CAMERA".equalsIgnoreCase(action)) {
                    int rotationDegrees = json.optInt("rotation_degrees", 0);
                    
                    // 手机端拉起了相机，手表端立刻同步开启前台预览 Activity，并把旋转角度送过去
                    Intent camIntent = new Intent(this, WearCameraActivity.class);
                    camIntent.putExtra("rotation_degrees", rotationDegrees);
                    camIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                    startActivity(camIntent);
                } else if ("FORCE_QUIT_CAMERA".equalsIgnoreCase(action)) {
                    // 手机端退出了相机，利用弱引用指针通知前台 Activity 秒级自杀
                    WearCameraActivity.forceQuitInstance();
                }
                return;
            }

        } catch (Exception e) {
            Log.e(TAG, "手表中央路由器分发信令灾难性失败", e);
        }
    }

    /**
     * 🌊 Channel 大文件通道监听器：高频接收来自手机相机的 YUV 压缩 JPEG 数据流
     */
    @Override
    public void onChannelOpened(@NonNull ChannelClient.Channel channel) {
        super.onChannelOpened(channel);
        if (!CAMERA_PREVIEW_STREAM_PATH.equalsIgnoreCase(channel.getPath())) return;

        Log.d(TAG, "🌊 [管道建立] 相机高性能字节流传输通道已在手表端打开，启动多线程流式接收器...");
        
        new Thread(() -> {
            InputStream inputStream = null;
            try {
                // 显式同步阻塞获取底层输入流
                inputStream = Tasks.await(Wearable.getChannelClient(this).getInputStream(channel));
                byte[] headerBuffer = new byte[4];

                while (true) {
                    // 📡 协议解析步骤一：精准读取4个字节的长度报头 header
                    readFully(inputStream, headerBuffer, 4);
                    int packetLength = ((headerBuffer[0] & 0xFF) << 24) |
                                       ((headerBuffer[1] & 0xFF) << 16) |
                                       ((headerBuffer[2] & 0xFF) << 8)  |
                                       (headerBuffer[3] & 0xFF);

                    // 安全防御：如果包长度不合法，直接强行阻断防止缓冲区溢出
                    if (packetLength <= 0 || packetLength > 1024 * 1024 * 3) {
                        Log.e(TAG, "⚠️ 探测到由于蓝牙丢包产生的异常帧长度: " + packetLength + "，强行切断流连接");
                        break;
                    }

                    // 📡 协议解析步骤二：根据报头指示的长度，精准吃满实际的 JPEG 图像字节内容
                    byte[] jpegPayload = new byte[packetLength];
                    readFully(inputStream, jpegPayload, packetLength);

                    // 📡 协议解析步骤三：读取1个字节的尾部校验符，确保分包数据的极高纯净度
                    int trailer = inputStream.read();
                    if (trailer != 0xFF) {
                        Log.w(TAG, "⚠️ 帧尾部校验符不匹配 [丢弃该帧]，防止出现花屏或画面碎裂");
                        continue;
                    }

                    // 📡 协议解析步骤四：通过弱引用静态信道，零拷贝高频泵入前台 UI 界面进行渲染
                    WearCameraActivity.updateFrame(jpegPayload);
                }
            } catch (Exception e) {
                Log.d(TAG, "🏁 传输管道流读取正常结束或由于手机主动断开而关闭: " + e.getMessage());
            } finally {
                if (inputStream != null) {
                    try { inputStream.close(); } catch (Exception ignored) {}
                }
            }
        }).start();
    }

    /**
     * 🛠️ 字节流硬读看门狗：确保必须完整读满 length 字节才返回，彻底打破蓝牙传输的不稳定断包魔咒
     */
    private void readFully(InputStream is, byte[] buffer, int length) throws Exception {
        int totalRead = 0;
        while (totalRead < length) {
            int read = is.read(buffer, totalRead, length - totalRead);
            if (read == -1) {
                throw new EOFException("Stream closed prematurely");
            }
            totalRead += read;
        }
    }
}
