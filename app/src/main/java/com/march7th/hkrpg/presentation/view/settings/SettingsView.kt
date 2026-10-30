package com.march7th.hkrpg.presentation.view.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.RocketLaunch
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.march7th.hkrpg.BuildConfig
import com.march7th.hkrpg.R
import com.march7th.hkrpg.constant.Routes
import com.march7th.hkrpg.data.preferences.AppSettingsManager
import com.march7th.hkrpg.presentation.components.ListItemDivider
import com.march7th.hkrpg.presentation.components.SettingsGroupCard
import com.march7th.hkrpg.presentation.components.TopAppBar
import com.march7th.hkrpg.presentation.viewmodel.SettingsViewModel
import com.march7th.hkrpg.theme.AppDesignTokens
import com.march7th.hkrpg.utils.i18n.LocaleBootstrap.resolveSelectedLanguage
import org.koin.androidx.compose.koinViewModel

/**
 * 设置一级页：只放分类入口，具体开关都在二级页。
 *
 * 原来是六个可折叠分区一次性铺开二十几行，跟系统设置动线差得远；
 * 现在按「通用 / 启动与运行 / 通知 / 日志与诊断 / 数据 / 关于」分成三组卡片，
 * 行上带一行当前状态摘要，点进去才是具体项。
 */
@Composable
fun SettingsView(
    navController: NavController,
    viewModel: SettingsViewModel = koinViewModel(),
) {
    val language by viewModel.language.collectAsStateWithLifecycle()
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val startupBackend by viewModel.startupBackend.collectAsStateWithLifecycle()
    val wakeUnlockType by viewModel.wakeUnlockType.collectAsStateWithLifecycle()

    val languageLabel = stringResource(
        when (resolveSelectedLanguage(language)) {
            AppSettingsManager.AppLanguage.EN -> R.string.settings_language_en
            else -> R.string.settings_language_zh
        }
    )
    val themeLabel = stringResource(
        when (themeMode) {
            AppSettingsManager.ThemeMode.SYSTEM -> R.string.settings_theme_system
            AppSettingsManager.ThemeMode.WHITE -> R.string.settings_theme_white
            AppSettingsManager.ThemeMode.DARK -> R.string.settings_theme_dark
            AppSettingsManager.ThemeMode.PURE_DARK -> R.string.settings_theme_pure_dark
        }
    )
    val unlockLabel = stringResource(
        when (wakeUnlockType) {
            AppSettingsManager.WAKE_TYPE_GESTURE -> R.string.settings_wake_unlock_type_gesture
            AppSettingsManager.WAKE_TYPE_PIN -> R.string.settings_wake_unlock_type_pin
            else -> R.string.settings_wake_unlock_type_swipe
        }
    )

    Scaffold(
        topBar = {
            TopAppBar(title = stringResource(R.string.settings_title))
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
        ) {
            item {
                SettingsGroupCard {
                    SettingsCategoryRow(
                        icon = Icons.Rounded.Tune,
                        title = stringResource(R.string.settings_category_general),
                        summary = joinSummary(languageLabel, themeLabel),
                        onClick = { navController.navigate(Routes.SETTINGS_GENERAL) },
                    )
                    ListItemDivider()
                    SettingsCategoryRow(
                        icon = Icons.Rounded.RocketLaunch,
                        title = stringResource(R.string.settings_category_launch),
                        summary = joinSummary(startupBackend.display, unlockLabel),
                        onClick = { navController.navigate(Routes.SETTINGS_LAUNCH) },
                    )
                }
            }

            item {
                SettingsGroupCard {
                    SettingsCategoryRow(
                        icon = Icons.Rounded.Notifications,
                        title = stringResource(R.string.settings_section_notification),
                        summary = stringResource(R.string.settings_notification_desc),
                        onClick = { navController.navigate(Routes.NOTIFICATION) },
                    )
                    ListItemDivider()
                    SettingsCategoryRow(
                        icon = Icons.Rounded.Description,
                        title = stringResource(R.string.settings_category_log),
                        summary = stringResource(R.string.settings_category_log_desc),
                        onClick = { navController.navigate(Routes.SETTINGS_LOG) },
                    )
                    ListItemDivider()
                    SettingsCategoryRow(
                        icon = Icons.Rounded.FolderZip,
                        title = stringResource(R.string.settings_section_data),
                        summary = stringResource(R.string.settings_category_data_desc),
                        onClick = { navController.navigate(Routes.SETTINGS_DATA) },
                    )
                }
            }

            item {
                SettingsGroupCard {
                    SettingsCategoryRow(
                        icon = Icons.Rounded.Info,
                        title = stringResource(R.string.settings_section_about),
                        summary = stringResource(
                            R.string.settings_category_version_summary,
                            BuildConfig.VERSION_NAME,
                        ),
                        onClick = { navController.navigate(Routes.SETTINGS_ABOUT) },
                    )
                }
            }

            item {
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

/** 摘要行里用「·」拼当前值，值为空时自动跳过，不留悬空分隔符。 */
private fun joinSummary(vararg parts: String?): String =
    parts.filterNot { it.isNullOrBlank() }.joinToString(" · ")

/**
 * 分类入口行：左侧图标底片 + 标题与当前状态摘要 + 右箭头。
 *
 * 图标统一用主色淡底，不走每类一色 —— 主题本身就是单色金，彩色底片会打架。
 */
@Composable
private fun SettingsCategoryRow(
    icon: ImageVector,
    title: String,
    summary: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(AppDesignTokens.CornerRadius.inner))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(19.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(modifier = Modifier.width(AppDesignTokens.Spacing.md))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
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
