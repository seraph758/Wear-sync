package cn.luke.wearsync; // ✅ 1. 修正为小写 package

import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import com.google.android.gms.wearable.CapabilityClient;
import com.google.android.gms.wearable.Wearable;

/**
 * 🎬 WearOS 手表端主控制与权限状态 Fragment 面板
 * 修复说明：
 * 1. 修正 package 关键字大小写错误。
 * 2. 优化 Context 获取方式，避免点击相机卡死。
 * 3. 确保 GMS 回调在主线程安全刷新 UI。
 */
public class WearSyncMainFragment extends PreferenceFragmentCompat {

    private static final String TAG = "WearSync_MainFragment";
    private static final String CAPABILITY_NAME = "wear_sync";

    private Preference connectivityPref;
    private Preference dndPref;
    private Preference accPref;
    private CapabilityClient.OnCapabilityChangedListener capabilityChangedListener;

    // 🎯 核心修复标志位：标记是否正在跳转到远端相机 Activity
    private boolean isNavigatingToCamera = false;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        WearLog.d(TAG, "① [生命周期] onCreatePreferences 启动 ─── 开始组装手表 Preference 树阵 ───");
        setPreferencesFromResource(R.xml.root_preferences, rootKey);

        connectivityPref = findPreference("connectivity_status");
        dndPref = findPreference("dnd_permission");
        accPref = findPreference("accessibility_service");

