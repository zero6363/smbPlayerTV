package com.smbplayer.tv.player

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
 * 键位：
 * - 确认键：播放 / 暂停（并呼出控制条）
 * - 左右方向键：快退 / 快进 10 秒（按住连发累计、实时预览落点，松开才真正 seek）
 * - 上下方向键：音量增减（系统音量条 OSD）；控制条显示时改为移动焦点
 * - 菜单键：呼出 / 收起控制条
 * - 返回键：先收控制条，退出前二次确认（进度已自动保存）
 * - 媒体键（播放/暂停/快进/快退/上一集/下一集）直接支持
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

    /**
     * 本次播放中用户是否主动调整过位置（快进快退 / 拖进度条）。
     * 用户明确选过的位置要以用户为准，不受「片头 10 秒不记录」「看完视频前 30 秒不覆盖」限制。
     */
    private var userAdjustedPosition = false

    /** 当前目录下的全部非目录文件名（用于在同级目录里找同名字幕） */
    private var allFileNames: Set<String> = emptySet()

    /** 当前视频的同名字幕（播前解析成 cue 列表，由叠加层渲染） */
    private var subtitleCues: List<SubtitleCue> = emptyList()

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
    // 按住方向键连发时只累计偏移量并实时预览落点，松开按键才真正 seek。
    // 网络流 seek 是一次昂贵的范围请求，不能每按一下就连发一次。

    /** 本次按住期间累计的 seek 偏移量（毫秒，负值为快退） */
    private var pendingSeekOffsetMs = 0L

    /** 正在按住的 seek 方向键（DPAD_LEFT / DPAD_RIGHT），无按键按住时为 null */
    private var pendingSeekKeyCode: Int? = null

    /** 预览基准位置：按下方向键那一刻的播放位置 */
    private var pendingSeekBaseMs = 0L

    /** 上次步进时刻：按住期间按固定节奏步进，不跟系统按键重复速率（约 20 次/秒太快） */
    private var pendingSeekLastStepMs = 0L

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 居中提示自动隐藏 */
    private val hideHintRunnable = Runnable { binding.tvHint.visibility = View.GONE }

    /** 底部显示一条操作提示（不挡画面），稍后自动隐藏 */
    private fun showHint(text: String) {
        binding.tvHint.text = text
        binding.tvHint.visibility = View.VISIBLE
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
            showPlaybackError(error)
        }
    }

    // ------------------------------------------------------------------ 生命周期

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        logInfo("PlayerActivity onCreate")
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupPlayer()
        setupControls()

        // 返回键：控制条显示时先收起；退出播放需连按两次（不弹窗，遥控器误触也不会退出）
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.topBar.visibility == View.VISIBLE) {
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
        when (keyCode) {
            // 确认键：只切换播放/暂停，不呼出控制条（控制条用菜单键召唤），
            // 避免把焦点带进按钮区导致左右键与快进/快退冲突
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (event?.repeatCount == 0) {
                    if (controlsHasFocus()) return super.onKeyDown(keyCode, event)
                    togglePlayPause()
                }
                return true
            }

            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                // 控制条显示且焦点在按钮上：交给焦点系统在按钮间移动
                if (binding.topBar.visibility == View.VISIBLE && controlsHasFocus()) {
                    return super.onKeyDown(keyCode, event)
                }
                accumulateSeek(keyCode)
                return true
            }

            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (binding.topBar.visibility == View.VISIBLE) {
                    // 控制条显示时分两种情况：
                    // 焦点还在画面上 → 上下键把焦点送进控制条（进入按钮操作模式）；
                    // 焦点已在控制条内 → 交给焦点系统在按钮间移动
                    if (!controlsHasFocus()) {
                        binding.btnPlayPause.requestFocus()
                        return true
                    }
                    return super.onKeyDown(keyCode, event)
                }
                // 隐藏时调节音量（连发跟随系统重复节奏）
                adjustVolume(if (keyCode == KeyEvent.KEYCODE_DPAD_UP) 1 else -1)
                return true
            }

            KeyEvent.KEYCODE_MENU -> {
                if (event?.repeatCount == 0) {
                    if (binding.topBar.visibility == View.VISIBLE) hideControls() else showControls(focusPlayPause = true)
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
                seekBy(SEEK_STEP_MS)
                return true
            }
            KeyEvent.KEYCODE_MEDIA_REWIND -> {
                seekBy(-SEEK_STEP_MS)
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
        // 松开方向键：把累计的偏移量一次性 seek 过去
        if (keyCode == pendingSeekKeyCode && (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)) {
            flushPendingSeek()
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    /**
     * 按住方向键期间累计偏移并实时预览落点（不真正 seek）。
     *
     * 步进节奏：首次按下立即步进一步，之后每 [SEEK_REPEAT_INTERVAL_MS] 步进一步——
     * 系统 key repeat 约 20 次/秒，直接跟随会一秒跳 200 秒，太快没法用；
     * 200ms/10s 即按住 1 秒拖 50 秒，连续拖动顺畅又不至于失控。
     */
    private fun accumulateSeek(keyCode: Int) {
        val p = player ?: return
        val delta = if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) SEEK_STEP_MS else -SEEK_STEP_MS
        val now = android.os.SystemClock.elapsedRealtime()
        if (pendingSeekKeyCode != keyCode) {
            // 新一次按压：以当前位置为基准重新开始累计。
            // 刻意不呼出控制条——左右键只负责快进/快退，控制条出现会把焦点
            // 带进按钮区，按键就和按钮移动打架了
            pendingSeekKeyCode = keyCode
            pendingSeekOffsetMs = 0L
            pendingSeekBaseMs = p.currentPosition
            pendingSeekLastStepMs = now
        } else {
            // 按住连发：按固定节奏节流
            if (now - pendingSeekLastStepMs < SEEK_REPEAT_INTERVAL_MS) return
            pendingSeekLastStepMs = now
        }
        pendingSeekOffsetMs += delta
        val duration = p.duration
        val target = pendingSeekBaseMs + pendingSeekOffsetMs
        val clamped = if (duration > 0) target.coerceIn(0L, duration) else target.coerceAtLeast(0L)
        // 进度条与时间文本实时显示预览落点
        if (duration > 0) {
            binding.seekProgress.progress = (clamped * 1000 / duration).toInt().coerceIn(0, 1000)
        }
        binding.tvPosition.text = formatDuration(clamped)
        val hint = if (delta > 0) R.string.tv_hint_seek_preview else R.string.tv_hint_rew_preview
        showHint(getString(hint, formatDuration(clamped)))
    }

    /** 松开按键 / 离开页面：把累计的偏移量真正 seek 过去 */
    private fun flushPendingSeek() {
        val keyCode = pendingSeekKeyCode ?: return
        pendingSeekKeyCode = null
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

    // ------------------------------------------------------------------ 音量控制

    private val audioManager by lazy {
        getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    }

    /**
     * 上下方向键调音量：直接走系统媒体音量步进，FLAG_SHOW_UI 让系统显示音量条 OSD，
     * 与遥控器音量键行为一致（连发跟随系统按键重复节奏）。
     */
    private fun adjustVolume(direction: Int) {
        val am = audioManager ?: return
        am.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            if (direction > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
            AudioManager.FLAG_SHOW_UI,
        )
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
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_title)
            .setMessage(getString(R.string.delete_file_message, name))
            .setPositiveButton(R.string.delete_confirm) { _, _ -> deleteCurrent(name) }
            .setNegativeButton(R.string.server_cancel, null)
            .show()
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
        val p = ExoPlayer.Builder(this).build()
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
            .show()
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

    /** 直接 seek（按钮点击 / 媒体键），与按住方向键的预览式 seek 相区分 */
    private fun seekBy(deltaMs: Long) {
        val p = player ?: return
        val target = (p.currentPosition + deltaMs).coerceAtLeast(0L)
        val duration = p.duration
        userAdjustedPosition = true
        p.seekTo(if (duration > 0) target.coerceAtMost(duration) else target)
        updateProgress()
        showControls()
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
        playlistDialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.player_playlist_title)
            .setView(view)
            .setNegativeButton(R.string.server_cancel, null)
            .create()
            .also { it.show() }
        // 打开时定位到正在播放的视频（show 之后列表才有尺寸，post 一下再滚）
        b.recyclerPlaylist.post {
            if (index in videos.indices) layoutManager.scrollToPositionWithOffset(index, 0)
        }
    }

    // ------------------------------------------------------------------ 错误提示

    private fun showPlaybackError(error: PlaybackException) {
        // 播放器错误回调可能在页面销毁后陆续到达：不活着就不弹，避免 BadTokenException
        if (!isAlive()) return
        val message = error.message ?: error.errorCodeName
        logError("播放失败", error)
        binding.tvError.text = getString(R.string.player_error, message)
        binding.tvError.visibility = View.VISIBLE

        val hasNext = index + 1 < videos.size
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.player_error_title)
            .setMessage(getString(R.string.player_error, message))
            .setCancelable(false)
            .setPositiveButton(
                if (hasNext) R.string.player_error_action_next else R.string.player_error_action_retry
            ) { _, _ ->
                if (hasNext) playNext(fromUser = true) else playAt(index)
            }
            .setNegativeButton(R.string.player_error_action_close) { _, _ -> finish() }
            .show()
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
        animateBarIn(binding.topBar, -1)
        animateBarIn(binding.bottomBar, 1)
        setBottomMargin(binding.tvSubtitleOverlay, 148)
        setBottomMargin(binding.tvHint, 168)
        if (focusPlayPause) binding.btnPlayPause.requestFocus()
        scheduleHideControls()
    }

    private fun hideControls() {
        animateBarOut(binding.topBar, -1)
        animateBarOut(binding.bottomBar, 1)
        setBottomMargin(binding.tvSubtitleOverlay, 40)
        setBottomMargin(binding.tvHint, 72)
        mainHandler.removeCallbacks(hideControlsRunnable)
        // 焦点从控制条收回画面：否则按钮已隐藏、焦点仍留在按钮上，
        // 左右键会被焦点系统吃掉，表现为「按左右键不快进」
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

    /** 调整叠加层距底部的距离：控制条显示时避让，隐藏时贴近底部 */
    private fun setBottomMargin(view: View, dp: Int) {
        val params = view.layoutParams as? FrameLayout.LayoutParams ?: return
        val px = (dp * resources.displayMetrics.density).toInt()
        if (params.bottomMargin != px) {
            params.bottomMargin = px
            view.layoutParams = params
        }
    }

    private fun scheduleHideControls() {
        if (binding.topBar.visibility != View.VISIBLE) return
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

        /** 遥控器快进 / 快退步长（按住可连发，松开生效） */
        private const val SEEK_STEP_MS = 10_000L

        /** 按住方向键时的步进间隔：每 200ms 步进一步（约 1 秒拖 50 秒），不跟系统重复速率 */
        private const val SEEK_REPEAT_INTERVAL_MS = 200L

        /** 连按两次返回退出的确认窗口：超时后第一次返回作废 */
        private const val EXIT_CONFIRM_WINDOW_MS = 3_000L

        /** 同名字幕整体读进内存的大小上限（超过就按无字幕播放，避免异常大文件） */
        private const val MAX_SUBTITLE_BYTES = 8L * 1024 * 1024

        /** 字幕叠加层刷新间隔 */
        private const val SUBTITLE_TICK_MS = 100L

        /** 播放记录落盘的最小位置变化（避免每 500ms 都写 SharedPreferences） */
        private const val PROGRESS_SAVE_INTERVAL_MS = 5_000L

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
