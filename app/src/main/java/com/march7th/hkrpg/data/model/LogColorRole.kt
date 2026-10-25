package com.march7th.hkrpg.data.model

/** 日志颜色语义角色——具体渲染色由 UI 层按主题解析（见 `theme/LogColors.kt`）。 */
enum class LogColorRole {
    /** 跟随 LocalContentColor */
    DEFAULT,
    INFO,
    SUCCESS,
    WARNING,
    ERROR,
    TRACE,
    RARE,

    STAR_1, STAR_2, STAR_3, STAR_4, STAR_5, STAR_6,
    ROBOT,

    ROGUELIKE_SUCCESS,
    ROGUELIKE_COMBAT,
    ROGUELIKE_EMERGENCY,
    ROGUELIKE_BOSS,
    ROGUELIKE_ABANDON,
}
