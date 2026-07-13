package cn.luke.wearsync;

import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.widget.Toast;
import org.json.JSONObject;

/**
 * 🌓 手錶端勿擾、掩碼解讀與無障礙自動化聯控核心管理器 (致敬舊代碼・終極完美閉跨版)
 */
public class WearSyncDndManager {
    private static final String TAG = "WearSync_WearDnd";

    // 🔒 在記憶體中靜態暫存用戶從手機端傳過來的聯動開關意願（預設全開，完美對齊舊代碼行為）
    private static boolean isSyncAllowed = true;          // Bit 0: 總同步開關
    private static boolean isVibrateSwitchOn = true;       // Bit 1: 勿擾震動子開關
    private static boolean isSleepLinkageOpen = true;      // Bit 2: 睡眠模式聯動開關
    private static boolean isPowerSaveLinkageOpen = true;  // Bit 3: 省電模式聯動開關

    /**
     * 📥 模組一：更新聯動配置掩碼（先到達）
     * 🧩 替代了原本混亂的 handleIncomingMask，只純淨更新和儲存開關配置
     */
        public static void updateConfigs(JSONObject json) {
        if (json == null) return;
    
        int statusMask = json.optInt("mask", -1);
        if (statusMask == -1) {
            WearLog.w(TAG, "⚠️ [Mask读取失败] 未找到mask");
            return;
        }
    
        isSyncAllowed = (statusMask & 0x01) != 0;
    
        if (!isSyncAllowed) {
            WearLog.w(TAG, "🛑 [Mask拦截] Bit0=0，总同步关闭");
            return;
        }
    
        isVibrateSwitchOn = (statusMask & 0x02) != 0;
        isSleepLinkageOpen = (statusMask & 0x04) != 0;
        isPowerSaveLinkageOpen = (statusMask & 0x08) != 0;
    
        WearLog.d(TAG,
                "📥 [Mask解析] mask=" + statusMask
                        + " 震动=" + isVibrateSwitchOn
                        + " 睡眠=" + isSleepLinkageOpen
                        + " 省电=" + isPowerSaveLinkageOpen);
        }

    /**
     * 📥 模組二：接收原生 DND 狀態並強行指揮所有子聯動（後到達）
     * 🔥 100% 復刻舊代碼比對精髓，唯有狀態不相等時才驅動變更！
     * @param dndStatePhone 手機傳過來的系統原生 filter 狀態值 (1, 2, 3, 4)
     */
public static void executeDndSync(Context context, int dndStatePhone) {

    if (!isSyncAllowed) {
        WearLog.w(TAG, "🛑 [DND拦截] 总开关关闭");
        return;
    }


    NotificationManager mNotificationManager =
            (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);


    if (mNotificationManager == null) {
        WearLog.e(TAG, "❌ NotificationManager 获取失败");
        return;
    }


    int currentDndState =
            mNotificationManager.getCurrentInterruptionFilter();


    WearLog.d(TAG,
            "🔍 [DND状态检查] 手机="
                    + dndStatePhone
                    + " 手表="
                    + currentDndState
                    + " mask震动="
                    + isVibrateSwitchOn
                    + " 睡眠="
                    + isSleepLinkageOpen
                    + " 省电="
                    + isPowerSaveLinkageOpen);



    /*
     * 注意：
     * 这里以前如果DND一致直接return，会导致：
     * 省电不同步
     * 睡眠不同步
     * 关闭勿扰无法恢复省电
     *
     * 所以只记录，不退出
     */
    if (dndStatePhone == currentDndState) {

        WearLog.d(TAG,
                "✅ [DND一致] 继续执行子联动");

    } else {

        WearLog.d(TAG,
                "⚡ [DND变化] 开始同步");

    }



    WearSyncNotificationService.isInternalUpdate = true;
    WearSyncNotificationService.lastInternalUpdateTime =
        System.currentTimeMillis();


    /*
     * 1. 优先震动
     * 避免后面的省电/DND切换影响震动
     */
    WearLog.d(TAG,
            "📳 [震动判断] 开关="
                    + isVibrateSwitchOn
                    + " dnd="
                    + dndStatePhone);



    if (isVibrateSwitchOn && dndStatePhone > 1) {

        WearLog.d(TAG,
                "📳 [开始震动]");

        vibrate(context);

    } else {

        WearLog.d(TAG,
                "🔇 [未满足震动条件]");

    }




    /*
     * 2. 睡眠模式联动
     */
    if (isSleepLinkageOpen) {

        WearLog.d(TAG,
                "🛌 [睡眠联动]执行");

        toggleBedtimeMode(context);

    } else {

        WearLog.d(TAG,
                "🛌 [睡眠联动]关闭");

    }




    /*
 * 3. 省电模式联动
 * 延迟执行，避免抢占DND系统服务
 */
if (isPowerSaveLinkageOpen) {

    boolean enable = dndStatePhone > 1;

    new Handler(Looper.getMainLooper()).postDelayed(() -> {

        try {

            Settings.Global.putInt(
                    context.getContentResolver(),
                    "low_power",
                    enable ? 1 : 0
            );

            context.sendBroadcast(
                    new Intent(
                            PowerManager.ACTION_POWER_SAVE_MODE_CHANGED
                    )
            );

            WearLog.d(TAG,
                    "🔋 [省电异步同步] "
                            +(enable ? "开启" : "关闭"));

        } catch(Exception e){

            WearLog.e(TAG,
                    "❌ [省电同步失败] "
                            + e.getMessage());

        }

    },5500);


} else {

    WearLog.d(TAG,
            "🔋 [省电联动]关闭");

}



    /*
     * 4. 最后写入手表DND状态
     */
    if (mNotificationManager.isNotificationPolicyAccessGranted()) {


        mNotificationManager.setInterruptionFilter(
                dndStatePhone
        );


        WearLog.d(TAG,
                "🌗 [写入DND] "
                        + dndStatePhone);


    } else {


        WearLog.e(TAG,
                "❌ 无DND权限");

    }




    new Handler(Looper.getMainLooper())
            .postDelayed(() -> {


                WearSyncNotificationService.isInternalUpdate = false;


                           WearLog.d(TAG,"🔓 内部同步锁释放");

        },7000);
}

