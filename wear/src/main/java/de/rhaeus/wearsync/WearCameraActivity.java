package de.rhaeus.wearsync;

import android.app.Activity;
import android.content.Intent;
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
 * 手表端预览画面流渲染主 Activity。
 * 完美支持协同正常关闭，点击关闭按钮时，同时通知手机下线、关闭手表的 MainActivity，
 * 并且在销毁时彻底释放重力横竖向传感器绑定，彻底解决画面持续跟着重力旋转卡死的问题。
 */
public class WearCameraActivity extends Activity {
    private static final String TAG = "WearSync_WearCamera";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_camera_preview); // 渲染手机传过来的 YUV/JPEG 画面流布局

        Button btnCloseCamera = findViewById(R.id.btn_close_camera);
        if (btnCloseCamera != null) {
            btnCloseCamera.setOnClickListener(v -> {
                Log.d(TAG, "🛎️ 用户在手表预览界面点击正常退出...");
                executeElegantExitFlow();
            });
        }
    }

    /**
     * 核心关闭链条：两端协调好，通过正常程序和正常通信逻辑关闭。
     */
    private void executeElegantExitFlow() {
        new Thread(() -> {
            try {
                // 1. 发送正常的停止命令给手机端，通知手机相机服务把拍照后台安全退出
                JSONObject json = new JSONObject();
                json.put("sender", "wear");
                json.put("type", "camera_action");
                json.put("action", "STOP_CAMERA_UI");
                json.put("timestamp", System.currentTimeMillis());

                byte[] data = json.toString().getBytes(StandardCharsets.UTF_8);
                List<Node> nodes = Tasks.await(Wearable.getNodeClient(this).getConnectedNodes());
                for (Node node : nodes) {
                    Wearable.getMessageClient(this).sendMessage(node.getId(), "/wear-universal-sync", data);
                }
                Log.d(TAG, "📤 手表已成功向手机端发送正常关闭相机信令");

                // 2. 正常程序逻辑发送完毕后，接力发送一条特殊本地广播来强制关掉手表端的 MainActivity
                Intent closeMainIntent = new Intent("DE_RHAEUS_WEARSYNC_FORCE_CLOSE_MAIN");
                closeMainIntent.setPackage(getPackageName());
                sendBroadcast(closeMainIntent);

                // 3. 退出手表端当前预览 Activity 自身
                runOnUiThread(this::finish);

            } catch (Exception e) {
                Log.e(TAG, "执行正常协同退出通信流程失败", e);
                runOnUiThread(this::finish); // 兜底退出
            }
        }).start();
    }

    @Override
    protected void onDestroy() {
        Log.d(TAG, "🧹 正在执行 onDestroy 生命周期。开始彻底释放手表的画面硬件持有与重力感应...");
        
        try {
            // 🎯 【重力旋转防死锁核心】
            // 彻底接触重力感应、屏幕方向强制归位，阻断整个画面无休止的旋转
            setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_NOSENSOR);
            
        } catch (Exception e) {
            Log.e(TAG, "清理重力感应残留异常", e);
        }

        super.onDestroy();
        // 彻底杀死当前进程的线程残留，保证彻底退干净
        android.os.Process.killProcess(android.os.Process.myPid());
    }
}