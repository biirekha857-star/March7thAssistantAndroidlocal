package com.march7th.hkrpg.presentation.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import com.march7th.hkrpg.R
import com.march7th.hkrpg.data.datasource.ResourceDownloader
import com.march7th.hkrpg.data.model.update.UpdateInfo

/**
 * 资源更新确认弹窗
 */
@Composable
fun UpdateConfirmDialog(
    updateInfo: UpdateInfo,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val displayVersion = ResourceDownloader.formatVersionForDisplay(updateInfo.version)

    AdaptiveTaskPromptDialog(
        visible = true,
        title = stringResource(R.string.update_confirm_title_resource),
        message = stringResource(R.string.update_confirm_message_resource, displayVersion),
        onConfirm = onConfirm,
        onDismissRequest = onDismiss,
        confirmText = stringResource(R.string.update_confirm_download_now),
        confirmColor = Color(0xFF4CAF50),
        dismissText = stringResource(R.string.dialog_update_later),
        icon = Icons.Rounded.Info,
        landscapeAdaptive = true
    )
}
