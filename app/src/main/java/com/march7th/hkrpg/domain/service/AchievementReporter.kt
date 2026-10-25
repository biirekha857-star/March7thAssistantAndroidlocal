package com.march7th.hkrpg.domain.service

/**
 * 成就上报器 —— **空实现**。
 *
 * ## 为什么保留这个类
 *
 * MAA-Meow 原本自带一套"元成就"系统（帕拉斯勋章、隐藏彩蛋、
 * 「首次使用自动战斗」这类统计），和**崩铁游戏内的成就完全是两回事**。
 * 它跟崩铁移植目标无关，已整体删除：
 *
 * - `data/achievement/` 整个包（含 `AchievementRepository` / `AchievementDefinitions`
 *   / `AchievementEvents` / 帕拉斯彩蛋）
 * - UI：`AchievementView` / `AchievementDebugView` / `PallasMedal` / `PallasCheers`
 *   / `PallasDrunkHost` / `DrunkResources` / `AchievementViewModel`
 * - 设置页的「成就」整块，以及 306 条 `achievement_*` 字符串
 *
 * 但这个类被 **85 处**调用（任务启动/停止、通知开关、日志导出、
 * 更新流程、偏好设置…），全部拆掉既啰嗦又容易引入回归。
 * 所以这里保留**同名同签名**的空实现：调用点一行不用改，
 * 成就逻辑彻底不执行。
 *
 * ## 想彻底清理的话
 *
 * 全项目搜 `achievementReporter` 与 `AchievementReporter`，
 * 把构造参数和调用语句一并删掉，再删本文件即可。
 * 注意 `AppModule.kt` / `UseCaseModule.kt` 里的 Koin 注册也要同步删。
 */
class AchievementReporter {

    /** 任务开始。 */
    fun reportTaskStarted(
        taskCount: Int,
        launchesGame: Boolean,
        gameAliveBeforeStart: Boolean? = null,
    ) = Unit

    /** 任务被停止。 */
    fun reportTaskStopped() = Unit

    /** 任务启动被拦截。 */
    fun reportTaskStartBlocked(reason: String) = Unit

    /** 全部任务完成。 */
    fun reportAllTasksCompleted(elapsedMillis: Long? = null) = Unit

    /** 通知渠道开关状态。 */
    fun reportNotificationProviders(
        enabledProviderIds: Set<String>,
        allProviderIds: Set<String>,
    ) = Unit

    /** 打开反馈群。 */
    fun reportFeedbackGroupOpened() = Unit

    /** 公告页顽固点击。 */
    fun reportAnnouncementStubbornClick() = Unit

    /** 调试模式开关变化。 */
    fun reportDebugModeChanged(enabled: Boolean) = Unit
}
