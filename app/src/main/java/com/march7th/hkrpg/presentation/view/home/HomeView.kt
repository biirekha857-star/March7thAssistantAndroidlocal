package com.march7th.hkrpg.presentation.view.home

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.march7th.hkrpg.R
import com.march7th.hkrpg.data.permission.PermissionState
import com.march7th.hkrpg.data.api.AnnouncementService
import com.march7th.hkrpg.data.preferences.AnnouncementStateStore
import com.march7th.hkrpg.data.preferences.AppSettingsManager
import com.march7th.hkrpg.domain.models.OverlayControlMode
import com.march7th.hkrpg.domain.models.RemoteBackend
import com.march7th.hkrpg.domain.models.RunMode
import com.march7th.hkrpg.domain.state.ResourceInitState
import com.march7th.hkrpg.manager.PermissionManager
import com.march7th.hkrpg.presentation.components.ChangelogDialog
import com.march7th.hkrpg.presentation.components.AdaptiveTaskPromptDialog
import com.march7th.hkrpg.presentation.components.ResourceInitDialog
import com.march7th.hkrpg.presentation.components.ShizukuReadinessGate
import com.march7th.hkrpg.presentation.state.StatusColorType
import com.march7th.hkrpg.presentation.state.UiEffect
import com.march7th.hkrpg.presentation.viewmodel.HomeViewModel
import com.march7th.hkrpg.presentation.viewmodel.UpdateViewModel
import com.march7th.hkrpg.theme.LocalReduceMotion
import com.march7th.hkrpg.theme.MaaDesignTokens
import com.march7th.hkrpg.theme.MaaThemeAlphas
import com.march7th.hkrpg.theme.MaaAnimatedVisibility
import com.march7th.hkrpg.utils.Misc
import com.march7th.hkrpg.utils.i18n.UiText
import com.march7th.hkrpg.utils.i18n.asString
import com.march7th.hkrpg.utils.i18n.overlayControlModeDisplayName
import com.march7th.hkrpg.utils.i18n.remoteBackendPermissionLabel
import com.march7th.hkrpg.utils.i18n.resolve
import kotlinx.coroutines.flow.collectLatest
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import timber.log.Timber


