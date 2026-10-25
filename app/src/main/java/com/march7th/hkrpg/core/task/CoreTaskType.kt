package com.march7th.hkrpg.core.task

/**
 * 任务类型枚举。
 *
 * `value` 是传给 `AppendTask` 的字符串，由内核侧解释。
 *
 * ## 崩铁内核（当前生效）
 *
 * 见 [com.march7th.hkrpg.starrail.StarRailTaskRegistry]：
 * `StartUp` / `CloseDown` / `Mail` / `Quest` / `Achievement`。
 *
 * ## 明日方舟枚举（保留）
 *
 * 下面是原 MaaCore 的任务类型。崩铁内核会明确拒绝这些（返回 -1），
 * 保留它们是为了将来若要并行支持方舟时不必重写枚举。
 */
enum class CoreTaskType(val value: String) {
    // ==================== 崩铁任务（当前生效）====================
    // 基础
    START_UP("StartUp"),
    CLOSE_DOWN("CloseDown"),
    // 奖励
    MAIL("Mail"),
    // 体力
    POWER("Power"),
    // 每日实训
    DAILY("Daily"),

    // ==================== 明日方舟任务（保留，崩铁内核不支持）====================
    // 日常任务
    FIGHT("Fight"),
    RECRUIT("Recruit"),
    INFRAST("Infrast"),
    MALL("Mall"),
    AWARD("Award"),
    SWITCH_THEME("SwitchTheme"),

    // 特殊任务
    ROGUELIKE("Roguelike"),
    RECLAMATION("Reclamation"),

    // 辅助任务
    COPILOT("Copilot"),
    SSS_COPILOT("SSSCopilot"),
    PARADOX_COPILOT("ParadoxCopilot"),

    // 识别任务
    DEPOT("Depot"),
    OPER_BOX("OperBox"),

    // 调试任务（不进 UI；由 adb 直接调 AppendTask 触发）
    DUMP("Dump"),

    // 自定义任务（小游戏等）
    CUSTOM("Custom");

    companion object {
        fun fromValue(value: String): CoreTaskType? =
            entries.find { it.value == value }
    }
}
