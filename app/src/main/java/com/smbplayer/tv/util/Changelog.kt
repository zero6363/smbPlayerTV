package com.smbplayer.tv.util

/**
 * 更新日志数据。
 *
 * 每次发版在这里最前面加一条，并同步：
 * 1. `app/build.gradle.kts` 的 versionName / versionCode；
 * 2. 项目根目录的 `CHANGELOG.md`。
 */
data class ChangelogEntry(
    val version: String,
    val date: String,
    val lines: List<String>,
)

object Changelog {

    val releases: List<ChangelogEntry> = listOf(
        ChangelogEntry(
            version = "1.0-tv",
            date = "2026-11-03",
            lines = listOf(
                "TV 版首个发布：基于手机版 SMB 播放器复刻，全面适配电视与遥控器操作",
                "最低支持 Android 5.0（API 21），覆盖老电视盒子",
                "遥控器键位：确认键播放/暂停，左右键快退/快进 10 秒（按住连发、松开生效，实时预览落点），上下键音量增减，菜单键呼出控制条，媒体键全支持",
                "返回键退出播放前二次确认，防遥控器误触；退出/取消时自动保存并恢复播放进度",
                "主页新增「最近播放」：展示最近 10 条播放记录，点击直接续播，长按可删除记录",
                "移除截取帧功能（上一帧/下一帧/导出当前帧）；视频渲染改用 SurfaceView，低配盒子性能更好",
                "深色主题、全屏横屏、TV 桌面横幅图标；焦点样式全面适配遥控器",
            ),
        ),
    )

    /** 拼成纯文本，供「更新日志」对话框展示 */
    fun asText(): String = releases.joinToString(separator = "\n\n") { entry ->
        buildString {
            append("v").append(entry.version).append("（").append(entry.date).append("）")
            entry.lines.forEach { line ->
                append("\n· ").append(line)
            }
        }
    }
}