        // 🎯 点击跳转远端相机控制
        Preference cameraPref = findPreference("remote_camera");
        if (cameraPref != null) {
            cameraPref.setOnPreferenceClickListener(preference -> {
                WearLog.w(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
                WearLog.w(TAG, "📸 [远端相机入口] 用户点击【远端相机控制】");
                WearLog.w(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");

                // 🎯 安全获取 ApplicationContext，防止 Context 提前释放
                Context appContext = requireContext().getApplicationContext();

                // 🎯 1. 置位标志位，防止 onPause 中误解绑 GMS 监听器
                isNavigatingToCamera = true;
                WearLog.d(TAG, "🚩 [防抖标记] 设置 isNavigatingToCamera = true，保护 Wearable 通道");

                try {
                    WearLog.d(TAG, "① 正在准备启动本地 WearCameraActivity...");
                    Intent intent = new Intent(appContext, WearCameraActivity.class);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); // ✅ 防止 Activity 启动卡顿
                    startActivity(intent);
                    WearLog.d(TAG, "✅ 本地 WearCameraActivity 启动请求已发出");

                    WearLog.d(TAG, "② 正在调用 WearSyncCommManager.openPhoneCamera()...");
                    WearSyncCommManager.getInstance(appContext).openPhoneCamera();
                    WearLog.d(TAG, "✅ WearSyncCommManager.openPhoneCamera() 指令发送完成");

                } catch (Exception e) {
                    WearLog.e(TAG, "❌ [远端相机入口] 启动相机界面或发送信令失败: " + e.getMessage(), e);
                    isNavigatingToCamera = false; // 异常时复位标记
                    Toast.makeText(appContext, "相机启动失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                }

                return true;
            });
        }

        if (dndPref != null) {
            dndPref.setOnPreferenceClickListener(preference -> {
                WearLog.d(TAG, "⚙️ [权限点击] 用户点击【勿扰权限】卡片");
                if (!hasDndPermission()) {
                    WearLog.w(TAG, "⚠️ [权限跳转] 未检测到勿扰权限，正在跳转系统设置页...");
                    try {
                        Intent intent = new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS);
                        startActivity(intent);
                    } catch (Exception e) {
                        WearLog.e(TAG, "❌ [权限跳转] 跳转系统勿扰设置页失败: " + e.getMessage());
                    }
                } else {
                    WearLog.d(TAG, "💡 [权限提示] 勿扰权限已具备，弹出 Toast 提示");
                    Toast.makeText(getContext(), "勿扰权限已授予", Toast.LENGTH_SHORT).show();
                }
                return true;
            });
        }

        if (accPref != null) {
            accPref.setOnPreferenceClickListener(preference -> {
                WearLog.d(TAG, "⚙️ [权限点击] 用户点击【无障碍服务】卡片");
                if (!isAccessibilityServiceEnabled()) {
                    WearLog.w(TAG, "⚠️ [权限跳转] 未检测到无障碍服务，正在跳转系统设置页...");
                    try {
                        Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
                        startActivity(intent);
                    } catch (Exception e) {
                        WearLog.e(TAG, "❌ [权限跳转] 跳转系统无障碍设置页失败: " + e.getMessage());
                    }
                } else {
                    WearLog.d(TAG, "💡 [权限提示] 无障碍服务已激活，弹出 Toast 提示");
                    Toast.makeText(getContext(), "无障碍服务已开启", Toast.LENGTH_SHORT).show();
                }
                return true;
            });
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        WearLog.d(TAG, "🔄 [生命周期] onResume 触发：界面重回前台，刷新 UI 并重新挂载监听器");

        // 🎯 重置防抖标记
        if (isNavigatingToCamera) {
            isNavigatingToCamera = false;
            WearLog.d(TAG, "🚩 [防抖标记] 界面已返回，重置 isNavigatingToCamera = false");
        }

        updateDndUI();
        updateAccessibilityUI();

        checkAndCheckCapability();
        registerConnectivityListener();
    }

    @Override
    public void onPause() {
        super.onPause();
        WearLog.w(TAG, "⏸️ [生命周期] onPause 触发：界面失去焦点");

        // 🎯 核心逻辑拦截：如果是跳转到 WearCameraActivity 触发的 onPause，跳过监听器卸载
        if (isNavigatingToCamera) {
            WearLog.d(TAG, "🛡️ [防抖保护] 检测到正准备进入 WearCameraActivity，跳过 GMS 监听器卸载，维持推流通道");
            return;
        }

        WearLog.w(TAG, "⏸️ [生命周期] 界面彻底切后台/退出，准备卸载高频后台监听器...");
        unregisterConnectivityListener();
    }

    private boolean hasDndPermission() {
        Context ctx = getContext();
        if (ctx == null) return false;
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        return nm != null && nm.isNotificationPolicyAccessGranted();
    }

    private boolean isAccessibilityServiceEnabled() {
        Context ctx = getContext();
        if (ctx == null) return false;
        String prefString = Settings.Secure.getString(
                ctx.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        );
        return prefString != null && prefString.contains(ctx.getPackageName());
    }

    private void updateDndUI() {
        if (dndPref != null) {
            boolean has = hasDndPermission();
            dndPref.setSummary(has ? "已授权 (点击可重新校验)" : "未授权，点击前往系统设置开启");
        }
    }

    private void updateAccessibilityUI() {
        if (accPref != null) {
            boolean enabled = isAccessibilityServiceEnabled();
            accPref.setSummary(enabled ? "已开启 (点击可重新校验)" : "未开启，点击前往系统设置开启");
        }
    }

    private void checkAndCheckCapability() {
        Context ctx = getContext();
        if (ctx == null) return;

        WearLog.d(TAG, "📡 [链路检测] 正在向 GMS 发起 CapabilityQuery 寻找匹配节点 [" + CAPABILITY_NAME + "]...");
        Wearable.getCapabilityClient(ctx)
                .getCapability(CAPABILITY_NAME, CapabilityClient.FILTER_REACHABLE)
                .addOnSuccessListener(capabilityInfo -> {
                    boolean connected = !capabilityInfo.getNodes().isEmpty();
                    WearLog.d(TAG, "📡 [链路检测] 节点状态回调 ➔ 是否有匹配手机节点: " + connected);
                    
                    // ✅ 安全在 UI 线程更新
                    if (getActivity() != null) {
                        getActivity().runOnUiThread(() -> updateConnectionUI(connected));
                    }
                })
                .addOnFailureListener(e -> {
                    WearLog.e(TAG, "❌ [链路检测] 获取 Capability 失败: " + e.getMessage());
                    if (getActivity() != null) {
                        getActivity().runOnUiThread(() -> updateConnectionUI(false));
                    }
                });
    }

    private void registerConnectivityListener() {
        Context ctx = getContext();
        if (ctx != null) {
            if (capabilityChangedListener == null) {
                capabilityChangedListener = capabilityInfo -> {
                    boolean connected = !capabilityInfo.getNodes().isEmpty();
                    WearLog.d(TAG, "📡 [监听器触发] GMS 节点状态突变 ➔ 连接状态: " + connected);
                    if (getActivity() != null) {
                        getActivity().runOnUiThread(() -> updateConnectionUI(connected));
                    }
                };
            }
            try {
                Wearable.getCapabilityClient(ctx).addListener(capabilityChangedListener, CAPABILITY_NAME);
            } catch (Exception e) {
                WearLog.e(TAG, "❌ [接线员挂载] 挂载监听器抛出异常: " + e.getMessage());
            }
        }
    }

    private void unregisterConnectivityListener() {
        Context ctx = getContext();
        if (ctx != null && capabilityChangedListener != null) {
            try {
                Wearable.getCapabilityClient(ctx).removeListener(capabilityChangedListener);
            } catch (Exception e) {
                WearLog.w(TAG, "⚠️ [接线员卸载] 注销监听器异常: " + e.getMessage());
            }
        }
    }

    private void updateConnectionUI(boolean connected) {
        if (connectivityPref != null) {
            connectivityPref.setSummary(connected ? "已连接到手机" : "未连接到手机");
        }
    }
}