// LazyColumn 项序（2 是更新卡），item 顺序变了要同步
@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun HomeView(
    navController: NavController,
    viewModel: HomeViewModel = koinViewModel(),
    updateViewModel: UpdateViewModel = koinViewModel(),
    permissionManager: PermissionManager = koinInject(),
    appSettingsManager: AppSettingsManager = koinInject(),
    announcementService: AnnouncementService = koinInject(),
    announcementState: AnnouncementStateStore = koinInject(),
) {

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // ==================== 首次启动自动弹公告 ====================
    //
    // ## 触发条件
    //
    // **公告内容变了就弹一次**，不是只弹一次。
    //
    // 判断依据是公告内容的哈希：和上次看过的不一样 → 弹。
    // 这样改了公告用户能第一时间看到，只是措辞微调又不会打扰人。
    //
    // ## 为什么放在首页
    //
    // 首页是用户打开 App 的第一屏，放在这里最自然。
    // 用 `LaunchedEffect(Unit)` 只跑一次，不会因为重组反复触发。
    var autoAnnouncement by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        runCatching {
            // 先看本地缓存/内置版（快），拿到后再决定要不要弹
            val text = announcementService.load()
            if (text.isBlank()) return@runCatching
            val hash = text.hashCode().toString()
            if (announcementState.isUnseen(hash)) {
                // 立刻记已读，避免用户杀掉 App 又弹一次
                announcementState.markSeen(hash)
                autoAnnouncement = text
            }
        }.onFailure { Timber.w(it, "自动加载公告失败") }
    }

    // 自动弹出的公告
    if (autoAnnouncement != null) {
        ChangelogDialog(
            content = autoAnnouncement.orEmpty(),
            title = stringResource(R.string.settings_about_announcement),
            onDismiss = { autoAnnouncement = null },
        )
    }
    val permissionState by permissionManager.state.collectAsStateWithLifecycle()
    val resourceVersion by updateViewModel.currentResourceVersion.collectAsStateWithLifecycle()
    val appVersion = updateViewModel.currentAppVersion

    val context = LocalContext.current
    val (width, height) = Misc.getScreenSize(context)
    val shizukuShortcutEnabled by appSettingsManager.shizukuShortcutEnabled.collectAsStateWithLifecycle()

    val startupDialog by updateViewModel.startupUpdateDialog.collectAsStateWithLifecycle()

    val homeListState = rememberLazyListState()

    // 启动时检查资源初始化
    LaunchedEffect(Unit) {
        viewModel.checkAndInitResource()
    }

    // 自动下载失败时弹 Toast
    LaunchedEffect(Unit) {
        updateViewModel.toastMessage.collect { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
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

    // 资源初始化完成后刷新版本号
    LaunchedEffect(uiState.resourceInitState) {
        if (uiState.resourceInitState is ResourceInitState.Ready) {
            updateViewModel.refreshResourceVersion()
            updateViewModel.checkPendingChangelog()
        }
    }
    // 资源初始化弹窗
    ResourceInitDialog(
        state = uiState.resourceInitState,
        onRetry = { viewModel.onTryResourceInit() }
    )

    // 「发现新版本」弹窗已删除。
    // 这是个崩铁移植包，跟随的应该是崩铁客户端版本，而不是上游 MAA-Meow 的
    // 版本号；每次启动弹一次还会挡住任务流程。更新检查逻辑保留在设置里
    // （UpdateViewModel 仍在），只是不再在首页自动弹窗。

    val showCoreDirInaccessibleDialog by viewModel.showCoreDirInaccessibleDialog.collectAsStateWithLifecycle()
    if (showCoreDirInaccessibleDialog) {
        AdaptiveTaskPromptDialog(
            visible = true,
            title = stringResource(R.string.dialog_core_dir_inaccessible_title),
            message = stringResource(R.string.dialog_core_dir_inaccessible_message),
            icon = Icons.Rounded.Warning,
            confirmText = stringResource(R.string.dialog_core_data_location_switch_confirm),
            dismissText = stringResource(R.string.common_cancel),
            onConfirm = { viewModel.switchCoreDataToLocalTmp() },
            onDismissRequest = { viewModel.dismissCoreDirInaccessibleDialog() }
        )
    }

    if (uiState.showRunModeUnsupportedDialog) {
        AdaptiveTaskPromptDialog(
            visible = true,
            title = stringResource(R.string.dialog_run_mode_unsupported_title),
            message = uiState.runModeUnsupportedMessage.asString(),
            confirmText = stringResource(R.string.common_i_got_it),
            dismissText = null,
            onConfirm = { viewModel.onDismissRunModeUnsupportedDialog() },
            onDismissRequest = { viewModel.onDismissRunModeUnsupportedDialog() }
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.home_app_title),
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.headlineMedium
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    actionIconContentColor = MaterialTheme.colorScheme.primary
                )
            )
            LazyColumn(
                state = homeListState,
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 8.dp,
                    bottom = 8.dp
                ),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    ScreenInfoCard(
                        screenWidth = width,
                        screenHeight = height,
                        resourceVersion = resourceVersion,
                        appVersion = appVersion,
                        serviceStatusColor = uiState.serviceStatusColor,
                        serviceStatusText = uiState.serviceStatusText,
                        serviceStatusLoading = uiState.serviceStatusLoading
                    )
                }

                item {
                    PermissionCard(
                        permissionState = permissionState,
                        isShowAccessibility = uiState.overlayControlMode == OverlayControlMode.ACCESSIBILITY,
                        isGranting = uiState.isGranting,
                        onRequestRemoteAccess = { viewModel.onRequestRemoteAccess() },
                        onRequestOverlay = { viewModel.onRequestOverlay(context) },
                        onRequestStorage = { viewModel.onRequestStorage(context) },
                        onRequestBatteryWhitelist = { viewModel.onRequestBatteryWhitelist(context) },
                        onRequestAccessibility = { viewModel.onRequestAccessibility(context) },
                        onRequestNotification = { viewModel.onRequestNotification(context) }
                    )
                }

                item {
                    HomeServiceActionButtons(
                        remoteServiceActive = uiState.remoteServiceActive,
                        isLoading = uiState.isLoading,
                        showShizukuShortcut = permissionState.startupBackend == RemoteBackend.SHIZUKU &&
                                shizukuShortcutEnabled,
                        onOpenShizuku = { viewModel.onOpenShizuku() },
                        onToggleRemoteService = { viewModel.onToggleRemoteService() }
                    )
                }

            }
        }

        ShizukuReadinessGate()
    }
}

