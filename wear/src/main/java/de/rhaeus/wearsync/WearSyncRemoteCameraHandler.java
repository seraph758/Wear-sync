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


    public WearSyncRemoteCameraHandler(Context context) {

        this.context = context.getApplicationContext();

        WearLog.d(TAG,
                "⚙️ [物理初始化] WearSyncRemoteCameraHandler 实例已构建。" +
                " context="
                + this.context.getPackageName());
    }


    /**
     * 手表端通过 RemoteActivityHelper 拉起手机端相机
     */
    public void openPhoneCamera() {

        WearLog.w(TAG,
                "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        WearLog.w(TAG,
                "🚀 [远端相机启动] 开始准备向手机发送 Camera 拉起请求");
        WearLog.w(TAG,
                "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");


        try {

            WearLog.d(TAG,
                    "① 正在初始化 RemoteActivityHelper");


            RemoteActivityHelper helper =
                    new RemoteActivityHelper(
                            context,
                            executor
                    );


            WearLog.d(TAG,
                    "✅ RemoteActivityHelper 初始化完成");


            WearLog.d(TAG,
                    "② 正在构建远程 Intent");


            Intent intent =
                    new Intent(Intent.ACTION_VIEW);


            // RemoteActivityHelper 强制要求
            // 必须有 BROWSABLE
            WearLog.d(TAG,
                    "③ 添加 Intent.CATEGORY_BROWSABLE");

            intent.addCategory(
                    Intent.CATEGORY_BROWSABLE
            );


            WearLog.d(TAG,
                    "④ 设置目标包名: de.rhaeus.wearsync");


            intent.setPackage(
                    "de.rhaeus.wearsync"
            );


            WearLog.d(TAG,
                    "⑤ 设置 URI: wearsync://camera");


            intent.setData(
                    Uri.parse("wearsync://camera")
            );


            WearLog.d(TAG,
                    "========== Intent 最终状态 ==========");

            WearLog.d(TAG,
                    "Action = "
                    + intent.getAction());

            WearLog.d(TAG,
                    "Package = "
                    + intent.getPackage());

            WearLog.d(TAG,
                    "Data = "
                    + intent.getData());

            WearLog.d(TAG,
                    "Categories = "
                    + intent.getCategories());

            WearLog.d(TAG,
                    "====================================");


            WearLog.w(TAG,
                    "🛰️ [发射] 正在调用 RemoteActivityHelper.startRemoteActivity()");


            com.google.common.util.concurrent.ListenableFuture<Void> future =
                    helper.startRemoteActivity(intent);


            WearLog.d(TAG,
                    "📡 [提交成功] startRemoteActivity 已返回 Future，等待 Google 通道回执");


            future.addListener(() -> {


                WearLog.d(TAG,
                        "📥 [回执线程] RemoteActivityHelper 返回结果");


                try {


                    future.get();


                    WearLog.w(TAG,
                            "✨ [成功] 手机端远程 Activity 唤醒请求已被 Google 通道确认");
                    Intent intent = new Intent(context, WearCameraActivity.class);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(intent);
                    
                    WearLog.d(TAG, "CAM-W000 WearCameraActivity 已主动拉起");


                } catch (Exception e) {


                    Throwable cause = e.getCause();


                    if (cause != null) {


                        WearLog.e(TAG,
                                "🔴 [失败真实原因] "
                                + cause.getClass().getName()
                                + " : "
                                + cause.getMessage(),
                                cause);


                    } else {


                        WearLog.e(TAG,
                                "🔴 [失败] "
                                + e.getClass().getName()
                                + " : "
                                + e.getMessage(),
                                e);

                    }

                }


            }, executor);



        } catch (Exception e) {


            WearLog.e(TAG,
                    "🔴 [致命异常] RemoteActivityHelper 调用失败: "
                    + e.getMessage(),
                    e);

        }


    }

}
