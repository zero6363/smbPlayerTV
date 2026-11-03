package com.smbplayer.tv.ui

import android.content.Context
import android.util.AttributeSet
import android.widget.LinearLayout

/**
 * 高度强制等于宽度的 LinearLayout，主页宫格卡片用它保证「正方形」。
 *
 * 网格布局测量时宽度一定是确定值（EXACTLY），所以直接把宽度的 MeasureSpec
 * 当作高度的 MeasureSpec 传给父类即可，不需要额外的宽高比属性。
 */
class SquareLinearLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, widthMeasureSpec)
    }
}
