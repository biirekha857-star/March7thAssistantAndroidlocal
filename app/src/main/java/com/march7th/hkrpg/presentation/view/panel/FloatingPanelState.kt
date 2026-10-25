package com.march7th.hkrpg.presentation.view.panel

import androidx.annotation.StringRes
import androidx.compose.runtime.Stable
import com.march7th.hkrpg.R
import com.march7th.hkrpg.utils.i18n.UiText

/**
 * 面板 Tab 类型。
 *
 * ## 只保留「一键长草」和「日志」
 *
 * 原版 MAA-Meow 有四个页签：一键长草 / 自动战斗 / 小工具 / 日志。
 * 但自动战斗（抄作业）和小工具（公招识别、仓库识别、干员识别等）
 * 全是**明日方舟专属**功能，跟崩铁移植目标无关，已整体删除。
 *
 * 崩铁侧的每日实训、成就、体力等任务都挂在 [TASKS] 下。
 */
enum class PanelTab(@param:StringRes val labelRes: Int) {
    TASKS(R.string.panel_tab_tasks),
    LOG(R.string.panel_tab_log);

    companion object {
        /**
         * 该页签是否显示"任务操作"（开始/停止）按钮。
         *
         * 只有任务列表页需要；日志页没有可操作的任务。
         */
        fun canShowTaskActions(state: PanelTab): Boolean {
            return state == TASKS
        }
    }
}

@Stable
data class FloatingPanelState(
    val isExpanded: Boolean = false,
    val currentTab: PanelTab = PanelTab.TASKS,
    val selectedNodeId: String? = null,
    val isEditMode: Boolean = false,
    val isAddingTask: Boolean = false,
    val isProfileMode: Boolean = false,
    val dialog: PanelDialogUiState? = null
)

enum class PanelDialogType {
    SUCCESS,
    WARNING,
    ERROR
}

enum class PanelDialogConfirmAction {
    DISMISS_ONLY,
    CONFIRM_PENDING_START,
    GO_LOG,
    GO_LOG_AND_STOP
}

@Stable
data class PanelDialogUiState(
    val type: PanelDialogType,
    val title: UiText,
    val message: UiText,
    val confirmText: UiText = UiText.Empty,
    val dismissText: UiText? = null,
    val confirmAction: PanelDialogConfirmAction = PanelDialogConfirmAction.DISMISS_ONLY
)
