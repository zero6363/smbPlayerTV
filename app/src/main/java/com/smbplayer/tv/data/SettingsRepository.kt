package com.smbplayer.tv.data

import android.content.Context
import android.content.SharedPreferences

/**
 * 播放器设置持久化（TV 版）。
 *
 * 存储后台播放开关、播放页常驻倍速、目录排序方式等用户偏好。
 */
object SettingsRepository {

    private const val PREFS_NAME = "smb_settings"
    private const val KEY_BACKGROUND_PLAY = "background_play"
    private const val KEY_NORMAL_SPEED = "normal_speed"
    private const val KEY_SORT_FIELD = "sort_field"
    private const val KEY_SORT_ASCENDING = "sort_ascending"
    private const val KEY_SUBTITLE_SIZE = "subtitle_size"
    private const val KEY_SHOW_ALL_FILES = "show_all_files"
    private const val KEY_LAST_UPDATE_CHECK = "last_update_check"
    private const val KEY_PENDING_UPDATE_VERSION = "pending_update_version"
    private const val KEY_DOWNLOADED_UPDATE_VERSION = "downloaded_update_version"

    private val lock = Any()

    @Volatile
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs != null) return
        synchronized(lock) {
            if (prefs != null) return
            prefs = context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
    }

    private fun store(): SharedPreferences =
        prefs ?: throw IllegalStateException("SettingsRepository.init() 未调用")

    /**
     * 后台播放：离开播放页（息屏 / 切到后台）时是否继续播放，默认 false。
     * TV 场景离开播放页基本就是退出了，默认关闭避免电视后台出声；
     * 需要边播边干别的（如切到设置页查日志）可在设置页打开。
     */
    var backgroundPlay: Boolean
        get() = store().getBoolean(KEY_BACKGROUND_PLAY, false)
        set(value) {
            store().edit().putBoolean(KEY_BACKGROUND_PLAY, value).apply()
        }

    /**
     * 播放页常驻倍速。默认 1.0f，每次 playAt 时应用到播放器，从而继承到下一集。
     */
    var normalSpeed: Float
        get() = store().getFloat(KEY_NORMAL_SPEED, 1.0f)
        set(value) {
            store().edit().putFloat(KEY_NORMAL_SPEED, value).apply()
        }

    /**
     * 目录列表排序字段：见 [SortOptions.FIELD_NAME] / [FIELD_SIZE] / [FIELD_CREATED]。
     * 默认按文件名。
     */
    var sortField: Int
        get() = store().getInt(KEY_SORT_FIELD, SortOptions.DEFAULT_FIELD)
        set(value) {
            store().edit().putInt(KEY_SORT_FIELD, value).apply()
        }

    /** 目录列表排序方向：true 升序，false 降序。默认升序。 */
    var sortAscending: Boolean
        get() = store().getBoolean(KEY_SORT_ASCENDING, SortOptions.DEFAULT_ASCENDING)
        set(value) {
            store().edit().putBoolean(KEY_SORT_ASCENDING, value).apply()
        }

    /**
     * 字幕字号档位：见 [SubtitleSizes]（0 小 / 1 中 / 2 大）。
     * 播放页按这个档位把字号换算成「屏幕高度的百分比」，换台电视不用重调。
     */
    var subtitleSize: Int
        get() = store().getInt(KEY_SUBTITLE_SIZE, SubtitleSizes.DEFAULT)
        set(value) {
            store().edit().putInt(KEY_SUBTITLE_SIZE, value).apply()
        }

    /**
     * 浏览页是否显示全部文件。
     *
     * 默认 false：目录里只列文件夹和视频（ts / flv / mp4 等），
     * 把字幕、nfo、封面图之类的杂项挡在外面。需要看图片或其他文件时，
     * 在浏览页菜单里临时切到「显示所有文件」。
     */
    var showAllFiles: Boolean
        get() = store().getBoolean(KEY_SHOW_ALL_FILES, false)
        set(value) {
            store().edit().putBoolean(KEY_SHOW_ALL_FILES, value).apply()
        }

    /** 上次检查更新的时间戳（毫秒），用于启动时按间隔节流，避免每次回主页都请求 GitHub */
    var lastUpdateCheck: Long
        get() = store().getLong(KEY_LAST_UPDATE_CHECK, 0L)
        set(value) {
            store().edit().putLong(KEY_LAST_UPDATE_CHECK, value).apply()
        }

    /**
     * 已经调起安装器、等待生效的版本（如 "1.9-tv"）。
     *
     * 电视盒子不会自己删安装包，这个标记让新版本第一次启动时知道
     * 「更新已经装上了」，从而把 cacheDir/update 里那个 APK 删掉。
     */
    var pendingUpdateVersion: String
        get() = store().getString(KEY_PENDING_UPDATE_VERSION, "").orEmpty()
        set(value) {
            store().edit().putString(KEY_PENDING_UPDATE_VERSION, value).apply()
        }

    /** 已经下载到本地、可以直接安装的版本（避免每次点「立即更新」都重新下 12MB） */
    var downloadedUpdateVersion: String
        get() = store().getString(KEY_DOWNLOADED_UPDATE_VERSION, "").orEmpty()
        set(value) {
            store().edit().putString(KEY_DOWNLOADED_UPDATE_VERSION, value).apply()
        }
}
