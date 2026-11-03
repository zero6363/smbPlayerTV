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
     * 后台播放：离开播放页（息屏 / 切到后台）时是否继续播放，默认 true。
     * 关闭后，播放页 onStop 会暂停，避免离开时后台出声。
     */
    var backgroundPlay: Boolean
        get() = store().getBoolean(KEY_BACKGROUND_PLAY, true)
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
}
