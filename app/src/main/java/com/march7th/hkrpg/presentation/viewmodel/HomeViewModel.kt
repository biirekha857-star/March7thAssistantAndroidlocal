package com.march7th.hkrpg.presentation.viewmodel

import android.content.Context
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.march7th.hkrpg.R
import com.march7th.hkrpg.constant.DisplayMode
import com.march7th.hkrpg.data.config.CorePathConfig
import com.march7th.hkrpg.data.preferences.AppSettingsManager
import com.march7th.hkrpg.domain.models.CoreDataLocation
import com.march7th.hkrpg.domain.models.RunMode
import com.march7th.hkrpg.domain.service.TaskCompositionService
import com.march7th.hkrpg.domain.service.CoreResourceLoader
import com.march7th.hkrpg.domain.service.ResourceInitService
import com.march7th.hkrpg.domain.service.update.UpdateService
import com.march7th.hkrpg.domain.state.TaskExecutionState
import com.march7th.hkrpg.domain.usecase.SwitchCoreDataLocationUseCase
import com.march7th.hkrpg.manager.PermissionManager
import com.march7th.hkrpg.manager.RemoteServiceManager
import com.march7th.hkrpg.manager.RemoteServiceManager.useRemoteService
import com.march7th.hkrpg.manager.ShizukuInstallHelper
import com.march7th.hkrpg.overlay.OverlayController
import com.march7th.hkrpg.presentation.state.HomeUiState
import com.march7th.hkrpg.presentation.state.StatusColorType
import com.march7th.hkrpg.presentation.state.UiEffect
import com.march7th.hkrpg.schedule.data.ScheduleStrategyRepository
import com.march7th.hkrpg.schedule.service.ScheduleAlarmManager
import com.march7th.hkrpg.utils.Misc
import com.march7th.hkrpg.utils.i18n.remoteBackendPermissionLabel
import com.march7th.hkrpg.utils.i18n.uiTextOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

