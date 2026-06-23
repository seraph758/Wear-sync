package de.rhaeus.wearsync;

import android.content.Context;
import android.app.NotificationManager;
import android.util.Log;

import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.List;

public class PhoneDndManager {

    private static final String TAG = "WearSync_PhoneDnd";

    public static int cachedMaskValue = 0;


    public static void handleIncomingAction(
            Context context,
            int wearSystemDndVal
    ) {

        Log.d(
                TAG,
                "收到手表反向勿扰同步: "
                + wearSystemDndVal
        );

        try {

            NotificationManager nm =
                    (NotificationManager)
                    context.getSystemService(
                            Context.NOTIFICATION_SERVICE
                    );


            if (nm != null &&
                    nm.isNotificationPolicyAccessGranted()) {


                nm.setInterruptionFilter(
                        wearSystemDndVal
                );


                Log.d(
                        TAG,
                        "手机勿扰设置成功"
                );
            }


        } catch(Exception e){

            Log.e(
                    TAG,
                    "勿扰同步失败",
                    e
            );
        }
    }



    public static void syncDndToWear(
            Context context,
            int currentFilter
    ) {


        new Thread(() -> {

            try {


                JSONObject json =
                        new JSONObject();


                json.put(
                        "sender",
                        "phone"
                );


                json.put(
                        "type",
                        "dnd"
                );


                json.put(
                        "action",
                        "SYNC_DND_STATUS"
                );


                json.put(
                        "dnd_state",
                        currentFilter
                );


                json.put(
                        "mask_value",
                        cachedMaskValue
                );


                json.put(
                        "timestamp",
                        System.currentTimeMillis()
                );


                byte[] data =
                        json.toString()
                        .getBytes(
                                StandardCharsets.UTF_8
                        );


                String nodeId =
                        WearSyncState.getNodeId(
                                context
                        );


                if(nodeId != null &&
                   !nodeId.isEmpty()) {


                    Tasks.await(
                            Wearable
                            .getMessageClient(context)
                            .sendMessage(
                                    nodeId,
                                    "/wear-universal-sync",
                                    data
                            )
                    );


                    Log.d(
                            TAG,
                            "DND缓存发送成功 "
                            + nodeId
                    );


                } else {


                    Log.w(
                            TAG,
                            "NodeId为空，开始查找手表"
                    );


                    List<Node> nodes =
                            Tasks.await(
                                    Wearable
                                    .getNodeClient(context)
                                    .getConnectedNodes()
                            );


                    for(Node node : nodes){


                        WearSyncState.setNodeId(
                                context,
                                node.getId()
                        );


                        Tasks.await(
                                Wearable
                                .getMessageClient(context)
                                .sendMessage(
                                        node.getId(),
                                        "/wear-universal-sync",
                                        data
                                )
                        );
                    }


                }


                Log.d(
                        TAG,
                        "DND同步完成"
                );


            }catch(Exception e){

                Log.e(
                        TAG,
                        "DND正向同步失败",
                        e
                );
            }


        }).start();

    }
}