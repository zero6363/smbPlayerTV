package com.smbplayer.tv.update

import android.os.SystemClock
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.smbplayer.tv.R
import com.smbplayer.tv.data.SettingsRepository
import com.smbplayer.tv.util.formatSize
import com.smbplayer.tv.util.isAlive
import com.smbplayer.tv.util.logError
import com.smbplayer.tv.util.logInfo
import com.smbplayer.tv.util.toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * 检查 → 提示 → 后台下载 → 安装 的完整流程（主页自动检查 + 设置页手动检查共用）。
 *
 * 电视上没有应用商店，用户也不会去点浏览器下载 APK，
 * 所以这里把「发现新版本」做成一个对话框：确认后应用自己在后台下、下完再提示安装。
 *
 * 下载本身在 [UpdateManager] 的应用级协程里跑，不绑页面：确认后就能关掉对话框去播放，
 * 页面只通过 [attach] 订阅进度。播放页不订阅，所以看片时不会突然弹出安装框；
 * 退出播放页回到列表页时再提示。
 */
object UpdateFlow {

    /** 启动自动检查的间隔：电视常年在线，6 小时足够，也能避开 GitHub 未认证限流 */
    private const val CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L

    /** 主页拿到焦点后再等一会儿弹窗，否则会和列表抢焦点 */
    private const val AUTO_CHECK_DELAY_MS = 1200L

    /** 更新说明太长时截断，TV 对话框一屏放不下 */
    private const val NOTES_LIMIT = 600

    /** 更新提示框无操作自动收起：电视常开着没人按，别让对话框一直占屏、抢主页焦点 */
    private const val UPDATE_DIALOG_TIMEOUT_MS = 30_000L

    @Volatile
    private var checking = false

    private var autoScheduled = false

    /** 权限没开时暂存的待装包：从系统设置授权回来可以直接接着装 */
    private var pendingApk: File? = null
    private var pendingVersion: String = ""

    /** 主页 onResume 调用：节流后静默检查，失败不打扰用户 */
    fun autoCheck(activity: AppCompatActivity) {
        // 已经有下好的包在等安装：别再弹「发现新版本」压在安装提示上
        val state = UpdateManager.state.value
        if (state is UpdateManager.State.Ready && state.apk.isFile) return
        if (autoScheduled) return
        val sinceLast = System.currentTimeMillis() - SettingsRepository.lastUpdateCheck
        if (sinceLast in 0 until CHECK_INTERVAL_MS) return
        autoScheduled = true
        activity.window.decorView.postDelayed({
            autoScheduled = false
            if (activity.isAlive()) check(activity, manual = false)
        }, AUTO_CHECK_DELAY_MS)
    }

