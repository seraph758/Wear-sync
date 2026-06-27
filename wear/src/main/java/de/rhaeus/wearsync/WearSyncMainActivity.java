package de.rhaeus.wearsync;

import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;

/**
 * 🎬 WearOS 手表端主框架入口 Activity 骨架
 * 极致动态日志全步进版：微秒级动态追踪组件挂载、冷热启动防震荡判定与生命周期重回焦点。
 */
public class WearSyncMainActivity extends AppCompatActivity {
    private static final String TAG = "WearSync_MainActivity";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        WearLog.d(TAG, "① [生命周期] onCreate 点火 ━━━ 手表端主框架大厅加载开门 ━━━");
        super.onCreate(savedInstanceState);
        
        WearLog.d(TAG, "⚙️ [UI挂载] 正在解析并注入物理布局 R.layout.activity_main...");
        setContentView(R.layout.activity_main);

        if (savedInstanceState == null) {
            WearLog.d(TAG, "🔄 [堆栈判定] 检测到系统【冷启动/全新开启】，准备动态将 PreferenceFragment 注入宿主骨架...");
            
            WearLog.d(TAG, "⚙️ [事务总线] 开启 FragmentTransaction 准备执行 replace() 替换卡槽...");
            getSupportFragmentManager()
                    .beginTransaction()
                    .replace(R.id.content_frame, new WearSyncMainFragment())
                    .commit();
            
            WearLog.d(TAG, "✨ [事务总线] commit() 提交指令已下达，WearSyncMainFragment 挂载成功。");
        } else {
            WearLog.w(TAG, "⚠️ [堆栈判定] 检测到系统【热启动/配置变更/实例重建】！savedInstanceState 不为 null");
            WearLog.w(TAG, "⚠️ [防震荡熔断] 触发实例防震荡保护机制，跳过重复注入 Fragment 逻辑，防止多层 UI 叠加死锁。");
        }
        
        WearLog.d(TAG, "① [生命周期] onCreate 流程执行完毕。");
    }

    @Override
    protected void onResume() {
        super.onResume();
        WearLog.d(TAG, "⏱️ [生命周期] onResume 触发 ➔ 手表主界面重新夺回屏幕物理可视前台焦点。");
    }

    @Override
    protected void onPause() {
        WearLog.w(TAG, "⏸️ [生命周期] onPause 触发 ➔ 手表主界面暂时失去屏幕焦点。");
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        WearLog.w(TAG, "🏳️ [生命周期] onDestroy 触发 ➔ 手表主界面即将被销毁释放。");
        super.onDestroy();
    }
}
