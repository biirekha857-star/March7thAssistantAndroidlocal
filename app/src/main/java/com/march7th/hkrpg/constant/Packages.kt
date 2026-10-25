package com.march7th.hkrpg.constant

/**
 * 客户端类型 → 游戏包名。
 *
 * 换芯为崩铁后改为《崩坏：星穹铁道》的包名。
 * key 沿用外壳既有的 `clientType` 标识（UI 上的「客户端类型」选择器），
 * 这样任务链、调度、通知等外壳逻辑一行不用改。
 *
 * > 原明日方舟包名（com.hypergryph.arknights 等）已移除。
 */
object Packages : Iterable<Map.Entry<String, String>> {
    private val packageName = mapOf(
        // 国服
        "Official" to "com.miHoYo.hkrpg",
        // 国际服
        "YoStarEN" to "com.HoYoverse.hkrpgoversea",
    )

    operator fun get(type: String): String? = packageName[type]

    override fun iterator(): Iterator<Map.Entry<String, String>> {
        return packageName.iterator()
    }
}
