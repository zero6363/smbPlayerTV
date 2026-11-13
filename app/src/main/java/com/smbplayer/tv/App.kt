package com.smbplayer.tv

import android.app.Application
import android.os.Process
import com.smbplayer.tv.data.FavoriteRepository
import com.smbplayer.tv.data.PlaybackProgressRepository
import com.smbplayer.tv.data.ServerRepository
import com.smbplayer.tv.data.SettingsRepository
import com.smbplayer.tv.update.UpdateInstaller
import com.smbplayer.tv.util.AppLog
import com.smbplayer.tv.util.logError
import com.smbplayer.tv.util.logInfo

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        ServerRepository.init(this)
        SettingsRepository.init(this)
        FavoriteRepository.init(this)
        PlaybackProgressRepository.init(this)
        AppLog.init(this)
        installCrashHandler()
        // 更新装好后第一次启动：把 cacheDir/update 里那份 APK 删掉（电视不会自己清）
        UpdateInstaller.cleanupOnStart(this)
        logInfo("应用启动")
    }

    /**
     * 未捕获异常兜底：把崩溃堆栈写进 AppLog（同步落盘），
     * 这样即使闪退回首页，下次也能在「日志」页复制到完整堆栈。
     */
    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                logError("未捕获异常: ${thread.name}", throwable)
            }
            previous?.uncaughtException(thread, throwable)
                ?: run {
                    Process.killProcess(Process.myPid())
                    System.exit(1)
                }
        }
    }

    companion object {
        lateinit var instance: App
            private set
    }
}
