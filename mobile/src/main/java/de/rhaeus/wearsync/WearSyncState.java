package de.rhaeus.wearsync;

import android.content.Context;

/**
 * 💾 手表活跃节点 ID 状态本地持久化管理器
 * 变更：加入 PhoneLog 日志监测体系，精简排版。
 */
public class WearSyncState {

    private static final String TAG = "WearSync_State";
    private static volatile String activeNodeId = null;
    private static final String PREF_NAME = "WearSync_State";
    private static final String KEY_NODE_ID = "active_node_id";

    public static void setNodeId(Context context, String nodeId) {
        if (nodeId == null || nodeId.isEmpty()) {
            return;
        }
        activeNodeId = nodeId;
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_NODE_ID, nodeId)
                .apply();
        PhoneLog.d(TAG, "💾 [节点写入] 成功持久化手表活跃节点 ID ➔ " + nodeId);
    }

    public static String getNodeId(Context context) {
        if (activeNodeId != null) {
            return activeNodeId;
        }
        activeNodeId = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .getString(KEY_NODE_ID, null);
        PhoneLog.d(TAG, "💾 [节点读取] 内存无缓存，从 SP 容器加载到的手表节点 ID ➔ " + activeNodeId);
        return activeNodeId;
    }

    public static void clear(Context context) {
        activeNodeId = null;
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_NODE_ID)
                .apply();
        PhoneLog.w(TAG, "🗑️ [节点擦除] 手表活跃节点数据已被强制清空");
    }
}

