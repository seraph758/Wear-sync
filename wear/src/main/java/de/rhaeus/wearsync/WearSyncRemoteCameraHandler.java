package de.rhaeus.wearsync;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import androidx.wear.remote.interactions.RemoteActivityHelper;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * 🛰️ 手表端逆向击穿发射器：负责通过谷歌微端物理总线将拉起指令投递给手机跳板 Activity
 * 极致动态日志全步进版：步步追踪穿透 Intent 封装、包名限制过滤以及远程异步执行绪回执状态。
 */
public class WearSyncRemoteCameraHandler {
    private static final String TAG = "WearSync_RemoteCamera";
    private final Context context;
    private final Executor executor = Executors.newSingleThreadExecutor();

    public WearSyncRemoteCameraHandler(Context context){
        this.context = context.getApplicationContext();
        WearLog.d(TAG, "⚙️ [物理初始化] WearSyncRemoteCameraHandler 实例已构建，已锁定 Application 级别全局上下文。");
    }

    /**
     * 🚀 向谷歌穿透网关提交申请，强制撕开手机后台拉起全透明跳板 Activity
     */
    public void openPhoneCamera(){
        WearLog.d(TAG, "🚀 [逆向击穿流] ─── 开始筹备手表逆向拉起手机相机指令 ───");
        try {
            WearLog.d(TAG, "⚙️ [交互器初始化] 正在初始化谷歌 RemoteActivityHelper 远程物理接线员...");
            RemoteActivityHelper helper = new RemoteActivityHelper(context, executor);
            
            // 封装发往手机端的远程 Intent
            WearLog.d(TAG, "⚙️ [穿透意图组装] 正在构建标准 ACTION_VIEW 远程深层链接 (DeepLink) 意图...");
            Intent intent = new Intent(Intent.ACTION_VIEW);
            
            // 锁定目标包名，防止 Android 隐式意图路由攻击
            WearLog.d(TAG, "⚙️ [穿透意图组装] 注入安全性 Package 过滤强锁: [de.rhaeus.wearsync]");
            intent.setPackage("de.rhaeus.wearsync");
            
            // 注入协议头
            WearLog.d(TAG, "⚙️ [穿透意图组装] 注入逆向击穿数据 URI 暗号: [wearsync://camera]");
            intent.setData(Uri.parse("wearsync://camera"));

            WearLog.w(TAG, "🛰️ [物理穿透发射] ━━━━━━━━ 正在通过谷歌微端蓝牙总线发射 Activity 穿透起飞请求... ━━━━━━━━");
            
            // 执行远程投递并挂载异步监听器
            com.google.common.util.concurrent.ListenableFuture<Void> future = helper.startRemoteActivity(intent);
            
            WearLog.d(TAG, "📡 [总线反馈] startRemoteActivity() 已将数据包踢入队列，成功挂载异部多线程拦截回执 ListenableFuture...");
            
            future.addListener(() -> {
                try {
                    // 呼叫 get() 触发阻塞检查是否有异常抛出（在单个执行绪中运行是安全的）
                    future.get();
                    WearLog.w(TAG, "✨ [物理穿透大捷] ━━━ 谷歌底层穿透通道正面答复：远程跳板唤醒数据包已【100% 成功送达】手机端物理网络骨干层！");
                } catch (Exception e) {
                    WearLog.e(TAG, "🔴 [物理穿透回执失败] 谷歌底层通道投递完毕，但手机端网络拒绝接收或连接超时: " + e.getMessage(), e);
                }
            }, executor);

        } catch (Exception e) {
            WearLog.e(TAG, "🔴 [远程交互致命异常] 调用 RemoteActivityHelper 封包或调用接口时发生严重穿透流产: " + e.getMessage(), e);
        }
    }
}
