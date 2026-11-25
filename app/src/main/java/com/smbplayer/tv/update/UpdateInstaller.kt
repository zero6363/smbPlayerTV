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
     * 当前是否已经允许本应用安装 APK。
     *
     * 注意：**不能把它当唯一判据**。小米电视（MIUI TV）根本没有「安装未知应用」的
     * 按应用授权页，只有一个系统级开关（设置 → 账号与安全 → 安装未知来源的应用），
     * 所以 canRequestPackageInstalls() 在小米电视上永远是 false，但直接调安装器照样能装。
     * 调用方遇到 false 时要先看 [hasUnknownSourcesSettings]，没有该页面就直接尝试安装。
     */
    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            context.packageManager.canRequestPackageInstalls()

    /** 「安装未知应用」的按应用授权页（带包名 + 全局列表两种写法，后者老系统也能认） */
    private fun unknownSourcesIntents(context: Context): List<Intent> = listOf(
        Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        ),
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES),
    )

    /**
     * 系统有没有「安装未知应用」授权页。
     *
     * 小米电视 / 部分国产 TV 没有这个页面（只有全局开关），返回 false，
     * 此时弹「去设置」没有任何意义——直接调安装器才是对的。
     */
    fun hasUnknownSourcesSettings(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        val pm = context.packageManager
        return unknownSourcesIntents(context).any { intent ->
            runCatching { intent.resolveActivity(pm) != null }.getOrDefault(false)
        }
    }

    /**
     * 跳到本应用的「安装未知应用」授权页。
     *
     * @return false 表示系统没有这个页面（小米电视常见），调用方应改为直接调起安装器
     */
    fun openInstallPermissionSettings(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        for (intent in unknownSourcesIntents(context)) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val resolvable = runCatching {
                intent.resolveActivity(context.packageManager) != null
            }.getOrDefault(false)
            if (!resolvable) continue
            if (runCatching { context.startActivity(intent) }.isSuccess) {
                logInfo("打开安装权限设置")
                return true
            }
        }
        return false
    }

    /**
     * 打开系统设置首页（没有专用授权页时的兜底，让用户自己去「账号与安全」）。
     * 小米电视的设置能响应 ACTION_SETTINGS，小米自家安全中心页则不一定。
     */
    fun openSystemSettings(context: Context): Boolean {
        val intents = listOf(
            Intent(Settings.ACTION_SECURITY_SETTINGS),
            Intent(Settings.ACTION_SETTINGS),
        )
        for (intent in intents) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { context.startActivity(intent) }.isSuccess) {
                logInfo("打开系统设置")
                return true
            }
        }
        logError("打开系统设置失败")
        return false
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
            // 下载只活在应用进程里：进程重启后没人会接着写 .part，半截文件直接清掉
            val partial = file.name.endsWith(".part")
            val stale = System.currentTimeMillis() - file.lastModified() > STALE_MS
            if (partial || updated || stale) {
                if (file.delete()) removed++
            }
        }
        if (updated) {
            SettingsRepository.pendingUpdateVersion = ""
            SettingsRepository.downloadedUpdateVersion = ""
            logInfo("已更新到 v$current，清理安装包 ${removed} 个")
        } else if (removed > 0) {
            logInfo("清理残留/过期安装包 ${removed} 个")
        }
    }
}
