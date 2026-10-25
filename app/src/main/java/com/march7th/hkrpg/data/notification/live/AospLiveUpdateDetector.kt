package com.march7th.hkrpg.data.notification.live

import android.app.NotificationManager
import android.content.Context
import android.os.Build
import com.march7th.hkrpg.domain.notification.OemLivePolicy

/**
 * 检测 Android 16「实时更新（Live Updates）」能力。
 *
 * ## 什么是 Live Updates
 *
 * Android 16（API 36）引入的一等公民特性：App 用
 * [android.app.Notification.ProgressStyle] 声明"这是一个正在进行的活动"，
 * 系统负责在**状态栏胶囊 / 锁屏 / AOD** 里持续渲染进度。
 *
 * 与旧的 promoted ongoing 的区别：promoted 只是"提权"，系统随时可能降级；
 * Live Updates 是系统认可的活动，有独立的展示位和更长的生命周期。
 *
 * ## 和 OPPO 流体云的关系
 *
 * **同一套 API。** OPPO ColorOS 16 的流体云完整接入了 Android 16
 * Live Updates —— 官方原话是"遵循谷歌实时活动 API 开发规范，就能
 * 直接打通适配 OPPO 的流体云"，且明确说采用"双兼容方案"。
 *
 * 所以这里**不需要任何 OPPO 私有接口**：只要系统是 API 36 以上，
 * 就走标准路径；在 ColorOS 16+ 上系统会自动把它渲染成流体云。
 *
 * ## 为什么 ColorOS 15 不行
 *
 * 实测这台机器（ColorOS 15 / API 35）：
 * ```
 * seed_whitelist=0
 * fluid_seeding_notification=[{"pkg":"com.android.incallui"},
 *                             {"pkg":"com.oplus.screenrecorder"},
 *                             {"pkg":"com.coloros.alarmclock"}]
 * ```
 * 流体云走的是**云端白名单**，只有系统应用和商务合作方（滴滴、抖音、
 * 高德）能进。第三方 App 没有公开的注册接口，纯代码无解。
 */
class AospLiveUpdateDetector(context: Context) {

    private val appContext = context.applicationContext

    /**
     * 系统是否具备 Live Updates 能力。
     *
     * 只认 `SDK_INT >= 36`，**不看品牌** —— 原生 Android 16、
     * ColorOS 16、三星 One UI 8.5+ 等都满足。
     */
    fun isApiSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA

    /**
     * 是否应该启用 Live Updates 档位。
     *
     * 除了 API 版本，还要确认通知本身没被用户关掉 ——
     * 关掉通知的时候申请活动胶囊没有任何意义。
     */
    fun isAvailable(): Boolean {
        if (!isApiSupported()) return false
        return runCatching {
            appContext.getSystemService(NotificationManager::class.java)
                ?.areNotificationsEnabled() ?: false
        }.getOrDefault(false)
    }

    /** 当前是否 ColorOS 系 ROM（仅用于 UI 文案/日志）。 */
    fun isColorOs(): Boolean = OemLivePolicy.isOppoFamily()

    /** ColorOS 版本（仅用于 UI 文案/日志）。 */
    fun colorOsVersion(): String? =
        if (OemLivePolicy.isOppoFamily()) OemLivePolicy.colorOsVersion() else null

    /**
     * 给设置页用的一句话说明：这台机器上的"岛/胶囊"现在能不能用。
     *
     * 之所以要写这个，是因为用户在 ColorOS 15 上会疑惑
     * "为什么我开了却不显示流体云" —— 那是系统白名单限制，
     * 不是功能坏了。
     */
    fun statusHint(): String = when {
        !isApiSupported() && isColorOs() ->
            "当前是 ColorOS ${colorOsVersion() ?: "旧版"}（Android ${Build.VERSION.RELEASE}），" +
                "系统未开放第三方流体云接入；升级到 ColorOS 16（Android 16）后自动支持"

        !isApiSupported() ->
            "当前 Android ${Build.VERSION.RELEASE}，状态栏活动胶囊需要 Android 16"

        isColorOs() ->
            "ColorOS ${colorOsVersion() ?: "16+"} 已支持实时活动，任务进行时会显示在流体云"

        else ->
            "系统支持实时活动，任务进行时会显示在状态栏胶囊"
    }
}
