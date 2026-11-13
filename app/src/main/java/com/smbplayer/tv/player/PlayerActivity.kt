package com.smbplayer.tv.player

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.SeekBar
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.smbplayer.tv.R
import com.smbplayer.tv.data.PlaybackProgressRepository
import com.smbplayer.tv.data.ServerRepository
import com.smbplayer.tv.data.SettingsRepository
import com.smbplayer.tv.data.SmbServer
import com.smbplayer.tv.data.SpeedOptions
import com.smbplayer.tv.data.SubtitleFormats
import com.smbplayer.tv.data.SubtitleSizes
import com.smbplayer.tv.data.VideoFormats
import com.smbplayer.tv.databinding.ActivityPlayerBinding
import com.smbplayer.tv.databinding.DialogPlaylistBinding
import com.smbplayer.tv.smb.SmbGateway
import com.smbplayer.tv.smb.SmbReadHandle
import com.smbplayer.tv.smb.SmbSession
import com.smbplayer.tv.ui.PlaylistAdapter
import com.smbplayer.tv.ui.PlaylistItem
import com.smbplayer.tv.util.formatDuration
import com.smbplayer.tv.util.isAlive
import com.smbplayer.tv.util.joinPath
import com.smbplayer.tv.util.logError
import com.smbplayer.tv.util.logInfo
import com.smbplayer.tv.util.toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * 播放页（TV 版）：播放 [directory] 目录下的所有视频，遥控器全程操作。
 *
 * 键位（方向键语义由「上下文 + 按压时长」决定，参照 VLC / Compose TV 播放器的通行做法）：
 * - 弹窗（播放列表 / 倍速 / 删除 / 错误）显示时：方向键、确认键全部让位给弹窗焦点系统
 * - 控制条显示（按钮焦点模式）：左右键在按钮间移动焦点，上下键循环切换按钮，确认键点击按钮
 * - 控制条隐藏（画面模式）：左右键快退 / 快进，短按 10 秒；按住 ≥400ms 进入连跳
 *   （每 250ms 30 秒），实时预览落点，松开才真正 seek
 * - 确认键（画面模式）：播放 / 暂停；上下键 / 菜单键：呼出控制条并进入按钮焦点模式
 * - 菜单键：呼出 / 收起控制条（显式切换模式）
 * - 返回键：先收控制条，退出前二次确认（进度已自动保存）
 * - 媒体键（播放/暂停/快进/快退/上一集/下一集）直接支持；快进/快退媒体键不呼出控制条，
 *   免得刚快进完左右键的语义就变了
 *
 * 支持删除当前视频（删除后自动跳到下一个）、上一个/下一个、播放列表、自动加载同名字幕。
 */
