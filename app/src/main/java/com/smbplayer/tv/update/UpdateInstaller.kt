package com.smbplayer.tv.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.smbplayer.tv.data.SettingsRepository
import com.smbplayer.tv.util.logError
import com.smbplayer.tv.util.logInfo
import java.io.File

/**
 * 安装更新包 + 收尾清理。
 *
 * 电视盒子（尤其 5.x/7.x 老系统）安装完**不会**自动删安装包，
 * 缓存里躺着的 12MB APK 会一直占着内部存储，所以：
 * - 调起安装器前记下 `pendingUpdateVersion`；
 * - 新版本第一次启动时（版本号已经 >= 待安装版本）把整个 update 目录删掉；
 * - 另外把超过一天的残留包也扫掉（用户点了安装又取消的情况）。
 */
object UpdateInstaller {

    /** 残留包保留时长：超过就认为这次更新已作废 */
    private const val STALE_MS = 24 * 60 * 60 * 1000L

    /** 当前 APK 版本名（versionName），取不到时返回空串 */
    fun appVersion(context: Context): String = runCatching {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    }.getOrDefault("")

    /** 已下载且大小完整的安装包，没有则 null */
    fun downloadedApk(context: Context): File? {
        val version = SettingsRepository.downloadedUpdateVersion
        if (version.isEmpty()) return null
        val file = UpdateDownloader.fileFor(context, version)
        return file.takeIf { it.isFile && it.length() > 0 }
    }

    /**
     * Android 8.0 起装 APK 需要用户显式允许「安装未知应用」。
     * 电视上这个开关藏在系统设置里，只能在首次更新时引导用户去打开。
     */
    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            context.packageManager.canRequestPackageInstalls()

    /** 跳到本应用的「安装未知应用」授权页（部分老盒子没有该页面，静默失败） */
    fun openInstallPermissionSettings(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
            .onFailure { logError("打开安装权限设置失败", it) }
    }

    /**
     * 调起系统安装器。安装确认弹窗必须由用户按遥控器点「安装」
     * （除非 root/系统签名，普通应用无法静默安装）。
     *
     * @return 是否成功调起
     */
    fun install(context: Context, apk: File, version: String): Boolean {
        if (!apk.isFile) return false
        SettingsRepository.pendingUpdateVersion = version
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return runCatching {
            context.startActivity(intent)
            logInfo("调起安装器: ${apk.name}")
            true
        }.getOrElse {
            logError("调起安装器失败", it)
            false
        }
    }

    /** 应用启动时调用：版本已更新则删包，顺带清理过期残留 */
    fun cleanupOnStart(context: Context) {
        val dir = UpdateDownloader.dir(context)
        if (!dir.exists()) return

        val current = appVersion(context)
        val pending = SettingsRepository.pendingUpdateVersion
        // 待安装的版本已经装上了（当前版本不低于它）——这次更新闭环，包可以删
        val updated = pending.isNotEmpty() && current.isNotEmpty() &&
            UpdateChecker.isSameOrNewer(current, pending)

        var removed = 0
        dir.listFiles()?.forEach { file ->
            val stale = System.currentTimeMillis() - file.lastModified() > STALE_MS
            if (updated || stale) {
                if (file.delete()) removed++
            }
        }
        if (updated) {
            SettingsRepository.pendingUpdateVersion = ""
            SettingsRepository.downloadedUpdateVersion = ""
            logInfo("已更新到 v$current，清理安装包 ${removed} 个")
        } else if (removed > 0) {
            logInfo("清理过期安装包 ${removed} 个")
        }
    }
}
