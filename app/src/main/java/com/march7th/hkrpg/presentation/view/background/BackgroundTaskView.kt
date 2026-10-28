package com.march7th.hkrpg.presentation.view.background

import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Matrix
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.os.SystemClock
import android.view.Surface
import android.view.TextureView
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NotificationsPaused
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Screenshot
import androidx.compose.material.icons.filled.StayCurrentPortrait
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChangedIgnoreConsumed
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.march7th.hkrpg.R
import com.march7th.hkrpg.constant.DefaultDisplayConfig
import com.march7th.hkrpg.data.preferences.AppSettingsManager
import com.march7th.hkrpg.domain.models.RunMode
import com.march7th.hkrpg.domain.models.RunDurationLimit
import com.march7th.hkrpg.domain.service.AppWatchdog
import com.march7th.hkrpg.domain.service.TaskCompositionService
import com.march7th.hkrpg.domain.service.UnifiedStateDispatcher
import com.march7th.hkrpg.domain.state.TaskExecutionState
import com.march7th.hkrpg.manager.PermissionManager
import com.march7th.hkrpg.overlay.screensaver.ScreenSaverOverlayManager
import com.march7th.hkrpg.presentation.LocalInputFocusManager
import com.march7th.hkrpg.presentation.components.AdaptiveTaskPromptDialog
import com.march7th.hkrpg.presentation.components.LogExportController
import com.march7th.hkrpg.presentation.components.M7aWindowInsets
import com.march7th.hkrpg.presentation.components.ShizukuReadinessGate
import com.march7th.hkrpg.presentation.navigation.BottomNavTab
import com.march7th.hkrpg.presentation.pip.LocalIsInPip
import com.march7th.hkrpg.presentation.pip.PipController
import com.march7th.hkrpg.presentation.pip.PipHost
import com.march7th.hkrpg.presentation.pip.PipRequest
import com.march7th.hkrpg.presentation.state.PreviewPointerSlots
import com.march7th.hkrpg.presentation.view.panel.LogPanel
import com.march7th.hkrpg.presentation.view.panel.PanelDialogType
import com.march7th.hkrpg.presentation.view.panel.PanelHeader
import com.march7th.hkrpg.presentation.view.panel.PanelTab
import com.march7th.hkrpg.presentation.view.panel.TaskListDetailLayout
import com.march7th.hkrpg.presentation.viewmodel.BackgroundTaskViewModel
import com.march7th.hkrpg.theme.LocalReduceMotion
import com.march7th.hkrpg.theme.M7aMotion
import com.march7th.hkrpg.theme.MaaThemeAlphas
import com.march7th.hkrpg.utils.i18n.asString
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import timber.log.Timber
import kotlin.math.roundToInt

