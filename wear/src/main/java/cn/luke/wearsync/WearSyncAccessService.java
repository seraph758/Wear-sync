package cn.luke.wearsync;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.graphics.Path;
import android.util.DisplayMetrics;
import android.view.accessibility.AccessibilityEvent;

public class WearSyncAccessService extends AccessibilityService {

    private static WearSyncAccessService instance;
    private static final String TAG = "WearSync_AccessService";

    public static WearSyncAccessService getSharedInstance() {
        return instance;
    }
    @Override
    protected void onServiceConnected() {
        WearLog.d(TAG, "♿ 无障碍服务已连接");
        instance = this;
    }
    
    @Override
    public boolean onUnbind(Intent intent) {
        WearLog.d(TAG, "♿ 无障碍服务已断开");
        instance = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent accessibilityEvent) {

    }

    @Override
    public void onInterrupt() {

    }

    public void openQuickSettings() {
        performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS);
    }

    public void goBack() {
        performGlobalAction(GLOBAL_ACTION_BACK);
    }

    public void clickIcon1_1() {
        DisplayMetrics displayMetrics = getResources().getDisplayMetrics();
    
        final int height = displayMetrics.heightPixels;
        final int midX = displayMetrics.widthPixels / 2;
    
        GestureDescription.Builder gestureBuilder = new GestureDescription.Builder();
        Path path = new Path();
        path.moveTo(midX, (int) (height * .4));
        gestureBuilder.addStroke(new GestureDescription.StrokeDescription(path, 0, 50));
        dispatchGesture(gestureBuilder.build(), null, null);
    }
    
    public void swipeDown() {
        DisplayMetrics displayMetrics = getResources().getDisplayMetrics();
        GestureDescription.Builder gestureBuilder = new GestureDescription.Builder();
        Path path = new Path();

        final int mid = displayMetrics.heightPixels / 2;
        final int midX = displayMetrics.widthPixels / 2;

        path.moveTo(midX, 0);
        path.lineTo(midX, mid);
        gestureBuilder.addStroke(new GestureDescription.StrokeDescription(path, 100, 50));
        dispatchGesture(gestureBuilder.build(), null, null);
    }
}
