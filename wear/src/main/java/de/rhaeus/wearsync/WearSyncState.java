package de.rhaeus.wearsync;


import android.content.Context;


public class WearSyncState {


    private static String activePhoneNodeId;


    private static final String PREF_NAME =
            "WearSync_State";


    private static final String KEY_PHONE_NODE_ID =
            "active_phone_node_id";



    public static synchronized void setPhoneNodeId(
            Context context,
            String nodeId
    ){

        activePhoneNodeId = nodeId;


        context.getSharedPreferences(
                PREF_NAME,
                Context.MODE_PRIVATE
        )
        .edit()
        .putString(
                KEY_PHONE_NODE_ID,
                nodeId
        )
        .apply();

    }




    public static synchronized String getPhoneNodeId(
            Context context
    ){


        if(activePhoneNodeId != null &&
                !activePhoneNodeId.isEmpty()){

            return activePhoneNodeId;

        }



        activePhoneNodeId =
                context.getSharedPreferences(
                        PREF_NAME,
                        Context.MODE_PRIVATE
                )
                .getString(
                        KEY_PHONE_NODE_ID,
                        null
                );


        return activePhoneNodeId;

    }




    public static synchronized void clear(
            Context context
    ){

        activePhoneNodeId=null;


        context.getSharedPreferences(
                PREF_NAME,
                Context.MODE_PRIVATE
        )
        .edit()
        .remove(
                KEY_PHONE_NODE_ID
        )
        .apply();

    }

}