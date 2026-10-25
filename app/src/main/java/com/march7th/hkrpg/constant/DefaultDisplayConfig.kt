package com.march7th.hkrpg.constant

/**
 * 默认允许的一些宽高配置
 *
 * ## 换芯为崩铁后的调整
 *
 * 原默认是 720p（明日方舟的识别资源按 720p 校准）。
 * 崩铁的模板图来自 PC 端 1920×1080，且实测「高归一化→1080」策略匹配最好，
 * 所以**默认改为 1080p**。
 */
object DefaultDisplayConfig {
    const val VD_NAME = "MAA_VD"
    const val DISPLAY_NONE = -1

    // 1080p（崩铁默认；模板来自 PC 端 1920×1080）
    const val WIDTH = 1920
    const val HEIGHT = 1080
    const val DPI = 240

    const val ASPECT_RATIO_WIDTH = 16
    const val ASPECT_RATIO_HEIGHT = 9

    /** 16:9 宽高比 */
    val ASPECT_RATIO: Float get() = WIDTH.toFloat() / HEIGHT

    const val FRAME_INTERVAL_MS = 16L

    data class Resolution(val width: Int, val height: Int, val dpi: Int)

    val RES_720P = Resolution(1280, 720, 160)
    val RES_1080P = Resolution(1920, 1080, 240)

    /** 用户可选的后台虚拟屏分辨率偏好 */
    enum class ResolutionPreference { P720, P1080 }

    /**
     * 根据用户偏好 + clientType 解析最终分辨率。
     *
     * 崩铁：统一用 1080p（模板基准），忽略客户端差异。
     */
    fun resolveResolution(
        clientType: String,
        preference: ResolutionPreference
    ): Resolution = RES_1080P
}
