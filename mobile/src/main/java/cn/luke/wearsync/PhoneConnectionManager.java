package cn.luke.wearsync;

import android.content.Context;
import com.google.android.gms.wearable.CapabilityClient;
import com.google.android.gms.wearable.CapabilityInfo;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;
import java.util.Set;

/**
 * 💡 手表连接管理器（替代旧版 NodeClient 逻辑）
 * 职责：基于能力发现精准查找手表，并自动维护 WearSyncState 缓存
 */
public class PhoneConnectionManager {
    private static final String TAG = "WearSync_ConnMgr";
    private static final String CAPABILITY_NAME = "wear_sync";

    private static volatile PhoneConnectionManager instance;
    private final Context appContext;

    private PhoneConnectionManager(Context context) {
        this.appContext = context.getApplicationContext();

        // 🔑 1. 防重复注册：先移除再添加
        Wearable.getCapabilityClient(appContext).removeListener(this::onCapabilityChanged, CAPABILITY_NAME);
        Wearable.getCapabilityClient(appContext).addListener(
            this::onCapabilityChanged,
            CAPABILITY_NAME
        );

        // 🔑 2. 初始化时主动查询一次
        discoverAndCacheWatchNode();
    }

    // 🔑 容错初始化入口：外部可以直接 getInstance(context)
    public static PhoneConnectionManager getInstance(Context context) {
        if (instance == null) {
            synchronized (PhoneConnectionManager.class) {
                if (instance == null) {
                    instance = new PhoneConnectionManager(context);
                }
            }
        }
        return instance;
    }

    public void discoverAndCacheWatchNode() {
        Wearable.getCapabilityClient(appContext)
            .getCapability(CAPABILITY_NAME, CapabilityClient.FILTER_REACHABLE)
            .addOnSuccessListener(this::updateNodeCache)
            .addOnFailureListener(e -> {
                PhoneLog.e(TAG, "❌ 初始查找手表节点失败", e);
                WearSyncState.clear(appContext);
            });
    }

    private void onCapabilityChanged(CapabilityInfo capabilityInfo) {
        PhoneLog.d(TAG, "🔄 检测到手表连接状态变化");
        updateNodeCache(capabilityInfo);
    }

    private void updateNodeCache(CapabilityInfo capabilityInfo) {
        if (capabilityInfo == null) return;

        Set<Node> nodes = capabilityInfo.getNodes();
        Node targetNode = null;

        // 🔑 优先寻找近场直连 (isNearby) 的活跃手表
        for (Node node : nodes) {
            if (node.isNearby()) {
                targetNode = node;
                break;
            }
        }

        // 如果没有 nearby 的，退而求其次选择列表里的第一个
        if (targetNode == null && !nodes.isEmpty()) {
            targetNode = nodes.iterator().next();
        }

        if (targetNode != null) {
            String nodeId = targetNode.getId();
            WearSyncState.setNodeId(appContext, nodeId);
            PhoneLog.d(TAG, "✅ 已成功绑定并缓存活跃手表节点: " + nodeId + " (" + targetNode.getDisplayName() + ")");
        } else {
            WearSyncState.clear(appContext);
            PhoneLog.w(TAG, "⚠️ 当前无可用/连通的手表节点，已清空节点缓存");
        }
    }
}
