package com.march7th.hkrpg.overlay.border

import androidx.core.graphics.toColorInt

/**
 * 边框样式配置
 */
data class BorderStyle(
    /** 边框宽度 (dp) */
    val widthDp: Float = 2f,
    /** 渐变颜色列表 */
    val colors: IntArray = DEFAULT_RAINBOW_COLORS,
    /** 动画一圈的时长 (毫秒) */
    val animationDurationMs: Long = 3000L
) {
    companion object {
        /** 默认彩虹色 */
        val DEFAULT_RAINBOW_COLORS = intArrayOf(
            "#FF0000".toColorInt(), // 红
            "#FF7F00".toColorInt(), // 橙
            "#FFFF00".toColorInt(), // 黄
            "#00FF00".toColorInt(), // 绿
            "#00FFFF".toColorInt(), // 青
            "#0000FF".toColorInt(), // 蓝
            "#8B00FF".toColorInt(), // 紫
            "#FF0000".toColorInt()  // 回到红（闭环）
        )
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as BorderStyle
        if (widthDp != other.widthDp) return false
        if (!colors.contentEquals(other.colors)) return false
        if (animationDurationMs != other.animationDurationMs) return false
        return true
    }

    override fun hashCode(): Int {
        var result = widthDp.hashCode()
        result = 31 * result + colors.contentHashCode()
        result = 31 * result + animationDurationMs.hashCode()
        return result
    }
}
