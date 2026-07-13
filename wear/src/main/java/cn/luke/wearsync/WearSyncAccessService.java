package cn.luke.wearsync;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.graphics.Path;
import android.util.DisplayMetrics;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
public class WearSyncAccessService extends AccessibilityService {
    private static final String TAG = "WearSync_AccessService";
    private static WearSyncAccessService instance;

    public static WearSyncAccessService getSharedInstance() {
        return instance;
    }

    @Override
    protected void onServiceConnected() {
        WearLog.d(TAG, "♿ 恭喜！手表无障碍高级交互接管服务成功绑定启动！");
        instance = this;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        WearLog.w(TAG, "♿ 无障碍交互服务被解绑断开");
        instance = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent accessibilityEvent) {}

    @Override
    public void onInterrupt() {}

    public boolean isQuickPanelReady() {
    AccessibilityNodeInfo root = getRootInActiveWindow();
    if (root == null) {
        return false;
    }
    return root.getChildCount() > 0;
}
    public void openQuickSettings() {
        WearLog.d(TAG, "⚙️ 触发执行模拟动作 ➔ 打开系统快捷控制中心");
        performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS);
    }

    public void goHome() {
        WearLog.d(TAG, "⚙️ 触发执行模拟动作 ➔ 返回表盘桌面");
        performGlobalAction(GLOBAL_ACTION_HOME);
    }

    public void openNotification() {
        WearLog.d(TAG, "⚙️ 触发执行模拟动作 ➔ 打开系统通知面板");
        performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS);
    }

    public void goBack() {
        WearLog.d(TAG, "⚙️ 触发执行模拟动作 ➔ 返回上一级");
        performGlobalAction(GLOBAL_ACTION_BACK);
    }

    public void click(float x, float y) {
        WearLog.d(TAG, "⚙️ 触发屏幕高级坐标重击 ➔ [" + x + ", " + y + "]");
        dispatchGesture(createClick(x, y), null, null);
    }

    public void clickIcon1_1() {
        DisplayMetrics displayMetrics = getResources().getDisplayMetrics();
        final int height = displayMetrics.heightPixels;
        final int midX = displayMetrics.widthPixels / 2;
        float targetY = (float) (height * .4);
        WearLog.d(TAG, "⚙️ 自动化计算：定位快捷面板首排中心坐标 ➔ [" + midX + ", " + targetY + "]");
        click(midX, targetY);
    }

    public void swipeDown() {
        WearLog.d(TAG, "⚙️ 触发执行顶层物理下滑手势...");
        DisplayMetrics displayMetrics = getResources().getDisplayMetrics();
        GestureDescription.Builder gestureBuilder = new GestureDescription.Builder();
        Path path = new Path();

        final int height = displayMetrics.heightPixels;
        final int midX = displayMetrics.widthPixels / 2;

        path.moveTo(midX, 0);
        path.lineTo(midX, (int)(height * .5));
        gestureBuilder.addStroke(new GestureDescription.StrokeDescription(path, 100, 50));
        dispatchGesture(gestureBuilder.build(), null, null);
    }

    private static GestureDescription createClick(float x, float y) {
        Path clickPath = new Path();
        clickPath.moveTo(x, y);
        GestureDescription.StrokeDescription clickStroke = new GestureDescription.StrokeDescription(clickPath, 0, 1);
        GestureDescription.Builder clickBuilder = new GestureDescription.Builder();
        return clickBuilder.addStroke(clickStroke).build();
    }
}