@OptIn(markerClass = [UnstableApi::class])
class PlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlayerBinding
    private lateinit var server: SmbServer
    private lateinit var session: SmbSession

    private var shareName: String = ""
    private var directory: String = ""

    /** 从「最近播放」进入时指定的起始文件名；null 表示按普通方式从 index 开始 */
    private var startFile: String? = null

    /** 当前目录下的全部视频文件名（已排序） */
    private val videos = mutableListOf<String>()
    private var index: Int = 0

    /** 当前播放器里媒体的 SMB 路径：切集/删文件时列表索引会变，保存记录只认它 */
    private var currentVideoPath: String? = null

    /** 上次写入播放记录的位置，用于节流（每 5 秒最多写一次） */
    private var lastSavedProgressMs = -1L

    // ---- 播放错误自救：源文件个别帧损坏时跳过损坏片段继续播 ----

    /** 上次出错时的播放位置（毫秒），Long.MIN_VALUE 表示当前视频还没出过错 */
    private var errorRecoverPosition = Long.MIN_VALUE

    /** 同一位置附近连续自救的次数，播放顺利推进后归零 */
    private var errorRecoverCount = 0

    /**
     * 本次播放中用户是否主动调整过位置（快进快退 / 拖进度条）。
     * 用户明确选过的位置要以用户为准，不受「片头 10 秒不记录」「看完视频前 30 秒不覆盖」限制。
     */
    private var userAdjustedPosition = false

    /** 当前目录下的全部非目录文件名（用于在同级目录里找同名字幕） */
    private var allFileNames: Set<String> = emptySet()

    /** 当前视频的同名字幕（播前解析成 cue 列表，由叠加层渲染） */
    private var subtitleCues: List<SubtitleCue> = emptyList()

    /** 字幕字号档位（设置页可调，进播放页时读一次，改完设置重进播放页生效） */
    private var subtitleSizeLevel = SettingsRepository.subtitleSize

    /** 字幕叠加层刷新（100ms 一帧，cue 切换更及时） */
    private val subtitleTickRunnable = object : Runnable {
        override fun run() {
            updateSubtitleOverlay()
            mainHandler.postDelayed(this, SUBTITLE_TICK_MS)
        }
    }

    private var player: ExoPlayer? = null
    private var userSeeking = false
    private var playlistDialog: android.app.Dialog? = null

    /** 播放页常驻倍速，取自设置，切到下一集时继承 */
    private var normalSpeed = SettingsRepository.normalSpeed

    // ---------- 遥控器快进快退（预览式 seek）----------
    // 只在「控制条隐藏」的画面模式生效：按下先登记，短按松手提交 10 秒，
    // 按住 ≥ SEEK_LONG_PRESS_MS 才进入连跳。全程只累计偏移并实时预览落点，
    // 松开按键才真正 seek——网络流 seek 是一次昂贵的范围请求，不能每按一下就发一次。

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 本次按住期间累计的 seek 偏移量（毫秒，负值为快退） */
    private var pendingSeekOffsetMs = 0L

    /** 正在按住的 seek 方向键（DPAD_LEFT / DPAD_RIGHT），无按键按住时为 null */
    private var pendingSeekKeyCode: Int? = null

    /** 当前 seek 方向：+1 快进 / -1 快退 */
    private var pendingSeekDirection = 0

    /** 预览基准位置：按下方向键那一刻的播放位置 */
    private var pendingSeekBaseMs = 0L

    /** 是否已越过 400ms 门槛进入长按连跳 */
    private var seekHoldStarted = false

    /** 当前前台的模态弹窗：显示期间方向键全部让位给弹窗的焦点系统 */
    private var activeDialog: android.app.Dialog? = null

    /** 控制条是否处于显示模式（显式状态，避开滑出动画 200ms 的 visibility 中间态） */
    private var controlsShown = false

    /** 400ms 判定：到点仍未松手，把首步放大到长按步长并开始连跳 */
    private val seekLongPressRunnable = Runnable {
        if (pendingSeekKeyCode != null && !seekHoldStarted) {
            seekHoldStarted = true
            appendSeekOffset(pendingSeekDirection * (SEEK_HOLD_STEP_MS - SEEK_STEP_MS))
            mainHandler.postDelayed(seekHoldRunnable, SEEK_REPEAT_INTERVAL_MS)
        }
    }

    /** 长按连跳：按固定节奏步进，不跟系统 key repeat（约 20 次/秒太快） */
    private val seekHoldRunnable = object : Runnable {
        override fun run() {
            if (pendingSeekKeyCode == null || !seekHoldStarted) return
            appendSeekOffset(pendingSeekDirection * SEEK_HOLD_STEP_MS)
            mainHandler.postDelayed(this, SEEK_REPEAT_INTERVAL_MS)
        }
    }

    /** 居中提示自动隐藏 */
    private val hideHintRunnable = Runnable { binding.tvHint.visibility = View.GONE }

    /** 底部显示一条操作提示（不挡画面），稍后自动隐藏 */
    private fun showHint(text: String) {
        binding.tvHint.text = text
        binding.tvHint.visibility = View.VISIBLE
        // 字幕可能正在显示：提示条要落在字幕上方，不能压在字幕上
        setBottomMarginPx(binding.tvHint, hintBottomPx(binding.topBar.visibility == View.VISIBLE))
        mainHandler.removeCallbacks(hideHintRunnable)
        mainHandler.postDelayed(hideHintRunnable, HINT_HIDE_DELAY_MS)
    }

    private val tickRunnable = object : Runnable {
        override fun run() {
            // 播放中每 5 秒落一次播放记录（saveCurrentProgress 内部节流）
            saveCurrentProgress()
            // 预览式 seek 期间不自动刷新：seek 后的实际位置有延迟，
            // tick 会把进度条拉回旧位置，和预览落点互相覆盖造成抖动
            if (pendingSeekKeyCode == null) updateProgress()
            mainHandler.postDelayed(this, TICK_INTERVAL_MS)
        }
    }

    private val hideControlsRunnable = Runnable {
        // 焦点还在控制条按钮上时不自动隐藏（遥控器正操作按钮，藏了会丢焦点）
        if (player?.isPlaying == true && !userSeeking && pendingSeekKeyCode == null && !isFinishing) {
            if (controlsHasFocus()) {
                scheduleHideControls()
            } else {
                hideControls()
            }
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            binding.progress.visibility =
                if (playbackState == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
            if (playbackState == Player.STATE_ENDED) {
                // 播放到结尾：先把当前视频记成「已看完」，进度线才会走到底
                markCurrentCompleted()
                playNext(fromUser = false)
            }
            updateControls()
            if (playbackState == Player.STATE_READY) scheduleHideControls()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            updateControls()
            if (isPlaying) scheduleHideControls()
        }

        override fun onPlayerError(error: PlaybackException) {
            binding.progress.visibility = View.GONE
            // 源文件个别帧损坏：先跳过损坏片段继续播，实在救不回来才弹窗
            if (recoverFromError(error)) return
            showPlaybackError(error)
        }
    }

    // ------------------------------------------------------------------ 生命周期

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        logInfo("PlayerActivity onCreate")
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySubtitleStyle()

        setupPlayer()
        setupControls()

        // 返回键：控制条显示时先收起；退出播放需连按两次（不弹窗，遥控器误触也不会退出）
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (controlsShown) {
                    hideControls()
                    return
                }
                if (exitConfirmPending) {
                    exitConfirmPending = false
                    finish()
                    return
                }
                exitConfirmPending = true
                showHint(getString(R.string.exit_twice_hint))
                mainHandler.postDelayed(exitConfirmReset, EXIT_CONFIRM_WINDOW_MS)
            }
        })

        val serverId = intent.getStringExtra(EXTRA_SERVER_ID)
        val loaded = serverId?.let { ServerRepository.get(it) }
        if (loaded == null) {
            logError("服务器不存在: $serverId")
            finish()
            return
        }
        server = loaded
        session = SmbGateway.session(server)
        shareName = intent.getStringExtra(EXTRA_SHARE).orEmpty()
        directory = intent.getStringExtra(EXTRA_DIR).orEmpty()
        index = intent.getIntExtra(EXTRA_INDEX, 0)
        startFile = intent.getStringExtra(EXTRA_START_FILE)?.takeIf { it.isNotBlank() }

        logInfo("播放: 服务器=${server.host} 共享=$shareName 目录=$directory 索引=$index 起始文件=$startFile")

        if (shareName.isBlank()) {
            logError("共享名为空")
            toast(R.string.player_no_video)
            finish()
            return
        }
        loadPlaylist()
    }

    override fun onStart() {
        super.onStart()
        mainHandler.postDelayed(tickRunnable, TICK_INTERVAL_MS)
        // 有字幕就恢复叠加层刷新（后台播放时也保持，暂停状态下 cue 不变，开销可忽略）
        if (subtitleCues.isNotEmpty()) {
            mainHandler.removeCallbacks(subtitleTickRunnable)
            mainHandler.post(subtitleTickRunnable)
        }
    }

    override fun onPause() {
        super.onPause()
        // 切后台 / 息屏前落一次记录，避免进程被回收时丢掉最后一段进度
        flushPendingSeek()
        saveCurrentProgress(force = true)
    }

    override fun onStop() {
        super.onStop()
        saveCurrentProgress(force = true)
        // 关闭后台播放时，离开播放页（息屏 / 切后台）就暂停，避免后台出声
        if (!SettingsRepository.backgroundPlay) {
            player?.pause()
        }
        mainHandler.removeCallbacks(tickRunnable)
        mainHandler.removeCallbacks(hideControlsRunnable)
        mainHandler.removeCallbacks(subtitleTickRunnable)
    }

    override fun onDestroy() {
        saveCurrentProgress(force = true)
        mainHandler.removeCallbacksAndMessages(null)
        player?.let { p ->
            p.removeListener(playerListener)
            p.release()
        }
        player = null
        super.onDestroy()
    }

    // ------------------------------------------------------------------ 遥控器按键

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // 模态弹窗（播放列表 / 倍速 / 删除 / 错误）在前台时全部让位：
        // 方向键、确认键交给弹窗的焦点系统，Activity 不抢按键。
        // 对应 VLC 的 isOptionsListShowing / isPlaybackSettingActive 直接 return false。
        if (isDialogBlockingKeys()) return super.onKeyDown(keyCode, event)

        when (keyCode) {
            // 确认键：焦点在控制条按钮上时交给系统点击按钮；否则只切换播放/暂停，
            // 不呼出控制条（控制条用菜单键 / 上下键召唤），避免把焦点带进按钮区
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (event?.repeatCount == 0) {
                    if (controlsHasFocus()) return super.onKeyDown(keyCode, event)
                    togglePlayPause()
                }
                return true
            }

            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (controlsShown) {
                    // 按钮焦点模式：左右键专职在按钮间移动焦点（交回系统焦点导航）。
                    // 控制条可见但焦点还留在画面上时，先落到播放/暂停按钮，
                    // 否则系统找不到焦点目标，按键看起来没反应。
                    if (!controlsHasFocus()) binding.btnPlayPause.requestFocus()
                    scheduleHideControls()
                    return super.onKeyDown(keyCode, event)
                }
                // 画面模式：预览式 seek。只在首次 KeyDown 登记任务，系统 key repeat
                // 只吞掉不叠加（步进由 400ms / 250ms 定时器驱动，遥控器不发重复键也能连跳）。
                if (event?.repeatCount == 0) startPendingSeek(keyCode)
                return true
            }

            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                // 上下键是进入按钮焦点模式的显式入口：隐藏时呼出控制条并落到主按钮，
                // 显示时在按钮之间循环（几何焦点搜索在电视上方向语义不好用）。
                if (!controlsShown) {
                    showControls()
                    binding.btnPlayPause.requestFocus()
                    return true
                }
                moveControlFocus(forward = keyCode == KeyEvent.KEYCODE_DPAD_DOWN)
                return true
            }

            KeyEvent.KEYCODE_MENU -> {
                if (event?.repeatCount == 0) {
                    if (controlsShown) hideControls() else showControls(focusPlayPause = true)
                }
                return true
            }

            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                if (event?.repeatCount == 0) togglePlayPause()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                if (event?.repeatCount == 0) player?.play()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                if (event?.repeatCount == 0) player?.pause()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                // 不呼出控制条：否则刚快进完，左右键就从「快进」变成「切按钮焦点」
                seekBy(SEEK_STEP_MS, revealControls = false)
                return true
            }
            KeyEvent.KEYCODE_MEDIA_REWIND -> {
                seekBy(-SEEK_STEP_MS, revealControls = false)
                return true
            }
            KeyEvent.KEYCODE_MEDIA_NEXT -> {
                if (event?.repeatCount == 0) playNext(fromUser = true)
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                if (event?.repeatCount == 0) playPrev()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (isDialogBlockingKeys()) return super.onKeyUp(keyCode, event)
        // 松开方向键：短按（未达 400ms）与长按连跳都在这里一次性提交
        if (keyCode == pendingSeekKeyCode && (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)) {
            flushPendingSeek()
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    /**
     * 画面模式按下左右键：登记一次「待寻道」任务并立即预览单步落点。
     *
     * 400ms 内松手 = 短按，提交 [SEEK_STEP_MS]（10 秒）；
     * 到点仍未松手 = 长按，[seekLongPressRunnable] 把首步放大到 [SEEK_HOLD_STEP_MS]
     * 并开始每 [SEEK_REPEAT_INTERVAL_MS] 一步的连跳。
     * 用计时器而非系统 key repeat 驱动，遥控器不发重复事件也能连跳。
     */
    private fun startPendingSeek(keyCode: Int) {
        val p = player ?: return
        cancelSeekTimers()
        pendingSeekKeyCode = keyCode
        pendingSeekDirection = if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1
        pendingSeekOffsetMs = 0L
        pendingSeekBaseMs = p.currentPosition
        seekHoldStarted = false
        // 刻意不呼出控制条：左右键只负责快进/快退，控制条一出现就进入按钮焦点模式
        appendSeekOffset(pendingSeekDirection * SEEK_STEP_MS)
        mainHandler.postDelayed(seekLongPressRunnable, SEEK_LONG_PRESS_MS)
    }

    /** 追加一帧预览偏移（只改进度条 / 时间 / 提示，不碰播放器位置） */
    private fun appendSeekOffset(deltaMs: Long) {
        pendingSeekOffsetMs += deltaMs
        val duration = player?.duration ?: 0L
        val target = pendingSeekBaseMs + pendingSeekOffsetMs
        val clamped = if (duration > 0) target.coerceIn(0L, duration) else target.coerceAtLeast(0L)
        if (duration > 0) {
            binding.seekProgress.progress = (clamped * 1000 / duration).toInt().coerceIn(0, 1000)
        }
        binding.tvPosition.text = formatDuration(clamped)
        val hint = if (deltaMs > 0) R.string.tv_hint_seek_preview else R.string.tv_hint_rew_preview
        showHint(getString(hint, formatDuration(clamped)))
    }

    /** 取消 400ms 判定与连跳定时器（换方向 / 松手 / 打开弹窗 / 切集时都要调用） */
    private fun cancelSeekTimers() {
        mainHandler.removeCallbacks(seekLongPressRunnable)
        mainHandler.removeCallbacks(seekHoldRunnable)
    }

    /** 松开按键 / 离开页面 / 打开弹窗：把累计的偏移量真正 seek 过去 */
    private fun flushPendingSeek() {
        if (pendingSeekKeyCode == null) return
        cancelSeekTimers()
        pendingSeekKeyCode = null
        seekHoldStarted = false
        pendingSeekDirection = 0
        val p = player ?: run { pendingSeekOffsetMs = 0L; return }
        if (pendingSeekOffsetMs == 0L) return
        val duration = p.duration
        val target = pendingSeekBaseMs + pendingSeekOffsetMs
        pendingSeekOffsetMs = 0L
        userAdjustedPosition = true
        p.seekTo(if (duration > 0) target.coerceIn(0L, duration) else target.coerceAtLeast(0L))
        updateProgress()
        scheduleHideControls()
    }

    /** 焦点是否在控制条按钮上 */
    private fun controlsHasFocus(): Boolean =
        binding.topBar.findFocus() != null || binding.bottomBar.findFocus() != null

    /** 是否有模态弹窗在前台（显示期间按键全部让位） */
    private fun isDialogBlockingKeys(): Boolean = activeDialog?.isShowing == true

    /**
     * 统一创建并展示播放页弹窗：登记 [activeDialog]、dismiss 后自动解绑。
     * 打开前先把还没提交的预览 seek 落定——否则弹窗会截走 KeyUp，偏移量会悬空。
     */
    private fun showPlayerDialog(builder: MaterialAlertDialogBuilder): android.app.Dialog {
        flushPendingSeek()
        val dlg = builder.create()
        dlg.setOnDismissListener { if (activeDialog === dlg) activeDialog = null }
        activeDialog = dlg
        dlg.show()
        return dlg
    }

    // ------------------------------------------------------------------ 音量控制

    /**
     * 在控制条按钮之间切换焦点（播放页上下键）。
     *
     * 底栏按钮是横向排列的，几何焦点搜索在电视上方向语义不好用，
     * 这里按「顶栏按钮 → 底栏按钮」的视觉顺序手动循环；
     * 焦点还不在控制条里时，从播放/暂停按钮开始。
     */
    private fun moveControlFocus(forward: Boolean): Boolean {
        val controls = mutableListOf<View>()
        collectFocusables(binding.topBar, controls)
        collectFocusables(binding.bottomBar, controls)
        if (controls.isEmpty()) return false
        val current = currentFocus
        val index = controls.indexOfFirst { it === current }
        if (index < 0) return binding.btnPlayPause.requestFocus()
        val next = if (forward) {
            (index + 1) % controls.size
        } else {
            (index - 1 + controls.size) % controls.size
        }
        return controls[next].requestFocus()
    }

    private fun collectFocusables(root: View, out: MutableList<View>) {
        if (root.visibility != View.VISIBLE) return
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) collectFocusables(root.getChildAt(i), out)
        } else if (root.isFocusable && root.isEnabled) {
            out.add(root)
        }
    }

    // ------------------------------------------------------------------ 退出确认

    /**
     * 退出前的第二次返回确认窗口：「再按一次返回退出」提示后，
     * 超过该时长未再按返回则自动取消确认状态。
     */
    private var exitConfirmPending = false
    private val exitConfirmReset = Runnable { exitConfirmPending = false }

    // ------------------------------------------------------------------ 删除当前视频

    private fun confirmDeleteCurrent() {
        val name = videos.getOrNull(index) ?: return
        showPlayerDialog(
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.delete_title)
                .setMessage(getString(R.string.delete_file_message, name))
                .setPositiveButton(R.string.delete_confirm) { _, _ -> deleteCurrent(name) }
                .setNegativeButton(R.string.server_cancel, null)
        )
    }

    /** 删除当前视频后自动跳到下一个；没有下一个则退出播放页 */
    private fun deleteCurrent(name: String) {
        val path = pathOf(name)
        val resumePosition = player?.currentPosition ?: 0L
        // 先停止播放、释放 SMB 读句柄：句柄还开着时删除只是「删除挂起」，
        // 文件仍占着目录，之后连整个文件夹都会删不掉（STATUS_DIRECTORY_NOT_EMPTY）
        player?.stop()
        lifecycleScope.launch {
            try {
                logInfo("删除视频: $path")
                // SmbSession.delete 内部已带「占用重试 + 兼容模式删除」，
                // 句柄释放慢导致的 STATUS_SHARING_VIOLATION 会自动重删
                withContext(Dispatchers.IO) { session.delete(shareName, path, false) }
                // 文件已经不存在，对应的播放记录一并清掉
                PlaybackProgressRepository.remove(server.id, shareName, path)
                if (currentVideoPath == path) currentVideoPath = null
                toast(getString(R.string.deleted, name))
                videos.removeAt(index)
                if (videos.isEmpty()) {
                    finish()
                    return@launch
                }
                index = index.coerceIn(0, videos.lastIndex)
                playAt(index)
            } catch (e: CancellationException) {
                throw e // 用户离开播放页：正常取消，别在原位置续播（player 可能已释放）
            } catch (e: Exception) {
                logError("删除视频失败: $path", e)
                toast(getString(R.string.player_delete_failed, e.message ?: e.toString()))
                // 删除失败，从原位置继续播放
                playAt(index, startPositionMs = resumePosition)
            }
        }
    }

    // ------------------------------------------------------------------ 播放列表

    private fun loadPlaylist() {
        binding.progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                logInfo("加载播放列表: 共享=$shareName 目录=$directory")
                val entries = withContext(Dispatchers.IO) {
                    session.list(shareName, directory)
                }
                // 记下同级目录里全部非目录文件名，供「同名字幕」查找（不必再为字幕多发一次列举请求）
                allFileNames = entries
                    .filter { !it.isDirectory }
                    .map { it.name }
                    .toSet()
                val list = entries
                    .filter { !it.isDirectory && VideoFormats.isVideo(it.name) }
                    .map { it.name }
                    .sortedWith(String.CASE_INSENSITIVE_ORDER)
                    .toList()
                videos.clear()
                videos.addAll(list)
                if (videos.isEmpty()) {
                    binding.progress.visibility = View.GONE
                    logError("播放列表为空: 共享=$shareName 目录=$directory")
                    toast(R.string.player_no_video)
                    finish()
                    return@launch
                }
                // 从「最近播放」进入时按文件名定位起始视频（忽略大小写；找不到再退回传入索引）
                val wanted = startFile
                if (!wanted.isNullOrEmpty()) {
                    val byName = videos.indexOfFirst { it.equals(wanted, ignoreCase = true) }
                    if (byName >= 0) {
                        index = byName
                    } else {
                        logError("起始文件不在当前目录: $wanted")
                    }
                }
                index = index.coerceIn(0, videos.lastIndex)
                logInfo("播放列表加载完成: ${list.size} 个视频")
                playAt(index)
            } catch (e: CancellationException) {
                throw e // 用户离开播放页：正常取消
            } catch (e: Exception) {
                binding.progress.visibility = View.GONE
                logError("加载播放列表失败: 共享=$shareName 目录=$directory", e)
                toast(getString(R.string.player_error, e.message ?: e.toString()))
                finish()
            }
        }
    }

    private fun pathOf(name: String) = joinPath(directory, name)

    /**
     * 仅用于展示的 SMB URI。路径段按 URI 规则转义（`|`、`#`、`?`、空格、中文等），
     * 否则 Uri 里出现非法字符会让后续解析/展示出错。真正的读写用 [pathOf] 的原始路径。
     */
    private fun displayUri(name: String): android.net.Uri {
        val encodedPath = pathOf(name)
            .split("/")
            .filter { it.isNotEmpty() }
            .joinToString("/") { android.net.Uri.encode(it) }
        return android.net.Uri.parse("smb://${server.host}/$shareName/$encodedPath")
    }

    private fun playAt(newIndex: Int, startPositionMs: Long? = null) {
        if (newIndex !in videos.indices) {
            logError("播放索引越界: $newIndex, 列表大小=${videos.size}")
            return
        }
        // 切走之前先把当前视频的播放进度记下来
        flushPendingSeek()
        saveCurrentProgress(force = true)
        currentVideoPath = null
        userAdjustedPosition = false

        index = newIndex
        val name = videos[newIndex]
        // 换视频：错误自救的计数归零（上一部的好坏跟这一部无关）
        errorRecoverPosition = Long.MIN_VALUE
        errorRecoverCount = 0
        // 没指定起点时接着上次的播放记录：看完的视频从头重播
        val resumePosition = startPositionMs
            ?: PlaybackProgressRepository.get(server.id, shareName, pathOf(name))?.resumePositionMs
            ?: 0L
        lastSavedProgressMs = resumePosition
        logInfo("开始播放: $name 位置=${resumePosition}ms")

        // 换视频：作废上一条字幕的刷新
        mainHandler.removeCallbacks(subtitleTickRunnable)

        // 先同步更新标题/进度等 UI；字幕的 SMB 读取放 IO 线程，避免卡主线程
        binding.tvTitle.text = name
        binding.tvSubtitle.text = getString(
            R.string.player_title_format,
            newIndex + 1,
            videos.size,
            if (directory.isEmpty()) "/" else directory
        )
        binding.tvError.visibility = View.GONE
        binding.progress.visibility = View.VISIBLE
        updateControls()
        showControls()

        lifecycleScope.launch {
            try {
                // 自动加载视频同名字幕：在同级目录找同名（或带语言标签）的 .srt/.ass 等文件，
                // 播前在 IO 线程整体读入内存并解析成 cue 列表，由播放页叠加层渲染。
                // 字幕只做「锦上花」：读取/解析失败都按无字幕播放，绝不影响视频本身。
                var subtitleError: String? = null
                val subtitleName = SubtitleFormats.findSubtitle(name, allFileNames)
                val cues = if (subtitleName != null) {
                    withContext(Dispatchers.IO) {
                        try {
                            val bytes = readSubtitleBytes(subtitleName)
                            SubtitleTextParser.parse(subtitleName, bytes)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            logError("字幕加载失败，按无字幕播放: $subtitleName", e)
                            subtitleError = subtitleName
                            emptyList()
                        }
                    }
                } else {
                    emptyList()
                }

                val smbUri = displayUri(name)
                val factory = SmbDataSource.Factory(session, shareName, pathOf(name), smbUri)
                val videoSource = ProgressiveMediaSource.Factory(factory, DefaultExtractorsFactory())
                    .createMediaSource(MediaItem.fromUri(smbUri))

                // 期间用户可能又切了别的视频：被取代的这次不再动手
                if (index != newIndex) return@launch

                val p = player ?: return@launch
                currentVideoPath = pathOf(name)
                lastSavedProgressMs = resumePosition
                p.setMediaSource(videoSource, resumePosition)
                p.prepare()
                p.playWhenReady = true
                if (resumePosition > 0L) {
                    showHint(getString(R.string.player_resume_from, formatDuration(resumePosition)))
                }

                // 应用播放页常驻倍速（每次切集都继承，保证「上一集的速度带到下一集」）
                normalSpeed = SettingsRepository.normalSpeed
                p.playbackParameters = PlaybackParameters(normalSpeed)
                updateSpeedButton()

                // 字幕：解析成功就挂上叠加层；失败只提示，视频照常播
                subtitleCues = cues
                val loadedSubtitle = if (subtitleName != null && cues.isNotEmpty()) subtitleName else null
                mainHandler.removeCallbacks(subtitleTickRunnable)
                if (loadedSubtitle != null) {
                    logInfo("已加载同名字幕: $loadedSubtitle（${cues.size} 条）")
                    showHint(getString(R.string.subtitle_loaded, loadedSubtitle))
                    updateSubtitleOverlay()
                    mainHandler.post(subtitleTickRunnable)
                } else {
                    binding.tvSubtitleOverlay.visibility = View.GONE
                }
                if (subtitleError != null) {
                    toast(getString(R.string.subtitle_load_failed, subtitleError))
                }
            } catch (e: CancellationException) {
                throw e // 用户离开播放页：正常取消
            } catch (e: Exception) {
                logError("播放失败: $name", e)
                if (index == newIndex) {
                    binding.progress.visibility = View.GONE
                    toast(getString(R.string.player_error, e.message ?: e.toString()))
                }
            }
        }
    }

    // ------------------------------------------------------------------ 播放记录

    /**
     * 保存当前视频的播放进度。
     *
     * [force] 为 false 时按位置变化节流（每 5 秒最多写一次），供播放中的 tick 调用；
     * 切集、暂停、离开页面等关键节点传 true。
     */
    private fun saveCurrentProgress(force: Boolean = false) {
        val p = player ?: return
        val path = currentVideoPath ?: return
        val duration = p.duration
        if (duration <= 0L) return
        val position = p.currentPosition
        if (position < 0L) return
        // 位置没变化就不用重复落盘；但用户主动拖回原位置（例如把看完的视频拖回 0）
        // 时仍要写一次，否则「看完」状态会一直留在记录里
        if (position == lastSavedProgressMs && !userAdjustedPosition) return
        if (!force && kotlin.math.abs(position - lastSavedProgressMs) < PROGRESS_SAVE_INTERVAL_MS) return
        PlaybackProgressRepository.save(
            serverId = server.id,
            shareName = shareName,
            path = path,
            positionMs = position,
            durationMs = duration,
            explicit = userAdjustedPosition,
        )
        lastSavedProgressMs = position
    }

    /** 播放到结尾：强制记成已看完，播放列表里的进度线会走到底 */
    private fun markCurrentCompleted() {
        val p = player ?: return
        val path = currentVideoPath ?: return
        val duration = p.duration
        if (duration <= 0L) return
        PlaybackProgressRepository.save(server.id, shareName, path, duration, duration)
        lastSavedProgressMs = duration
    }

    /** 刷新字幕叠加层：按当前播放位置找 active cue */
    private fun updateSubtitleOverlay() {
        val overlay = binding.tvSubtitleOverlay
        if (subtitleCues.isEmpty()) {
            overlay.visibility = View.GONE
            return
        }
        val pos = player?.currentPosition ?: return
        val cue = subtitleCues.firstOrNull { pos >= it.startMs && pos < it.endMs }
        if (cue != null) {
            if (overlay.text?.toString() != cue.text) overlay.text = cue.text
            overlay.visibility = View.VISIBLE
        } else {
            overlay.visibility = View.GONE
        }
    }

    /** 把同名字幕整体读进内存；文件为空/过大时抛错，由调用方按「无字幕」处理 */
    private fun readSubtitleBytes(name: String): ByteArray {
        val path = pathOf(name)
        SmbReadHandle(session, shareName, path).use { handle ->
            handle.openAt(0)
            val total = handle.length
            if (total <= 0L) throw IOException("字幕文件为空: $name")
            if (total > MAX_SUBTITLE_BYTES) throw IOException("字幕文件过大（${total} 字节）: $name")
            val buffer = ByteArray(total.toInt())
            var read = 0
            while (read < buffer.size) {
                val n = handle.read(buffer, read, buffer.size - read)
                if (n < 0) break
                if (n == 0) throw IOException("字幕读取停滞: $name")
                read += n
            }
            return if (read == buffer.size) buffer else buffer.copyOf(read)
        }
    }

    // ------------------------------------------------------------------ 播放控制

    private fun setupPlayer() {
        // 开启解码器回退：硬解初始化/解码失败时自动换下一个解码器（通常是软解），
        // 电视盒子的硬解对个别损坏帧特别敏感，这一层能挡掉相当一部分「打不开」
        val renderersFactory = DefaultRenderersFactory(this).setEnableDecoderFallback(true)
        val p = ExoPlayer.Builder(this, renderersFactory).build()
        p.addListener(playerListener)
        binding.playerView.player = p
        player = p
    }

    private fun setupControls() {
        binding.btnBack.setOnClickListener { finish() }
        binding.btnPlayPause.setOnClickListener { togglePlayPause() }
        binding.btnRew.setOnClickListener { seekBy(-SEEK_STEP_MS) }
        binding.btnFfwd.setOnClickListener { seekBy(SEEK_STEP_MS) }
        binding.btnPrevVideo.setOnClickListener { playPrev() }
        binding.btnNextVideo.setOnClickListener { playNext(fromUser = true) }
        binding.btnPlaylist.setOnClickListener { showPlaylist() }
        binding.btnDelete.setOnClickListener { confirmDeleteCurrent() }

        // 播放页常驻倍速
        binding.btnSpeed.setOnClickListener { showNormalSpeedDialog() }
        updateSpeedButton()
    }

    // ------------------------------------------------------------------ 倍速控制

    /** 刷新顶部栏常驻倍速按钮上的数值 */
    private fun updateSpeedButton() {
        binding.btnSpeed.text = getString(R.string.settings_speed_value, SettingsRepository.normalSpeed)
    }

    /** 播放页常驻倍速选择，选中即设置成功并立即应用到当前视频 */
    private fun showNormalSpeedDialog() {
        val labels = SpeedOptions.VALUES
            .map { getString(R.string.settings_speed_value, it) }
            .toTypedArray()
        val checked = SpeedOptions.indexOf(SettingsRepository.normalSpeed)
            .coerceAtLeast(SpeedOptions.DEFAULT_INDEX)
        showPlayerDialog(
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.player_speed_title)
                .setSingleChoiceItems(labels, checked) { dlg, which ->
                    val sp = SpeedOptions.VALUES[which]
                    SettingsRepository.normalSpeed = sp
                    normalSpeed = sp
                    player?.playbackParameters = PlaybackParameters(sp)
                    updateSpeedButton()
                    toast(getString(R.string.settings_speed_value, sp))
                    dlg.dismiss()
                }
                .setNegativeButton(R.string.server_cancel, null)
        )
    }

    // ------------------------------------------------------------------ 播放控制

    private fun togglePlayPause() {
        val p = player ?: return
        if (p.isPlaying) {
            p.pause()
            saveCurrentProgress(force = true)
            showHint(getString(R.string.hint_pause))
            showControls(focusPlayPause = true)
        } else {
            p.play()
            showHint(getString(R.string.hint_play))
            scheduleHideControls()
        }
    }

    /**
     * 直接 seek（按钮点击 / 媒体键），与按住方向键的预览式 seek 相区分。
     * [revealControls] 为 false 时不呼出控制条：媒体键快进属于画面模式的动作，
     * 不该顺手把按键语义切成「按钮焦点模式」。
     */
    private fun seekBy(deltaMs: Long, revealControls: Boolean = true) {
        val p = player ?: return
        val target = (p.currentPosition + deltaMs).coerceAtLeast(0L)
        val duration = p.duration
        userAdjustedPosition = true
        p.seekTo(if (duration > 0) target.coerceAtMost(duration) else target)
        updateProgress()
        if (revealControls) showControls()
        showHint(getString(R.string.hint_seek, formatDuration(p.currentPosition)))
    }

    private fun playNext(fromUser: Boolean): Boolean {
        if (index + 1 >= videos.size) {
            if (fromUser) toast(R.string.player_no_more)
            return false
        }
        playAt(index + 1)
        return true
    }

    private fun playPrev(): Boolean {
        if (index == 0) return false
        playAt(index - 1)
        return true
    }

    // ------------------------------------------------------------------ 播放列表弹窗

    private fun showPlaylist() {
        // 打开列表前先落一次当前进度，正在播放的视频也能看到最新的线
        saveCurrentProgress(force = true)
        val progressByPath = PlaybackProgressRepository.getAll(
            server.id,
            shareName,
            videos.map { pathOf(it) },
        )
        val items = videos.map { name ->
            PlaylistItem(name, progressByPath[pathOf(name)]?.displayFraction)
        }

        val view = LayoutInflater.from(this).inflate(R.layout.dialog_playlist, null)
        val b = DialogPlaylistBinding.bind(view)
        val layoutManager = LinearLayoutManager(this)
        b.recyclerPlaylist.layoutManager = layoutManager
        b.recyclerPlaylist.adapter = PlaylistAdapter(items, index) { position ->
            playlistDialog?.dismiss()
            if (position != index) playAt(position)
        }
        playlistDialog = showPlayerDialog(
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.player_playlist_title)
                .setView(view)
                .setNegativeButton(R.string.server_cancel, null)
        )
        // 打开时定位到正在播放的视频，并把焦点落到该项（遥控器可直接上下选择）
        b.recyclerPlaylist.post {
            if (index in videos.indices) layoutManager.scrollToPositionWithOffset(index, 0)
            b.recyclerPlaylist.post {
                val target = layoutManager.findViewByPosition(index)
                    ?: layoutManager.findViewByPosition(0)
                target?.requestFocus()
            }
        }
    }

    // ------------------------------------------------------------------ 错误提示

    /**
     * 播放错误自救：源文件有个别损坏的帧 / 片段时，跳过这一小段后重新准备播放，
     * 而不是直接弹窗让用户关掉。
     *
     * 判定规则：
     * - 解码器初始化失败、格式不支持、文件不存在这类「换位置也没用」的错误不重试；
     * - 同一位置附近连续失败超过 [ERROR_RECOVER_MAX] 次不重试（说明是整段/整个文件坏了）；
     * - 已经播到接近片尾时报错，按「播放完毕」处理，直接进入下一集。
     *
     * @return true 表示已经接管处理（不再弹窗）
     */
    private fun recoverFromError(error: PlaybackException): Boolean {
        if (!isAlive()) return false
        val p = player ?: return false
        if (!isRecoverable(error)) return false

        val position = p.currentPosition.coerceAtLeast(0L)
        val duration = p.duration

        // 位置几乎没动 → 认为是同一处的连续失败
        val sameSpot = errorRecoverPosition != Long.MIN_VALUE &&
            position - errorRecoverPosition in 0L..ERROR_SAME_POSITION_MS
        errorRecoverCount = if (sameSpot) errorRecoverCount + 1 else 1
        errorRecoverPosition = position

        if (errorRecoverCount > ERROR_RECOVER_MAX) {
            logError("同一位置连续 ${errorRecoverCount} 次播放失败，不再跳过（${error.errorCodeName}）", error)
            return false
        }

        val target = position + ERROR_SKIP_MS
        if (duration > 0L && target >= duration - END_TOLERANCE_MS) {
            // 片尾数据坏了，当已看完处理，顺延到下一集
            logError("片尾出现播放错误（${error.errorCodeName}），按播放完毕处理", error)
            markCurrentCompleted()
            playNext(fromUser = false)
            return true
        }

        logError(
            "播放出错（${error.errorCodeName}）：跳过 ${ERROR_SKIP_MS}ms 后重试，第 $errorRecoverCount 次",
            error,
        )
        showHint(getString(R.string.player_error_skipping))
        p.seekTo(target)
        p.prepare()
        p.playWhenReady = true
        return true
    }

    /** 这类错误换个位置重试有意义吗（解码器/格式/权限类错误换位置也没用） */
    private fun isRecoverable(error: PlaybackException): Boolean = when (error.errorCode) {
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
        PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
        -> false

        else -> true
    }

    private fun showPlaybackError(error: PlaybackException) {
        // 播放器错误回调可能在页面销毁后陆续到达：不活着就不弹，避免 BadTokenException
        if (!isAlive()) return
        val message = error.message ?: error.errorCodeName
        // 已经自动跳过若干次还救不回来：文案说明「试过了」，用户才知道不是没处理
        val text = if (errorRecoverCount > 0) {
            getString(R.string.player_error_after_retry, message)
        } else {
            getString(R.string.player_error, message)
        }
        logError("播放失败", error)
        binding.tvError.text = text
        binding.tvError.visibility = View.VISIBLE

        val hasNext = index + 1 < videos.size
        showPlayerDialog(
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.player_error_title)
                .setMessage(text)
                .setCancelable(false)
                .setPositiveButton(
                    if (hasNext) R.string.player_error_action_next else R.string.player_error_action_retry
                ) { _, _ ->
                    if (hasNext) playNext(fromUser = true) else playAt(index)
                }
                .setNegativeButton(R.string.player_error_action_close) { _, _ -> finish() }
        )
    }

    // ------------------------------------------------------------------ 控制条显隐

    private fun updateControls() {
        val p = player ?: return
        binding.btnPlayPause.setImageResource(if (p.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)

        val hasPrev = index > 0
        val hasNext = index + 1 < videos.size
        binding.btnPrevVideo.isEnabled = hasPrev
        binding.btnNextVideo.isEnabled = hasNext
        binding.btnPrevVideo.alpha = if (hasPrev) 1f else 0.35f
        binding.btnNextVideo.alpha = if (hasNext) 1f else 0.35f

        binding.btnPlaylist.isEnabled = videos.isNotEmpty()

        updateProgress()
    }

    private fun updateProgress() {
        val p = player ?: return
        if (userSeeking) return
        val position = p.currentPosition
        val duration = p.duration
        // 已经播过出错点很远：说明自救成功，计数归零，后面的坏帧可以重新计数
        if (errorRecoverCount > 0 && errorRecoverPosition != Long.MIN_VALUE &&
            position > errorRecoverPosition + ERROR_SAME_POSITION_MS * 2
        ) {
            errorRecoverCount = 0
            errorRecoverPosition = Long.MIN_VALUE
        }
        binding.tvPosition.text = formatDuration(position)
        binding.tvDuration.text = if (duration > 0) formatDuration(duration) else "--:--"
        if (duration > 0 && !binding.seekProgress.isPressed) {
            binding.seekProgress.progress = (position * 1000 / duration).toInt().coerceIn(0, 1000)
        }
    }

    /**
     * 显示控制条。
     *
     * [focusPlayPause] 为 true 时把焦点交给播放/暂停按钮——遥控器用户呼出控制条后
     * 一般要么直接按确认，要么在按钮间移动，落到主按钮上最顺手。
     */
    private fun showControls(focusPlayPause: Boolean = false) {
        val entering = !controlsShown
        // 从画面模式切进来时，若还有没松手的预览 seek，先提交收尾，别跨模式悬挂
        if (entering) flushPendingSeek()
        controlsShown = true
        animateBarIn(binding.topBar, -1)
        animateBarIn(binding.bottomBar, 1)
        setBottomMarginPx(binding.tvSubtitleOverlay, subtitleBottomPx(controlsVisible = true))
        setBottomMarginPx(binding.tvHint, hintBottomPx(controlsVisible = true))
        if (focusPlayPause) binding.btnPlayPause.requestFocus()
        scheduleHideControls()
        // 刚从画面模式切进来时提示一次：此时左右键是切按钮焦点，不再是快进
        if (entering) showHint(getString(R.string.tv_hint_controls_mode))
    }

    private fun hideControls() {
        controlsShown = false
        animateBarOut(binding.topBar, -1)
        animateBarOut(binding.bottomBar, 1)
        setBottomMarginPx(binding.tvSubtitleOverlay, subtitleBottomPx(controlsVisible = false))
        setBottomMarginPx(binding.tvHint, hintBottomPx(controlsVisible = false))
        mainHandler.removeCallbacks(hideControlsRunnable)
        // 焦点从控制条收回画面：按钮隐藏后不该继续持有焦点，
        // 否则确认键会点到看不见的按钮，画面模式的左右键也少了明确的焦点归属
        val focused = currentFocus
        if (focused != null && (isInside(binding.topBar, focused) || isInside(binding.bottomBar, focused))) {
            binding.root.requestFocus()
        }
    }

    /** view 是否在 parent 内部（ViewGroup.isAncestorOf 不是公开 API） */
    private fun isInside(parent: ViewGroup, child: View?): Boolean {
        var v: View? = child
        while (v != null) {
            if (v === parent) return true
            v = v.parent as? View
        }
        return false
    }

    /** 滑动位移基准：优先用控件实际高度，未布局完成时按 100dp 兜底 */
    private fun barSlideOffset(bar: View, sign: Int): Float {
        val h = if (bar.height > 0) bar.height.toFloat() else 100f * resources.displayMetrics.density
        return sign * h
    }

    /** 控制栏滑入（顶部从上、底部从下）并淡入 */
    private fun animateBarIn(bar: View, fromSign: Int) {
        bar.animate().cancel()
        if (bar.visibility != View.VISIBLE) {
            bar.visibility = View.VISIBLE
            bar.alpha = 0f
            bar.translationY = barSlideOffset(bar, fromSign)
        }
        bar.animate().translationY(0f).alpha(1f).setDuration(200L).start()
    }

    /** 控制栏滑出（顶部向上、底部向下）并淡出，结束后真正 GONE */
    private fun animateBarOut(bar: View, toSign: Int) {
        bar.animate().cancel()
        if (bar.visibility != View.VISIBLE) return
        bar.animate().translationY(barSlideOffset(bar, toSign))
            .alpha(0f).setDuration(200L)
            .withEndAction {
                bar.visibility = View.GONE
                bar.translationY = 0f
                bar.alpha = 1f
            }.start()
    }

    /**
     * 字幕样式：字号按**屏幕高度的比例**算，不写死 sp。
     *
     * TV 盒子的 density 差异极大：同样 1080p 屏，density=1 时 1sp 只有 1px，
     * 原来的 18sp 占屏高不到 2%，离电视两米基本看不清。改成按屏高比例后，
     * 默认「中」档在 1080p 上约 52px（屏高 4.8%）、4K 上等比放大；
     * 行距和描边阴影也跟着字号缩放，两行字幕不会粘在一起，亮画面也有轮廓。
     */
    private fun applySubtitleStyle() {
        val overlay = binding.tvSubtitleOverlay
        val px = subtitleTextPx()
        overlay.setTextSize(TypedValue.COMPLEX_UNIT_PX, px)
        overlay.setLineSpacing(px * 0.25f, 1f)
        overlay.setShadowLayer(px * 0.16f, px * 0.04f, px * 0.06f, Color.BLACK)
    }

    /** 字幕字号（px）= 屏幕高度 × 档位比例 */
    private fun subtitleTextPx(): Float =
        resources.displayMetrics.heightPixels * SubtitleSizes.ratioOf(subtitleSizeLevel)

    /** 字幕距底部：原有 dp 间距与屏高比例取大者（低 density 盒子上 dp 会偏小） */
    private fun subtitleBottomPx(controlsVisible: Boolean): Int = maxOf(
        dpPx(if (controlsVisible) 148 else 40),
        (resources.displayMetrics.heightPixels *
            if (controlsVisible) SUBTITLE_BOTTOM_SHOWN_RATIO else SUBTITLE_BOTTOM_HIDDEN_RATIO).toInt(),
    )

    /** 提示条距底部：字幕正在显示时抬到字幕上方，免得盖住字幕 */
    private fun hintBottomPx(controlsVisible: Boolean): Int {
        val base = dpPx(if (controlsVisible) 168 else 72)
        val overlay = binding.tvSubtitleOverlay
        if (overlay.visibility != View.VISIBLE) return base
        val subtitleTop = subtitleBottomPx(controlsVisible) + overlay.height
        return maxOf(base, subtitleTop + dpPx(8))
    }

    private fun dpPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    /** 调整叠加层距底部的距离（px）：控制条显示时避让，隐藏时贴近底部 */
    private fun setBottomMarginPx(view: View, px: Int) {
        val params = view.layoutParams as? FrameLayout.LayoutParams ?: return
        if (params.bottomMargin != px) {
            params.bottomMargin = px
            view.layoutParams = params
        }
    }

    private fun scheduleHideControls() {
        if (!controlsShown) return
        mainHandler.removeCallbacks(hideControlsRunnable)
        mainHandler.postDelayed(hideControlsRunnable, CONTROL_HIDE_DELAY_MS)
    }

    companion object {
        private const val EXTRA_SERVER_ID = "extra_server_id"
        private const val EXTRA_SHARE = "extra_share"
        private const val EXTRA_DIR = "extra_dir"
        private const val EXTRA_INDEX = "extra_index"

        /** 「最近播放」进入时指定的起始文件名（目录下按名定位） */
        private const val EXTRA_START_FILE = "extra_start_file"

        private const val TICK_INTERVAL_MS = 500L
        private const val CONTROL_HIDE_DELAY_MS = 4_000L
        private const val HINT_HIDE_DELAY_MS = 1_500L

        /** 画面模式短按左右键的快退 / 快进步长 */
        private const val SEEK_STEP_MS = 10_000L

        /** 长按连跳每一步的步长（幅度放大到 30 秒） */
        private const val SEEK_HOLD_STEP_MS = 30_000L

        /** 短按 / 长按的分界：按住 400ms 仍未松手才进入连跳（参照 TV 遥控器通行做法） */
        private const val SEEK_LONG_PRESS_MS = 400L

        /** 长按连跳的步进间隔：每 250ms 走一步，不跟系统重复速率 */
        private const val SEEK_REPEAT_INTERVAL_MS = 250L

        /** 连按两次返回退出的确认窗口：超时后第一次返回作废 */
        private const val EXIT_CONFIRM_WINDOW_MS = 3_000L

        /** 同名字幕整体读进内存的大小上限（超过就按无字幕播放，避免异常大文件） */
        private const val MAX_SUBTITLE_BYTES = 8L * 1024 * 1024

        /** 字幕叠加层刷新间隔 */
        private const val SUBTITLE_TICK_MS = 100L

        /** 字幕距屏幕底部的比例：控制条显示 / 隐藏时（与 dp 间距取较大值） */
        private const val SUBTITLE_BOTTOM_SHOWN_RATIO = 0.155f
        private const val SUBTITLE_BOTTOM_HIDDEN_RATIO = 0.055f

        /** 播放记录落盘的最小位置变化（避免每 500ms 都写 SharedPreferences） */
        private const val PROGRESS_SAVE_INTERVAL_MS = 5_000L

        /** 播放出错后跳过多少毫秒再重试（损坏的帧/片段通常只有几百毫秒） */
        private const val ERROR_SKIP_MS = 1_500L

        /** 同一处最多连续自救几次，超过就认为整段坏了，交给用户决定 */
        private const val ERROR_RECOVER_MAX = 4

        /** 位置差在这个范围内视为「同一处出错」 */
        private const val ERROR_SAME_POSITION_MS = 3_000L

        /** 距离片尾不到这个长度时出错，直接按播放完毕处理 */
        private const val END_TOLERANCE_MS = 1_000L

        fun intent(
            context: Context,
            serverId: String,
            share: String,
            directory: String,
            index: Int,
            startFile: String? = null,
        ): Intent = Intent(context, PlayerActivity::class.java)
            .putExtra(EXTRA_SERVER_ID, serverId)
            .putExtra(EXTRA_SHARE, share)
            .putExtra(EXTRA_DIR, directory)
            .putExtra(EXTRA_INDEX, index)
            .putExtra(EXTRA_START_FILE, startFile)
    }
}
