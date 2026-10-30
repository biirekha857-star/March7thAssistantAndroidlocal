package com.march7th.hkrpg.presentation.view.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.march7th.hkrpg.BuildConfig
import com.march7th.hkrpg.R
import com.march7th.hkrpg.constant.M7aApi
import com.march7th.hkrpg.data.api.AnnouncementService
import com.march7th.hkrpg.domain.service.AchievementReporter
import com.march7th.hkrpg.presentation.components.ChangelogDialog
import com.march7th.hkrpg.presentation.components.ListItemDivider
import com.march7th.hkrpg.presentation.components.SettingsGroupCard
import com.march7th.hkrpg.presentation.viewmodel.SettingsViewModel
import com.march7th.hkrpg.theme.AppDesignTokens
import com.march7th.hkrpg.utils.Misc
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

@Composable
fun AboutSettingsScreen(
    navController: NavController,
    viewModel: SettingsViewModel = koinViewModel(),
    achievementReporter: AchievementReporter = koinInject(),
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val showChangelog by viewModel.showChangelog.collectAsStateWithLifecycle()
    val currentChangelog by viewModel.currentChangelog.collectAsStateWithLifecycle()

    // 内容从自有静态站拉取，失败退回 APK 内置 md（断网也有内容）。
    // 直接用服务而不过 ViewModel —— 公告是一次性读取，没有需要持久化的状态。
    val announcementService: AnnouncementService = koinInject()
    var showAnnouncement by remember { mutableStateOf(false) }
    var announcementContent by remember { mutableStateOf<String?>(null) }

    SettingsDetailScreen(
        title = stringResource(R.string.settings_section_about),
        navController = navController,
    ) {
        item {
            SettingsGroupCard {
                SettingInfoRow(
                    label = stringResource(R.string.settings_about_version),
                    value = BuildConfig.VERSION_NAME,
                )
                ListItemDivider()
                SettingInfoRow(
                    label = stringResource(R.string.settings_about_developer),
                    value = "miguan",
                )
            }
        }

        item {
            SettingsGroupCard {
                SettingClickItem(
                    title = stringResource(R.string.settings_about_faq_title),
                    description = stringResource(R.string.settings_about_faq_desc),
                    onClick = { Misc.openUriSafely(context, M7aApi.FAQ_URL) },
                )
                ListItemDivider()
                SettingClickItem(
                    title = stringResource(R.string.settings_about_feedback_title),
                    description = stringResource(R.string.settings_about_feedback_desc),
                    onClick = { Misc.openUriSafely(context, M7aApi.FEEDBACK_URL) },
                )
                ListItemDivider()
                SettingClickItem(
                    title = stringResource(R.string.settings_about_qq_group_title),
                    description = stringResource(R.string.settings_about_qq_group_desc),
                    onClick = {
                        achievementReporter.reportFeedbackGroupOpened()
                        Misc.openUriSafely(context, M7aApi.QQ_GROUP_URL)
                    },
                )
                ListItemDivider()
                // ## Telegram 群
                //
                // 给海外或不用 QQ 的用户一条路。
                // 复用 QQ 群的成就计数 —— 对用户来说都是"点开了反馈群"，
                // 不必为渠道差异单独记一个成就。
                SettingClickItem(
                    title = stringResource(R.string.settings_about_telegram_title),
                    description = stringResource(R.string.settings_about_telegram_desc),
                    onClick = {
                        achievementReporter.reportFeedbackGroupOpened()
                        Misc.openUriSafely(context, M7aApi.TELEGRAM_GROUP_URL)
                    },
                )
            }
        }

        item {
            SettingsGroupCard {
                // ## 查看公告
                //
                // 内容来自自有静态站（`M7aApi.SITE_BASE`），
                // 拉不到就用 APK 内置的 md —— 保证断网也有内容。
                // 展示复用 ChangelogDialog（它本身就是 Markdown 渲染器）。
                SettingClickItem(
                    title = stringResource(R.string.settings_about_announcement),
                    onClick = {
                        coroutineScope.launch {
                            announcementContent = announcementService.load()
                            showAnnouncement = true
                        }
                    },
                )
                ListItemDivider()
                SettingClickItem(
                    title = stringResource(R.string.settings_about_changelog),
                    onClick = { viewModel.onShowChangelog() },
                )
            }
        }

        item {
            Text(
                text = stringResource(R.string.settings_about_star),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        Misc.openUriSafely(context, "https://github.com/Aliothmoon/MAA-Meow")
                    }
                    .padding(vertical = AppDesignTokens.Spacing.listItemVertical),
                textAlign = TextAlign.Center,
            )
        }
    }

    if (showAnnouncement) {
        ChangelogDialog(
            content = announcementContent.orEmpty(),
            title = stringResource(R.string.settings_about_announcement),
            onDismiss = {
                showAnnouncement = false
                announcementContent = null
            },
        )
    }

    if (showChangelog) {
        ChangelogDialog(
            content = currentChangelog?.markdown
                ?: stringResource(R.string.settings_about_changelog_empty),
            title = stringResource(R.string.settings_about_changelog),
            onDismiss = { viewModel.onDismissChangelog() },
        )
    }
}
