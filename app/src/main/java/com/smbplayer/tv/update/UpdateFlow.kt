package com.smbplayer.tv.update

import android.os.SystemClock
import android.view.LayoutInflater
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.smbplayer.tv.R
import com.smbplayer.tv.data.SettingsRepository
import com.smbplayer.tv.util.formatSize
import com.smbplayer.tv.util.isAlive
import com.smbplayer.tv.util.logError
import com.smbplayer.tv.util.logInfo
import com.smbplayer.tv.util.toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * 检查 → 提示 → 下载 → 安装 的完整流程（主页自动检查 + 设置页手动检查共用）。
 *
 * 电视上没有应用商店，用户也不会去点浏览器下载 APK，
 * 所以这里把「发现新版本」做成一个对话框：确认后应用自己下、自己调起安装器。
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

    /** 主页 onResume 调用：节流后静默检查，失败不打扰用户 */
    fun autoCheck(activity: AppCompatActivity) {
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

    private fun showUpdateDialog(
        activity: AppCompatActivity,
        release: UpdateChecker.Release,
        local: String,
    ) {
        val notes = release.notes
            .replace("\r\n", "\n")
            .trim()
            .let { if (it.length > NOTES_LIMIT) it.take(NOTES_LIMIT).trimEnd() + "…" else it }
        val message = buildString {
            append(activity.getString(R.string.update_version_line, local, release.version))
            if (release.apkSize > 0) {
                append("　").append(formatSize(release.apkSize))
            }
            if (notes.isNotEmpty()) {
                append("\n\n").append(notes)
            }
            append("\n\n").append(
                activity.getString(R.string.update_auto_dismiss_hint, UPDATE_DIALOG_TIMEOUT_MS / 1000)
            )
        }
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(activity.getString(R.string.update_found_title, release.version))
            .setMessage(message)
            .setPositiveButton(R.string.update_now) { _, _ -> proceed(activity, release) }
            .setNegativeButton(R.string.update_later, null)
            .show()

        // 电视经常开着没人按：无操作到点自动收起（等同「稍后」），避免提示框一直占屏。
        // 任意按键刷新计时；协程绑定 Activity 生命周期，页面销毁自动取消，不留后台定时器。
        var lastInteractionMs = SystemClock.elapsedRealtime()
        val timeoutJob = activity.lifecycleScope.launch {
            while (isActive && dialog.isShowing) {
                val remaining = UPDATE_DIALOG_TIMEOUT_MS -
                    (SystemClock.elapsedRealtime() - lastInteractionMs)
                if (remaining <= 0) {
                    dialog.dismiss()
                    break
                }
                delay(remaining)
            }
        }
        dialog.setOnKeyListener { _, _, _ ->
            lastInteractionMs = SystemClock.elapsedRealtime()
            false // 不消费按键，方向键 / 确认键照常操作对话框
        }
        dialog.setOnDismissListener { timeoutJob.cancel() }
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
        download(activity, release)
    }

    private fun download(activity: AppCompatActivity, release: UpdateChecker.Release) {
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_update_progress, null)
        val bar = view.findViewById<LinearProgressIndicator>(R.id.update_progress)
        val label = view.findViewById<TextView>(R.id.tv_update_progress)
        label.text = activity.getString(R.string.update_download_progress, 0)

        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(activity.getString(R.string.update_downloading_title, release.version))
            .setView(view)
            .setCancelable(false)
            .create()
        dialog.show()

        activity.lifecycleScope.launch {
            try {
                val apk: File = UpdateDownloader.download(activity, release) { percent ->
                    activity.runOnUiThread {
                        if (activity.isAlive() && dialog.isShowing) {
                            bar.progress = percent
                            label.text = activity.getString(R.string.update_download_progress, percent)
                        }
                    }
                }
                SettingsRepository.downloadedUpdateVersion = release.version
                if (dialog.isShowing) dialog.dismiss()
                if (!activity.isAlive()) return@launch
                install(activity, apk, release.version)
            } catch (e: CancellationException) {
                if (dialog.isShowing) dialog.dismiss()
                throw e
            } catch (e: Exception) {
                if (dialog.isShowing) dialog.dismiss()
                logError("下载更新失败", e)
                if (activity.isAlive()) {
                    val reason = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
                    activity.toast(activity.getString(R.string.update_download_failed, reason))
                }
            }
        }
    }

    private fun install(activity: AppCompatActivity, apk: File, version: String) {
        if (!UpdateInstaller.canInstall(activity)) {
            // Android 8+ 必须在系统设置里允许「安装未知应用」，电视上没有别的入口
            MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.update_permission_title)
                .setMessage(
                    activity.getString(
                        R.string.update_permission_message,
                        activity.getString(R.string.app_name),
                    )
                )
                .setPositiveButton(R.string.update_permission_open) { _, _ ->
                    UpdateInstaller.openInstallPermissionSettings(activity)
                }
                .setNegativeButton(R.string.server_cancel, null)
                .show()
            return
        }
        if (UpdateInstaller.install(activity, apk, version)) {
            activity.toast(R.string.update_installing)
        } else {
            activity.toast(R.string.update_install_failed)
        }
    }
}
