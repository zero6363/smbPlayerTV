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
            version = "1.6-tv",
            date = "2026-11-03",
            lines = listOf(
                "主页宫格改为 6 列，卡片缩小一半，一屏能看到更多内容；最近播放上限改为 12 条（正好两排）",
                "顶栏配色改为深灰，蓝色只用来表示遥控器焦点，焦点框和顶栏不再混淆",
                "播放页：单击左右键快进 10 秒，按住连发每一步 30 秒",
            ),
        ),
        ChangelogEntry(
            version = "1.5-tv",
            date = "2026-11-03",
            lines = listOf(
                "播放容错：源文件个别帧损坏时自动跳过损坏片段继续播放，不再直接弹窗中断",
                "同一处最多连续尝试 4 次；距片尾出错按播放完毕处理，自动顺延下一集",
                "开启解码器失败回退（硬解失败自动换软解），减少整片打不开的情况",
            ),
        ),
        ChangelogEntry(
            version = "1.4-tv",
            date = "2026-11-03",
            lines = listOf(
                "主页宫格改为 3 列，卡片改为正方形（高度等于宽度），最近播放 / 收藏路径 / 服务器统一为方形卡片",
                "卡片封面图标放大，历史卡片右下角新增影片时长角标",
                "服务器卡片的「>」进入标识移到封面右上角",
            ),
        ),
        ChangelogEntry(
            version = "1.3-tv",
            date = "2026-11-03",
            lines = listOf(
                "播放页键位调整：左右键专职控制进度，上下键弹出控制条并在按钮之间切换焦点",
                "音量改用遥控器自带的音量键，播放页不再占用上下键",
                "进度条不再参与焦点，避免切换按钮时停在进度条上",
            ),
        ),
        ChangelogEntry(
            version = "1.2-tv",
            date = "2026-11-03",
            lines = listOf(
                "主页改为宫格视图（2 列）：最近播放、收藏路径、服务器都用卡片排列，分区标题和底部操作条目占满整行",
                "服务器卡片去掉编辑/删除小图标（长按确认键的菜单里已有），保留「>」进入标识",
                "播放页播放列表支持遥控器选择：打开时焦点直接落在当前播放的视频上",
                "浏览页按遥控器菜单/设置键，焦点跳到顶部工具栏按钮",
            ),
        ),
        ChangelogEntry(
            version = "1.1-tv",
            date = "2026-11-03",
            lines = listOf(
                "主页改为单个列表：最近播放 / 收藏路径 / 服务器 / 操作条目一条链，遥控器焦点不再卡在分区边界",
                "添加服务器、设置改为列表底部条目；工具栏不再有可聚焦按钮，焦点不会在按钮与列表之间循环",
                "冷启动和返回主页自动落焦点；进入文件夹自动定位到第一条并滚到顶部",
                "播放页键位分工：左右键只负责快退/快进（不再拉起控制条），上下键调音量，菜单键呼出控制条后方向键操作按钮",
                "退出播放改为连按两次返回（提示「再按一次返回退出」，不弹窗）",
                "浏览页顶栏显示「文件夹 x · 文件 y」；对不返回文件属性的服务器补查目录类型，避免文件夹被当成文件",
                "焦点样式加强为 3dp 蓝色描边，远距离也能看清",
            ),
        ),
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
