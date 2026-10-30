package com.march7th.hkrpg.constant

object Routes {
    const val HOME = "home"
    const val SETTINGS = "settings"
    // 设置二级页：一级页只放分类入口，具体开关都在这些子页里
    const val SETTINGS_GENERAL = "settings_general"
    const val SETTINGS_LAUNCH = "settings_launch"
    const val SETTINGS_LOG = "settings_log"
    const val SETTINGS_DATA = "settings_data"
    const val SETTINGS_ABOUT = "settings_about"
    const val ACHIEVEMENT = "achievement"
    const val ACHIEVEMENT_DEBUG = "achievement_debug"
    const val LOG_HISTORY = "log_history"
    const val ERROR_LOG = "error_log"
    const val SCHEDULE = "schedule"
    const val SCHEDULE_EDIT = "schedule_edit/{strategyId}"
    const val SCHEDULE_TRIGGER_LOG = "schedule_trigger_log"
    const val NOTIFICATION = "notification"
    const val WALLPAPER = "wallpaper"
    const val TASK_OVERRIDE_EDITOR = "task_override_editor"
}
