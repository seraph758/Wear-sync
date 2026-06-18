package de.rhaeus.wearsync;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.hardware.Camera; 
import android.os.IBinder;
import android.util.Log;
import android.view.WindowManager;
import androidx.annotation.Nullable;

import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.ChannelClient;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;

import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class PhoneSyncCameraService extends Service implements Camera.PreviewCallback {
    private static final String TAG = "WearSync_PhoneSyncCamera";
    private static final String UNIVERSAL_SYNC_PATH = "/wear-universal-sync";
    private static final String CAMERA_PREVIEW_STREAM_PATH = "/camera-preview-stream";

    public static final String ACTION_START_CAMERA = "de.rhaeus.wearsync.ACTION_START_CAMERA";
    public static final String ACTION_STOP_CAMERA_STREAM = "de.rhaeus.wearsync.ACTION_STOP_CAMERA_STREAM";
    public static final String ACTION_TRIGGER_SHUTTER = "de.rhaeus.wearsync.ACTION_TRIGGER_SHUTTER";

    private Camera mCamera;
    private ChannelClient.Channel mTargetChannel;
    private OutputStream mChannelOutputStream;
    private boolean isStreaming = false;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "🚀 PhoneSyncCameraService 收到触发信令...");
        
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            String channelId = "camera_sync_channel";
            android.app.NotificationChannel channel = new android.app.NotificationChannel(
                    channelId, "相机远端同步", android.app.NotificationManager.IMPORTANCE_LOW);
            android.app.NotificationManager nm = (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(channel);
    
            android.app.Notification notification = new android.app.Notification.Builder(this, channelId)
                    .setContentTitle("WearSync")
                    .setContentText("远端相机流同步交互中...")
                    .setSmallIcon(android.R.drawable.ic_menu_camera)
                    .build();
            startForeground(8899, notification);
        }

        if (intent == null || intent.getAction() == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        String action = intent.getAction();
        Log.d(TAG, "⚙️ 手机相机服务收到动作: " + action);

        // 🎯 修正点：严格使用 if-else 分流，彻底删除了最下方那个错误的 initAndStartCameraPipeline()
        if (ACTION_START_CAMERA.equals(action)) {
            startCameraAndSetupPipeline();
        } else if (ACTION_STOP_CAMERA_STREAM.equals(action)) {
            releaseCameraAndPipeline();
        } else if (ACTION_TRIGGER_SHUTTER.equals(action)) {
            executePhoneShutter();
        }
        
        return START_NOT_STICKY;
    }

    private void startCameraAndSetupPipeline() {
        if (isStreaming) return;
        isStreaming = true;

        new Thread(() -> {
            try {
                mCamera = Camera.open(Camera.CameraInfo.CAMERA_FACING_BACK);
                Camera.Parameters parameters = mCamera.getParameters();
                parameters.setPreviewSize(640, 480); 
                mCamera.setParameters(parameters);
                mCamera.setPreviewCallback(this);

                int rotationDegrees = calculatePhoneRotation();
                Log.d(TAG, "📐 手机检测到当前画面旋转角度: " + rotationDegrees);

                sendControlMessageToWatch("START_CAMERA", rotationDegrees);

                List<Node> nodes = Tasks.await(Wearable.getNodeClient(this).getConnectedNodes());
                if (nodes != null && !nodes.isEmpty()) {
                    String watchNodeId = nodes.get(0).getId();
                    
                    mTargetChannel = Tasks.await(Wearable.getChannelClient(this)
                            .openChannel(watchNodeId, CAMERA_PREVIEW_STREAM_PATH));
                    
                    mChannelOutputStream = Tasks.await(Wearable.getChannelClient(this)
                            .getOutputStream(mTargetChannel));
                    
                    Log.d(TAG, "🚀 [管道打通] 开始向手表泵入实时帧。");
                    mCamera.startPreview();
                } else {
                    Log.w(TAG, "⚠️ 找不到可用的手表节点");
                    releaseCameraAndPipeline();
                }
            } catch (Exception e) {
                Log.e(TAG, "建立相机传输管道灾难性失败", e);
                releaseCameraAndPipeline();
            }
        }).start();
    }

    @Override
    public void onPreviewFrame(byte[] data, Camera camera) {
        if (!isStreaming || mChannelOutputStream == null || data == null) return;

        new Thread(() -> {
            try {
                Camera.Size size = camera.getParameters().getPreviewSize();
                YuvImage yuvImage = new YuvImage(data, ImageFormat.NV21, size.width, size.height, null);
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                yuvImage.compressToJpeg(new Rect(0, 0, size.width, size.height), 80, baos); 
                
                byte[] jpegBytes = baos.toByteArray();
                int packetLength = jpegBytes.length;
                byte[] header = new byte[]{
                        (byte) ((packetLength >> 24) & 0xFF),
                        (byte) ((packetLength >> 16) & 0xFF),
                        (byte) ((packetLength >> 8) & 0xFF),
                        (byte) (packetLength & 0xFF)
                };

                synchronized (mChannelOutputStream) {
                    mChannelOutputStream.write(header);
                    mChannelOutputStream.write(jpegBytes);
                    mChannelOutputStream.write(0xFF); 
                    mChannelOutputStream.flush(); 
                }
            } catch (Exception e) {
                Log.e(TAG, "向通道泵入画面帧失败:", e);
            }
        }).start();
    }

    private void executePhoneShutter() {
        if (mCamera != null) {
            Log.d(TAG, "📸 [快门联动] 手机本地代点快门拍照！");
            mCamera.takePicture(null, null, (data, camera) -> {
                Log.d(TAG, "💾 照片资产已成功在手机本地落盘。");
                if (mCamera != null) mCamera.startPreview(); 
            });
        }
    }

    private void releaseCameraAndPipeline() {
        if (!isStreaming) return;
        isStreaming = false;
        Log.d(TAG, "🧹 销毁释放手机端相机与传输管道...");

        try {
            if (mCamera != null) {
                mCamera.setPreviewCallback(null);
                mCamera.stopPreview();
                mCamera.release();
                mCamera = null;
            }
            if (mChannelOutputStream != null) {
                mChannelOutputStream.close();
                mChannelOutputStream = null;
            }
            if (mTargetChannel != null) {
                Wearable.getChannelClient(this).close(mTargetChannel);
                mTargetChannel = null;
            }
            sendControlMessageToWatch("FORCE_QUIT_CAMERA", 0);
        } catch (Exception e) {
            Log.e(TAG, "释放相机管道资源时发生异常", e);
        } finally {
            stopSelf();
        }
    }

    private void sendControlMessageToWatch(String actionStr, int rotation) {
        try {
            JSONObject json = new JSONObject();
            json.put("sender", "phone");
            json.put("type", "camera_control");
            json.put("action", actionStr);
            json.put("rotation_degrees", rotation); 

            byte[] payload = json.toString().getBytes(StandardCharsets.UTF_8);
            List<Node> nodes = Tasks.await(Wearable.getNodeClient(this).getConnectedNodes());
            if (nodes != null) {
                for (Node n : nodes) {
                    Wearable.getMessageClient(this).sendMessage(n.getId(), UNIVERSAL_SYNC_PATH, payload);
                }
            }
        } catch (Exception ignored) {}
    }

    private int calculatePhoneRotation() {
        WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        if (wm == null) return 0;
        int rotation = wm.getDefaultDisplay().getRotation();
        switch (rotation) {
            case 1: return 90;
            case 2: return 180;
            case 3: return 270;
            default: return 0;
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }
}
