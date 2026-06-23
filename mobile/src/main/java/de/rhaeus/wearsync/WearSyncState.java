package de.rhaeus.wearsync;

import android.content.Context;

public class WearSyncState {


    private static String activeNodeId = null;


    private static final String PREF_NAME =
            "WearSync_State";


    private static final String KEY_NODE_ID =
            "active_node_id";



    // 写入缓存
    public static void setNodeId(Context context, String nodeId) {

        activeNodeId = nodeId;


        context.getSharedPreferences(
                PREF_NAME,
                Context.MODE_PRIVATE
        )
        .edit()
        .putString(
                KEY_NODE_ID,
                nodeId
        )
        .apply();

    }



    // 读取缓存
    public static String getNodeId(Context context) {


        // ① 先读内存
        if (activeNodeId != null
                && !activeNodeId.isEmpty()) {

            return activeNodeId;

        }


        // ② 内存没有，读本地
        activeNodeId =
                context.getSharedPreferences(
                        PREF_NAME,
                        Context.MODE_PRIVATE
                )
                .getString(
                        KEY_NODE_ID,
                        null
                );


        return activeNodeId;

    }



    // 清除
    public static void clear(Context context) {


        activeNodeId = null;


        context.getSharedPreferences(
                PREF_NAME,
                Context.MODE_PRIVATE
        )
        .edit()
        .remove(KEY_NODE_ID)
        .apply();

    }


}