/**
 * 首页顶部状态卡。
 *
 * ## 为什么改成"大状态 + 小信息格"
 *
 * 原来是一列 `标签 ——— 值` 的等宽行（屏幕分辨率 / App版本 / 服务状态），
 * 三行长得一模一样，用户得逐行读才知道哪个是重点。
 *
 * 现在把**服务状态**提成主视觉：一个大圆点 + 一句状态词，
 * 一眼就能看出"能不能用"；分辨率/版本这类次要信息降级成底部两个小格，
 * 字号更小、颜色更淡，不抢注意力。
 */
@Composable
private fun ScreenInfoCard(
    screenWidth: Int,
    screenHeight: Int,
    resourceVersion: String,
    appVersion: String,
    serviceStatusColor: StatusColorType,
    serviceStatusText: UiText,
    serviceStatusLoading: Boolean,
    modifier: Modifier = Modifier,
) {
    val serviceStatusLabel = serviceStatusText.asString()
    val statusColor = when (serviceStatusColor) {
        StatusColorType.PRIMARY -> MaterialTheme.colorScheme.primary
        StatusColorType.WARNING -> MaterialTheme.colorScheme.tertiary
        StatusColorType.ERROR -> MaterialTheme.colorScheme.error
        StatusColorType.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(MaaDesignTokens.Card.innerPadding),
            verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.md)
        ) {
            // ---- 主视觉：服务状态 ----
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.md)
            ) {
                // 状态点：加载中转圈，否则实心圆（带一圈同色光晕）
                Box(
                    modifier = Modifier.size(28.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (serviceStatusLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(18.dp)
                                .clip(CircleShape)
                                .background(statusColor.copy(alpha = 0.18f))
                        )
                        Box(
                            modifier = Modifier
                                .size(9.dp)
                                .clip(CircleShape)
                                .background(statusColor)
                        )
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = stringResource(R.string.home_service_status),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = serviceStatusLabel,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = statusColor
                    )
                }
            }

            HorizontalDivider(
                thickness = MaaDesignTokens.Separator.thickness,
                color = MaterialTheme.colorScheme.outlineVariant
            )

            // ---- 次要信息：两格并排 ----
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.md)
            ) {
                InfoCell(
                    label = stringResource(R.string.home_screen_resolution),
                    value = "$screenWidth × $screenHeight",
                    modifier = Modifier.weight(1f)
                )
                InfoCell(
                    label = stringResource(R.string.home_app_version_label),
                    value = appVersion,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

/** 状态卡里的次要信息格：小标签 + 值，纵向排列。 */
@Composable
private fun InfoCell(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = MaaThemeAlphas.SECONDARY)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
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
private fun HomeServiceActionButtons(
    remoteServiceActive: Boolean,
    isLoading: Boolean,
    showShizukuShortcut: Boolean,
    onOpenShizuku: () -> Unit,
    onToggleRemoteService: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 服务已在运行时不再显示按钮，避免误触关闭
        if (!remoteServiceActive) {
            OutlinedButton(
                onClick = onToggleRemoteService,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = MaterialTheme.shapes.large,
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.primary
                ),
                border = BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
                ),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                enabled = !isLoading
            ) {
                Icon(
                    imageVector = Icons.Rounded.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.size(6.dp))
                Text(
                    text = stringResource(R.string.home_btn_open_service),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    textAlign = TextAlign.Center
                )
            }
        }
        if (showShizukuShortcut) {
            OutlinedButton(
                onClick = onOpenShizuku,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = MaterialTheme.shapes.large,
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.secondary
                ),
                border = BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.secondary.copy(alpha = 0.55f)
                ),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                enabled = !isLoading
            ) {
                Icon(
                    imageVector = Icons.Rounded.Build,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.size(6.dp))
                Text(
                    text = stringResource(R.string.home_btn_open_shizuku),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    textAlign = TextAlign.Center
                )
            }
        }
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
    contentColor: Color
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(32.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            color = contentColor
        )
        TextButton(
            onClick = onClick,
            enabled = !granted && !isLoading,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp
                )
            } else {
                Text(text = if (granted) grantedText else ungrantedText)
            }
        }
    }
}

