package com.march7th.hkrpg.presentation.view.home

import android.widget.Toast
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.march7th.hkrpg.R
import com.march7th.hkrpg.data.api.AnnouncementService
import com.march7th.hkrpg.data.preferences.AnnouncementStateStore
import com.march7th.hkrpg.data.preferences.AppSettingsManager
import com.march7th.hkrpg.domain.models.RemoteBackend
import com.march7th.hkrpg.domain.state.ResourceInitState
import com.march7th.hkrpg.manager.PermissionManager
import com.march7th.hkrpg.presentation.components.AdaptiveTaskPromptDialog
import com.march7th.hkrpg.presentation.components.ChangelogDialog
import com.march7th.hkrpg.presentation.components.ResourceInitDialog
import com.march7th.hkrpg.presentation.state.UiEffect
import com.march7th.hkrpg.presentation.view.background.BackgroundTaskView
import com.march7th.hkrpg.presentation.viewmodel.BackgroundTaskViewModel
import com.march7th.hkrpg.presentation.viewmodel.HomeViewModel
import com.march7th.hkrpg.presentation.viewmodel.UpdateViewModel
import com.march7th.hkrpg.utils.Misc
import com.march7th.hkrpg.utils.i18n.asString
import com.march7th.hkrpg.utils.i18n.resolve
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import timber.log.Timber

/**
 * 首页 = 预检 + 控制台。
 *
 * ## 为什么合并
 *
 * 合并前的首页只有「服务状态卡 / 权限卡 / 开启服务」三块，本质是开跑前的体检；
 * 六项权限全绿时它占满一屏却没有新信息，每次打开都要先滑过去。
 * 后台任务页反过来，是真正的工作面（虚拟屏预览 / 任务链 / 日志 / 开始按钮），
 * 但缺一句「现在到底能不能跑」。
 *
 * 现在合成一页：[PreflightCard] 收成顶部一行（缺东西才自动展开），
 * 下面直接是 [BackgroundTaskView] 控制台。
 *
 * 本文件只保留首页原本的**一次性副作用与弹窗**（公告、资源初始化、资源目录异常、
 * 运行模式不支持），布局全部交给下面两个组件。
 */
@Composable
fun HomeConsoleView(
    backgroundTaskViewModel: BackgroundTaskViewModel,
    isActivePage: Boolean = true,
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

    if (autoAnnouncement != null) {
        ChangelogDialog(
            content = autoAnnouncement.orEmpty(),
            title = stringResource(R.string.settings_about_announcement),
            onDismiss = { autoAnnouncement = null },
        )
    }

    val permissionState by permissionManager.state.collectAsStateWithLifecycle()
    val appVersion = updateViewModel.currentAppVersion

    val context = LocalContext.current
    val (width, height) = Misc.getScreenSize(context)
    val shizukuShortcutEnabled by appSettingsManager.shizukuShortcutEnabled.collectAsStateWithLifecycle()

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

    ResourceInitDialog(
        state = uiState.resourceInitState,
        onRetry = { viewModel.onTryResourceInit() },
    )

    // 更新弹窗**不在这里**。
    //
    // 它挂在 MainActivity 的导航根层（见 AppUpdateDialog），因为要能盖住
    // 激活向导和设置页；服务端标记为强制更新时更是必须挡住全部界面。
    // 首页只负责刷新资源版本、以及展示"本版本已装"的更新公告。

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
            onDismissRequest = { viewModel.dismissCoreDirInaccessibleDialog() },
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
            onDismissRequest = { viewModel.onDismissRunModeUnsupportedDialog() },
        )
    }

    BackgroundTaskView(
        viewModel = backgroundTaskViewModel,
        isActivePage = isActivePage,
        preflight = { taskActive ->
            PreflightCard(
                serviceStatusColor = uiState.serviceStatusColor,
                serviceStatusText = uiState.serviceStatusText,
                serviceStatusLoading = uiState.serviceStatusLoading,
                taskActive = taskActive,
                permissionState = permissionState,
                isGranting = uiState.isGranting,
                remoteServiceActive = uiState.remoteServiceActive,
                serviceLoading = uiState.isLoading,
                showShizukuShortcut = permissionState.startupBackend == RemoteBackend.SHIZUKU &&
                        shizukuShortcutEnabled,
                screenWidth = width,
                screenHeight = height,
                appVersion = appVersion,
                onRequestRemoteAccess = { viewModel.onRequestRemoteAccess() },
                onRequestOverlay = { viewModel.onRequestOverlay(context) },
                onRequestStorage = { viewModel.onRequestStorage(context) },
                onRequestBatteryWhitelist = { viewModel.onRequestBatteryWhitelist(context) },
                onRequestNotification = { viewModel.onRequestNotification(context) },
                onOpenShizuku = { viewModel.onOpenShizuku() },
                onToggleRemoteService = { viewModel.onToggleRemoteService() },
            )
        },
    )
}
