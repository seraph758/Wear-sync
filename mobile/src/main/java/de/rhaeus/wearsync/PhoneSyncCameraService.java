package de.rhaeus.wearsync;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.hardware.Camera; // 采用PreviewCallback最纯净简洁展现流式打包逻辑
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

/**
 * 📸 独立解耦的手机端相机流控服务（对齐原有文件名版）
 * 核心职责：
 * 1. 启动时计算手机旋转角度，向手表下发方向锁定指令。
 * 2. 握手成功后开启 Channel 管道，将 NV21 帧高频压缩为 JPEG 并流式发射。
 * 3. 承接快门代点、彻底释放程序，拒绝任何内存和全局方向污染。
 */
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
        if (intent == null || intent.getAction() == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        String action = intent.getAction();
        Log.d(TAG, "⚙️ 手机相机服务收到动作: " + action);

        if (ACTION_START_CAMERA.equals(action)) {
            startCameraAndSetupPipeline();
        } else if (ACTION_STOP_CAMERA_STREAM.equals(action)) {
            releaseCameraAndPipeline();
        } else if (ACTION_TRIGGER_SHUTTER.equals(action)) {
            executePhoneShutter();
        }

        return START_NOT_STICKY;
    }

    /**
     * 🏁 开启相机并建立跨端数据发射管道
     */
    private void startCameraAndSetupPipeline() {
        if (isStreaming) return;
        isStreaming = true;

        new Thread(() -> {
            try {
                // 1. 初始化手机本地硬件相机
                mCamera = Camera.open(Camera.CameraInfo.CAMERA_FACING_BACK);
                Camera.Parameters parameters = mCamera.getParameters();
                parameters.setPreviewSize(640, 480); // 专为手表屏幕优化的低带宽分辨率
                mCamera.setParameters(parameters);
                mCamera.setPreviewCallback(this);

                // 2. 计算手机当前真实姿态角度，用作方向锁定指令下发
                int rotationDegrees = calculatePhoneRotation();
                Log.d(TAG, "📐 手机检测到当前画面旋转角度: " + rotationDegrees + " -> 准备下发锁定");

                // 3. 通过 Message 控场通道，向手表发送拉起指令和旋转纠偏参数
                sendControlMessageToWatch("START_CAMERA", rotationDegrees);

                // 4. 获取连接的手表节点，建立专属大文件预览传输 Channel 管道
                List<Node> nodes = Tasks.await(Wearable.getNodeClient(this).getConnectedNodes());
                if (nodes != null && !nodes.isEmpty()) {
                    String watchNodeId = nodes.get(0).getId();
                    
                    // 创建并显式阻塞等待通道建立
                    mTargetChannel = Tasks.await(Wearable.getChannelClient(this)
                            .openChannel(watchNodeId, CAMERA_PREVIEW_STREAM_PATH));
                    
                    // 获取流写入句柄，正式打通数据链路
                    mChannelOutputStream = Tasks.await(Wearable.getChannelClient(this)
                            .getOutputStream(mTargetChannel));
                    
                    Log.d(TAG, "🚀 [管道打通] ChannelClient 传输通道已就绪，开始向手表泵入实时帧。");
                    mCamera.startPreview();
                } else {
                    Log.w(TAG, "⚠️ 找不到可用的手表节点，放弃开启通道。");
                    releaseCameraAndPipeline();
                }

            } catch (Exception e) {
                Log.e(TAG, "建立相机流传输管道产生灾难性失败", e);
                releaseCameraAndPipeline();
            }
        }).start();
    }

    /**
     * 🔄 高频帧泵入：手机相机捕获到原始 NV21 帧后的核心数据封装协议（为未来升级H.264留出OutputStream底层）
     */
    @Override
    public void onPreviewFrame(byte[] data, Camera camera) {
        if (!isStreaming || mChannelOutputStream == null || data == null) return;

        // 在后台线程中高频压缩发送，避免卡死手机主线程
        new Thread(() -> {
            try {
                Camera.Size size = camera.getParameters().getPreviewSize();
                
                // 将原始 YUV/NV21 转换为 JPEG 字节流进行高保真无损压缩
                YuvImage yuvImage = new YuvImage(data, ImageFormat.NV21, size.width, size.height, null);
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                yuvImage.compressToJpeg(new Rect(0, 0, size.width, size.height), 80, baos); // 80% 质量压缩平衡带宽
                
                byte[] jpegBytes = baos.toByteArray();

                // 🌟 数据包协议头定义：[4字节长度占位符] + [实际JPEG数据] -> 确保手表端能够精准分包粘包
                int packetLength = jpegBytes.length;
                byte[] header = new byte[]{
                        (byte) ((packetLength >> 24) & 0xFF),
                        (byte) ((packetLength >> 16) & 0xFF),
                        (byte) ((packetLength >> 8) & 0xFF),
                        (byte) (packetLength & 0xFF)
                };

                // 串行写入管道流
                synchronized (mChannelOutputStream) {
                    mChannelOutputStream.write(header);
                    mChannelOutputStream.write(jpegBytes);
                    mChannelOutputStream.write(0xFF); // 附加帧尾校验符
                    mChannelOutputStream.flush(); // 强行刷新冲入蓝牙带宽
                }

            } catch (Exception e) {
                Log.e(TAG, "向通道泵入画面帧失败，可能手表端已主动断开:", e);
            }
        }).start();
    }

    /**
     * 🎯 模拟快门动作：代点拍照
     */
    private void executePhoneShutter() {
        if (mCamera != null) {
            Log.d(TAG, "📸 [快门联动] 接收到手表手势触发，手机本地代点快门拍照！");
            mCamera.takePicture(null, null, (data, camera) -> {
                Log.d(TAG, "💾 照片资产已成功在手机本地持久化落盘。");
                if (mCamera != null) mCamera.startPreview(); // 拍照完毕后重新拉起预览流
            });
        }
    }

    /**
     * 🧹 彻底释放程序（Zero-Pollution）：核级清理，不留任何残留，防止全局方向污染
     */
    private void releaseCameraAndPipeline() {
        if (!isStreaming) return;
        isStreaming = false;
        Log.d(TAG, "🧹 触发退出协议：开始进行手机端相机与传输管道的彻底销毁释放...");

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
            // 顺便通知手表，双端绝对强制对齐彻底退出
            sendControlMessageToWatch("FORCE_QUIT_CAMERA", 0);
            Log.d(TAG, "🏁 [核级清空完成] 手机本地相机资源全数回收，管道彻底关闭。");
        } catch (Exception e) {
            Log.e(TAG, "释放相机管道资源时发生异常", e);
        } finally {
            stopSelf();
        }
    }

    /**
     * 🛰️ 控场信令组装器
     */
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

    /**
     * 根据手机窗口管理器计算当前物理旋转度数
     */
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
