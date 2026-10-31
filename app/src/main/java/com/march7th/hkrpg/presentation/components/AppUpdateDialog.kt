package com.march7th.hkrpg.presentation.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.RocketLaunch
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.march7th.hkrpg.R
import com.march7th.hkrpg.data.model.update.AppUpdatePrompt

/**
 * 启动时的应用更新弹窗。
 *
 * # 内容
 *
 * 版本号 + 更新要点（静态站 `update.md` 的正文，按 Markdown 渲染）。
 * 更新要点就是 Release 里那份发布说明，用户不用跳出去就能看清这次改了什么。
 *
 * # 强制更新（"死更新"）
 *
 * [AppUpdatePrompt.forced] 为 true 时：
 *
 * - 不给「稍后」按钮（`dismissText = ""`）；
 * - 点弹窗外面不关（`dismissOnOutsideClick = false`，同时禁掉返回键）；
 * - 唯一的出路是点「立即更新」。
 *
 * 三处一起做，是因为少任何一处都能被绕过去 —— 只藏按钮，按返回键照样能关。
 */
@Composable
fun AppUpdateDialog(
    prompt: AppUpdatePrompt,
    onUpdate: () -> Unit,
    onLater: () -> Unit,
) {
    val forced = prompt.forced

    AdaptiveTaskPromptDialog(
        visible = true,
        title = if (forced) {
            stringResource(R.string.update_dialog_title_forced)
        } else {
            stringResource(R.string.update_dialog_title, prompt.versionName)
        },
        onConfirm = onUpdate,
        // 强制更新时这个回调理论上进不来，仍然守一道
        onDismissRequest = { if (!forced) onLater() },
        confirmText = stringResource(R.string.update_dialog_update_now),
        // 强制更新时传空串而不是 null：
        // AdaptiveTaskPromptDialog 会把 null 兜底成「取消」，只有空串才真的不渲染按钮。
        dismissText = if (forced) "" else stringResource(R.string.dialog_update_later),
        dismissOnOutsideClick = !forced,
        icon = Icons.Rounded.RocketLaunch,
        landscapeAdaptive = true,
        content = {
            Column {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = if (prompt.versionName.isBlank()) {
                        // 断网兜底：只知道"必须更新"，不知道具体版本
                        stringResource(R.string.update_dialog_forced_hint)
                    } else {
                        stringResource(
                            R.string.update_dialog_version_line,
                            prompt.currentVersion,
                            prompt.versionName,
                        )
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (prompt.changelog.isNotBlank()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    M7aMarkdownText(
                        markdown = prompt.changelog,
                        modifier = Modifier.fillMaxWidth(),
                        // 与更新公告一致：长文本用小一档字号，一屏能多看几行
                        style = MaterialTheme.typography.bodySmall.copy(
                            lineHeight = MaterialTheme.typography.bodySmall.lineHeight * 1.15f,
                        ),
                    )
                }
            }
        },
    )
}
