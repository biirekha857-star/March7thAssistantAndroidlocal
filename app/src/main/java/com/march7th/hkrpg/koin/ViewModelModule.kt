package com.march7th.hkrpg.koin

import com.march7th.hkrpg.presentation.viewmodel.AppEventsViewModel
import com.march7th.hkrpg.presentation.viewmodel.BackgroundTaskViewModel
import com.march7th.hkrpg.presentation.viewmodel.ErrorLogViewModel
import com.march7th.hkrpg.presentation.viewmodel.ExpandedControlPanelViewModel
import com.march7th.hkrpg.presentation.viewmodel.HomeViewModel
import com.march7th.hkrpg.presentation.viewmodel.LogHistoryViewModel
import com.march7th.hkrpg.presentation.viewmodel.NotificationSettingsViewModel
import com.march7th.hkrpg.presentation.viewmodel.SettingsViewModel
import com.march7th.hkrpg.presentation.viewmodel.TaskOverrideEditorViewModel
import com.march7th.hkrpg.presentation.viewmodel.UpdateViewModel
import com.march7th.hkrpg.schedule.ui.ScheduleEditViewModel
import com.march7th.hkrpg.schedule.ui.ScheduleListViewModel
import com.march7th.hkrpg.schedule.ui.ScheduleTriggerLogViewModel
import org.koin.core.module.dsl.singleOf
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module


val viewModelModule = module {
    viewModelOf(::HomeViewModel)
    viewModelOf(::AppEventsViewModel)
    viewModelOf(::SettingsViewModel)
    viewModelOf(::UpdateViewModel)
    viewModelOf(::LogHistoryViewModel)
    viewModelOf(::ErrorLogViewModel)
    viewModelOf(::BackgroundTaskViewModel)
    viewModelOf(::ScheduleListViewModel)
    viewModelOf(::ScheduleEditViewModel)
    viewModelOf(::ScheduleTriggerLogViewModel)
    viewModelOf(::NotificationSettingsViewModel)
    viewModelOf(::TaskOverrideEditorViewModel)
}


/**
 * 悬浮窗面板模块。
 *
 * 原本还注册 CopilotViewModel（自动战斗/抄作业）和 ToolboxViewModel
 * （公招识别、仓库识别、干员识别、牛牛抽卡等），这些是**明日方舟专属**
 * 功能，与崩铁移植无关，已整体删除。
 */
val floatingWindowModule = module {
    singleOf(::ExpandedControlPanelViewModel)
}
