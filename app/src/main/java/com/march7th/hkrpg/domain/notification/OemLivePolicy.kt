package com.march7th.hkrpg.domain.notification

import android.os.Build

/**
 * 各 OEM 对「实时活动 / 灵动岛 / 流体云」的支持策略。
 *
 * ## 为什么要单独抽出来
 *
 * 同一套 Android API，在不同 ROM 上表现完全不同：
 *
 * | ROM | 形态 | 支持方式 |
 * |---|---|---|
 * | 小米 HyperOS | 焦点通知 / 灵动岛 | **私有** `miui.focus.*` extras |
 * | OPPO ColorOS 16+ | 流体云 | **标准** Android 16 Live Updates |
 * | OPPO ColorOS 15 及更早 | 流体云 | 只给白名单合作 App，第三方无门 |
 * | 原生 / 其它 | 状态栏胶囊 | 标准 API + promoted ongoing |
 * | 三星 One UI 8.5+ | NowBar | promoted ongoing 即可 |
 *
 * 所以这里只做**厂商识别**，具体走哪个后端由
 * `LivePublisherRouter` 决定。
 */
object OemLivePolicy {

    /** OPPO / 一加 / realme 共用 ColorOS 系 ROM。 */
    fun isOppoFamily(): Boolean {
        val brand = Build.BRAND.orEmpty().lowercase()
        val manufacturer = Build.MANUFACTURER.orEmpty().lowercase()
        val brandMatch = listOf("oppo", "oneplus", "oplus", "realme").any {
            brand.contains(it) || manufacturer.contains(it)
        }
        if (brandMatch) return true
        // 部分一加/realme 机型的 ro.product.brand 被改成 generic，
        // 但 oplusrom 属性一定存在
        return systemProperty("ro.build.version.oplusrom").isNotEmpty()
    }

    /**
     * ColorOS 版本号（如 `15.0.2`）；拿不到返回 null。
     *
     * 只看 `ro.build.version.oplusrom`，因为 `ro.build.version.release`
     * 是 Android 版本，跟 ColorOS 版本不是一回事。
     */
    fun colorOsVersion(): String? {
        val v = systemProperty("ro.build.version.oplusrom")
            .ifBlank { systemProperty("ro.oplus.version") }
        return v.ifBlank { null }
    }

    /** ColorOS 主版本，例如 15 / 16；未知返回 0。 */
    fun colorOsMajor(): Int =
        colorOsVersion()?.substringBefore('.')?.toIntOrNull() ?: 0

    /**
     * 该系统上的流体云是否能被**第三方 App** 使用。
     *
     * ColorOS 16 起官方接入 Android 16 Live Updates，遵循标准 API
     * 即可适配；更早版本只有云端白名单，第三方接不进去。
     *
     * ⚠️ 这里只作为**提示信息**用，不影响逻辑分支。实际是否走
     * Live Updates 档位一律以 `Build.VERSION.SDK_INT >= 36` 为准 ——
     * 那跟品牌无关，原生 Android 16 / 三星 / 荣耀同样支持。
     */
    fun fluidCloudLikelyAvailable(): Boolean =
        isOppoFamily() && colorOsMajor() >= 16

    fun isSamsung(): Boolean = Build.MANUFACTURER.equals("samsung", true)

    fun isXiaomiFamily(): Boolean {
        val m = Build.MANUFACTURER.orEmpty().lowercase()
        return m.contains("xiaomi") || m.contains("redmi") || m.contains("poco")
    }

    /**
     * 读系统属性。
     *
     * `android.os.SystemProperties` 是隐藏 API，直接调用在某些 ROM 上
     * 会 `NoSuchMethodError`。这里用反射包一层，失败返回空串 ——
     * 厂商识别是锦上添花，绝不能因为它崩掉整条通知链路。
     */
    private fun systemProperty(key: String): String = runCatching {
        val cls = Class.forName("android.os.SystemProperties")
        val get = cls.getMethod("get", String::class.java, String::class.java)
        (get.invoke(null, key, "") as? String).orEmpty()
    }.getOrDefault("")
}