@Composable
private fun PermissionCard(
    permissionState: PermissionState,
    isShowAccessibility: Boolean,
    isGranting: Boolean,
    onRequestRemoteAccess: () -> Unit,
    onRequestOverlay: () -> Unit,
    onRequestStorage: () -> Unit,
    onRequestBatteryWhitelist: () -> Unit,
    onRequestAccessibility: () -> Unit,
    onRequestNotification: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var expandedPermissions by remember { mutableStateOf(false) }
    val contentColor = MaterialTheme.colorScheme.onSurface

    Card(
        modifier = modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            Text(
                text = stringResource(R.string.home_permission_section),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                color = contentColor,
                modifier = Modifier.padding(bottom = 6.dp)
            )

            PermissionRow(
                title = context.remoteBackendPermissionLabel(permissionState.startupBackend),
                granted = permissionState.remoteAccessGranted,
                onClick = onRequestRemoteAccess,
                isLoading = isGranting,
                contentColor = contentColor
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expandedPermissions = !expandedPermissions }
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (expandedPermissions)
                        stringResource(R.string.home_permission_collapse)
                    else
                        stringResource(R.string.home_permission_expand),
                    style = MaterialTheme.typography.bodySmall,
                    color = contentColor.copy(alpha = 0.7f)
                )
                Icon(
                    imageVector = if (expandedPermissions) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = contentColor.copy(alpha = 0.7f)
                )
            }

            MaaAnimatedVisibility(visible = expandedPermissions) {
                Column {
                    PermissionRow(
                        title = stringResource(R.string.home_permission_overlay),
                        granted = permissionState.overlay,
                        onClick = onRequestOverlay,
                        contentColor = contentColor
                    )
                    PermissionRow(
                        title = stringResource(R.string.home_permission_storage),
                        granted = permissionState.storage,
                        onClick = onRequestStorage,
                        contentColor = contentColor
                    )
                    PermissionRow(
                        title = stringResource(R.string.home_permission_battery),
                        granted = permissionState.batteryWhitelist,
                        onClick = onRequestBatteryWhitelist,
                        grantedText = stringResource(R.string.home_permission_battery_added),
                        contentColor = contentColor
                    )
                    if (isShowAccessibility) {
                        PermissionRow(
                            title = stringResource(R.string.home_permission_accessibility),
                            granted = permissionState.accessibility,
                            onClick = onRequestAccessibility,
                            ungrantedText = if (permissionState.remoteAccessGranted)
                                stringResource(R.string.home_permission_quick_grant)
                            else
                                stringResource(R.string.home_permission_request),
                            contentColor = contentColor
                        )
                    }
                    PermissionRow(
                        title = stringResource(R.string.home_permission_notification),
                        granted = permissionState.notification,
                        onClick = onRequestNotification,
                        contentColor = contentColor
                    )
                }
            }
        }
    }
}

@Composable
private fun ForegroundModeSection(
    overlayControlMode: OverlayControlMode,
    isShowControlOverlay: Boolean,
    isLoading: Boolean,
    onChangeTo16x9Resolution: () -> Unit,
    onResetResolution: () -> Unit,
    onControlOverlayModeChanged: (OverlayControlMode) -> Unit,
    onToggleOverlay: () -> Unit
) {
    val context = LocalContext.current
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            shape = MaterialTheme.shapes.medium,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = stringResource(R.string.home_resolution_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    maxItemsInEachRow = 2
                ) {
                    Button(
                        onClick = onChangeTo16x9Resolution,
                        modifier = Modifier.weight(1f),
                        shape = MaterialTheme.shapes.medium,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.home_resolution_apply_16_9),
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                            maxLines = 1
                        )
                    }

                    Button(
                        onClick = onResetResolution,
                        modifier = Modifier.weight(1f),
                        shape = MaterialTheme.shapes.medium,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.home_resolution_reset),
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                            maxLines = 1
                        )
                    }
                }
            }
        }
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            shape = MaterialTheme.shapes.medium,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.home_overlay_mode_title),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (overlayControlMode == OverlayControlMode.ACCESSIBILITY)
                            stringResource(R.string.home_overlay_mode_accessibility_desc)
                        else
                            stringResource(R.string.home_overlay_mode_floatball_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = context.overlayControlModeDisplayName(overlayControlMode),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Switch(
                        checked = overlayControlMode == OverlayControlMode.ACCESSIBILITY,
                        onCheckedChange = { isAccessibility ->
                            onControlOverlayModeChanged(
                                if (isAccessibility) OverlayControlMode.ACCESSIBILITY
                                else OverlayControlMode.FLOAT_BALL
                            )
                        }
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Button(
            onClick = onToggleOverlay,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isShowControlOverlay)
                    MaterialTheme.colorScheme.error
                else
                    MaterialTheme.colorScheme.primary
            ),
            shape = MaterialTheme.shapes.large,
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 2.dp
                )
            } else {
                Text(
                    text = if (isShowControlOverlay)
                        stringResource(R.string.home_overlay_close)
                    else
                        stringResource(R.string.home_overlay_open),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}
