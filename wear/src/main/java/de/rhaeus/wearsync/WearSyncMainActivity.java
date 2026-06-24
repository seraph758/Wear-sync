package de.rhaeus.wearsync;

import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;

public class WearSyncMainActivity extends AppCompatActivity {
    private static final String TAG = "WearSync_MainActivity";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WearLog.d(TAG, "🚀 onCreate: 手表端主框架大厅加载开门...");
        setContentView(R.layout.activity_main);

        if (savedInstanceState == null) {
            WearLog.d(TAG, "🔄 冷启动：将 PreferenceFragment 注入宿主骨架...");
            getSupportFragmentManager()
                    .beginTransaction()
                    .replace(R.id.content_frame, new WearSyncMainFragment())
                    .commit();
        } else {
            WearLog.w(TAG, "⚠️ 实例防震荡机制触发，跳过重复注入 Fragment");
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        WearLog.d(TAG, "⏱️ onResume: 手表主界面回到可视前台");
    }
}
