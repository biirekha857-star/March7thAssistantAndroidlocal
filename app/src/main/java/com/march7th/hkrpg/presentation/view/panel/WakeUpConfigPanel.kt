package com.march7th.hkrpg.presentation.view.panel

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.march7th.hkrpg.R
import com.march7th.hkrpg.data.model.WakeUpConfig
import com.march7th.hkrpg.domain.service.TaskCompositionService
import com.march7th.hkrpg.domain.state.TaskExecutionState
import com.march7th.hkrpg.presentation.components.CheckBoxWithLabel
import com.march7th.hkrpg.presentation.components.ITextField
import com.march7th.hkrpg.presentation.components.SelectableChipGroup
import com.march7th.hkrpg.utils.i18n.wakeUpClientTypeDisplayName
import org.koin.compose.koinInject

/**
 * 开始唤醒配置面板
 *
 * see StartUpSettingsUserControl.xaml
 * 包含客户端类型选择功能
 */
@Composable
fun WakeUpConfigPanel(
    config: WakeUpConfig,
    onConfigChange: (WakeUpConfig) -> Unit,
    compositionService: TaskCompositionService = koinInject()
) {
    val context = LocalContext.current
    val state = compositionService.state.collectAsStateWithLifecycle()
    val isTaskActive =
        state.value == TaskExecutionState.STARTING || state.value == TaskExecutionState.RUNNING
    val showAccountSwitchInput = config.clientType in WakeUpConfig.ACCOUNT_SWITCH_CLIENT_TYPES

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(PaddingValues(start = 12.dp, end = 12.dp, top = 2.dp, bottom = 4.dp)),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 客户端类型选择
        SelectableChipGroup(
            label = stringResource(R.string.panel_wakeup_client_type),
            selectedValue = config.clientType,
            options = WakeUpConfig.CLIENT_TYPES.map { it to context.wakeUpClientTypeDisplayName(it) },
            onSelected = { onConfigChange(config.copy(clientType = it)) },
            enabled = !isTaskActive,
            labelFontWeight = FontWeight.Medium
        )

        if (showAccountSwitchInput) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ITextField(
                    value = config.accountName,
                    onValueChange = { onConfigChange(config.copy(accountName = it)) },
                    enabled = !isTaskActive,
                    label = stringResource(R.string.panel_wakeup_account_switch),
                    placeholder = "123****4567"
                )

                Text(
                    text = stringResource(R.string.panel_wakeup_account_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant,
                            RoundedCornerShape(8.dp)
                        )
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                )
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)

        // 启动游戏开关
        CheckBoxWithLabel(
            checked = config.startGameEnabled,
            onCheckedChange = { onConfigChange(config.copy(startGameEnabled = it)) },
            label = stringResource(R.string.panel_wakeup_launch_game)
        )
    }
}
