package de.rhaeus.wearsync;

import android.content.Context;

public class WearSyncState {

    private static volatile String activeNodeId = null;

    private static final String PREF_NAME = "WearSync_State";
    private static final String KEY_NODE_ID = "active_node_id";


    public static void setNodeId(Context context, String nodeId) {

        if (nodeId == null || nodeId.isEmpty()) {
            return;
        }

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


    public static String getNodeId(Context context) {

        if (activeNodeId != null) {
            return activeNodeId;
        }


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


    public static void clear(Context context) {

        activeNodeId = null;

        context.getSharedPreferences(
                PREF_NAME,
                Context.MODE_PRIVATE
        )
        .edit()
        .remove(
                KEY_NODE_ID
        )
        .apply();
    }
}
