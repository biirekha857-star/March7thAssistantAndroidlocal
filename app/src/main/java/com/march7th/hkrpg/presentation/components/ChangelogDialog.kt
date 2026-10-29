package com.march7th.hkrpg.presentation.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.march7th.hkrpg.R

@Composable
fun ChangelogDialog(
    content: String,
    onDismiss: () -> Unit,
    title: String? = null,
) {
    AdaptiveTaskPromptDialog(
        visible = true,
        title = title ?: stringResource(R.string.dialog_changelog_title),
        onConfirm = onDismiss,
        onDismissRequest = onDismiss,
        confirmText = stringResource(R.string.common_i_got_it),
        dismissText = null,
        icon = Icons.Rounded.Info,
        landscapeAdaptive = true,
        content = {
            Column {
                Spacer(modifier = Modifier.height(12.dp))
                M7aMarkdownText(
                    markdown = content,
                    modifier = Modifier.fillMaxWidth(),
                    // ## 字号比正文小一档
                    //
                    // 公告/更新日志是**长文本**，用 `bodyMedium`（14sp）时
                    // 手机一屏只能显示十来行，翻起来很累。
                    //
                    // 用 `bodySmall`（12sp）+ 行高收紧，一屏能多看 30~40%。
                    // 层级靠 Markdown 的 `#` 标题区分，不依赖字号。
                    style = MaterialTheme.typography.bodySmall.copy(
                        lineHeight = MaterialTheme.typography.bodySmall.lineHeight * 1.15f,
                    ),
                )
            }
        }
    )
}
