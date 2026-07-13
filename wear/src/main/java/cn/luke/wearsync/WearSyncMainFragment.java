package cn.luke.wearsync;

import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Toast;

import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import com.google.android.gms.wearable.CapabilityClient;
import com.google.android.gms.wearable.Wearable;


/**
 * 🎬 WearOS 手表端主控制与权限状态 Fragment 面板
 * 极致动态日志全步进版：微秒级动态追踪无障碍、DND权限轮询、谷歌微端链路突变及监听器生命周期挂载。
 */
public class WearSyncMainFragment extends PreferenceFragmentCompat {
    private static final String TAG = "WearSync_MainFragment";
    private Preference connectivityPref;
    private Preference dndPref;
    private Preference accPref;
    private CapabilityClient.OnCapabilityChangedListener capabilityChangedListener;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        WearLog.d(TAG, "① [生命周期] onCreatePreferences 点火 ─── 开始组装手表 Preference 树阵 ───");
        
        // 从 XML 载入 Preference 结构
        setPreferencesFromResource(R.xml.root_preferences, rootKey);

        WearLog.d(TAG, "⚙️ [键值对检索] 正在从根节点捞取物理卡面 UI 组件...");
        connectivityPref = findPreference("connectivity_state_key");
        dndPref = findPreference("dnd_permission_key");
        accPref = findPreference("acc_permission_key");

        WearLog.d(TAG, "⚙️ [组件检索快照] connectivityPref=[" + (connectivityPref != null ? "已挂载" : "NULL") + "], " +
                "dndPref=[" + (dndPref != null ? "已挂载" : "NULL") + "], accPref=[" + (accPref != null ? "已挂载" : "NULL") + "]");

        if (dndPref != null) {
            WearLog.d(TAG, "🔒 [UI行为控制] 锁定 DND 权限项为只读状态（禁止非必要的手动反复敲击触发）");
            dndPref.setSelectable(false);
        }

