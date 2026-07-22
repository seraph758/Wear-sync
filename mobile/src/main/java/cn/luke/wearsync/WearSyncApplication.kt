package cn.luke.wearsync

import android.app.Application
import timber.log.Timber

class WearSyncApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // ⚠️ 必须是第一行，在所有其他初始化之前
        PhoneLog.init(BuildConfig.DEBUG, this)
        
        Timber.d("WearSyncApplication 已启动")
    }
}
