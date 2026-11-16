package com.smbplayer.tv.update

import android.content.Context
import com.smbplayer.tv.data.SettingsRepository
import com.smbplayer.tv.util.logError
import com.smbplayer.tv.util.logInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * 后台更新下载器。
 *
 * GitHub 附件在国内经常只有几十 KB/s，一个 12MB 的包要下十几分钟。
 * 以前下载挂在一个「不可取消」的进度对话框上，绑着页面生命周期：
 * 用户想边下边看片，一进播放页下载就被取消了。
 *
 * 现在下载跑在**应用级协程**里（不绑任何页面），页面只是订阅 [state] 显示进度：
 * - 用户可以随便浏览、播放，甚至回主页待着；
 * - 下载完成后状态变 [State.Ready]，由前台的列表页弹「立即安装」；
 * - 播放页不订阅，所以看片途中不会被安装弹窗打断，退出播放页回到主页时再提示。
 *
 * 进程被杀（用户彻底退出应用）时下载会中断，未完成的 `.part` 文件在下次启动时清掉，
 * 下次检查更新从头再下——TV 上常驻的应用，这种情况很少。
 */
object UpdateManager {

    sealed class State {
        /** 没有下载任务 */
        object Idle : State()

        /** 正在后台下载，[percent] 为 0..100 */
        data class Downloading(val version: String, val percent: Int) : State()

        /** 已下好、可以直接安装 */
        data class Ready(val version: String, val apk: File, val size: Long) : State()

        /** 所有下载源都失败 */
        data class Failed(val version: String, val reason: String) : State()
    }

    /** 应用级作用域：不随任何 Activity 销毁而取消 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private var job: Job? = null

    /** 下载完成后的「安装」提示是否还没弹过 */
    @Volatile
    private var promptPending = false

    /** 下载失败是否还没提示过（只弹一次，别每次回主页都弹） */
    @Volatile
    private var failurePending = false

    fun isDownloading(version: String? = null): Boolean {
        val current = _state.value
        return current is State.Downloading && (version == null || current.version == version)
    }

    /**
     * 开始（或换一个版本重新开始）后台下载。
     *
     * @return false 表示该版本已经在下载中，没有重复发起
     */
    fun start(context: Context, release: UpdateChecker.Release): Boolean {
        if (isDownloading(release.version)) return false

        // 换版本时先停掉旧任务；旧协程的收尾逻辑会按版本号判断，不会覆盖新状态
        job?.cancel()
        job = null
        promptPending = false
        failurePending = false
        _state.value = State.Downloading(release.version, 0)
        logInfo("开始后台下载更新 v${release.version}")

        val app = context.applicationContext
        job = scope.launch {
            try {
                val apk = UpdateDownloader.download(app, release) { percent ->
                    val current = _state.value
                    if (current is State.Downloading && current.version == release.version) {
                        _state.value = current.copy(percent = percent)
                    }
                }
                // 先置标记再改状态：订阅方收到 Ready 时一定还能取到「待安装提示」
                promptPending = true
                SettingsRepository.downloadedUpdateVersion = release.version
                _state.value = State.Ready(release.version, apk, apk.length())
                logInfo("更新包下载完成：${apk.name}（${apk.length()} 字节）")
            } catch (e: CancellationException) {
                // 只有还停在本次任务上才清状态，避免把新任务的状态冲掉
                val current = _state.value
                if (current is State.Downloading && current.version == release.version) {
                    _state.value = State.Idle
                }
                throw e
            } catch (e: Exception) {
                failurePending = true
                val reason = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
                _state.value = State.Failed(release.version, reason)
                logError("后台下载更新失败", e)
            }
        }
        return true
    }

    /** 用户主动取消下载 */
    fun cancel() {
        val running = job
        job = null
        promptPending = false
        _state.value = State.Idle
        running?.cancel()
        logInfo("已取消更新下载")
    }

    /** 取一次「安装提示」：同一份下载只提示一次，取走后不再返回 */
    fun takeInstallPrompt(): State.Ready? {
        val ready = _state.value
        if (ready !is State.Ready || !promptPending) return null
        promptPending = false
        return ready
    }

    /** 取一次「下载失败」提示 */
    fun takeFailure(): State.Failed? {
        val failed = _state.value
        if (failed !is State.Failed || !failurePending) return null
        failurePending = false
        return failed
    }
}
