package com.march7th.hkrpg.presentation.view.home

import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.march7th.hkrpg.R
import com.march7th.hkrpg.data.permission.PermissionState
import com.march7th.hkrpg.presentation.state.StatusColorType
import com.march7th.hkrpg.theme.AppAnimatedVisibility
import com.march7th.hkrpg.theme.AppDesignTokens
import com.march7th.hkrpg.utils.i18n.UiText
import com.march7th.hkrpg.utils.i18n.asString
import com.march7th.hkrpg.utils.i18n.remoteBackendPermissionLabel
import kotlinx.coroutines.delay

/** 全部就绪后卡片在页面上停留多久才退场 */
private const val READY_LINGER_MS = 1500L

/** 任务运行中，权限详情最多展开这么久就自动收起 */
private const val RUNNING_DETAILS_LINGER_MS = 1500L

/**
 * 首页顶部的预检卡：一行「能不能跑」，点开才铺开体检细节。
 *
 * ## 为什么要收起来
 *
 * 合并前的首页把状态卡、权限卡、服务按钮全铺开，占满一屏。
 * 但绝大多数时候六项权限都是绿的、服务也在线，这一屏没有新信息 ——
 * 用户每次打开都要先滑过它才能看到任务。
 *
 * 现在默认只占一行：状态点 + 服务状态 + 权限 n/m。
 * **只要缺权限或服务没起，自动展开**（[expandWhenBlocked]），
 * 该修的东西第一时间摆在眼前；一切正常时它安静待着。
 */
@Composable
internal fun PreflightCard(
    serviceStatusColor: StatusColorType,
    serviceStatusText: UiText,
    serviceStatusLoading: Boolean,
    /** 任务已开始（STARTING/RUNNING/STOPPING）：此时卡片只留一行，不铺详情 */
    taskActive: Boolean,
    permissionState: PermissionState,
    isGranting: Boolean,
    remoteServiceActive: Boolean,
    serviceLoading: Boolean,
    showShizukuShortcut: Boolean,
    screenWidth: Int,
    screenHeight: Int,
    appVersion: String,
    onRequestRemoteAccess: () -> Unit,
    onRequestOverlay: () -> Unit,
    onRequestStorage: () -> Unit,
    onRequestBatteryWhitelist: () -> Unit,
    onRequestNotification: () -> Unit,
    onOpenShizuku: () -> Unit,
    onToggleRemoteService: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val statusColor = when (serviceStatusColor) {
        StatusColorType.PRIMARY -> MaterialTheme.colorScheme.primary
        StatusColorType.WARNING -> MaterialTheme.colorScheme.tertiary
        StatusColorType.ERROR -> MaterialTheme.colorScheme.error
        StatusColorType.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    // 必须项：主权限 + 悬浮窗 + 存储 + 电池白名单 + 通知
    val requiredGrants = buildList {
        add(permissionState.remoteAccessGranted)
        add(permissionState.overlay)
        add(permissionState.storage)
        add(permissionState.batteryWhitelist)
        add(permissionState.notification)
    }
    val total = requiredGrants.size
    val granted = requiredGrants.count { it }
    val ready = granted == total && remoteServiceActive && !serviceStatusLoading

    var expanded by rememberSaveable { mutableStateOf(false) }
    // 全部就绪后退场：先停留一下让「已就绪」被看见，再淡出。
    // 一旦真缺东西（而不是还在检查）立刻回来并展开。
    var exited by rememberSaveable { mutableStateOf(false) }
    // 「加载中」不算出问题：否则每次切回应用都会先闪一下卡片再淡出
    val blocked = !ready && !serviceStatusLoading
    LaunchedEffect(ready, blocked, taskActive) {
        when {
            // 跑任务时把卡片叫回来，但只留一行结论。
            // 这个分支排在最前：运行中绝不自动铺开权限详情。
            taskActive -> exited = false

            ready -> {
                delay(READY_LINGER_MS)
                exited = true
            }

            blocked -> {
                exited = false
                expanded = true
            }
        }
    }

    // 运行中详情只给展一眼的时间：展开 1.5 秒后自己收起，
    // 不占着虚拟屏预览的高度（收起动作由上面的状态机负责）。
    LaunchedEffect(taskActive, expanded) {
        if (taskActive && expanded) {
            delay(RUNNING_DETAILS_LINGER_MS)
            expanded = false
        }
    }

    AppAnimatedVisibility(visible = !(ready && exited)) {
        Column(modifier = modifier.fillMaxWidth()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                // 与设置分组卡一致：靠 1dp 细线区分边界，不用投影
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    // ---- 收起态：一行结论 ----
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { expanded = !expanded }
                            .padding(horizontal = AppDesignTokens.Spacing.md, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(AppDesignTokens.Spacing.md),
                    ) {
                        Box(modifier = Modifier.size(22.dp), contentAlignment = Alignment.Center) {
                            if (serviceStatusLoading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .size(9.dp)
                                        .clip(CircleShape)
                                        .background(statusColor),
                                )
                            }
                        }
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(1.dp),
                        ) {
                            Text(
                                text = serviceStatusText.asString(),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                                color = statusColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (!ready) {
                                Text(
                                    text = stringResource(R.string.home_preflight_blocked_hint),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        Text(
                            text = stringResource(R.string.home_preflight_permission_count, granted, total),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (granted == total) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                        )
                        Icon(
                            imageVector = if (expanded) {
                                Icons.Rounded.KeyboardArrowUp
                            } else {
                                Icons.Rounded.KeyboardArrowDown
                            },
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    AppAnimatedVisibility(
                        visible = expanded,
                        enter = expandVertically(),
                        exit = shrinkVertically(),
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            HorizontalDivider(
                                thickness = AppDesignTokens.Separator.thickness,
                                color = MaterialTheme.colorScheme.outlineVariant,
                            )
                            // 展开内容限高可滚：极窄或横屏时也不会把下面的任务列表挤没
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 320.dp)
                                    .verticalScroll(rememberScrollState())
                                    .padding(horizontal = AppDesignTokens.Spacing.md, vertical = AppDesignTokens.Spacing.md),
                                verticalArrangement = Arrangement.spacedBy(AppDesignTokens.Spacing.md),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(AppDesignTokens.Spacing.md),
                                ) {
                                    InfoCell(
                                        label = stringResource(R.string.home_screen_resolution),
                                        value = "$screenWidth × $screenHeight",
                                        modifier = Modifier.weight(1f),
                                    )
                                    InfoCell(
                                        label = stringResource(R.string.home_app_version_label),
                                        value = appVersion,
                                        modifier = Modifier.weight(1f),
                                    )
                                }

                                PermissionList(
                                    permissionState = permissionState,
                                    isGranting = isGranting,
                                    onRequestRemoteAccess = onRequestRemoteAccess,
                                    onRequestOverlay = onRequestOverlay,
                                    onRequestStorage = onRequestStorage,
                                    onRequestBatteryWhitelist = onRequestBatteryWhitelist,
                                    onRequestNotification = onRequestNotification,
                                )

                                ServiceButtons(
                                    remoteServiceActive = remoteServiceActive,
                                    isLoading = serviceLoading,
                                    showShizukuShortcut = showShizukuShortcut,
                                    onOpenShizuku = onOpenShizuku,
                                    onToggleRemoteService = onToggleRemoteService,
                                )
                            }
                        }
                    }
                }
            }
            // 卡片退场后不留空档
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

/** 信息格：小标签 + 值，纵向排列。 */
@Composable
private fun InfoCell(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 权限清单。
 *
 * 原来权限卡有「展开更多权限」的二级折叠，现在外层预检卡本身就能收，
 * 再套一层折叠会变成三层开合 —— 这里直接平铺，缺哪项点哪项的按钮即可。
 */
@Composable
private fun PermissionList(
    permissionState: PermissionState,
    isGranting: Boolean,
    onRequestRemoteAccess: () -> Unit,
    onRequestOverlay: () -> Unit,
    onRequestStorage: () -> Unit,
    onRequestBatteryWhitelist: () -> Unit,
    onRequestNotification: () -> Unit,
) {
    val context = LocalContext.current
    val contentColor = MaterialTheme.colorScheme.onSurface

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        PermissionRow(
            title = context.remoteBackendPermissionLabel(permissionState.startupBackend),
            granted = permissionState.remoteAccessGranted,
            onClick = onRequestRemoteAccess,
            isLoading = isGranting,
            contentColor = contentColor,
        )
        PermissionRow(
            title = stringResource(R.string.home_permission_overlay),
            granted = permissionState.overlay,
            onClick = onRequestOverlay,
            contentColor = contentColor,
        )
        PermissionRow(
            title = stringResource(R.string.home_permission_storage),
            granted = permissionState.storage,
            onClick = onRequestStorage,
            contentColor = contentColor,
        )
        PermissionRow(
            title = stringResource(R.string.home_permission_battery),
            granted = permissionState.batteryWhitelist,
            onClick = onRequestBatteryWhitelist,
            grantedText = stringResource(R.string.home_permission_battery_added),
            contentColor = contentColor,
        )
        PermissionRow(
            title = stringResource(R.string.home_permission_notification),
            granted = permissionState.notification,
            onClick = onRequestNotification,
            contentColor = contentColor,
        )
    }
}

@Composable
private fun PermissionRow(
    title: String,
    granted: Boolean,
    onClick: () -> Unit,
    isLoading: Boolean = false,
    grantedText: String = stringResource(R.string.home_permission_granted),
    ungrantedText: String = stringResource(R.string.home_permission_request),
    contentColor: Color,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(34.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            color = contentColor,
        )
        TextButton(
            onClick = onClick,
            enabled = !granted && !isLoading,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
        ) {
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            } else {
                Text(text = if (granted) grantedText else ungrantedText)
            }
        }
    }
}

