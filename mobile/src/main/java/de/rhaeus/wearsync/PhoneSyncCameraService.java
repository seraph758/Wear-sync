package de.rhaeus.wearsync;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;

import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.ChannelClient;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;

import org.json.JSONObject;

import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;


public class PhoneSyncCameraService extends Service implements LifecycleOwner {

    private static final String TAG = "WearSync_PhoneCamera";

    private static final String UNIVERSAL_SYNC_PATH =
            "/wear-universal-sync";

    private static final String CAMERA_PREVIEW_STREAM_PATH =
            "/camera-preview-stream";


    public static final String ACTION_START_CAMERA =
            "de.rhaeus.wearsync.ACTION_START_CAMERA";

    public static final String ACTION_STOP_CAMERA_STREAM =
            "de.rhaeus.wearsync.ACTION_STOP_CAMERA_STREAM";

    public static final String ACTION_TRIGGER_SHUTTER =
            "de.rhaeus.wearsync.ACTION_TRIGGER_SHUTTER";


    private MediaCodec mEncoder;

    private ChannelClient.Channel mTargetChannel;

    private OutputStream mChannelOutputStream;

    private boolean isStreaming = false;


    private LifecycleRegistry lifecycleRegistry;


    @NonNull
    @Override
    public Lifecycle getLifecycle() {
        return lifecycleRegistry;
    }



    @Override
    public void onCreate() {

        super.onCreate();

        lifecycleRegistry =
                new LifecycleRegistry(this);

        lifecycleRegistry.handleLifecycleEvent(
                Lifecycle.Event.ON_CREATE
        );
    }



    @Override
    public int onStartCommand(
            Intent intent,
            int flags,
            int startId
    ) {


        lifecycleRegistry.handleLifecycleEvent(
                Lifecycle.Event.ON_START
        );


        if(intent == null ||
                intent.getAction() == null){

            stopSelf();

            return START_NOT_STICKY;
        }


        String action =
                intent.getAction();



        Log.d(TAG,
                "收到动作: "
                        + action);



        if(ACTION_START_CAMERA.equals(action)){


            if(android.os.Build.VERSION.SDK_INT >= 26){


                String channelId =
                        "camera_sync_channel";


                android.app.NotificationChannel channel =
                        new android.app.NotificationChannel(
                                channelId,
                                "相机远端同步",
                                android.app.NotificationManager.IMPORTANCE_LOW
                        );


                android.app.NotificationManager nm =
                        (android.app.NotificationManager)
                                getSystemService(
                                        NOTIFICATION_SERVICE
                                );


                if(nm != null){
                    nm.createNotificationChannel(channel);
                }



                android.app.Notification notification =
                        new android.app.Notification.Builder(
                                this,
                                channelId
                        )
                        .setContentTitle("WearSync")
                        .setContentText("远端相机同步中")
                        .setSmallIcon(
                                android.R.drawable.ic_menu_camera
                        )
                        .build();


                startForeground(
                        8899,
                        notification
                );
            }


            startCameraAndSetupPipeline();


        }else if(ACTION_STOP_CAMERA_STREAM.equals(action)){


            releaseCameraAndPipeline();


        }else if(ACTION_TRIGGER_SHUTTER.equals(action)){


            executePhoneShutter();

        }


        return START_NOT_STICKY;
    }





    private void startCameraAndSetupPipeline(){


        if(isStreaming){
            return;
        }


        isStreaming = true;



        new Thread(() -> {


            try{


                MediaFormat format =
                        MediaFormat.createVideoFormat(
                                MediaFormat.MIMETYPE_VIDEO_AVC,
                                640,
                                480
                        );


                format.setInteger(
                        MediaFormat.KEY_COLOR_FORMAT,
                        MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                );


                format.setInteger(
                        MediaFormat.KEY_BIT_RATE,
                        1000000
                );


                format.setInteger(
                        MediaFormat.KEY_FRAME_RATE,
                        24
                );


                format.setInteger(
                        MediaFormat.KEY_I_FRAME_INTERVAL,
                        1
                );



                mEncoder =
                        MediaCodec.createEncoderByType(
                                MediaFormat.MIMETYPE_VIDEO_AVC
                        );


                mEncoder.configure(
                        format,
                        null,
                        null,
                        MediaCodec.CONFIGURE_FLAG_ENCODE
                );


                Surface inputSurface =
                        mEncoder.createInputSurface();


                mEncoder.start();



                int rotation =
                        calculatePhoneRotation();



                sendControlMessageToWatch(
                        "START_CAMERA",
                        rotation
                );




                String watchNodeId =
                        WearSyncState.getNodeId(this);



                if(watchNodeId == null ||
                        watchNodeId.isEmpty()){


                    List<Node> nodes =
                            Tasks.await(
                                    Wearable.getNodeClient(this)
                                    .getConnectedNodes()
                            );


                    if(nodes != null &&
                            !nodes.isEmpty()){


                        watchNodeId =
                                nodes.get(0)
                                .getId();



                        WearSyncState.setNodeId(
                                this,
                                watchNodeId
                        );
                    }
                }




                if(watchNodeId != null){


                    mTargetChannel =
                            Tasks.await(
                                    Wearable.getChannelClient(this)
                                    .openChannel(
                                            watchNodeId,
                                            CAMERA_PREVIEW_STREAM_PATH
                                    )
                            );


                    mChannelOutputStream =
                            Tasks.await(
                                    Wearable.getChannelClient(this)
                                    .getOutputStream(
                                            mTargetChannel
                                    )
                            );


                    new Handler(
                            Looper.getMainLooper()
                    ).post(() ->
                            bindCameraXToSurface(
                                    inputSurface
                            )
                    );


                    pumpEncodedStreamToWatch();



                }else{


                    Log.w(TAG,
                            "没有找到手表");


                    releaseCameraAndPipeline();
                }



            }catch(Exception e){


                Log.e(TAG,
                        "相机管道失败",
                        e);


                releaseCameraAndPipeline();

            }



        }).start();
    }







