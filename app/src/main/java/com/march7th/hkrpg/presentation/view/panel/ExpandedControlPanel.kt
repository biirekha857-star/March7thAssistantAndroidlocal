package com.march7th.hkrpg.presentation.view.panel

import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.march7th.hkrpg.R
import com.march7th.hkrpg.data.preferences.AppSettingsManager
import com.march7th.hkrpg.domain.models.RunMode
import com.march7th.hkrpg.domain.service.TaskCompositionService
import com.march7th.hkrpg.domain.state.TaskExecutionState
import com.march7th.hkrpg.presentation.LocalFloatingWindowContext
import com.march7th.hkrpg.presentation.LocalInputFocusManager
import com.march7th.hkrpg.presentation.components.AdaptiveTaskPromptDialog
import com.march7th.hkrpg.presentation.components.ResourceLoadingOverlay
import com.march7th.hkrpg.presentation.components.clearFocusOnBlankTap
import com.march7th.hkrpg.presentation.state.UiEffect
import com.march7th.hkrpg.presentation.view.panel.PanelDialogType.ERROR
import com.march7th.hkrpg.presentation.view.panel.PanelDialogType.SUCCESS
import com.march7th.hkrpg.presentation.viewmodel.ExpandedControlPanelViewModel
import com.march7th.hkrpg.utils.i18n.asString
import com.march7th.hkrpg.utils.i18n.resolve
import org.koin.compose.koinInject


@Composable
fun ExpandedControlPanel(
    modifier: Modifier = Modifier,
    onClose: () -> Unit,
    onHome: () -> Unit = {},
    isLocked: Boolean = false,
    onLockToggle: (Boolean) -> Unit = {},
    viewModel: ExpandedControlPanelViewModel = viewModel(),
    service: TaskCompositionService = koinInject(),
    appSettings: AppSettingsManager = koinInject(),
) {
    val uiState by viewModel.state.collectAsStateWithLifecycle()
    val maaState by service.state.collectAsStateWithLifecycle()
    val runMode by appSettings.runMode.collectAsStateWithLifecycle()

    val nodes by viewModel.chainState.chain.collectAsStateWithLifecycle()
    val profiles by viewModel.chainState.profiles.collectAsStateWithLifecycle()
    val profileId by viewModel.chainState.profileId.collectAsStateWithLifecycle()
    val selectedNode = nodes.find { it.id == uiState.selectedNodeId }
    val clientType = remember(nodes) { viewModel.chainState.clientType }
    val inputFocusManager = LocalInputFocusManager.current
    val context = LocalContext.current

    val pagerState = rememberPagerState(
        initialPage = uiState.currentTab.ordinal,
        pageCount = { PanelTab.entries.size }
    )

    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { page ->
            val newTab = PanelTab.entries[page]
            if (newTab != uiState.currentTab) {
                viewModel.onTabChange(newTab)
            }
        }
    }

    LaunchedEffect(uiState.currentTab) {
        if (pagerState.currentPage != uiState.currentTab.ordinal) {
            pagerState.scrollToPage(uiState.currentTab.ordinal)
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is UiEffect.Toast -> Toast.makeText(
                    context,
                    effect.message.resolve(context),
                    if (effect.long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .clearFocusOnBlankTap()
    ) {
        Card(
            modifier = Modifier
                .fillMaxSize()
                .shadow(
                    elevation = 4.dp,
                    shape = RoundedCornerShape(4.dp)
                ),
            shape = RoundedCornerShape(4.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp)
            ) {
                // 标题栏
                PanelHeader(
                    selectedTab = uiState.currentTab,
                    onTabSelected = viewModel::onTabChange,
                    isLocked = isLocked,
                    onLockToggle = onLockToggle,
                    onHome = onHome
                )

                // 中间内容区域 - 使用 HorizontalPager
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    userScrollEnabled = false,
                    beyondViewportPageCount = 1
                ) { page ->
                    when (page) {
                        0 -> { // PanelTab.ONE_KEY_TASKS
                            TaskListDetailLayout(
                                nodes = nodes,
                                selectedNode = selectedNode,
                                selectedNodeId = uiState.selectedNodeId,
                                isEditMode = uiState.isEditMode,
                                isAddingTask = uiState.isAddingTask,
                                isProfileMode = uiState.isProfileMode,
                                profiles = profiles,
                                activeProfileId = profileId,
                                clientType = clientType,
                                onNodeEnabledChange = viewModel::onNodeEnabledChange,
                                onNodeSelected = viewModel::onNodeSelected,
                                onNodeMove = viewModel::onNodeMove,
                                onToggleEditMode = viewModel::onToggleEditMode,
                                onToggleAddingTask = viewModel::onToggleAddingTask,
                                onToggleProfileMode = viewModel::onToggleProfileMode,
                                onConfigChange = { config ->
                                    selectedNode?.id?.let {
                                        viewModel.onNodeConfigChange(it, config)
                                    }
                                },
                                onAddNode = viewModel::onAddNode,
                                onRemoveNode = viewModel::onRemoveNode,
                                onDuplicateNode = viewModel::onDuplicateNode,
                                onRenameNode = viewModel::onRenameNode,
                                onSwitchProfile = viewModel::onSwitchProfile,
                                onRenameProfile = viewModel::onRenameProfile,
                                onDuplicateProfile = viewModel::onDuplicateProfile,
                                onDeleteProfile = viewModel::onDeleteProfile,
                                onCreateProfile = viewModel::onCreateProfile,
                                onReorderProfile = viewModel::onReorderProfile,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }

                        1 -> { // PanelTab.LOG
                            val runtimeLogs by viewModel.runtimeLogs.collectAsStateWithLifecycle()
                            LogPanel(
                                logs = runtimeLogs,
                                onClearLogs = { viewModel.onClearLogs() },
                            )
                        }
                    }
                }

                if (PanelTab.canShowTaskActions(uiState.currentTab)) {
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 6.dp),
                        thickness = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                    BottomButtons(
                        onClose = { onClose() },
                        onStart = {
                            inputFocusManager.clear()
                            viewModel.onStartTasks()
                        },
                        isStarting = maaState == TaskExecutionState.STARTING
                    )
                }
            }
        }


        val dialog = uiState.dialog
        val dialogTitle = dialog?.title.asString()
        val dialogMessage = dialog?.message.asString()
        val dialogConfirmText = dialog?.confirmText.asString()
        val dialogDismissText = dialog?.dismissText.asString()
        val confirmColor = when (dialog?.type) {
            SUCCESS -> MaterialTheme.colorScheme.primary
            ERROR -> MaterialTheme.colorScheme.error
            else -> MaterialTheme.colorScheme.tertiary
        }
        AdaptiveTaskPromptDialog(
            visible = dialog != null,
            onDismissRequest = viewModel::onDialogDismiss,
            title = dialogTitle,
            message = AnnotatedString(dialogMessage),
            icon = when (dialog?.type) {
                SUCCESS -> Icons.Filled.CheckCircle
                else -> Icons.Filled.Warning
            },
            iconTint = confirmColor,
            confirmColor = confirmColor,
            confirmText = dialogConfirmText.takeIf { it.isNotBlank() }
                ?: stringResource(R.string.common_confirm),
            dismissText = dialogDismissText.takeIf { it.isNotBlank() }
                ?: stringResource(R.string.common_close),
            onConfirm = viewModel::onDialogConfirm,
        )
    }
}
