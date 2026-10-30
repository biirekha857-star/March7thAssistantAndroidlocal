package com.march7th.hkrpg.presentation.state

import com.march7th.hkrpg.data.model.update.UpdateProcessState
import com.march7th.hkrpg.domain.models.RunMode
import com.march7th.hkrpg.domain.state.ResourceInitState
import com.march7th.hkrpg.utils.i18n.UiText

data class HomeUiState(
    val isShowControlOverlay: Boolean = false,
    val isLoading: Boolean = false,
    val resourceUpdateState: UpdateProcessState = UpdateProcessState.Idle,
    val serviceStatusText: UiText = UiText.Empty,
    val serviceStatusColor: StatusColorType = StatusColorType.NEUTRAL,
    val serviceStatusLoading: Boolean = false,
    val remoteServiceActive: Boolean = false,
    val resourceInitState: ResourceInitState = ResourceInitState.NotChecked,
    val runMode: RunMode = RunMode.BACKGROUND,
    val isGranting: Boolean = false,
    val showRunModeUnsupportedDialog: Boolean = false,
    val runModeUnsupportedMessage: UiText = UiText.Empty
)
