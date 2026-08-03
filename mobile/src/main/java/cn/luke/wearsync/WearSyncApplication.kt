package cn.luke.wearsync

import android.app.Application
import timber.log.Timber

class WearSyncApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        // ⚠️ 必须是第一行
        PhoneLog.init(BuildConfig.DEBUG, this)

        // ✅ 注册崩溃日志自动备份
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                // 1. 先将崩溃堆栈写入日志文件（确保崩溃信息被记录）
                Timber.e(throwable, "💥 FATAL CRASH on thread [%s]", thread.name)

                // 2. 强制刷新并备份到 Downloads
                PhoneLog.exportBackupFile()
            } catch (e: Exception) {
                // 备份本身不能再崩溃，静默忽略
            }

            // 3. 交还给系统默认处理器（弹出"应用已停止"对话框 / 重启等）
            defaultHandler?.uncaughtException(thread, throwable)
        }

        Timber.d("WearSyncApplication 已启动")
    }
}