@Composable
fun BackgroundTaskView(
    viewModel: BackgroundTaskViewModel,
    /** 子页面盖上来时 MainScreen 用 alpha 0 保活组合树，本页仍在组合但看不见，此时不能开画中画 */
    isActivePage: Boolean = true,
    compositionService: TaskCompositionService = koinInject(),
    dispatcher: UnifiedStateDispatcher = koinInject(),
    screenSaverManager: ScreenSaverOverlayManager = koinInject(),
    appWatchdog: AppWatchdog = koinInject(),
    appSettingsManager: AppSettingsManager = koinInject(),
    permissionManager: PermissionManager = koinInject(),
) {

    val coroutineScope = rememberCoroutineScope()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val maaState by compositionService.state.collectAsStateWithLifecycle()
    val isTaskActive = maaState != TaskExecutionState.IDLE && maaState != TaskExecutionState.ERROR
    val runMode by appSettingsManager.runMode.collectAsStateWithLifecycle()
    val permissionState by permissionManager.state.collectAsStateWithLifecycle()
    val markers by viewModel.markers.collectAsStateWithLifecycle()
    val displayResolution by compositionService.displayResolution.collectAsStateWithLifecycle()
    val pipOnHome by appSettingsManager.pipOnHome.collectAsStateWithLifecycle()
    val isChainLoaded by viewModel.chainState.isLoaded.collectAsStateWithLifecycle()
    var hasInitialized by rememberSaveable { mutableStateOf(false) }
    if (isChainLoaded) {
        hasInitialized = true
    }
    val isInitialized = hasInitialized

    var showCloseConfirm by remember { mutableStateOf(false) }
    var showMoreActions by remember { mutableStateOf(false) }
    var showLogExportSheet by remember { mutableStateOf(false) }
    LogExportController(
        sheetVisible = showLogExportSheet,
        onSheetDismiss = { showLogExportSheet = false },
    )

    val nodes by viewModel.chainState.chain.collectAsStateWithLifecycle()
    val profiles by viewModel.chainState.profiles.collectAsStateWithLifecycle()
    val profileId by viewModel.chainState.profileId.collectAsStateWithLifecycle()
    val selectedNode = nodes.find { it.id == state.selectedNodeId }
    val clientType = remember(nodes) { viewModel.chainState.clientType }
    val canShowTaskActions = PanelTab.canShowTaskActions(state.current)

    // 引导靶点在任务面板，重看时先切回去

    val pagerState = rememberPagerState(
        initialPage = state.current.ordinal, pageCount = { PanelTab.entries.size })

    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { page ->
            val newTab = PanelTab.entries[page]
            if (newTab != state.current) {
                viewModel.onTabChange(newTab)
            }
        }
    }

    val reduceMotion = LocalReduceMotion.current
    LaunchedEffect(state.current, reduceMotion) {
        if (pagerState.currentPage != state.current.ordinal) {
            pagerState.animateScrollToPage(
                state.current.ordinal,
                animationSpec = tween(
                    durationMillis = M7aMotion.pagerDuration(1, reduceMotion),
                    easing = M7aMotion.Emphasized,
                ),
            )
        }
    }
    val context = LocalContext.current
    val serviceDiedMessage = stringResource(R.string.bg_toast_service_died)
    val appDiedMessage = stringResource(R.string.bg_toast_app_died)
    val displayDriftMessage = stringResource(R.string.bg_toast_display_drift)

    // pageReady 栅栏已移除：启动由 LaunchPipeline 驱动，无需等本页 Surface

    LaunchedEffect(Unit) {
        dispatcher.serviceDiedEvent.collect {
            Toast.makeText(
                context, serviceDiedMessage, Toast.LENGTH_SHORT
            ).show()
        }
    }

    LaunchedEffect(Unit) {
        appWatchdog.appDiedEvent.collect {
            Toast.makeText(
                context, appDiedMessage, Toast.LENGTH_SHORT
            ).show()
        }
    }

    LaunchedEffect(Unit) {
        appWatchdog.displayDriftEvent.collect {
            Toast.makeText(
                context, displayDriftMessage, Toast.LENGTH_LONG
            ).show()
        }
    }

    LaunchedEffect(Unit) {
        viewModel.screenshotMessage.collect { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    val shouldHideMoreActions by remember {
        derivedStateOf {
            !canShowTaskActions || showCloseConfirm || state.isFullscreenMonitor || state.dialog != null
        }
    }
    LaunchedEffect(shouldHideMoreActions) {
        if (shouldHideMoreActions) showMoreActions = false
    }


    var isSurfaceAvailable by remember { mutableStateOf(false) }
    var lastSentSurface by remember { mutableStateOf<Surface?>(null) }
    val currentResolution by rememberUpdatedState(displayResolution)

    val pipHost = context as? PipHost
    val isInPip = LocalIsInPip.current

    // 竞态兜底：pipEligible 已排除全屏态，但 setPictureInPictureParams 要跨进程生效，
    // 点开全屏后立刻按 Home 仍可能带着全屏态进小窗，进去就退掉
    LaunchedEffect(isInPip) {
        if (isInPip) viewModel.onExitFullscreenMonitor()
    }

    var previewBounds by remember { mutableStateOf<Rect?>(null) }

    val previewContent = remember {
        movableContentOf {
            val innerScope = rememberCoroutineScope()
            Box(
                modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center
            ) {
                Box(modifier = Modifier.aspectRatio(DefaultDisplayConfig.ASPECT_RATIO)) {
                    AndroidView(
                        factory = { ctx ->
                            // ## TextureView 替代 SurfaceView（只改这一处）
                            //
                            // ### 为什么要换
                            //
                            // `SurfaceView` 把 buffer **按 1:1 像素**贴到视图上，
                            // **完全不缩放**。预览框正常大小（约 1000×560），
                            // 而 buffer 是 1920×1080 ——
                            // 于是画面**右下角整块超出预览框被裁掉**，用户看不到。
                            //
                            // 用户手绘示意：
                            // > 红色是预览，黑色是游戏画面，游戏比预览大，右下超出
                            // > 至于超出部分我看不到
                            //
                            // 这正是 SurfaceView 的固有行为：比例不变（1:1 当然不变形），
                            // 但内容比可视区大。
                            //
                            // ### 为什么用 TextureView
                            //
                            // 它是一张纹理，支持 `setTransform(Matrix)`。
                            // 按**等比缩放**算出 Matrix，画面完整填进预览框。
                            //
                            // ### 对正常手机无影响
                            //
                            // 布局层仍是 `aspectRatio(16/9)`，视图本来就是 16:9 时
                            // scale 刚好填满、平移为 0 —— 和原来完全一致。
                            TextureView(ctx).apply {
                                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                                    override fun onSurfaceTextureAvailable(
                                        st: SurfaceTexture, width: Int, height: Int
                                    ) {
                                        isSurfaceAvailable = true
                                        Timber.d("TextureView 可用 ${width}x$height")
                                        val surface = Surface(st)
                                        if (lastSentSurface !== surface) {
                                            lastSentSurface?.let { runCatching { it.release() } }
                                            lastSentSurface = surface
                                            viewModel.onSurfaceAvailable(surface)
                                        }
                                        applyFitCenterTransform()
                                    }

                                    override fun onSurfaceTextureSizeChanged(
                                        st: SurfaceTexture, width: Int, height: Int
                                    ) {
                                        Timber.d("TextureView 尺寸变化 ${width}x$height")
                                        applyFitCenterTransform()
                                    }

                                    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                                        isSurfaceAvailable = false
                                        lastSentSurface?.let { runCatching { it.release() } }
                                        lastSentSurface = null
                                        viewModel.onSurfaceDestroyed()
                                        return true
                                    }

                                    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {
                                        applyFitCenterTransform()
                                    }
                                }
                            }
                        }, modifier = Modifier.fillMaxSize()
                    )
                    // 必须重新读，movableContent 是 remember 出来的，捕获外层 val 会拿到陈旧值
                    if (!LocalIsInPip.current && markers.isNotEmpty()) TouchPreviewOverlay(
                        markers = markers,
                        displayResolution = displayResolution,
                        modifier = Modifier.fillMaxSize()
                    )
                    val gameFps by viewModel.gameFps.collectAsStateWithLifecycle()
                    gameFps?.let { fps ->
                        Text(
                            text = "${fps.roundToInt()} FPS",
                            color = Color.White.copy(alpha = 0.85f),
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(6.dp)
                                .background(
                                    Color.Black.copy(alpha = 0.35f),
                                    RoundedCornerShape(4.dp)
                                )
                                .padding(horizontal = 5.dp, vertical = 2.dp)
                        )
                    }
                }
            }
        }
    }

    // 全屏预览态排除在外：auto-enter 没有前置钩子，带着强制横屏和收起的系统栏进小窗会互相打架
    val pipEligible = pipOnHome &&
            isActivePage &&
            !state.isFullscreenMonitor &&
            runMode == RunMode.BACKGROUND &&
            (maaState == TaskExecutionState.STARTING || maaState == TaskExecutionState.RUNNING) &&
            isSurfaceAvailable &&
            PipController.isSupported(context)
    val pipActivity = pipHost as? Activity
    DisposableEffect(pipHost, pipActivity, pipEligible, displayResolution, previewBounds) {
        fun arm(enabled: Boolean, sourceRect: Rect?) {
            val host = pipHost ?: return
            val activity = pipActivity ?: return
            val request = PipRequest(displayResolution, sourceRect)
            host.pipRequest = if (enabled) request else null
            PipController.updateParams(activity, enabled, request)
        }
        arm(pipEligible, previewBounds)
        onDispose { arm(enabled = false, sourceRect = null) }
    }

    if (isInPip) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            previewContent()
        }
        return
    }

    // 放在画中画分支之后，小窗里不该弹准备度引导
    ShizukuReadinessGate()

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(M7aWindowInsets.topBar)
                .padding(horizontal = 16.dp)
                .padding(top = 8.dp, bottom = 8.dp)
        ) {
            // --- 预览图区域：实时加载 ---
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(3f)
                                ) {
                if (!state.isFullscreenMonitor) {
                    VirtualDisplayPreview(
                        modifier = Modifier
                            .fillMaxSize()
                            .onGloballyPositioned { coords ->
                                val bounds = coords.boundsInWindow()
                                val next = Rect(
                                    bounds.left.toInt(),
                                    bounds.top.toInt(),
                                    bounds.right.toInt(),
                                    bounds.bottom.toInt(),
                                )
                                if (!next.isEmpty && next != previewBounds) {
                                    previewBounds = next
                                }
                            },
                        isRunning = maaState == TaskExecutionState.RUNNING,
                        isSurfaceAvailable = isSurfaceAvailable,
                        onClick = { viewModel.onToggleFullscreenMonitor() }) {
                        previewContent()
                    }
                } else {
                    Spacer(modifier = Modifier.fillMaxSize())
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // --- 业务内容区域：阶梯加载 ---
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(7f)
            ) {
                PanelHeader(
                    selectedTab = state.current,
                    onTabSelected = { tab -> viewModel.onTabChange(tab) },
                    showActions = false,
                )

                if (isInitialized) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    ) {
                        HorizontalPager(
                            state = pagerState,
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            userScrollEnabled = true,
                            beyondViewportPageCount = 0
                        ) { page ->
                            when (page) {
                                0 -> {
                                    TaskListDetailLayout(
                                        nodes = nodes,
                                        selectedNode = selectedNode,
                                        selectedNodeId = state.selectedNodeId,
                                        isEditMode = state.isEditMode,
                                        isAddingTask = state.isAddingTask,
                                        isProfileMode = state.isProfileMode,
                                        profiles = profiles,
                                        activeProfileId = profileId,
                                        clientType = clientType,
                                        onNodeEnabledChange = viewModel::onNodeEnabledChange,
                                        onNodeSelected = viewModel::onNodeSelected,
                                        onNodeMove = viewModel::onNodeMove,
                                        onToggleEditMode = viewModel::onToggleEditMode,
                                        onToggleAddingTask = viewModel::onToggleAddingTask,
                                        onToggleProfileMode = viewModel::onToggleProfileMode,
                                        onConfigChange = { config ->
                                            val nodeId = selectedNode?.id
                                                ?: return@TaskListDetailLayout
                                            viewModel.onNodeConfigChange(nodeId, config)
                                        },
                                        onAddNode = viewModel::onAddNode,
                                        onRemoveNode = viewModel::onRemoveNode,
                                        onDuplicateNode = viewModel::onDuplicateNode,
                                        onRenameNode = viewModel::onRenameNode,
                                        onSwitchProfile = viewModel::onSwitchProfile,
                                        onRenameProfile = viewModel::onRenameProfile,
                                        onDuplicateProfile = viewModel::onDuplicateProfile,
                                        onDeleteProfile = viewModel::onDeleteProfile,
                                        onCreateProfile = viewModel::onCreateProfile,
                                        onReorderProfile = viewModel::onReorderProfile,
                                        modifier = Modifier.fillMaxSize(),
                                        wrapDetailInCard = true,
                                    )
                                }

                                1 -> {
                                    val runtimeLogs by viewModel.logs.collectAsStateWithLifecycle()
                                    LogPanel(
                                        logs = runtimeLogs,
                                        onClearLogs = { viewModel.onClearLogs() },
                                        onExportLogs = { showLogExportSheet = true },
                                    )
                                }
                            }
                        }

                        if (canShowTaskActions) {
                            Spacer(modifier = Modifier.height(6.dp))
                            val inputFocusManager = LocalInputFocusManager.current
                            // 启动按钮的禁用态：远程后端（Shizuku/Root）不可用时显示为禁用，
                            // 但仍可点击并给出提示（防呆），与领域层 checkPreconditions 守卫一致。
                            // 前台模式已删除，不再需要「请先切到后台模式」这条分支。
                            val backendBlocked =
                                !permissionState.isStartupBackendAvailable(permissionState.startupBackend)
                            val startBlocked = backendBlocked
                            val backendUnavailableMessage = stringResource(
                                R.string.home_toast_backend_unavailable,
                                permissionState.startupBackend.display
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // 运行中换成停止，空出的位置给「快捷选项」
                                val taskRunning =
                                    maaState == TaskExecutionState.RUNNING ||
                                            maaState == TaskExecutionState.STOPPING
                                if (taskRunning) {
                                    OutlinedButton(
                                        onClick = { viewModel.onStopTasks() },
                                        enabled = maaState == TaskExecutionState.RUNNING,
                                        modifier = Modifier.weight(1f),
                                        shape = RoundedCornerShape(8.dp),
                                        colors = ButtonDefaults.outlinedButtonColors(
                                            contentColor = MaterialTheme.colorScheme.error
                                        )
                                    ) {
                                        if (maaState == TaskExecutionState.STOPPING) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(20.dp),
                                                color = MaterialTheme.colorScheme.error,
                                                strokeWidth = 2.dp
                                            )
                                        } else {
                                            Text(
                                                text = stringResource(R.string.task_btn_stop),
                                                maxLines = 1,
                                            )
                                        }
                                    }
                                } else {
                                    Button(
                                        onClick = {
                                            inputFocusManager.clear()
                                            if (backendBlocked) {
                                                Toast.makeText(
                                                    context,
                                                    backendUnavailableMessage,
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                                return@Button
                                            }
                                            viewModel.onStartTasks()
                                        },
                                        enabled = !isTaskActive,
                                        colors = if (startBlocked) {
                                            ButtonDefaults.buttonColors(
                                                containerColor = MaterialTheme.colorScheme.onSurface.copy(
                                                    alpha = 0.12f
                                                ),
                                                contentColor = MaterialTheme.colorScheme.onSurface.copy(
                                                    alpha = MaaThemeAlphas.DISABLED
                                                ),
                                            )
                                        } else {
                                            ButtonDefaults.buttonColors()
                                        },
                                        modifier = Modifier.weight(1f),
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        if (maaState == TaskExecutionState.STARTING) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(20.dp),
                                                color = MaterialTheme.colorScheme.onPrimary,
                                                strokeWidth = 2.dp
                                            )
                                        } else {
                                            Text(
                                                text = stringResource(R.string.task_btn_start),
                                                maxLines = 1,
                                            )
                                        }
                                    }
                                }

                                // 次级操作不参与等分，按内容取宽，剩下的都留给主操作
                                OutlinedButton(
                                    onClick = { showMoreActions = !showMoreActions },
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 14.dp),
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        contentColor = if (showMoreActions) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurface
                                        }
                                    ),
                                    border = BorderStroke(
                                        1.dp,
                                        if (showMoreActions) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.outline
                                        }
                                    )
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.MoreVert,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = stringResource(R.string.task_btn_quick_options),
                                        maxLines = 1,
                                    )
                                }
                            }
                        }
                    }
                } else {
                    // 初始化中的骨架占位
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(32.dp), strokeWidth = 2.dp
                        )
                    }
                }
            }
        }

        BackHandler(enabled = showMoreActions) {
            showMoreActions = false
        }

        if (showMoreActions) {
            val isGameMuted by viewModel.isGameMuted.collectAsStateWithLifecycle()
            val runDeadline by compositionService.runDeadline.collectAsStateWithLifecycle()
            BackgroundMoreActionsOverlay(
                onDismissRequest = { showMoreActions = false },
                isTaskActive = isTaskActive,
                runDeadline = runDeadline,
                isGameMuted = isGameMuted,
                onToggleGameSound = viewModel::onToggleGameSound,
                onScreenOff = viewModel::onScreenOff,
                onShowScreenSaver = { coroutineScope.launch { screenSaverManager.show() } },
                onCaptureScreenshot = viewModel::onCaptureDebugScreenshot,
                onCloseApp = {
                    if (maaState == TaskExecutionState.RUNNING) {
                        showCloseConfirm = true
                    } else {
                        coroutineScope.launch { compositionService.stopVirtualDisplay() }
                    }
                },
            )
        }

        // 全屏预览
        if (state.isFullscreenMonitor) {
            val activity = context as? Activity

            DisposableEffect(Unit) {
                val window = activity?.window
                val controller = window?.let {
                    WindowCompat.getInsetsController(it, it.decorView)
                }
                controller?.hide(WindowInsetsCompat.Type.systemBars())
                controller?.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

                onDispose {
                    controller?.show(WindowInsetsCompat.Type.systemBars())
                }
            }

            DisposableEffect(Unit) {
                val originalOrientation = activity?.requestedOrientation
                onDispose {
                    if (originalOrientation != null) {
                        activity.requestedOrientation = originalOrientation
                    }
                }
            }

            LaunchedEffect(Unit) {
                val current = activity?.resources?.configuration?.orientation
                if (current != Configuration.ORIENTATION_LANDSCAPE) {
                    activity?.requestedOrientation =
                        ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                }
            }

            BackHandler { viewModel.onToggleFullscreenMonitor() }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .pointerInput(Unit) {
                        val slots = PreviewPointerSlots()
                        try {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    for (change in event.changes) {
                                        val pointerId = change.id.value
                                        val down = change.changedToDownIgnoreConsumed()
                                        val up = change.changedToUpIgnoreConsumed()
                                        if (!down && !up && !change.positionChangedIgnoreConsumed()) continue

                                        // 画面外按下忽略；拖出画面钳到边缘，保证抬起送达
                                        val point = viewToVirtualDisplay(
                                            change.position,
                                            size,
                                            displayResolution
                                        )
                                        val (x, y) = point.offset
                                        when {
                                            down -> {
                                                if (!point.inside) continue
                                                val contact = slots.acquire(pointerId)
                                                if (contact < 0) continue
                                                viewModel.onTouchDown(x, y, contact)
                                            }

                                            up -> {
                                                val contact = slots.release(pointerId)
                                                if (contact < 0) continue
                                                viewModel.onTouchUp(x, y, contact)
                                            }

                                            else -> {
                                                val contact = slots.indexOf(pointerId)
                                                if (contact < 0) continue
                                                viewModel.onTouchMove(x, y, contact)
                                            }
                                        }
                                        change.consume()
                                    }
                                }
                            }
                        } finally {
                            // 预览退出时仍按着的手指由远端整体 CANCEL
                            viewModel.onTouchCancel()
                        }
                    }, contentAlignment = Alignment.Center
            ) {
                previewContent()

                IconButton(
                    onClick = { viewModel.onToggleFullscreenMonitor() },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 8.dp, end = 8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.task_close_preview_cd),
                        tint = Color.White.copy(alpha = 0.7f),
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
        }

        state.dialog?.let { dialog ->
            val (onDismiss, onConfirm) = viewModel::onDialogDismiss to viewModel::onDialogConfirm
            val confirmColor = when (dialog.type) {
                PanelDialogType.SUCCESS -> MaterialTheme.colorScheme.primary
                PanelDialogType.WARNING -> MaterialTheme.colorScheme.tertiary
                PanelDialogType.ERROR -> MaterialTheme.colorScheme.error
            }
            val dialogTitle = dialog.title.asString()
            val dialogMessage = dialog.message.asString()
            val dialogConfirmText = dialog.confirmText.asString()
            val dialogDismissText = dialog.dismissText.asString()
            AdaptiveTaskPromptDialog(
                visible = true,
                title = dialogTitle,
                message = AnnotatedString(dialogMessage),
                onDismissRequest = onDismiss,
                onConfirm = onConfirm,
                confirmText = dialogConfirmText.ifBlank {
                    stringResource(R.string.common_confirm)
                },
                dismissText = dialogDismissText.ifBlank {
                    stringResource(R.string.common_close)
                },
                icon = when (dialog.type) {
                    PanelDialogType.SUCCESS -> Icons.Filled.CheckCircle
                    else -> Icons.Filled.Warning
                },
                iconTint = confirmColor,
                confirmColor = confirmColor,
            )
        }

        if (showCloseConfirm) {
            AdaptiveTaskPromptDialog(
                visible = true,
                title = stringResource(R.string.dialog_close_app_title),
                message = AnnotatedString(stringResource(R.string.dialog_close_app_message)),
                onDismissRequest = { showCloseConfirm = false },
                onConfirm = {
                    showCloseConfirm = false
                    coroutineScope.launch { compositionService.stopVirtualDisplay() }
                },
                confirmText = stringResource(R.string.dialog_close_app_confirm),
                dismissText = stringResource(R.string.common_cancel),
                icon = Icons.Filled.Warning,
                iconTint = MaterialTheme.colorScheme.error,
                confirmColor = MaterialTheme.colorScheme.error,
            )
        }

    }
}