    /**
     * 页面 onCreate 调用一次：把后台下载状态接到界面上（进入前台时自动开始订阅，离开自动停）。
     *
     * - 下载失败只提示一次；
     * - 下载完成时按 [UpdateManager.takeInstallPrompt] 弹「立即安装」，只弹一次；
     * - [onState] 在每次状态变化时回调（主页顶栏、设置页说明文字用它刷新进度）。
     *
     * 订阅只在 RESUMED 状态生效：页面被播放页盖住时自然停止，回到前台拿到当前状态继续。
     * 播放页不调用，所以看片时不会被安装弹窗打断。
     */
    fun attach(activity: AppCompatActivity, onState: (UpdateManager.State) -> Unit = {}) {
        if (!activity.isAlive()) return
        activity.lifecycleScope.launch {
            activity.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                UpdateManager.state.collect { state ->
                    if (activity.isAlive()) {
                        reportFailure(activity)
                        resumePendingInstall(activity)
                        onState(state)
                        if (state is UpdateManager.State.Ready) showInstallPrompt(activity)
                    }
                }
            }
        }
    }

    /**
     * @param manual true：用户主动点「检查更新」，失败/已最新都给出提示；
     *               false：启动时静默检查，只在发现新版本时弹窗
     */
    fun check(activity: AppCompatActivity, manual: Boolean) {
        if (checking || !activity.isAlive()) return
        checking = true
        if (manual) activity.toast(R.string.update_checking)

        activity.lifecycleScope.launch {
            try {
                val release = UpdateChecker.latest()
                // 拿到响应（含「仓库还没发布过」）才记时间；断网不记，下次进主页会再试
                SettingsRepository.lastUpdateCheck = System.currentTimeMillis()
                val local = UpdateInstaller.appVersion(activity)
                if (release == null || !UpdateChecker.isNewer(release.version, local)) {
                    if (manual) activity.toast(activity.getString(R.string.update_latest, local))
                    return@launch
                }
                if (UpdateManager.isDownloading(release.version)) {
                    if (manual) {
                        val percent = (UpdateManager.state.value as? UpdateManager.State.Downloading)
                            ?.percent ?: 0
                        activity.toast(
                            activity.getString(
                                R.string.update_already_downloading,
                                release.version,
                                percent,
                            )
                        )
                    }
                    return@launch
                }
                logInfo("发现新版本 v${release.version}（当前 v$local）")
                if (activity.isAlive()) showUpdateDialog(activity, release, local)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logError("检查更新失败", e)
                if (manual && activity.isAlive()) {
                    val reason = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
                    activity.toast(activity.getString(R.string.update_check_failed, reason))
                }
            } finally {
                checking = false
            }
        }
    }

    /**
     * 设置页「检查更新」：按当前状态分流。
     *
     * 正在后台下载 → 显示进度（可取消）；已下好 → 直接问要不要装；否则去检查新版本。
     */
    fun manualCheck(activity: AppCompatActivity) {
        val state = UpdateManager.state.value
        if (state is UpdateManager.State.Downloading) {
            showDownloadingDialog(activity, state)
            return
        }
        if (installCached(activity)) return
        check(activity, manual = true)
    }

    private fun showUpdateDialog(
        activity: AppCompatActivity,
        release: UpdateChecker.Release,
        local: String,
    ) {
        val notes = release.notes
            .replace("\r\n", "\n")
            .trim()
            .let { if (it.length > NOTES_LIMIT) it.take(NOTES_LIMIT).trimEnd() + "…" else it }
        // 上次下好但没装成（比如停在授权页 / 用户点了稍后）：这次点「立即更新」直接装
        val cached = SettingsRepository.downloadedUpdateVersion == release.version &&
            UpdateInstaller.downloadedApk(activity) != null
        val message = buildString {
            append(activity.getString(R.string.update_version_line, local, release.version))
            if (release.apkSize > 0) {
                append("　").append(formatSize(release.apkSize))
            }
            if (notes.isNotEmpty()) {
                append("\n\n").append(notes)
            }
            append("\n\n").append(
                activity.getString(
                    if (cached) R.string.update_cached_hint else R.string.update_background_hint
                )
            )
            append("\n").append(
                activity.getString(R.string.update_auto_dismiss_hint, UPDATE_DIALOG_TIMEOUT_MS / 1000)
            )
        }
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(activity.getString(R.string.update_found_title, release.version))
            .setMessage(message)
            .setPositiveButton(R.string.update_now) { _, _ -> proceed(activity, release) }
            .setNegativeButton(R.string.update_later, null)
            .create()
        showTracked(activity, dialog, UPDATE_DIALOG_TIMEOUT_MS)
    }

    /** 已经下好且大小对得上就直接装，省一次 12MB 下载（比如上次停在授权页） */
    private fun proceed(activity: AppCompatActivity, release: UpdateChecker.Release) {
        val apk = UpdateInstaller.downloadedApk(activity)
        if (apk != null &&
            SettingsRepository.downloadedUpdateVersion == release.version &&
            (release.apkSize <= 0 || apk.length() == release.apkSize)
        ) {
            install(activity, apk, release.version)
            return
        }
        // 后台下载：立刻返回，用户可以继续浏览 / 播放，下完由 attach 弹安装提示
        if (UpdateManager.start(activity, release)) {
            activity.toast(activity.getString(R.string.update_download_started, release.version))
        }
    }

    /** 有已下载好的包就问一句要不要装；没有则返回 false */
    private fun installCached(activity: AppCompatActivity): Boolean {
        val apk = UpdateInstaller.downloadedApk(activity) ?: return false
        val version = SettingsRepository.downloadedUpdateVersion
        if (version.isEmpty()) return false
        if (!UpdateChecker.isNewer(version, UpdateInstaller.appVersion(activity))) return false
        confirmInstall(activity, apk, version)
        return true
    }

    /** 下载完成后的安装提示（每次下载只弹一次，见 [UpdateManager.takeInstallPrompt]） */
    private fun showInstallPrompt(activity: AppCompatActivity) {
        if (!activity.isAlive()) return
        val ready = UpdateManager.takeInstallPrompt() ?: return
        // 包被系统清缓存删掉时别再弹，下次检查更新会重新下
        if (!ready.apk.isFile) return
        confirmInstall(activity, ready.apk, ready.version)
    }

    private fun confirmInstall(activity: AppCompatActivity, apk: File, version: String) {
        if (!activity.isAlive()) return
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(activity.getString(R.string.update_ready_title, version))
            .setMessage(
                activity.getString(
                    R.string.update_ready_message,
                    formatSize(apk.length()),
                    UPDATE_DIALOG_TIMEOUT_MS / 1000,
                )
            )
            .setPositiveButton(R.string.update_install_now) { _, _ ->
                install(activity, apk, version)
            }
            .setNegativeButton(R.string.update_later, null)
            .create()
        showTracked(activity, dialog, UPDATE_DIALOG_TIMEOUT_MS)
    }

    /** 后台下载中的进度框：可查看到哪了，也可直接取消；下载结束（成功/失败/取消）自动关掉 */
    private fun showDownloadingDialog(
        activity: AppCompatActivity,
        state: UpdateManager.State.Downloading,
    ) {
        if (!activity.isAlive()) return
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(activity.getString(R.string.update_downloading_title, state.version))
            .setMessage(activity.getString(R.string.update_downloading_message, state.percent))
            .setPositiveButton(R.string.update_keep_downloading, null)
            .setNegativeButton(R.string.update_cancel_download) { _, _ ->
                UpdateManager.cancel()
                if (activity.isAlive()) activity.toast(R.string.update_download_cancelled)
            }
            .create()

        // 进度框开着的时候下载可能刚好结束：跟着状态刷新文案，并自动收起，
        // 免得留下一个「已完成 45%」的旧框，还能点取消把刚下好的包作废
        val watcher = activity.lifecycleScope.launch {
            UpdateManager.state.collect { current ->
                if (dialog.isShowing) {
                    if (current is UpdateManager.State.Downloading &&
                        current.version == state.version
                    ) {
                        dialog.setMessage(
                            activity.getString(R.string.update_downloading_message, current.percent)
                        )
                    } else {
                        dialog.dismiss()
                    }
                }
            }
        }
        showTracked(activity, dialog, timeoutMs = null) { watcher.cancel() }
    }

    private fun reportFailure(activity: AppCompatActivity) {
        val failed = UpdateManager.takeFailure() ?: return
        activity.toast(activity.getString(R.string.update_download_failed, failed.reason))
    }

    /**
     * 显示对话框，可选「无操作自动收起」。
     *
     * @param timeoutMs 非 null 时无操作到点自动收起（等同「稍后」）：
     *                  电视经常开着没人按，提示框不该一直占屏、抢焦点。
     *                  任意按键刷新计时；协程绑定页面生命周期，页面销毁自动取消。
     * @param onDismiss 对话框关闭时额外要做的收尾（如停掉进度框的状态监听）
     */
    private fun showTracked(
        activity: AppCompatActivity,
        dialog: AlertDialog,
        timeoutMs: Long?,
        onDismiss: () -> Unit = {},
    ) {
        var lastInteractionMs = SystemClock.elapsedRealtime()
        val timer = timeoutMs?.let { limit ->
            activity.lifecycleScope.launch {
                while (isActive && dialog.isShowing) {
                    val remaining = limit - (SystemClock.elapsedRealtime() - lastInteractionMs)
                    if (remaining <= 0) {
                        dialog.dismiss()
                        break
                    }
                    delay(remaining)
                }
            }
        }
        dialog.setOnKeyListener { _, _, _ ->
            lastInteractionMs = SystemClock.elapsedRealtime()
            false // 不消费按键，方向键 / 确认键照常操作对话框
        }
        dialog.setOnDismissListener {
            timer?.cancel()
            onDismiss()
        }
        dialog.show()
    }

    private fun install(activity: AppCompatActivity, apk: File, version: String) {
        // 记下待装的包：用户去系统设置开权限、回来时自动接着装，不用再点一次「检查更新」
        pendingApk = apk
        pendingVersion = version
        if (UpdateInstaller.canInstall(activity)) {
            launchPendingInstall(activity)
            return
        }
        showPermissionDialog(activity)
    }

    /**
     * 没有安装权限时的引导。
     *
     * 分两种机器：
     * - 有「安装未知应用」按应用授权页（原生 Android TV / 大部分盒子）：引导去设置；
     * - 没有该页面（小米电视等）：只有一个系统级开关，[UpdateInstaller.canInstall] 永远 false，
     *   这时「去设置」会跳空，改成告诉用户具体菜单路径，并允许直接调安装器。
     */
    private fun showPermissionDialog(activity: AppCompatActivity) {
        val hasPage = UpdateInstaller.hasUnknownSourcesSettings(activity)
        val builder = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.update_permission_title)
            .setNegativeButton(R.string.server_cancel) { _, _ -> clearPendingInstall() }
        if (hasPage) {
            builder.setMessage(
                activity.getString(
                    R.string.update_permission_message,
                    activity.getString(R.string.app_name),
                )
            )
            builder.setPositiveButton(R.string.update_permission_open) { _, _ ->
                if (!UpdateInstaller.openInstallPermissionSettings(activity)) {
                    activity.toast(R.string.update_permission_not_found)
                }
            }
        } else {
            builder.setMessage(R.string.update_permission_message_tv)
            builder.setPositiveButton(R.string.update_permission_try_install) { _, _ ->
                launchPendingInstall(activity)
            }
            builder.setNeutralButton(R.string.update_permission_system_settings) { _, _ ->
                if (!UpdateInstaller.openSystemSettings(activity)) {
                    activity.toast(R.string.update_permission_not_found)
                }
            }
        }
        builder.show()
    }

    /** 调起系统安装器；调用后清掉待装信息，避免从设置页回来又弹一次 */
    private fun launchPendingInstall(activity: AppCompatActivity): Boolean {
        val apk = pendingApk ?: return false
        val version = pendingVersion
        clearPendingInstall()
        if (!apk.isFile) {
            activity.toast(R.string.update_install_failed)
            return false
        }
        if (UpdateInstaller.install(activity, apk, version)) {
            activity.toast(R.string.update_installing)
            return true
        }
        activity.toast(R.string.update_install_failed)
        return false
    }

    private fun clearPendingInstall() {
        pendingApk = null
        pendingVersion = ""
    }

    /** 从系统设置回来：权限已经打开就自动继续安装 */
    private fun resumePendingInstall(activity: AppCompatActivity) {
        if (pendingApk == null) return
        if (UpdateInstaller.canInstall(activity)) {
            logInfo("安装权限已打开，继续安装待装包")
            launchPendingInstall(activity)
            return
        }
        // 小米电视这类没有授权页的机器：刚从设置回来，再把「直接安装」递到手上
        if (!UpdateInstaller.hasUnknownSourcesSettings(activity)) showPermissionDialog(activity)
    }
}