        if (accPref != null) {
            WearLog.d(TAG, "🎯 [交互事件挂载] 正在为 [无障碍辅助功能] 项注入物理点击跳转拦截器...");
            accPref.setOnPreferenceClickListener(preference -> {
                WearLog.d(TAG, "🔘 [交互触发] 用户点击了无障碍辅助功能 Preference 项，准备穿透调起系统设置页面...");
                try {
                    Intent accIntent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
// 🚀 直接把原本的 accIntent.addFlags(...) 这一行整行删掉！
                    WearLog.d(TAG, "🚀 [物理跳转] 正在呼叫 startActivity -> ACTION_ACCESSIBILITY_SETTINGS");
                    startActivity(accIntent);
                } catch (Exception e) {
                    WearLog.e(TAG, "🔴 [物理跳转崩溃] 无法完成向系统无障碍设置页面的跳转动作: " + e.getMessage(), e);
                    Toast.makeText(getContext(), getString(R.string.acc_jump_failed), Toast.LENGTH_SHORT).show();
                }
                return true;
            });
        }
        // ========================================================================
        // 📸 远端相机控制入口
        // ========================================================================
        Preference cameraPref = findPreference("camera_control_key");
        if (cameraPref != null) {
            WearLog.d(TAG, "📸 [交互挂载] 成功找到 camera_control_key，正在注册点击监听器...");
        
            cameraPref.setOnPreferenceClickListener(preference -> {
        
                WearLog.w(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
                WearLog.w(TAG, "📸 [远端相机入口] 用户点击【远端相机控制】");
                WearLog.w(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        
                try {
                        WearLog.d(TAG, "① 正在准备启动本地 WearCameraActivity...");

                    // 🎯 干净清爽！直接把那行 addFlags 删掉即可
                        Intent intent = new Intent(requireContext(), WearCameraActivity.class);
                        startActivity(intent);
                    
                        WearLog.d(TAG, "✅ 本地 WearCameraActivity 启动请求已发出。");
                    
                    } catch (Exception e) {
                        WearLog.e(TAG, "❌ 本地 WearCameraActivity 启动失败：" + e.getMessage(), e);
                    }
                    
                    
                    try {
                        WearLog.d(TAG, "② 正在调用 WearSyncRemoteCameraHandler.openPhoneCamera()...");
                    
                        new WearSyncRemoteCameraHandler(requireContext())
                                .openPhoneCamera();
                    
                        WearLog.d(TAG, "✅ openPhoneCamera() 已调用完成");
                    
                    } catch (Exception e) {
                        WearLog.e(TAG, "❌ openPhoneCamera调用失败：" + e.getMessage(), e);
                    }
        
                        WearLog.w(TAG, "📸 [远端相机入口] 点击事件处理结束。");
        
                        return true;
                    });
                
                } else {
                    WearLog.e(TAG, "❌ [交互挂载失败] 未找到 Preference：camera_control_key");
                }
                
                WearLog.d(TAG, "⚙️ [底层初始化] 正在引导加载谷歌微端物理链路异步探针...");
                setupConnectionCheck();
                WearLog.d(TAG, "① [生命周期] onCreatePreferences 配置完毕。");
            }

    @Override
    public void onResume() {
        super.onResume();
        WearLog.d(TAG, "🔄 [生命周期] onResume 触发：UI 叠层重回焦点。开始激活全量数据轮询与动态监听器...");
        
        WearLog.d(TAG, "🔄 [权限轮询] 💡 准备刷新当前系统的权限状态快照...");
        updatePermissionStatus();
        
        WearLog.d(TAG, "📡 [链路监听] 💡 准备向谷歌微端框架动态注册联络链路突变哨兵...");
        registerConnectivityListener();
    }

    private void updatePermissionStatus() {
        Context ctx = getContext();
        if (ctx == null) {
            WearLog.w(TAG, "⚠️ [权限轮询终止] 检测到当前 Fragment 处于脱离托管上下文状态 (getContext == null)，终止刷新。");
            return;
        }

        WearLog.d(TAG, "🔍 [系统权限检索] ─── 开始核对系统底层权限白名单 ───");
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);

        if (dndPref != null && nm != null) {
            boolean hasDnd = nm.isNotificationPolicyAccessGranted();
            WearLog.d(TAG, "🌓 [系统权限检索] 📋 勿扰模式(DND/系统通知)权限最新状态: 【" + (hasDnd ? "已授权/TRUE" : "未授权/FALSE") + "】");
            dndPref.setSummary(hasDnd ? getString(R.string.dnd_granted) : getString(R.string.dnd_denied));
        } else {
            WearLog.w(TAG, "⚠️ [系统权限检索] 边缘跳过：dndPref 或 NotificationManager 实体不存续。");
        }

        // 判定服务活体句柄是否就绪
        boolean hasAccessibility = WearSyncAccessService.getSharedInstance() != null;
        WearLog.d(TAG, "♿ [系统权限检索] 📋 无障碍辅助功能服务实时活体状态: 【" + (hasAccessibility ? "服务已激活/TRUE" : "服务死亡/FALSE") + "】");
        
        if (accPref != null) {
            accPref.setSummary(hasAccessibility ?  getString(R.string.acc_activated) : getString(R.string.acc_deactivated));
        }
    }

    @Override
    public void onPause() {
        WearLog.w(TAG, "⏸️ [生命周期] onPause 触发：界面暂时失去焦点，开始紧急卸载解绑高频后台监听器...");
        super.onPause();
        unregisterConnectivityListener();
    }

