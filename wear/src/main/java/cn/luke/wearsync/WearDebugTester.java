package cn.luke.wearsync;

import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityNodeInfo;

public final class WearDebugTester {

    private static final String TAG = "WearDebug";

    private WearDebugTester() {}

    /**
     * 打印当前整个无障碍节点树
     */
    public static void dumpCurrentWindow() {

        WearSyncAccessService service = WearSyncAccessService.getSharedInstance();

        if (service == null) {
            WearLog.e(TAG, "❌ AccessibilityService == null");
            return;
        }

        AccessibilityNodeInfo root = service.getRootInActiveWindow();

        if (root == null) {
            WearLog.e(TAG, "❌ Root == null");
            return;
        }

        WearLog.e(TAG, "========== NODE TREE BEGIN ==========");

        dumpNode(root, 0);

        WearLog.e(TAG, "=========== NODE TREE END ===========");
    }

    private static void dumpNode(AccessibilityNodeInfo node, int level) {

        if (node == null) return;

        StringBuilder prefix = new StringBuilder();

        for (int i = 0; i < level; i++) {
            prefix.append("  ");
        }

        WearLog.d(TAG,
                prefix +
                        "class=" + node.getClassName() +
                        " text=" + node.getText() +
                        " desc=" + node.getContentDescription() +
                        " id=" + node.getViewIdResourceName() +
                        " clickable=" + node.isClickable());

        for (int i = 0; i < node.getChildCount(); i++) {
            dumpNode(node.getChild(i), level + 1);
        }
    }

    /**
     * 延迟打印窗口（方便动画完成以后观察）
     */
    public static void dumpWindowDelayed(long delayMillis) {

        new Handler(Looper.getMainLooper()).postDelayed(
                WearDebugTester::dumpCurrentWindow,
                delayMillis
        );
    }

    /**
     * 测试打开快捷菜单
     */
    public static void testOpenQuickSettings() {

        WearSyncAccessService service = WearSyncAccessService.getSharedInstance();

        if (service == null) {
            WearLog.e(TAG, "❌ AccessibilityService == null");
            return;
        }

        WearLog.d(TAG, "===== TEST OPEN QUICK SETTINGS =====");

        service.openQuickSettings();

        dumpWindowDelayed(800);
    }

    /**
     * 测试下拉手势
     */
    public static void testSwipeDown() {

        WearSyncAccessService service = WearSyncAccessService.getSharedInstance();

        if (service == null) {
            WearLog.e(TAG, "❌ AccessibilityService == null");
            return;
        }

        WearLog.d(TAG, "===== TEST SWIPE DOWN =====");

        service.swipeDown();

        dumpWindowDelayed(800);
    }

    /**
     * 测试点击第一排图标
     */
    public static void testClickFirstIcon() {

        WearSyncAccessService service = WearSyncAccessService.getSharedInstance();

        if (service == null) {
            WearLog.e(TAG, "❌ AccessibilityService == null");
            return;
        }

        WearLog.d(TAG, "===== TEST CLICK ICON =====");

        service.clickIcon1_1();
    }
}
