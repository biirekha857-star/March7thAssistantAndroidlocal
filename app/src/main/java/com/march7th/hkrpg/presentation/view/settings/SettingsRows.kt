package com.march7th.hkrpg.presentation.view.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.march7th.hkrpg.R
import com.march7th.hkrpg.presentation.components.AdaptiveTaskPromptDialog
import com.march7th.hkrpg.presentation.components.SettingRow
import com.march7th.hkrpg.presentation.components.TopAppBar
import com.march7th.hkrpg.theme.AppDesignTokens

/**
 * 设置二级页的统一骨架：带返回箭头的顶栏 + 纵向分组列表。
 *
 * 一级页（[SettingsView]）只负责分类入口，所有开关都落在这些子页里，
 * 免得首页一次铺开二十几行。所有子页共用同一个骨架，滚动边距与分组间距才一致。
 */
@Composable
internal fun SettingsDetailScreen(
    title: String,
    navController: NavController,
    content: LazyListScope.() -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = title,
                navigationIcon = Icons.AutoMirrored.Filled.ArrowBack,
                onNavigationClick = { navController.navigateUp() },
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(
                horizontal = AppDesignTokens.Spacing.listHorizontal,
                vertical = AppDesignTokens.Spacing.sm,
            ),
            verticalArrangement = Arrangement.spacedBy(AppDesignTokens.Spacing.sectionGap),
            content = content,
        )
    }
}

/**
 * 可跳转的设置行：标题 +（可选）说明与当前值 + 右箭头。
 *
 * 与 [SettingRow] 的区别是右箭头贴在最右侧，不再给文字留 16dp 尾距，
 * 观感与系统设置一致。
 */
@Composable
internal fun SettingClickItem(
    title: String,
    description: String? = null,
    value: String? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = AppDesignTokens.Spacing.listItemVertical),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(AppDesignTokens.Spacing.rowTitleGap),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (description != null) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (value != null) {
            Spacer(modifier = Modifier.width(AppDesignTokens.Spacing.sm))
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.width(AppDesignTokens.Spacing.xs))
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
        )
    }
}

@Composable
internal fun SettingSwitchItem(
    title: String,
    description: String? = null,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    SettingRow(
        title = title,
        description = description,
        titleColor = contentColor,
        descriptionColor = contentColor.copy(alpha = 0.7f),
        enabled = enabled,
        trailing = {
            Switch(
                checked = checked,
                enabled = enabled,
                onCheckedChange = onCheckedChange,
            )
        },
    )
}

@Composable
internal fun SettingInfoRow(
    label: String,
    value: String,
) {
    SettingRow(
        title = label,
        trailing = {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    )
}

/**
 * 单选弹窗：把原来铺在行里的圆点收进弹窗，行上只显示当前值。
 *
 * 手机系统设置的常规做法，也避免了窄屏上四五个选项挤一行。
 */
@Composable
internal fun <T> SettingOptionDialog(
    title: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    AdaptiveTaskPromptDialog(
        visible = true,
        title = title,
        confirmText = stringResource(R.string.common_cancel),
        dismissText = "",
        onConfirm = onDismiss,
        onDismissRequest = onDismiss,
        content = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                options.forEach { (option, label) ->
                    val isSelected = option == selected
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(AppDesignTokens.CornerRadius.inner))
                            .selectable(
                                selected = isSelected,
                                role = Role.RadioButton,
                                onClick = {
                                    onSelect(option)
                                    onDismiss()
                                },
                            )
                            .padding(horizontal = AppDesignTokens.Spacing.sm, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = isSelected, onClick = null)
                        Spacer(modifier = Modifier.width(AppDesignTokens.Spacing.sm))
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        },
    )
}