    private void setupConnectionCheck() {
        Context ctx = getContext();
        Context targetContext = (ctx != null) ? ctx : (getActivity() != null ? getActivity().getApplicationContext() : null);
        
        if (targetContext == null) {
            WearLog.e(TAG, "🔴 [链路点火阻断] 极其严重的生命周期错位：无法获取合法的 Context 实体，setupConnectionCheck 宣告流产！");
            return;
        }

        WearLog.d(TAG, "📡 [链路点火] 正在向谷歌微端中心发射一次性异步 Capability 状态扫描求助... 目标能力名: [dnd_sync]");
        try {
            Wearable.getCapabilityClient(targetContext)
                    .getCapability("dnd_sync", CapabilityClient.FILTER_REACHABLE)
                    .addOnSuccessListener(capabilityInfo -> {
                        if (capabilityInfo != null && capabilityInfo.getNodes() != null) {
                            int nodeCount = capabilityInfo.getNodes().size();
                            boolean isConnected = nodeCount > 0;
                            WearLog.d(TAG, "📡 [链路一次性异步快照] 接收到谷歌中心应答：在线配对宿主手机节点计数 = [" + nodeCount + "] ➔ 判定连通状态 = " + isConnected);
                            updateConnectionUI(isConnected);
                        } else {
                            WearLog.w(TAG, "⚠️ [链路一次性异步快照] 谷歌中心反馈数据包为空(null)，强制判定断开。");
                            updateConnectionUI(false);
                        }
                    })
                    .addOnFailureListener(e -> {
                        WearLog.e(TAG, "🔴 [链路一次性异步快照] 无法联络到 Google Play Services 底层穿透库: " + e.getMessage(), e);
                    });

            // 构造实时监听接线员
            WearLog.d(TAG, "⚙️ [链路点火] 正在内存中组装实时突变事件接线员: OnCapabilityChangedListener...");
            capabilityChangedListener = capabilityInfo -> {
                if (capabilityInfo != null && capabilityInfo.getNodes() != null) {
                    int nodeCount = capabilityInfo.getNodes().size();
                    boolean isConnected = nodeCount > 0;
                    WearLog.w(TAG, "📡 [链路突变捕获] 🗺️ 检测到手表的物理拓扑网络发生变动！新在线节点数 = [" + nodeCount + "] ➔ 最新在线判定 = " + isConnected);
                    updateConnectionUI(isConnected);
                } else {
                    WearLog.w(TAG, "📡 [链路突变捕获] 拓扑数据回传空包，判定手机侧链路闪断。");
                    updateConnectionUI(false);
                }
            };
        } catch (Exception e) {
            WearLog.e(TAG, "🔴 [链路点火致命] 构造谷歌 Wearable 接口时遭遇环境崩溃: " + e.getMessage(), e);
        }
    }

    private void registerConnectivityListener() {
        Context ctx = getContext();
        if (ctx != null && capabilityChangedListener != null) {
            WearLog.d(TAG, "📡 [接线员挂载] 🚀 正在物理挂载 addListener() 到 CapabilityClient，锁死 [dnd_sync] 频道...");
            try {
                Wearable.getCapabilityClient(ctx).addListener(capabilityChangedListener, "dnd_sync");
                WearLog.d(TAG, "📡 [接线员挂载] 挂载命令成功扔进系统总线。");
            } catch (Exception e) {
                WearLog.e(TAG, "🔴 [接线员挂载失败] 执行 addListener 遭遇底层死锁拦截: " + e.getMessage());
            }
        } else {
            WearLog.w(TAG, "⚠️ [接线员挂载边缘阻断] 挂载取消 -> 状态: Context為空=" + (ctx == null) + ", 监听员物件為空=" + (capabilityChangedListener == null));
        }
    }

    private void unregisterConnectivityListener() {
        Context ctx = getContext();
        if (ctx != null && capabilityChangedListener != null) {
            WearLog.w(TAG, "📡 [接线员卸载] 🧹 正在执行物理回收 removeListener() 动作，杜绝手表内存泄露游离...");
            try {
                Wearable.getCapabilityClient(ctx).removeListener(capabilityChangedListener);
                WearLog.w(TAG, "📡 [接线员卸载] 回收动作已从系统总线安全剥离。");
            } catch (Exception e) {
                WearLog.w(TAG, "📡 [接线员卸载边缘微调] 剥离过程抛出非致命错误 (可能之前并未成功挂载): " + e.getMessage());
            }
        } else {
            WearLog.d(TAG, "📡 [接线员卸载边缘跳过] 无需剥离 -> 状态: Context為空=" + (ctx == null) + ", 监听员物件為空=" + (capabilityChangedListener == null));
        }
    }

    private void updateConnectionUI(boolean connected) {
        if (connectivityPref != null) {
            connectivityPref.setSummary(connected ? getString(R.string.connectivity_connected) : getString(R.string.connectivity_disconnected));
            WearLog.w(TAG, "📡 [UI渲染变更] ─── 联络链路状态突变探查 ➔ 在线=[" + connected + "] ───");
        } else {
            WearLog.e(TAG, "❌ [UI渲染失败] 试图将联络状态 [" + connected + "] 推送至卡面，但 connectivityPref 组件尚未加载，数据流丢失！");
        }
    }
}
