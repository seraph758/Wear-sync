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
    
    private static volatile WearConnectionManager instance;
    private final Context appContext;

    public static WearConnectionManager getInstance(Context context) {
        if (instance == null) {
            synchronized (WearConnectionManager.class) {
                if (instance == null) {
                    instance = new WearConnectionManager(context.getApplicationContext());
                }
            }
        }
        return instance;
    }

    private WearConnectionManager(Context context) {
        this.appContext = context;
        
        // ✅ 注册监听器：手表连接/断开时自动更新缓存
        Wearable.getCapabilityClient(appContext).addListener(
            this::onCapabilityChanged, 
            CAPABILITY_NAME
        );
        
        // ✅ 初始化时主动查询一次
        discoverAndCacheWatchNode();
    }

    private void discoverAndCacheWatchNode() {
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
        Set<Node> nodes = capabilityInfo.getNodes();
        if (nodes != null && !nodes.isEmpty()) {
            String nodeId = nodes.iterator().next().getId();
            WearSyncState.setNodeId(appContext, nodeId);
            PhoneLog.d(TAG, "✅ 已更新手表节点缓存: " + nodeId);
        } else {
            WearSyncState.clear(appContext);
            PhoneLog.w(TAG, "⚠️ 无可用手表节点，已清空缓存");
        }
    }
}
