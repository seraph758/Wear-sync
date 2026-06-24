package de.rhaeus.wearsync;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import androidx.wear.remote.interactions.RemoteActivityHelper;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public class WearSyncRemoteCameraHandler {
    private static final String TAG = "WearSync_RemoteCamera";
    private final Context context;
    private final Executor executor = Executors.newSingleThreadExecutor();

    public WearSyncRemoteCameraHandler(Context context){
        this.context = context.getApplicationContext();
    }

    public void openPhoneCamera(){
        try {
            RemoteActivityHelper helper = new RemoteActivityHelper(context, executor);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setPackage("de.rhaeus.wearsync");
            intent.setData(Uri.parse("wearsync://camera"));

            WearLog.d(TAG, "🛰️ 正在通过远程交互器向手机端骨干网发射 Activity 穿透起飞请求...");

            helper.startRemoteActivity(intent).addListener(() -> {
                WearLog.d(TAG, "✨ 谷歌底层穿透通道答复：远程跳板唤醒数据包投递成功");
            }, executor);

        } catch(Exception e){
            WearLog.e(TAG, "❌ 调用 RemoteActivityHelper 发生严重穿透失效", e);
        }
    }
}
