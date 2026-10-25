package com.march7th.hkrpg.presentation.viewmodel

import android.content.Context
import com.march7th.hkrpg.R
import com.march7th.hkrpg.domain.service.TaskCompositionService
import com.march7th.hkrpg.domain.service.resolveStartResultMessage
import com.march7th.hkrpg.domain.state.TaskExecutionState
import com.march7th.hkrpg.presentation.view.panel.PanelDialogConfirmAction
import com.march7th.hkrpg.presentation.view.panel.PanelDialogType
import com.march7th.hkrpg.presentation.view.panel.PanelDialogUiState
import com.march7th.hkrpg.utils.i18n.UiText
import com.march7th.hkrpg.utils.i18n.uiTextOf

internal fun Context.resolveTaskStartFailureMessage(
    result: TaskCompositionService.StartResult,
): UiText? = resolveStartResultMessage(result)

internal fun Context.formatStartResult(
    result: TaskCompositionService.StartResult,
    successMessage: UiText = uiTextOf(R.string.task_start_success),
): UiText {
    return resolveTaskStartFailureMessage(result) ?: successMessage
}

internal fun Context.createStartFailedDialog(message: UiText): PanelDialogUiState {
    return PanelDialogUiState(
        type = PanelDialogType.ERROR,
        title = uiTextOf(R.string.task_start_dialog_failed_title),
        message = message,
        confirmText = uiTextOf(R.string.task_start_dialog_view_log),
        confirmAction = PanelDialogConfirmAction.GO_LOG,
    )
}

internal fun Context.createStartBlockedDialog(message: UiText): PanelDialogUiState {
    return PanelDialogUiState(
        type = PanelDialogType.WARNING,
        title = uiTextOf(R.string.task_start_dialog_info_title),
        message = message,
        confirmText = uiTextOf(R.string.task_start_dialog_ack),
        confirmAction = PanelDialogConfirmAction.DISMISS_ONLY,
    )
}

internal fun Context.createStartWarningDialog(message: UiText): PanelDialogUiState {
    return PanelDialogUiState(
        type = PanelDialogType.WARNING,
        title = uiTextOf(R.string.toolbox_dialog_start_warning_title),
        message = message,
        confirmText = uiTextOf(R.string.toolbox_dialog_start_anyway),
        dismissText = uiTextOf(R.string.common_cancel),
        confirmAction = PanelDialogConfirmAction.CONFIRM_PENDING_START,
    )
}

internal fun Context.createExecutionEndDialog(endState: TaskExecutionState): PanelDialogUiState {
    val message = when (endState) {
        TaskExecutionState.ERROR -> uiTextOf(R.string.task_start_execution_aborted_message)
        else -> uiTextOf(R.string.task_start_execution_finished_message)
    }
    return if (endState == TaskExecutionState.ERROR) {
        PanelDialogUiState(
            type = PanelDialogType.ERROR,
            title = uiTextOf(R.string.task_start_dialog_info_title),
            message = message,
            confirmText = uiTextOf(R.string.task_start_dialog_ack),
            confirmAction = PanelDialogConfirmAction.GO_LOG_AND_STOP,
        )
    } else {
        PanelDialogUiState(
            type = PanelDialogType.SUCCESS,
            title = uiTextOf(R.string.task_start_execution_completed_title),
            message = message,
            confirmText = uiTextOf(R.string.task_start_dialog_view_log),
            confirmAction = PanelDialogConfirmAction.GO_LOG,
        )
    }
}