/**
 * 服务启停按钮。
 *
 * ## 只保留「开启服务」，没有「关闭所有服务」
 *
 * 原版提供一键关闭全部后台服务（含虚拟屏、Shizuku 连接等）。
 * 但关闭后重启成本很高，而且崩铁的虚拟屏任务链**必须**全程保持
 * 服务在线，误触会导致任务中断。所以这里只留开启入口，
 * 真要停服务直接杀进程即可。
 */
@Composable
private fun ServiceButtons(
    remoteServiceActive: Boolean,
    isLoading: Boolean,
    showShizukuShortcut: Boolean,
    onOpenShizuku: () -> Unit,
    onToggleRemoteService: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(AppDesignTokens.Spacing.sm),
    ) {
        // 服务已在运行时不再显示按钮，避免误触关闭
        if (!remoteServiceActive) {
            OutlinedButton(
                onClick = onToggleRemoteService,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = MaterialTheme.shapes.large,
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.primary,
                ),
                border = BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.55f),
                ),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                enabled = !isLoading,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.size(6.dp))
                Text(
                    text = stringResource(R.string.home_btn_open_service),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    textAlign = TextAlign.Center,
                )
            }
        }
        if (showShizukuShortcut) {
            OutlinedButton(
                onClick = onOpenShizuku,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = MaterialTheme.shapes.large,
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.secondary,
                ),
                border = BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.secondary.copy(alpha = 0.55f),
                ),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                enabled = !isLoading,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Build,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.size(6.dp))
                Text(
                    text = stringResource(R.string.home_btn_open_shizuku),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