    /**
     * 🛌 透過無障礙模擬點擊切換手錶系統的就寢模式（睡眠模式）
     */
    private static void toggleBedtimeMode(Context context) {
        WearSyncAccessService serv = WearSyncAccessService.getSharedInstance();
        if (serv == null) {
            Toast.makeText(context.getApplicationContext(),
                    "無障礙服務未連接，無法同步睡眠模式", Toast.LENGTH_LONG).show();
            return;
        }

        // ✅ 使用合法组合强制唤醒屏幕 + 保持// ✅ 修正：使用 SCREEN_BRIGHT_WAKE_LOCK 替代 PARTIAL_WAKE_LOCK
PowerManager pm = (PowerManager) context.getApplicationContext()
        .getSystemService(Context.POWER_SERVICE);
if (pm == null) return;

final PowerManager.WakeLock wakeLock = pm.newWakeLock(
        PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                | PowerManager.ACQUIRE_CAUSES_WAKEUP
                | PowerManager.ON_AFTER_RELEASE,
        "dndsync:BedtimeAutomation"
);

// 10秒安全兜底
wakeLock.acquire(10 * 1000L);

Toast.makeText(context.getApplicationContext(),
        "正在同步睡眠模式...", Toast.LENGTH_SHORT).show();

new Handler(Looper.getMainLooper()).post(() -> {

    waitScreenReady(pm, () -> {

        try {

            waitQuickPanelReady(serv, () -> {

    try {

        WearLog.d(TAG, "👆 快捷面板已就緒，點擊就寢模式");

        serv.clickIcon1_1();

        serv.goBack();

        WearLog.d(TAG, "✨ [無障礙聯動] 就寢模式自動化執行完畢");

    } catch (Exception e) {

        WearLog.e(TAG, "❌ 點擊就寢模式失敗", e);

    } finally {

        if (wakeLock.isHeld()) {
            wakeLock.release();
            WearLog.d(TAG, "🔒 Wakelock 提前釋放成功");
        }

    }

});
}

private static void waitQuickPanelReady(WearSyncAccessService serv,Runnable next){

    Handler handler = new Handler(Looper.getMainLooper());

    final int[] retry = {0};

    Runnable[] task = new Runnable[1];

    task[0] = new Runnable() {

        @Override
        public void run() {

            if (serv.isQuickPanelReady()) {

                WearLog.d(TAG, "✅ QuickPanel Ready");

                next.run();

                return;

            }

            retry[0]++;

            if (retry[0] > 30) {

                WearLog.e(TAG, "❌ QuickPanel 打開超時");

                return;

            }

            WearLog.d(TAG, "⬇️ QuickPanel 未打開，第 " + retry[0] + " 次重新下拉");

            serv.swipeDown();

            handler.postDelayed(this,100);

        }

    };

    handler.post(task[0]);

}

private static void waitScreenReady(PowerManager pm,Runnable next){
    Handler handler=new Handler(Looper.getMainLooper());

    Runnable[] task=new Runnable[1];

    task[0]=new Runnable(){
        @Override
        public void run(){

            if(pm.isInteractive()){
                WearLog.d(TAG,"✅ Screen Interactive");
                next.run();
                return;
            }

            handler.postDelayed(this,50);
        }
    };

    handler.post(task[0]);
}

    private static void vibrate(Context context) {
        Vibrator v=(Vibrator)context.getSystemService(Context.VIBRATOR_SERVICE);
    
        if(v==null){
            WearLog.e(TAG,"❌ Vibrator==null");
            return;
        }
    
        if(!v.hasVibrator()){
            WearLog.e(TAG,"❌ 设备没有振动器");
            return;
        }
    
        WearLog.d(TAG,"📳 真正执行系统震动");
    
        v.vibrate(
                VibrationEffect.createOneShot(
                        50,
                        VibrationEffect.DEFAULT_AMPLITUDE
                )
        );
    }
}
