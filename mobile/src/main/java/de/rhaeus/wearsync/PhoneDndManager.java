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

    
    public static void handleIncomingAction(Context context, int wearSystemDndVal) {
        PhoneLog.d(TAG, "📥 [逆向同步] 收到手表反向勿扰信令，准备同步至手机系统. 目标值 = " + wearSystemDndVal);

        try {
            NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);

            if (nm != null) {
                boolean hasPermission = nm.isNotificationPolicyAccessGranted();
                PhoneLog.d(TAG, "🔍 [逆向同步检查] 手机端『勿扰模式控制权限』状态 = " + hasPermission);

                if (hasPermission) {
                    nm.setInterruptionFilter(wearSystemDndVal);
                    PhoneLog.d(TAG, "✨ [逆向同步成功] 手机系统勿扰模式已成功设置为 = " + wearSystemDndVal);
                } else {
                    PhoneLog.w(TAG, "⚠️ [逆向同步失败] 手机端没有『勿扰模式控制权』(NotificationPolicyAccess)，请去系统设置授予！");
                }
            } else {
                PhoneLog.e(TAG, "❌ [逆向同步失败] 无法获取手机系统的 NotificationManager 实例");
            }

        } catch (Exception e) {
            PhoneLog.e(TAG, "🔴 [逆向同步异常] 强行修改手机勿扰状态时发生崩溃: " + e.getMessage(), e);
        }
    }

    /**
     * 🛰️ 正向同步勿扰状态至手表
     */
    public static void syncDndToWear(Context context, int currentFilter) {
        PhoneLog.d(TAG, "🛰️ [正向同步启动] 准备打包勿扰数据并异步发送至手表。当前 Filter = " + currentFilter + ", 当前 Mask = " + cachedMaskValue);

        new Thread(() -> {
            try {
                JSONObject json = new JSONObject();
                json.put("sender", "phone");
                json.put("type", "dnd");
                json.put("action", "SYNC_DND_STATUS");
                json.put("dnd_state", currentFilter);
                json.put("mask_value", cachedMaskValue);
                json.put("timestamp", System.currentTimeMillis());

                byte[] data = json.toString().getBytes(StandardCharsets.UTF_8);
                PhoneLog.d(TAG, "📦 [正向同步打包] JSON序列化成功 -> " + json.toString());

                // 🎯 从全局变量获取手表节点 ID
                String nodeId = WearSyncState.getNodeId(context);

                if (nodeId != null && !nodeId.isEmpty()) {
                    PhoneLog.d(TAG, "⚡ [正向同步发信] 命中全局车牌号缓存，直接通过 MessageClient 发送 -> " + nodeId);
                    Tasks.await(Wearable.getMessageClient(context).sendMessage(nodeId, "/wear-universal-sync", data));
                    PhoneLog.d(TAG, "🚀 [正向同步成功] 勿扰状态成功投递至缓存节点: " + nodeId);
                } else {
                    PhoneLog.w(TAG, "⚠️ [正向同步降级] 发现全局变量 NodeId 为空，开始在线查找已连接的手表节点...");

                    List<Node> nodes = Tasks.await(Wearable.getNodeClient(context).getConnectedNodes());

                    if (nodes != null && !nodes.isEmpty()) {
                        PhoneLog.d(TAG, "🔍 [正向同步降级] 在线找到 " + nodes.size() + " 个可用的物理手表节点");
                        for (Node node : nodes) {
                            PhoneLog.d(TAG, "  └─ 正在为节点建立连接并重写缓存: " + node.getId() + " (" + node.getDisplayName() + ")");
                            WearSyncState.setNodeId(context, node.getId());
                            
                            Tasks.await(Wearable.getMessageClient(context).sendMessage(node.getId(), "/wear-universal-sync", data));
                            PhoneLog.d(TAG, "     🚀 节点发信成功: " + node.getId());
                        }
                    } else {
                        PhoneLog.w(TAG, "❌ [正向同步断联] 传输失败：没有找到任何处于连线状态的手表设备，数据在空气中蒸发！");
                    }
                }
                PhoneLog.d(TAG, "🏁 [正向同步流转结束] 本轮勿扰线程执行完毕");

            } catch (Exception e) {
                PhoneLog.e(TAG, "🔴 [正向同步崩溃] 勿扰正向线程执行期间发生致命错误: " + e.getMessage(), e);
            }
        }).start();
    }
}