    private void bindCameraXToSurface(
            Surface encoderInputSurface
    ){


        ProcessCameraProvider.getInstance(this)
                .addListener(() -> {


                    try{


                        ProcessCameraProvider provider =
                                ProcessCameraProvider
                                .getInstance(this)
                                .get();



                        provider.unbindAll();



                        Preview preview =
                                new Preview.Builder()
                                .setTargetResolution(
                                        new android.util.Size(
                                                640,
                                                480
                                        )
                                )
                                .build();



                        preview.setSurfaceProvider(
                                request ->
                                        request.provideSurface(
                                                encoderInputSurface,
                                                ContextCompat.getMainExecutor(this),
                                                result -> {}
                                        )
                        );



                        provider.bindToLifecycle(
                                this,
                                CameraSelector.DEFAULT_BACK_CAMERA,
                                preview
                        );



                    }catch(Exception e){

                        Log.e(TAG,
                                "CameraX绑定失败",
                                e);
                    }


                },
                ContextCompat.getMainExecutor(this));
    }







    private void pumpEncodedStreamToWatch(){


        MediaCodec.BufferInfo info =
                new MediaCodec.BufferInfo();



        while(isStreaming &&
                mEncoder != null &&
                mChannelOutputStream != null){


            try{


                int index =
                        mEncoder.dequeueOutputBuffer(
                                info,
                                10000
                        );



                if(index >= 0){


                    ByteBuffer buffer =
                            mEncoder.getOutputBuffer(index);



                    if(buffer != null &&
                            info.size > 0){


                        byte[] data =
                                new byte[info.size];


                        buffer.position(info.offset);

                        buffer.limit(
                                info.offset +
                                info.size
                        );


                        buffer.get(data);



                        mChannelOutputStream.write(data);

                        mChannelOutputStream.flush();

                    }


                    mEncoder.releaseOutputBuffer(
                            index,
                            false
                    );
                }


            }catch(Exception e){


                Log.e(TAG,
                        "推流中断",
                        e);

                break;

            }

        }

    }







    private void executePhoneShutter(){

        Log.d(TAG,
                "收到快门");

    }







    private void releaseCameraAndPipeline(){


        if(!isStreaming){
            return;
        }


        isStreaming=false;



        try{


            if(mEncoder != null){


                mEncoder.stop();

                mEncoder.release();

                mEncoder=null;
            }




            new Handler(
                    Looper.getMainLooper()
            ).post(() -> {


                try{

                    ProcessCameraProvider
                    .getInstance(this)
                    .get()
                    .unbindAll();


                }catch(Exception ignored){}

            });




            if(mChannelOutputStream != null){

                mChannelOutputStream.close();

                mChannelOutputStream=null;
            }




            if(mTargetChannel != null){

                Wearable.getChannelClient(this)
                .close(mTargetChannel);


                mTargetChannel=null;
            }




            sendControlMessageToWatch(
                    "FORCE_QUIT_CAMERA",
                    0
            );



        }catch(Exception e){

            Log.e(TAG,
                    "释放失败",
                    e);
        }

    }







    private void sendControlMessageToWatch(
            String action,
            int rotation
    ){


        new Thread(() -> {


            try{


                JSONObject json =
                        new JSONObject();


                json.put(
                        "sender",
                        "phone"
                );


                json.put(
                        "type",
                        "camera_control"
                );


                json.put(
                        "action",
                        action
                );


                json.put(
                        "rotation_degrees",
                        rotation
                );



                byte[] data =
                        json.toString()
                        .getBytes(
                                StandardCharsets.UTF_8
                        );



                String nodeId =
                        WearSyncState.getNodeId(this);



                if(nodeId == null ||
                        nodeId.isEmpty()){


                    List<Node> nodes =
                            Tasks.await(
                                    Wearable.getNodeClient(this)
                                    .getConnectedNodes()
                            );


                    if(nodes != null &&
                            !nodes.isEmpty()){


                        nodeId =
                                nodes.get(0)
                                .getId();



                        WearSyncState.setNodeId(
                                this,
                                nodeId
                        );

                    }
                }



                if(nodeId != null){


                    Tasks.await(
                            Wearable.getMessageClient(this)
                            .sendMessage(
                                    nodeId,
                                    UNIVERSAL_SYNC_PATH,
                                    data
                            )
                    );

                }


            }catch(Exception e){

                Log.e(TAG,
                        "发送控制失败",
                        e);
            }


        }).start();

    }







    private int calculatePhoneRotation(){


        WindowManager wm =
                (WindowManager)
                getSystemService(
                        Context.WINDOW_SERVICE
                );


        if(wm == null){
            return 0;
        }


        switch(
                wm.getDefaultDisplay()
                .getRotation()
        ){


            case 1:
                return 90;


            case 2:
                return 180;


            case 3:
                return 270;


            default:
                return 0;
        }

    }






    @Nullable
    @Override
    public IBinder onBind(Intent intent){

        return null;
    }






    @Override
    public void onDestroy(){


        releaseCameraAndPipeline();


        lifecycleRegistry.handleLifecycleEvent(
                Lifecycle.Event.ON_DESTROY
        );


        super.onDestroy();

    }

}