/** offset 已钳到边缘，inside 取原始落点 */
private class DisplayPoint(val offset: IntOffset, val inside: Boolean)

private fun viewToVirtualDisplay(
    view: Offset,
    viewSize: IntSize,
    display: DefaultDisplayConfig.Resolution,
): DisplayPoint {
    val bufferW = display.width.toFloat()
    val bufferH = display.height.toFloat()
    val scale = minOf(viewSize.width / bufferW, viewSize.height / bufferH)
    val offsetX = (viewSize.width - bufferW * scale) / 2f
    val offsetY = (viewSize.height - bufferH * scale) / 2f
    val vx = ((view.x - offsetX) / scale).toInt()
    val vy = ((view.y - offsetY) / scale).toInt()
    val inside = vx in 0 until display.width && vy in 0 until display.height
    return DisplayPoint(
        IntOffset(vx.coerceIn(0, display.width - 1), vy.coerceIn(0, display.height - 1)),
        inside,
    )
}


@Composable
private fun BackgroundMoreActionsOverlay(
    onDismissRequest: () -> Unit,
    isTaskActive: Boolean,
    runDeadline: Long?,
    isGameMuted: Boolean,
    onToggleGameSound: () -> Unit,
    onScreenOff: () -> Unit,
    onShowScreenSaver: () -> Unit,
    onCaptureScreenshot: () -> Unit,
    onCloseApp: () -> Unit,
    appSettingsManager: AppSettingsManager = koinInject(),
) {
    val coroutineScope = rememberCoroutineScope()
    val muteOnGameLaunch by appSettingsManager.muteOnGameLaunch.collectAsStateWithLifecycle()
    val closeAppOnTaskEnd by appSettingsManager.closeAppOnTaskEnd.collectAsStateWithLifecycle()
    val runDurationLimitEnabled by appSettingsManager.runDurationLimitEnabled.collectAsStateWithLifecycle()
    val runDurationLimitMinutes by appSettingsManager.runDurationLimitMinutes.collectAsStateWithLifecycle()
    val useHardwareScreenOff by appSettingsManager.useHardwareScreenOff.collectAsStateWithLifecycle()
    val showTouchPreview by appSettingsManager.showTouchPreview.collectAsStateWithLifecycle()
    val debugMode by appSettingsManager.debugMode.collectAsStateWithLifecycle()
    var showHardwareScreenOffConfirm by remember { mutableStateOf(false) }
    var showRunDurationInput by remember { mutableStateOf(false) }
    // 非空表示静音确认框待确认，确认后执行；仅静音方向弹，解除不弹
    var pendingMuteAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    val overlayInteractionSource = remember { MutableInteractionSource() }
    val cardInteractionSource = remember { MutableInteractionSource() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = overlayInteractionSource,
                indication = null,
                onClick = onDismissRequest
            )
    ) {
        Card(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 64.dp)
                .clickable(
                    interactionSource = cardInteractionSource, indication = null, onClick = {}),
            shape = RoundedCornerShape(4.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(modifier = Modifier.padding(10.dp)) {
                // 标题与快速操作组
                Text(
                    text = stringResource(R.string.bg_actions_title),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    ActionTile(
                        icon = Icons.Filled.PowerSettingsNew,
                        label = stringResource(R.string.bg_action_screen_off),
                        onClick = {
                            if (useHardwareScreenOff) onScreenOff() else onShowScreenSaver()
                        },
                        modifier = Modifier.weight(1f),
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onSurface
                    )
                    ActionTile(
                        icon = Icons.AutoMirrored.Filled.ExitToApp,
                        label = stringResource(R.string.bg_action_close_game),
                        onClick = onCloseApp,
                        modifier = Modifier.weight(1f),
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.error
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    ActionTile(
                        icon = if (isGameMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                        label = if (isGameMuted) stringResource(R.string.bg_action_game_muted)
                        else stringResource(R.string.bg_action_mute_game),
                        onClick = {
                            if (isGameMuted) onToggleGameSound() else pendingMuteAction = onToggleGameSound
                        },
                        modifier = Modifier.weight(1f),
                        containerColor = if (isGameMuted) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.secondary,
                        contentColor = if (isGameMuted) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurface
                    )
                }

                // 调试模式：截图按钮，保存到 {rootDir}/debug/screenshots
                if (debugMode) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        ActionTile(
                            icon = Icons.Filled.Screenshot,
                            label = stringResource(R.string.bg_action_screenshot),
                            onClick = onCaptureScreenshot,
                            modifier = Modifier.weight(1f),
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp
                )
                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = stringResource(R.string.bg_auto_settings_title),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(4.dp))

                SettingSwitchRow(
                    icon = Icons.Filled.NotificationsPaused,
                    label = stringResource(R.string.bg_auto_mute_on_launch),
                    checked = muteOnGameLaunch,
                    onCheckedChange = { checked ->
                        val apply = {
                            coroutineScope.launch { appSettingsManager.setMuteOnGameLaunch(checked) }
                            Unit
                        }
                        if (checked) pendingMuteAction = apply else apply()
                    })
                SettingSwitchRow(
                    icon = Icons.Filled.Cancel,
                    label = stringResource(R.string.bg_auto_close_on_end),
                    checked = closeAppOnTaskEnd,
                    onCheckedChange = {
                        coroutineScope.launch { appSettingsManager.setCloseAppOnTaskEnd(it) }
                    })
                // 本轮已按开始时的设置计时，运行中禁改
                SettingSwitchRow(
                    icon = Icons.Filled.Timer,
                    label = stringResource(R.string.bg_auto_run_duration_limit),
                    checked = runDurationLimitEnabled,
                    enabled = !isTaskActive,
                    onCheckedChange = {
                        coroutineScope.launch { appSettingsManager.setRunDurationLimitEnabled(it) }
                    })
                if (runDeadline != null) {
                    RunDurationRemainingRow(deadline = runDeadline)
                } else if (runDurationLimitEnabled) {
                    RunDurationStepperRow(
                        minutes = runDurationLimitMinutes,
                        enabled = !isTaskActive,
                        onMinutesChange = {
                            coroutineScope.launch { appSettingsManager.setRunDurationLimitMinutes(it) }
                        },
                        onEditClick = { showRunDurationInput = true })
                }
                SettingSwitchRow(
                    icon = Icons.Filled.StayCurrentPortrait,
                    label = stringResource(R.string.bg_auto_hardware_screen_off),
                    checked = useHardwareScreenOff,
                    onCheckedChange = { checked ->
                        if (checked) {
                            showHardwareScreenOffConfirm = true
                        } else {
                            coroutineScope.launch {
                                appSettingsManager.setUseHardwareScreenOff(
                                    false
                                )
                            }
                        }
                    })
                SettingSwitchRow(
                    icon = Icons.Filled.TouchApp,
                    label = stringResource(R.string.bg_auto_show_touch_preview),
                    checked = showTouchPreview,
                    onCheckedChange = {
                        coroutineScope.launch { appSettingsManager.setShowTouchPreview(it) }
                    })
            }
        }
    }

    pendingMuteAction?.let { action ->
        AdaptiveTaskPromptDialog(
            visible = true,
            title = stringResource(R.string.dialog_mute_game_title),
            message = AnnotatedString(stringResource(R.string.dialog_mute_game_message)),
            onDismissRequest = { pendingMuteAction = null },
            onConfirm = {
                pendingMuteAction = null
                action()
            },
            confirmText = stringResource(R.string.dialog_mute_game_confirm),
            dismissText = stringResource(R.string.common_cancel),
            icon = Icons.AutoMirrored.Filled.VolumeOff,
            iconTint = MaterialTheme.colorScheme.primary,
            confirmColor = MaterialTheme.colorScheme.primary,
        )
    }

    // 任务开始即收起，结束后不再冒出来
    LaunchedEffect(isTaskActive) {
        if (isTaskActive) showRunDurationInput = false
    }
    if (showRunDurationInput && !isTaskActive) {
        RunDurationInputDialog(
            minutes = runDurationLimitMinutes,
            onDismissRequest = { showRunDurationInput = false },
            onConfirm = { minutes ->
                showRunDurationInput = false
                coroutineScope.launch { appSettingsManager.setRunDurationLimitMinutes(minutes) }
            },
        )
    }

    if (showHardwareScreenOffConfirm) {
        AdaptiveTaskPromptDialog(
            visible = true,
            title = stringResource(R.string.dialog_hardware_screen_off_title),
            message = AnnotatedString(stringResource(R.string.dialog_hardware_screen_off_message)),
            onDismissRequest = { showHardwareScreenOffConfirm = false },
            onConfirm = {
                showHardwareScreenOffConfirm = false
                coroutineScope.launch { appSettingsManager.setUseHardwareScreenOff(true) }
            },
            confirmText = stringResource(R.string.common_confirm),
            dismissText = stringResource(R.string.common_cancel),
            icon = Icons.Filled.PowerSettingsNew,
            iconTint = MaterialTheme.colorScheme.primary,
            confirmColor = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun ActionTile(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color,
    contentColor: Color
) {
    Surface(
        onClick = onClick,
        modifier = modifier.height(36.dp),
        shape = RoundedCornerShape(4.dp),
        color = containerColor.copy(alpha = 0.08f),
        contentColor = contentColor,
        border = BorderStroke(0.5.dp, containerColor.copy(alpha = 0.2f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = containerColor.copy(alpha = 0.8f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun SettingSwitchRow(
    icon: ImageVector,
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(32.dp)
            .clickable(enabled = enabled) { onCheckedChange(!checked) },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = MaaThemeAlphas.DISABLED)
            },
            modifier = Modifier.weight(1f)
        )
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
            Checkbox(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = enabled,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

/** 与开关行文字对齐的缩进行 */
@Composable
private fun RunDurationSubRow(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(32.dp)
            .padding(start = 26.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
private fun RunDurationStepperRow(
    minutes: Int,
    enabled: Boolean,
    onMinutesChange: (Int) -> Unit,
    onEditClick: () -> Unit,
) {
    RunDurationSubRow {
        Row(
            modifier = Modifier
                .weight(1f)
                .clickable(
                    enabled = enabled,
                    onClickLabel = stringResource(R.string.bg_auto_run_duration_limit_edit),
                    onClick = onEditClick,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val valueColor = if (enabled) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = MaaThemeAlphas.DISABLED)
            }
            Text(
                text = stringResource(R.string.bg_auto_run_duration_limit_value, minutes),
                style = MaterialTheme.typography.bodyMedium,
                color = valueColor,
            )
            Spacer(modifier = Modifier.width(4.dp))
            Icon(
                imageVector = Icons.Filled.Edit,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = valueColor,
            )
        }
        IconButton(
            onClick = { onMinutesChange(RunDurationStep.decrease(minutes)) },
            enabled = enabled && RunDurationStep.canDecrease(minutes),
            modifier = Modifier.size(32.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.Remove,
                contentDescription = stringResource(R.string.bg_auto_run_duration_limit_decrease),
                modifier = Modifier.size(18.dp)
            )
        }
        IconButton(
            onClick = { onMinutesChange(RunDurationStep.increase(minutes)) },
            enabled = enabled && RunDurationStep.canIncrease(minutes),
            modifier = Modifier.size(32.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = stringResource(R.string.bg_auto_run_duration_limit_increase),
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun RunDurationInputDialog(
    minutes: Int,
    onDismissRequest: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    var input by remember {
        val text = minutes.toString()
        mutableStateOf(TextFieldValue(text, selection = TextRange(0, text.length)))
    }
    val focusRequester = remember { FocusRequester() }
    val submit = {
        val parsed = RunDurationStep.parseInput(input.text)
        if (parsed == null) onDismissRequest() else onConfirm(parsed)
    }
    AdaptiveTaskPromptDialog(
        visible = true,
        title = stringResource(R.string.bg_auto_run_duration_limit_input_title),
        onDismissRequest = onDismissRequest,
        onConfirm = submit,
        icon = Icons.Filled.Timer,
    ) {
        OutlinedTextField(
            value = input,
            onValueChange = { if (RunDurationStep.acceptsInput(it.text)) input = it },
            label = { Text(stringResource(R.string.bg_auto_run_duration_limit_input_label)) },
            supportingText = {
                Text(
                    stringResource(
                        R.string.bg_auto_run_duration_limit_input_hint,
                        RunDurationLimit.MIN_MINUTES,
                        RunDurationLimit.MAX_MINUTES,
                    )
                )
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester),
        )
    }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
}

@Composable
private fun RunDurationRemainingRow(deadline: Long) {
    val remainingMs by produceState(deadline - SystemClock.elapsedRealtime(), deadline) {
        while (true) {
            value = deadline - SystemClock.elapsedRealtime()
            delay(1_000L)
        }
    }
    RunDurationSubRow {
        Text(
            text = stringResource(
                R.string.bg_auto_run_duration_limit_remaining,
                RunDurationStep.formatRemaining(remainingMs),
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/**
 * 把 [TextureView] 的内容**等比缩放以完整显示**。
 *
 * ```
 * scale = min(viewW / srcW, viewH / srcH)
 * dx    = (viewW - srcW * scale) / 2
 * dy    = (viewH - srcH * scale) / 2
 * ```
 *
 * - 视图比例恰好 16:9 → scale 刚好填满，dx = dy = 0（无黑边）
 * - 视图偏小/偏大     → 等比缩放 + 居中，**内容完整可见，永不裁切**
 */
private fun TextureView.applyFitCenterTransform() {
    val vw = width.toFloat()
    val vh = height.toFloat()
    if (vw <= 0f || vh <= 0f) return

    val sw = DefaultDisplayConfig.WIDTH.toFloat()
    val sh = DefaultDisplayConfig.HEIGHT.toFloat()

    val scale = minOf(vw / sw, vh / sh)
    val dx = (vw - sw * scale) / 2f
    val dy = (vh - sh * scale) / 2f

    val m = Matrix()
    m.setScale(scale, scale)
    m.postTranslate(dx, dy)
    setTransform(m)
}