class HomeViewModel(
    private val application: Context,
    private val appSettingsManager: AppSettingsManager,
    private val overlayController: OverlayController,
    private val updateService: UpdateService,
    private val permissionManager: PermissionManager,
    private val resourceLoader: CoreResourceLoader,
    private val compositionService: TaskCompositionService,
    private val resourceInitService: ResourceInitService,
    private val scheduleAlarmManager: ScheduleAlarmManager,
    private val scheduleStrategyRepository: ScheduleStrategyRepository,
    private val pathConfig: CorePathConfig,
    private val switchCoreDataLocation: SwitchCoreDataLocationUseCase,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        HomeUiState(serviceStatusText = uiTextOf(R.string.home_status_disconnected))
    )
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private val _effects = Channel<UiEffect>(Channel.BUFFERED)
    val effects = _effects.receiveAsFlow()

    // ========== 提权进程访问不了 core 数据目录（#227） ==========

    private val _coreDirDialogDismissed = MutableStateFlow(false)

    val showCoreDirInaccessibleDialog: StateFlow<Boolean> = combine(
        resourceLoader.state, _coreDirDialogDismissed
    ) { state, dismissed ->
        !dismissed && !pathConfig.isCoreSeparated
                && state is CoreResourceLoader.State.Failed
                && state.reason == CoreResourceLoader.State.FailReason.STORAGE_INACCESSIBLE
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun dismissCoreDirInaccessibleDialog() {
        _coreDirDialogDismissed.value = true
    }

    fun switchCoreDataToLocalTmp() {
        _coreDirDialogDismissed.value = true
        viewModelScope.launch { switchCoreDataLocation(CoreDataLocation.LOCAL_TMP) }
    }

    init {
        observeResourceUpdateState()
        observeServiceStatus()
        observeResourceInitState()
        observeRunMode()
        observeIsGranting()
        observeOverlayActive()
    }

    private fun observeResourceUpdateState() {
        viewModelScope.launch {
            updateService.resourceProcessState.collect { state ->
                Timber.i("ResourceUpdateState collect $state")
                _uiState.update { it.copy(resourceUpdateState = state) }
            }
        }
    }

    private fun observeServiceStatus() {
        viewModelScope.launch {
            combine(
                RemoteServiceManager.state, resourceLoader.state, compositionService.state
            ) { serviceState, resourceState, executionState ->
                Timber.i("ServiceState collect $serviceState $resourceState $executionState")
                val remoteServiceActive =
                    serviceState is RemoteServiceManager.ServiceState.Connected || serviceState is RemoteServiceManager.ServiceState.Connecting
                val status = when {
                    serviceState is RemoteServiceManager.ServiceState.Died || serviceState is RemoteServiceManager.ServiceState.Error -> Triple(
                        uiTextOf(R.string.home_status_service_error), StatusColorType.ERROR, false
                    )

                    serviceState is RemoteServiceManager.ServiceState.Connecting -> Triple(
                        uiTextOf(R.string.home_status_service_connecting),
                        StatusColorType.WARNING,
                        true
                    )

                    serviceState is RemoteServiceManager.ServiceState.Disconnected -> Triple(
                        uiTextOf(R.string.home_status_disconnected), StatusColorType.NEUTRAL, false
                    )

                    resourceState is CoreResourceLoader.State.Loading || resourceState is CoreResourceLoader.State.Reloading -> Triple(
                        uiTextOf(R.string.home_status_resource_loading),
                        StatusColorType.WARNING,
                        true
                    )

                    resourceState is CoreResourceLoader.State.Failed -> Triple(
                        uiTextOf(R.string.home_status_resource_failed), StatusColorType.ERROR, false
                    )

                    resourceState is CoreResourceLoader.State.NotLoaded -> Triple(
                        uiTextOf(R.string.home_status_resource_not_loaded),
                        StatusColorType.NEUTRAL,
                        false
                    )

                    executionState == TaskExecutionState.ERROR -> Triple(
                        uiTextOf(R.string.home_status_task_error), StatusColorType.ERROR, false
                    )

                    executionState == TaskExecutionState.STARTING -> Triple(
                        uiTextOf(R.string.home_status_task_starting), StatusColorType.WARNING, true
                    )

                    executionState == TaskExecutionState.RUNNING -> Triple(
                        uiTextOf(R.string.home_status_task_running), StatusColorType.PRIMARY, true
                    )

                    else -> Triple(
                        uiTextOf(R.string.home_status_ready),
                        StatusColorType.PRIMARY,
                        false
                    )
                }
                Pair(status, remoteServiceActive)
            }.collect { (status, remoteServiceActive) ->
                val (text, color, loading) = status
                _uiState.update {
                    it.copy(
                        serviceStatusText = text,
                        serviceStatusColor = color,
                        serviceStatusLoading = loading,
                        remoteServiceActive = remoteServiceActive
                    )
                }
            }
        }
    }

    private fun observeIsGranting() {
        viewModelScope.launch {
            permissionManager.isGranting.collect { granting ->
                _uiState.update { it.copy(isGranting = granting) }
            }
        }
    }

    private fun observeOverlayActive() {
        viewModelScope.launch {
            overlayController.isActive.collect { active ->
                _uiState.update { it.copy(isShowControlOverlay = active) }
            }
        }
    }

    private fun observeResourceInitState() {
        viewModelScope.launch {
            resourceInitService.state.collect { state ->
                _uiState.update { it.copy(resourceInitState = state) }
            }
        }
    }

    private fun observeRunMode() {
        viewModelScope.launch {
            appSettingsManager.runMode.collect { mode ->
                _uiState.update { it.copy(runMode = mode) }
            }
        }
    }

    fun checkAndInitResource() {
        viewModelScope.launch {
            resourceInitService.checkAndInit()
        }
    }

    fun onTryResourceInit() {
        viewModelScope.launch {
            resourceInitService.doExtractFromAssets()
        }
    }

    fun onRequestRemoteAccess() {
        viewModelScope.launch {
            val backend = permissionManager.permissions.startupBackend
            if (!permissionManager.permissions.isStartupBackendAvailable(backend)) {
                _effects.send(
                    UiEffect.toast(
                        R.string.home_toast_backend_unavailable, backend.display
                    )
                )
                return@launch
            }
            val granted = permissionManager.requestRemoteAccess()
            if (!granted) {
                _effects.send(
                    UiEffect.toast(
                        R.string.home_toast_backend_auth_failed, backend.display
                    )
                )
            }
        }
    }

    fun onRequestOverlay(context: Context) {
        viewModelScope.launch {
            permissionManager.requestOverlay(context)
        }
    }

    fun onRequestStorage(context: Context) {
        viewModelScope.launch {
            permissionManager.requestStorage(context)
        }
    }

    fun onRequestBatteryWhitelist(context: Context) {
        viewModelScope.launch {
            permissionManager.requestBatteryWhitelist(context)
        }
    }

    fun onRequestNotification(context: Context) {
        viewModelScope.launch {
            permissionManager.requestNotification(context)
        }
    }

    fun onStartControlOverlay() {
        viewModelScope.launch {
            try {
                _uiState.update { it.copy(isLoading = true) }

                // 刷新权限状态
                permissionManager.refresh()
                val state = permissionManager.permissions

                // 检查必要权限
                if (!state.remoteAccessGranted) {
                    _uiState.update { it.copy(isLoading = false) }
                    _effects.send(
                        UiEffect.toast(
                            R.string.home_toast_grant_permission,
                            application.remoteBackendPermissionLabel(state.startupBackend)
                        )
                    )
                    return@launch
                }

                val missingPermissions = buildList {
                    if (!state.overlay) add(application.getString(R.string.home_permission_overlay))
                    if (!state.storage) add(application.getString(R.string.home_permission_storage))
                }

                if (missingPermissions.isNotEmpty()) {
                    _uiState.update { it.copy(isLoading = false) }
                    val separator =
                        application.getString(R.string.home_toast_missing_permissions_separator)
                    _effects.send(
                        UiEffect.toast(
                            R.string.home_toast_missing_permissions,
                            missingPermissions.joinToString(separator)
                        )
                    )
                    return@launch
                }

                // 检查分辨率是否为 16:9
                if (!checkResolution()) {
                    _uiState.update { it.copy(isLoading = false) }
                    return@launch
                }

                overlayController.show()
                _uiState.update { it.copy(isLoading = false) }
            } catch (e: Exception) {
                Timber.e(e, "Error starting floating window")
                _uiState.update { it.copy(isLoading = false) }
                _effects.send(
                    UiEffect.toast(
                        R.string.home_toast_start_overlay_failed, e.message.orEmpty()
                    )
                )
            }
        }
    }

    fun onStopControlOverlay() {
        viewModelScope.launch {
            try {
                _uiState.update { it.copy(isLoading = true) }
                overlayController.hideAll()
                Timber.i("onStopFloatingWindow: Floating window hidden")
                _uiState.update { it.copy(isLoading = false) }
            } catch (e: Exception) {
                Timber.e(e, "Error stopping floating window")
                _uiState.update { it.copy(isLoading = false) }
                _effects.send(
                    UiEffect.toast(
                        R.string.home_toast_stop_overlay_failed, e.message.orEmpty()
                    )
                )
            }
        }
    }

    fun onOpenShizuku() {
        val opened = if (!appSettingsManager.shizukuShortcutEnabled.value) {
            false
        } else {
            ShizukuInstallHelper.openShizuku(
                application, appSettingsManager.shizukuLaunchPackage.value
            )
        }
        if (!opened) {
            _effects.trySend(UiEffect.toast(R.string.home_toast_open_shizuku_failed))
        }
    }

    fun onToggleRemoteService() {
        if (_uiState.value.remoteServiceActive) {
            onCloseRemoteService()
        } else {
            onOpenRemoteService()
        }
    }

    private fun onOpenRemoteService() {
        viewModelScope.launch {
            try {
                _uiState.update { it.copy(isLoading = true) }

                permissionManager.refresh()
                val state = permissionManager.permissions
                val backend = state.startupBackend
                if (!state.isStartupBackendAvailable(backend)) {
                    _uiState.update { it.copy(isLoading = false) }
                    _effects.send(
                        UiEffect.toast(
                            R.string.home_toast_backend_unavailable, backend.display
                        )
                    )
                    return@launch
                }

                if (!state.remoteAccessGranted) {
                    val granted = permissionManager.requestRemoteAccess()
                    if (!granted) {
                        _uiState.update { it.copy(isLoading = false) }
                        _effects.send(
                            UiEffect.toast(
                                R.string.home_toast_backend_auth_failed, backend.display
                            )
                        )
                        return@launch
                    }
                }

                RemoteServiceManager.bind()

                Timber.i("onOpenRemoteService: Service binding started")
                _uiState.update { it.copy(isLoading = false) }
            } catch (e: Exception) {
                Timber.e(e, "Error opening remote service")
                _uiState.update { it.copy(isLoading = false) }
                _effects.send(
                    UiEffect.toast(
                        R.string.home_toast_open_service_failed, e.message.orEmpty()
                    )
                )
            }
        }
    }

    private fun onCloseRemoteService() {
        viewModelScope.launch {
            try {
                _uiState.update { it.copy(isLoading = true) }

                // 先关闭依赖远程服务的入口，避免继续操作已断开的 Binder。
                overlayController.hideAll()
                RemoteServiceManager.unbind()

                Timber.i("onCloseRemoteService: Service unbound")
                _uiState.update { it.copy(isLoading = false) }
            } catch (e: Exception) {
                Timber.e(e, "Error closing remote service")
                _uiState.update { it.copy(isLoading = false) }
                _effects.send(
                    UiEffect.toast(
                        R.string.home_toast_close_service_failed, e.message.orEmpty()
                    )
                )
            }
        }
    }

    fun onChangeTo16x9Resolution(ctx: Context) {
        viewModelScope.launch {
            try {
                val label =
                    application.remoteBackendPermissionLabel(permissionManager.permissions.startupBackend)
                if (!permissionManager.permissions.remoteAccessGranted) {
                    val ret = permissionManager.requestRemoteAccess()
                    if (!ret) {
                        _effects.send(
                            UiEffect.toast(
                                R.string.home_toast_backend_not_acquired, label
                            )
                        )
                        return@launch
                    }
                }
                _uiState.update { it.copy(isLoading = true) }
                val (width, height) = Misc.getPhysicalSize(ctx)

                val (targetWidth, targetHeight) = Misc.calculate16x9Resolution(
                    width, height
                )
                val ret = withContext(Dispatchers.IO) {
                    useRemoteService { service ->
                        service.setForcedDisplaySize(targetWidth, targetHeight)
                    }
                }
                Timber.i("onChangeTo16x9Resolution: setForcedDisplaySize result: %s", ret)

                _uiState.update { it.copy(isLoading = false) }
            } catch (e: Exception) {
                Timber.e(e, "onChangeTo16x9Resolution: Error changing resolution")
                _uiState.update { it.copy(isLoading = false) }
                _effects.send(
                    UiEffect.toast(
                        R.string.home_toast_change_resolution_failed, e.message.orEmpty()
                    )
                )
            }
        }
    }

    fun onResetResolution() {
        Timber.i("onResetResolution: Resetting resolution to default")
        viewModelScope.launch {
            try {
                val label =
                    application.remoteBackendPermissionLabel(permissionManager.permissions.startupBackend)
                if (!permissionManager.permissions.remoteAccessGranted) {
                    val ret = permissionManager.requestRemoteAccess()
                    if (!ret) {
                        _effects.send(
                            UiEffect.toast(
                                R.string.home_toast_backend_not_acquired, label
                            )
                        )
                        return@launch
                    }
                }
                _uiState.update { it.copy(isLoading = true) }
                val ret = withContext(Dispatchers.IO) {
                    useRemoteService { it.clearForcedDisplaySize() }
                }
                Timber.i("onResetResolution: %s", ret)
                _uiState.update { it.copy(isLoading = false) }
            } catch (e: Exception) {
                Timber.e(e, "Error resetting resolution")
                _uiState.update { it.copy(isLoading = false) }
                _effects.send(
                    UiEffect.toast(
                        R.string.home_toast_reset_resolution_failed, e.message.orEmpty()
                    )
                )
            }
        }
    }

    private fun checkResolution(): Boolean {
        // 不能用 application.resources.displayMetrics：application context 的 DisplayMetrics
        // 不反映 setForcedDisplaySize 修改后的 forced size（在 Android 9 上持续返回原生物理分辨率
        // 减去系统栏的值）。改用 Misc.getScreenSize，其底层走 Display.getRealMetrics /
        // WindowMetrics.getBounds，能正确读到 IWindowManager 层的 forced size。
        val (width, height) = Misc.getScreenSize(application)
        val isValid = Misc.isAspectRatio16x9(width, height)
        if (!isValid) {
            _effects.trySend(UiEffect.toast(R.string.home_toast_not_16_9_resolution, long = true))
            Timber.w("resolution check failed: ${width}x${height}, required 16:9")
        }
        return isValid
    }

    fun onRunModeChange(isBackground: Boolean) {
        viewModelScope.launch {
            if (isBackground && Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                _uiState.update {
                    it.copy(
                        showRunModeUnsupportedDialog = true,
                        runModeUnsupportedMessage = uiTextOf(R.string.dialog_run_mode_unsupported_message_pre_q)
                    )
                }
                return@launch
            }

            // 前台模式已删除，本方法只剩「确保处于后台模式」的语义
            appSettingsManager.setRunMode(RunMode.BACKGROUND)
            // lead 时间依赖 runMode（FG 0s / BG 30s），切换后立即重排避免沿用旧偏移
            scheduleAlarmManager.rescheduleAll(scheduleStrategyRepository.strategies.value)
            val mode = DisplayMode.BACKGROUND
            if (permissionManager.permissions.remoteAccessGranted) {
                useRemoteService {
                    it.setVirtualDisplayMode(mode)
                }
            }
        }
    }

    fun onDismissRunModeUnsupportedDialog() {
        _uiState.update { it.copy(showRunModeUnsupportedDialog = false) }
    }

    fun checkRunModeChangeEnabled(): Boolean {
        val value = compositionService.state.value
        return !(value == TaskExecutionState.RUNNING || value == TaskExecutionState.STARTING || value == TaskExecutionState.STOPPING)
    }


}
