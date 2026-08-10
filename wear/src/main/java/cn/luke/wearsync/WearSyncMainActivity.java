package cn.luke.wearsync;

import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;

/**
 * 🎬 WearOS 手表端主框架入口 Activity 骨架
 * 说明：提供 Fragment 宿主容器，保持简洁的生命周期追踪。
 */
public class WearSyncMainActivity extends AppCompatActivity {
    private static final String TAG = "WearSync_MainActivity";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        WearLog.d(TAG, "① [生命周期] onCreate 启动 ━━━ 手表端主框架大厅加载开门 ━━━");
        super.onCreate(savedInstanceState);
        
        WearLog.d(TAG, "⚙️ [UI挂载] 正在解析并注入物理布局 R.layout.activity_main...");
        setContentView(R.layout.activity_main);

        if (savedInstanceState == null) {
            WearLog.d(TAG, "🔄 [堆栈判定] 检测到系统【冷启动/全新开启】，准备动态将 PreferenceFragment 注入宿主骨架...");
            
            getSupportFragmentManager()
                    .beginTransaction()
                    .replace(R.id.content_frame, new WearSyncMainFragment())
                    .commit();
            
            WearLog.d(TAG, "✨ [事务总线] commit() 提交指令已下达，WearSyncMainFragment 挂载成功");
        } else {
            WearLog.w(TAG, "⚠️ [堆栈判定] 检测到系统【热启动/配置变更/实例重建】！savedInstanceState 不为 null");
            WearLog.w(TAG, "⚠️ [防震荡熔断] 触发实例防震荡保护机制，跳过重复注入 Fragment 逻辑，防止多层 UI 叠加");
        }
        
        WearLog.d(TAG, "① [生命周期] onCreate 流程执行完毕");
    }

    @Override
    protected void onResume() {
        super.onResume();
        WearLog.d(TAG, "⏱️ [生命周期] onResume 触发 ➔ 手表主界面重新夺回屏幕物理焦点");
    }

    @Override
    protected void onPause() {
        super.onPause();
        WearLog.w(TAG, "⏸️ [生命周期] onPause 触发 ➔ 手表主界面暂时失去屏幕焦点");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        WearLog.w(TAG, "🛑 [生命周期] onDestroy 触发 ➔ 手表主界面销毁退出");
    }
}
