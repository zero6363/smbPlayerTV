package com.smbplayer.tv.data

/**
 * 字幕字号档位。
 *
 * 字号不用固定 sp，而是按**屏幕高度的比例**换算成像素：
 * TV 盒子的 density 差异极大——同样 1080p 屏，有的盒子 density=1（1dp == 1px），
 * 有的 2（1dp == 2px）。写死 18sp 在 density=1 的盒子上只有 18px 高，
 * 不到屏高的 2%，离电视两米基本看不清。按屏高比例算，1080p / 4K 观感一致。
 */
object SubtitleSizes {

    /** 档位下标：0 小、1 中、2 大（与设置页单选项顺序一致） */
    const val SMALL = 0
    const val MEDIUM = 1
    const val LARGE = 2

    /** 默认档位：中（1080p 下字号约 52px，占屏高 4.8%） */
    const val DEFAULT = MEDIUM

    /** 各档位字号占屏幕高度的比例 */
    private val RATIOS = floatArrayOf(0.038f, 0.048f, 0.060f)

    /** 取档位对应的字号比例；非法值回退默认档位 */
    fun ratioOf(level: Int): Float = RATIOS.getOrElse(level) { RATIOS[DEFAULT] }
}
