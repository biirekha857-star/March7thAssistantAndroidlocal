package com.march7th.hkrpg.domain.service

import android.content.Context
import com.alibaba.fastjson2.JSON
import com.march7th.hkrpg.M7aCoreCallback
import com.march7th.hkrpg.M7aCoreService
import com.march7th.hkrpg.R
import com.march7th.hkrpg.RemoteService
import com.march7th.hkrpg.constant.DefaultDisplayConfig
import com.march7th.hkrpg.constant.Packages
import com.march7th.hkrpg.data.model.LogLevel
import com.march7th.hkrpg.data.preferences.AppSettingsManager
import com.march7th.hkrpg.data.preferences.TaskChainState
import com.march7th.hkrpg.data.resource.ActivityManager
import com.march7th.hkrpg.domain.models.RemoteBackend
import com.march7th.hkrpg.domain.models.RunMode
import com.march7th.hkrpg.domain.notification.LiveSessionCoordinator
import com.march7th.hkrpg.domain.state.TaskExecutionState
import com.march7th.hkrpg.core.AsstMsg
import com.march7th.hkrpg.core.CoreInstanceOptions.ANDROID
import com.march7th.hkrpg.core.CoreInstanceOptions.DEPLOYMENT_WITH_PAUSE
import com.march7th.hkrpg.core.CoreInstanceOptions.TOUCH_MODE
import com.march7th.hkrpg.core.callback.CoreCallbackDispatcher
import com.march7th.hkrpg.core.callback.CoreStateHolder
import com.march7th.hkrpg.core.callback.SubTaskHandler
import com.march7th.hkrpg.core.callback.TaskChainStatusTracker
import com.march7th.hkrpg.core.callback.ToolboxResultCollector
import com.march7th.hkrpg.remote.CoreManager
import com.march7th.hkrpg.domain.models.TaskFallbackChain
import com.march7th.hkrpg.domain.models.appendFirstSuccessful
import com.march7th.hkrpg.core.task.CoreTaskParams
import com.march7th.hkrpg.core.task.TaskSlot
import com.march7th.hkrpg.manager.RemoteAccessCoordinator
import com.march7th.hkrpg.manager.RemoteServiceManager
import com.march7th.hkrpg.manager.RemoteServiceManager.useRemoteService
import com.march7th.hkrpg.manager.ShizukuManager
import com.march7th.hkrpg.remote.PermissionGrantRequest
import com.march7th.hkrpg.utils.EyeProtectionDetector
import com.march7th.hkrpg.utils.Misc
import com.march7th.hkrpg.utils.i18n.UiText
import com.march7th.hkrpg.utils.i18n.resolve
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.koin.java.KoinJavaComponent.inject
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class TaskCompositionService(
    private val context: Context,
    private val resourceLoader: CoreResourceLoader,
    private val appSettings: AppSettingsManager,
    private val gameMuteCoordinator: GameMuteCoordinator,
    private val unifiedStateDispatcher: UnifiedStateDispatcher,
    private val sessionLogger: CoreSessionLogger,
    private val activityManager: ActivityManager,
    private val appWatchdog: AppWatchdog,
    private val gameFpsWatcher: GameFpsWatcher,
    private val taskChainState: TaskChainState,
    private val subTaskHandler: SubTaskHandler,
    private val taskChainStatusTracker: TaskChainStatusTracker,
    private val notificationCenter: TaskNotificationCenter,
    private val liveCoordinator: LiveSessionCoordinator,
    private val dropsRefresher: FightDropsRefresher,
    private val toolboxResultCollector: ToolboxResultCollector,
    private val coreDataPusher: CoreDataPusher,
) : CoreStateHolder {

    private val _state = MutableStateFlow(TaskExecutionState.IDLE)
    val state: StateFlow<TaskExecutionState> = _state.asStateFlow()

    /** 停止发起方：用户操作 / 回调侧（掉线等）中止 / 到达运行时长上限 */
    enum class StopOrigin { USER, CALLBACK, RUN_DURATION_LIMIT }

    private val runDeadlineGuard = RunDeadlineGuard()

    /** 本轮运行时长上限的截止时间（elapsedRealtime），未计时为 null */
    val runDeadline: StateFlow<Long?> = runDeadlineGuard.deadline

    /** 本轮 STOPPING 的发起方，STARTING 时复位；供 TaskEndRegistry 区分手动停止、异常中止与到达时长上限 */
    @Volatile
    var lastStopOrigin: StopOrigin = StopOrigin.USER
        private set

    private val defaultResolution = DefaultDisplayConfig.Resolution(
        DefaultDisplayConfig.WIDTH, DefaultDisplayConfig.HEIGHT, DefaultDisplayConfig.DPI
    )
    private val _displayResolution = MutableStateFlow(defaultResolution)
    val displayResolution: StateFlow<DefaultDisplayConfig.Resolution> =
        _displayResolution.asStateFlow()

    override fun currentRunState(): TaskExecutionState = _state.value

    override fun reportRunState(state: TaskExecutionState) {
        // STOPPING 期间，回调不主动设 IDLE — 由 finishStop() 统一处理
        if (_state.value == TaskExecutionState.STOPPING && state == TaskExecutionState.IDLE) {
            return
        }
        setRunState(state)
    }

    override fun requestStopFromCallback() {
        val current = _state.value
        if (current != TaskExecutionState.RUNNING && current != TaskExecutionState.STARTING) {
            Timber.d("忽略回调侧停止请求：当前状态 $current")
            return
        }
        // 掉线弹窗会连着触发 OfflineConfirm 与 OfflineConfirmAfterBattle，去重后只停一次
        if (!callbackStopRequested.compareAndSet(false, true)) {
            Timber.d("回调侧停止请求已在处理中")
            return
        }
        scope.launch {
            try {
                stop(StopOrigin.CALLBACK)
            } finally {
                callbackStopRequested.set(false)
            }
        }
    }

    private fun setRunState(state: TaskExecutionState) {
        // 仅在 STARTING 拉起前台服务；终态不做外部 stopService —
        // 快速失败时 stopService 可能抢在服务创建之前到达，系统会因
        // startForeground 契约未履行直接杀进程（RemoteServiceException）。
        // 服务自身观察状态流，startForeground 后对 IDLE/ERROR 自行 stopSelf
        if (state != TaskExecutionState.STARTING) {
            // 自然完成走回调直接置 IDLE，不经过 finishStop，统一在这里撤掉计时
            if (state == TaskExecutionState.IDLE || state == TaskExecutionState.ERROR) {
                runDeadlineGuard.disarm()
            }
            _state.value = state
            return
        }
        // specialUse 被系统拒绝时拉起 FGS 必崩，本轮降级为无进度通知运行
        val withFgs = !SpecialUseFgsGate.isDenied(context)
        lastStopOrigin = StopOrigin.USER
        val runToken = liveCoordinator.beginRun()
        // 顺带提前拿断网闸门：这里在 IO 线程，留给 FGS 的 startForeground 拿会占主线程
        // 不起 FGS 就没人撤进度通知，闸门不能拿
        if (withFgs) liveCoordinator.prepareProgress(runToken)
        _state.value = state
        if (withFgs) {
            TaskExecutionService.start(context)
        } else {
            SpecialUseFgsGate.appendDeniedLog(context, sessionLogger)
        }
    }

    private val callbackDispatcher: CoreCallbackDispatcher by inject(CoreCallbackDispatcher::class.java)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val connectDeferred = AtomicReference<CompletableDeferred<Boolean>?>()

    /** 回调侧停止请求的去重闸门 */
    private val callbackStopRequested = AtomicBoolean(false)

    sealed class StartResult {
        data class Success(val version: String) : StartResult()

        /** 资源加载失败（网络/IO/解压） */
        data class ResourceError(
            val exception: Throwable? = null
        ) : StartResult()

        /** MaaCore 实例初始化失败（创建实例、设置选项） */
        data class InitializationError(
            val phase: InitPhase,
        ) : StartResult() {
            enum class InitPhase {
                CREATE_INSTANCE,
                SET_TOUCH_MODE,
            }
        }

        /** 显示/连接层失败（虚拟屏幕、连接）；[shizukuAsRoot] 标记 Shizuku 后端却以 root 运行 */
        data class ConnectionError(
            val phase: ConnectPhase,
            val shizukuAsRoot: Boolean = false,
        ) : StartResult() {
            enum class ConnectPhase {
                DISPLAY_MODE,
                VIRTUAL_DISPLAY,
                MAA_CONNECT,
            }
        }

        /** MaaCore 运行时启动失败 */
        data object StartError : StartResult()

        /** 前台模式下检测到竖屏（高 > 宽），需要横屏才能运行 */
        data object PortraitOrientationError : StartResult()

        /** 前台模式下物理分辨率不是 16:9，MAA 识别要求 16:9 */
        data object InvalidAspectRatioError : StartResult()

        /** 远程服务正在连接中，任务无法立即启动 */
        data object ServiceConnecting : StartResult()

        /** 远程后端（Shizuku/Root）不可用或无法获取，任务拒绝启动 */
        data class RemoteAccessUnavailable(val backend: RemoteBackend) : StartResult()

        /** 已有任务在启动/运行/停止，拒绝重入 */
        data object AlreadyRunning : StartResult()
    }

    sealed class StopResult {
        data object Success : StopResult()
        data object Failed : StopResult()
    }


    init {
        scope.launch {
            unifiedStateDispatcher.serviceDiedEvent.collect {
                stopBackgroundMonitors()
                setRunState(TaskExecutionState.ERROR)
                sessionLogger.completeSessionAndWait(
                    "SERVICE_DIED",
                    context.getString(R.string.runlog_service_terminated),
                    LogLevel.ERROR
                )
                notificationCenter.notifyServiceDied()
            }
        }

        scope.launch {
            appWatchdog.appDiedEvent.collect { packageName ->
                Timber.w("App watchdog detected app died: %s", packageName)
                sessionLogger.appendAndWait(
                    context.getString(R.string.runlog_game_process_gone, packageName),
                    LogLevel.WARNING
                )
            }
        }

        scope.launch {
            appWatchdog.displayDriftEvent.collect { packageName ->
                Timber.w("App watchdog detected display drift: %s", packageName)
                sessionLogger.appendAndWait(
                    context.getString(R.string.runlog_game_left_virtual_display, packageName),
                    LogLevel.WARNING
                )
            }
        }
    }

    fun handleCallback(msg: Int, json: String?) {
        if (onAsyncConnectCallback(msg, json)) return
        callbackDispatcher.onEvent(msg, json)
    }

    val callback = object : M7aCoreCallback.Stub() {
        override fun onCallback(msg: Int, json: String?) = handleCallback(msg, json)
    }

    private fun onAsyncConnectCallback(msg: Int, json: String?): Boolean {
        if (msg != AsstMsg.AsyncCallInfo.value) return false
        val deferred = connectDeferred.get() ?: return true
        val obj = JSON.parseObject(json)
        val details = obj.getJSONObject("details")
        if (details != null) {
            val ret = details.getBooleanValue("ret", false)
            deferred.complete(ret)
        }
        return true
    }

    suspend fun start(
        tasks: List<CoreTaskParams>,
        clientType: String,
        preflightLogs: List<Pair<UiText, LogLevel>> = emptyList(),
        fallbacks: Map<TaskSlot, TaskFallbackChain> = emptyMap(),
        onSessionStarted: (suspend () -> Unit)? = null
    ): StartResult = executeStart(
        tasks = tasks,
        clientType = clientType,
        fallbacks = fallbacks,
        startMessage = context.getString(R.string.runlog_task_start, tasks.size),
        successMessage = context.getString(R.string.runlog_task_started),
        preflightLogs = preflightLogs,
        onSessionStarted = onSessionStarted,
        limitRunDuration = true,
    )

    // 抄作业 / 工具箱 / 小游戏不受运行时长上限约束
    suspend fun startCopilot(
        tasks: List<CoreTaskParams>,
        clientType: String = taskChainState.clientType
    ): StartResult = executeStart(
        tasks = tasks,
        clientType = clientType,
        startMessage = context.getString(R.string.runlog_copilot_start),
        successMessage = context.getString(R.string.runlog_copilot_started),
    )

    private suspend fun failStart(
        message: String, sessionStatus: String, result: StartResult
    ): StartResult {
        setRunState(TaskExecutionState.ERROR)
        sessionLogger.appendAndWait(message, LogLevel.ERROR)
        sessionLogger.endSessionAndWait(sessionStatus)
        notificationCenter.notifyStartFailed(message)
        return result
    }

    /** 服务尚未就绪，任务拒绝启动但不进入 ERROR 状态（服务本身没有故障） */
    private suspend fun rejectStart(
        message: String, sessionStatus: String, result: StartResult
    ): StartResult {
        setRunState(TaskExecutionState.IDLE)
        sessionLogger.appendAndWait(message, LogLevel.WARNING)
        sessionLogger.endSessionAndWait(sessionStatus)
        // 前置失败未进 STARTING；先 beginRun 刷新 token，否则 notifyStartFailed 会因旧 run 已 claim 结果而被吞
        liveCoordinator.beginRun()
        notificationCenter.notifyStartFailed(message)
        return result
    }

    /**
     * 启动前对齐资源档，必须早于 mute——换进程会让旧进程收尾时解除游戏静音
     * 持 [startMutex] 挡住并发启动，加锁顺序与 [executeStart] 一致
     * 失败交给 [checkPreconditions] 统一报错
     */
    suspend fun prepareResources(clientType: String) = startMutex.withLock {
        // 换进程会杀掉虚拟显示器
        when (_state.value) {
            TaskExecutionState.IDLE, TaskExecutionState.ERROR -> Unit
            else -> {
                Timber.i("Skip resource prepare while busy: %s", _state.value)
                return@withLock
            }
        }
        // 可能在主线程调用，解绑是同步 binder 调用
        withContext(Dispatchers.IO) {
            resourceLoader.ensureLoaded(clientType)
        }
        Unit
    }

    private suspend fun checkPreconditions(mode: RunMode, clientType: String): StartResult? {
        // 服务连接中时直接拒绝，避免与后台自动 load() 并发触发 LoadResource
        // 换进程重连是资源加载自己发起的，不算，交给 ensureLoaded 等它结束
        val serviceState = RemoteServiceManager.state.value
        val resourceState = resourceLoader.state.value
        val reconnectingForResource = resourceState is CoreResourceLoader.State.Loading ||
                resourceState is CoreResourceLoader.State.Reloading
        if (serviceState is RemoteServiceManager.ServiceState.Connecting && !reconnectingForResource) {
            return rejectStart(
                context.getString(R.string.runlog_service_connecting),
                "SERVICE_CONNECTING",
                StartResult.ServiceConnecting
            )
        }

        val access = RemoteAccessCoordinator.refresh()
        val backend = access.configuredBackend
        if (!access.isAvailable(backend)) {
            return rejectStart(
                context.getString(R.string.runlog_backend_unavailable, backend.display),
                "BACKEND_UNAVAILABLE",
                StartResult.RemoteAccessUnavailable(backend)
            )
        }

        activityManager.runIfDirty { resourceLoader.load(clientType) }
        val loaded = resourceLoader.ensureLoaded(clientType)
        if (loaded.isFailure) {
            return rejectStart(
                context.getString(R.string.runlog_resource_load_failed), "RESOURCE_ERROR",
                StartResult.ResourceError(loaded.exceptionOrNull())
            )
        }
        // 后台虚拟屏自带 16:9 且分辨率固定 1920x1080，
        // 所以不再需要"必须横屏 + 16:9"的前置校验 ——
        // 那条校验原本是给前台模式（镜像主屏）用的。
        return null
    }

    private suspend fun ensureMaaInstance(maa: M7aCoreService): StartResult? {
        if (maa.hasInstance()) return null
        if (!maa.CreateInstance(callback)) {
            return failStart(
                context.getString(R.string.runlog_create_instance_failed), "CREATE_INSTANCE_ERROR",
                StartResult.InitializationError(StartResult.InitializationError.InitPhase.CREATE_INSTANCE)
            )
        }
        if (!maa.SetInstanceOption(TOUCH_MODE, ANDROID)) {
            return failStart(
                context.getString(R.string.runlog_set_touch_mode_failed), "SET_TOUCH_MODE_ERROR",
                StartResult.InitializationError(StartResult.InitializationError.InitPhase.SET_TOUCH_MODE)
            )
        }
        return null
    }

    private suspend fun asyncConnect(maa: M7aCoreService, config: String): StartResult? {
        val deferred = CompletableDeferred<Boolean>()
        connectDeferred.set(deferred)
        maa.AsyncConnect("", "Android", config, false)
        val ret = withTimeoutOrNull(2000) { deferred.await() }
        connectDeferred.set(null)
        if (ret != true) {
            return failStart(
                context.getString(R.string.runlog_maa_connect_failed), "MAA_CONNECT_ERROR",
                StartResult.ConnectionError(StartResult.ConnectionError.ConnectPhase.MAA_CONNECT)
            )
        }
        return null
    }

    private suspend fun setupDisplayAndConnect(
        service: RemoteService, maa: M7aCoreService, mode: RunMode, clientType: String
    ): StartResult? {
        if (!service.setVirtualDisplayMode(mode.displayMode))
            return failStart(
                context.getString(R.string.runlog_display_mode_failed), "DISPLAY_MODE_ERROR",
                StartResult.ConnectionError(StartResult.ConnectionError.ConnectPhase.DISPLAY_MODE)
            )
        // 前台模式已删除，只剩虚拟屏路径。
        // 关键：这里喂给引擎的**永远是虚拟屏的固定尺寸**（1920x1080），
        // 而不是 Misc.getScreenSize() 那种各设备不同的物理屏尺寸 ——
        // 后者会让所有按 1920x1080 建的坐标/模板全部失配。
        val r = resolveAndSetResolution(service, clientType)
        val displayId = service.startVirtualDisplay()
        if (displayId == -1) return failVirtualDisplayStart()
        val config = buildConnectConfig(r.width, r.height, displayId)
        // 在 MAA 连接（含 force_stop 重启游戏）之前提前授予电池优化豁免与后台不受限权限，
        // 让新进程一启动就处于受保护状态
        grantGameBatteryExemption(clientType)
        // Assistant 实例跨任务复用，关闭时必须显式写 0
        maa.SetInstanceOption(
            DEPLOYMENT_WITH_PAUSE,
            if (appSettings.deployWithPause.value) "1" else "0"
        )
        return asyncConnect(maa, config)
    }

    /** 虚拟显示启动失败；若是 Root 授权的 Shizuku（uid 0）则附加改用内置 Root 模式的提示 */
    private suspend fun failVirtualDisplayStart(): StartResult {
        val shizukuAsRoot =
            RemoteServiceManager.connectedBackendOrNull() == RemoteBackend.SHIZUKU &&
                    ShizukuManager.isRunningAsRoot()
        val message = if (shizukuAsRoot) {
            context.getString(R.string.runlog_virtual_display_failed_shizuku_as_root)
        } else {
            context.getString(R.string.runlog_virtual_display_failed)
        }
        return failStart(
            message,
            "VIRTUAL_DISPLAY_ERROR",
            StartResult.ConnectionError(
                StartResult.ConnectionError.ConnectPhase.VIRTUAL_DISPLAY,
                shizukuAsRoot = shizukuAsRoot,
            )
        )
    }

    private fun grantGameBatteryExemption(clientType: String) {
        val pkg = Packages[clientType] ?: return
        runCatching {
            RemoteServiceManager.getInstanceOrNull()?.grantPermissions(
                PermissionGrantRequest(
                    packageName = pkg,
                    permissions = PermissionGrantRequest.PERM_BATTERY or PermissionGrantRequest.PERM_BACKGROUND
                )
            )
            Timber.d("Battery exemption granted for game: %s", pkg)
        }.onFailure { e ->
            Timber.w(e, "Failed to grant battery exemption for game")
        }
    }

    private fun logAppendFailed(name: String) {
        sessionLogger.append(
            context.getString(R.string.runlog_append_task_failed, name),
            LogLevel.ERROR,
        )
    }

    /** 主任务被 core 拒绝后按序试后备，首个成功即停；全失败则该任务位本轮无产出 */
    private fun appendFallbacks(
        maa: M7aCoreService,
        slot: TaskSlot,
        chain: TaskFallbackChain,
    ) {
        val hit = chain.appendFirstSuccessful(
            log = { text, level -> sessionLogger.append(text.resolve(context), level) },
            append = { c ->
                sessionLogger.appendToFileOnly("[TaskParams] ${c.type.value}: ${c.params}")
                maa.AppendTask(c.type.value, c.params)
            },
            onAppendFailed = { c -> logAppendFailed(c.logName.resolve(context)) },
        ) ?: return
        val (id, c) = hit
        dropsRefresher.stage(slot, c.dropTarget)
        taskChainStatusTracker.register(id, c.type.value, slot, c.logName)
        dropsRefresher.bind(slot, id)
    }

    private suspend fun appendTasksAndStart(
        maa: M7aCoreService,
        tasks: List<CoreTaskParams>,
        fallbacks: Map<TaskSlot, TaskFallbackChain>,
        successMessage: String,
        mode: RunMode,
        limitRunDuration: Boolean,
    ): StartResult {
        // 独立目录：先投递用户文件，送不过去 core 那边就是 file-not-found，直接报资源错误
        if (!coreDataPusher.pushUserData()) {
            Timber.e("core user data push failed before start")
            return StartResult.ResourceError(IllegalStateException("core user data push failed"))
        }
        taskChainStatusTracker.clear()
        // 不清 dropsRefresher：stage 已在 Analyze 完成，会话结束/下次 Analyze 再清
        tasks.forEach { t ->
            sessionLogger.appendToFileOnly("[TaskParams] ${t.type.value}: ${t.params}")
            val taskId = maa.AppendTask(t.type.value, t.params)
            if (taskId > 0) {
                taskChainStatusTracker.register(taskId, t.type.value, t.slot, t.logName)
                t.slot?.let { dropsRefresher.bind(it, taskId) }
                return@forEach
            }
            // core 拒绝参数才会到这里，此前是静默吞掉，用户看不到任何痕迹
            logAppendFailed(t.logName?.resolve(context) ?: t.type.value)
            val slot = t.slot ?: return@forEach
            fallbacks[slot]?.let { appendFallbacks(maa, slot, it) }
        }
        if (!maa.Start()) {
            return failStart(
                context.getString(R.string.runlog_maa_start_failed),
                "START_ERROR",
                StartResult.StartError
            )
        }
        // 先于 RUNNING 计时，秒完的回调置 IDLE 时才能撤掉
        if (limitRunDuration && appSettings.runDurationLimitEnabled.value) {
            val limitMinutes = appSettings.runDurationLimitMinutes.value
            runDeadlineGuard.arm(limitMinutes) { stopByRunDurationLimit(limitMinutes) }
        }
        setRunState(TaskExecutionState.RUNNING)
        if (mode == RunMode.BACKGROUND) {
            startBackgroundMonitors()
        }
        sessionLogger.appendAndWait(successMessage, LogLevel.SUCCESS)
        return StartResult.Success(maa.GetVersion())
    }

    private suspend fun stopByRunDurationLimit(limitMinutes: Int) {
        // 检查与切 STOPPING 须原子，被抢先则交给原流程
        if (!_state.compareAndSet(TaskExecutionState.RUNNING, TaskExecutionState.STOPPING)) return
        lastStopOrigin = StopOrigin.RUN_DURATION_LIMIT
        try {
            Timber.i("Run duration limit reached: %d min, stopping", limitMinutes)
            sessionLogger.appendAndWait(
                context.getString(R.string.runlog_run_duration_limit_reached, limitMinutes),
                LogLevel.WARNING,
            )
            performStop()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 计时协程没有异常处理器，漏出去会直接崩进程
            Timber.e(e, "Stop by run duration limit failed")
        }
    }

    /** 启动互斥：前置检查耗时，双入口/双击可能同时穿过 AlreadyRunning 窗口 */
    private val startMutex = Mutex()

    private suspend fun executeStart(
        tasks: List<CoreTaskParams>,
        clientType: String,
        startMessage: String,
        successMessage: String,
        preflightLogs: List<Pair<UiText, LogLevel>> = emptyList(),
        fallbacks: Map<TaskSlot, TaskFallbackChain> = emptyMap(),
        onSessionStarted: (suspend () -> Unit)? = null,
        limitRunDuration: Boolean = false,
    ): StartResult = startMutex.withLock {
        executeStartLocked(
            tasks, clientType, startMessage, successMessage, preflightLogs, fallbacks, onSessionStarted,
            limitRunDuration,
        )
    }

    private suspend fun executeStartLocked(
        tasks: List<CoreTaskParams>,
        clientType: String,
        startMessage: String,
        successMessage: String,
        preflightLogs: List<Pair<UiText, LogLevel>> = emptyList(),
        fallbacks: Map<TaskSlot, TaskFallbackChain> = emptyMap(),
        onSessionStarted: (suspend () -> Unit)? = null,
        limitRunDuration: Boolean,
    ): StartResult {
        // 会话与日志先开；STARTING/FGS 必须在前置检查通过后再进入。
        // 否则竖屏等快速失败会 stop 尚未 startForeground 的 FGS，触发
        // ForegroundServiceDidNotStartInTimeException。
        when (_state.value) {
            TaskExecutionState.STARTING,
            TaskExecutionState.RUNNING,
            TaskExecutionState.STOPPING -> return StartResult.AlreadyRunning

            TaskExecutionState.IDLE,
            TaskExecutionState.ERROR -> Unit
        }
        val mode = appSettings.runMode.value
        sessionLogger.startSession(tasks.map { it.type.value })
        subTaskHandler.resetSessionState()
        toolboxResultCollector.onSessionStart()
        onSessionStarted?.invoke()
        sessionLogger.appendAndWait(startMessage, LogLevel.INFO)
        preflightLogs.forEach { (text, level) ->
            sessionLogger.appendAndWait(text.resolve(context), level)
        }
        sessionLogger.appendAndWait(fetchDeviceMemoryInfo(), LogLevel.INFO)
        val eyeProtection = EyeProtectionDetector.detect(context)
        if (eyeProtection.isEnabled) {
            val eyeProtectionMsg = context.getString(
                R.string.task_start_eye_protection_warning_log,
                eyeProtection.source.orEmpty()
            )
            sessionLogger.appendAndWait(eyeProtectionMsg, LogLevel.WARNING)
            Timber.w("isEyeProtectionEnabled at runtime: true (source=%s)", eyeProtection.source)
        }

        return withContext(Dispatchers.IO) {
            checkPreconditions(mode, clientType)?.let { return@withContext it }

            setRunState(TaskExecutionState.STARTING)

            try {
                useRemoteService { service ->
                    val maa = service.getM7aCoreService()
                    ensureMaaInstance(maa)?.let { return@useRemoteService it }

                    // 告诉崩铁引擎本次要操作哪个游戏客户端
                    // （引擎侧用它做 force-stop / 启动 / 漂移检测）
                    Packages[clientType]?.let { pkg ->
                        runCatching { CoreManager.starRailEngine.setPackage(pkg) }
                            .onFailure { Timber.w(it, "设置引擎目标包名失败") }
                    }

                    setupDisplayAndConnect(
                        service,
                        maa,
                        mode,
                        clientType
                    )?.let { return@useRemoteService it }
                    val result = appendTasksAndStart(
                        maa, tasks, fallbacks, successMessage, mode, limitRunDuration,
                    )
                    if (result is StartResult.Success) {
                        taskChainState.saveLastUsedClientType(clientType)
                    }
                    result
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Failed to acquire remote service during start")
                rejectStart(
                    context.getString(R.string.runlog_remote_connect_failed, e.message ?: ""),
                    "REMOTE_ACCESS_UNAVAILABLE",
                    StartResult.RemoteAccessUnavailable(RemoteAccessCoordinator.configuredBackend())
                )
            }
        }
    }

    private fun resolveAndSetResolution(
        service: RemoteService,
        clientType: String
    ): DefaultDisplayConfig.Resolution {
        val preference = appSettings.backgroundResolution.value
        val r = DefaultDisplayConfig.resolveResolution(clientType, preference)
        service.setVirtualDisplayResolution(r.width, r.height, r.dpi)
        Timber.i(
            "Virtual display resolution: %dx%d@%d for client=%s, pref=%s",
            r.width,
            r.height,
            r.dpi,
            clientType,
            preference
        )
        _displayResolution.value = r
        return r
    }

    private fun buildConnectConfig(width: Int, height: Int, displayId: Int): String {
        return buildJsonObject {
            put("library_path", "libbridge.so")
            put("screen_resolution", buildJsonObject {
                put("width", width)
                put("height", height)
            })
            put("display_id", displayId)
            put("force_stop", true)
        }.toString()
    }


    private fun fetchDeviceMemoryInfo(): String {
        return try {
            val am = context.getSystemService(android.app.ActivityManager::class.java)
                ?: return context.getString(R.string.task_start_device_memory_unavailable)
            val mi = android.app.ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            val mb = 1024L * 1024
            val availMb = mi.availMem / mb
            val totalMb = mi.totalMem / mb
            val usedPercent = if (totalMb > 0) (totalMb - availMb) * 100 / totalMb else 0
            val base = context.getString(
                R.string.task_start_device_memory, availMb, totalMb, usedPercent
            )
            if (mi.lowMemory) base + context.getString(R.string.task_start_device_memory_low) else base
        } catch (e: Exception) {
            Timber.w(e, "读取设备内存信息失败")
            context.getString(R.string.task_start_device_memory_unavailable)
        }
    }

    suspend fun stop(origin: StopOrigin = StopOrigin.USER): StopResult {
        // 先记来源再切状态，TaskEndRegistry 在 STOPPING→IDLE 边沿读取
        lastStopOrigin = origin
        setRunState(TaskExecutionState.STOPPING)
        return performStop()
    }

    private suspend fun performStop(): StopResult {
        sessionLogger.appendAndWait(context.getString(R.string.runlog_task_stopping), LogLevel.INFO)

        return withContext(Dispatchers.IO) {
            useRemoteService { service ->
                val maa = service.getM7aCoreService()
                if (!maa.Running()) {
                    return@useRemoteService finishStop(StopResult.Success)
                }

                if (!maa.Stop()) {
                    return@useRemoteService finishStop(StopResult.Failed)
                }

                // 轮询等待 Core 真正停止，60 秒超时
                var elapsed = 0
                while (maa.Running() && elapsed < 60_000) {
                    delay(100)
                    elapsed += 100
                }

                if (maa.Running()) {
                    finishStop(StopResult.Failed)
                } else {
                    finishStop(StopResult.Success)
                }
            }
        }
    }

    // 后台模式随会话启停的监视器：游戏存活/漂移看门狗、帧率
    private fun startBackgroundMonitors() {
        appWatchdog.startWatching()
        gameFpsWatcher.start()
    }

    private fun stopBackgroundMonitors() {
        appWatchdog.stopWatching()
        gameFpsWatcher.stop()
    }

    private fun finishStop(result: StopResult): StopResult {
        stopBackgroundMonitors()
        setRunState(TaskExecutionState.IDLE)
        val status = if (result is StopResult.Success) "STOPPED" else "STOP_FAILED"
        sessionLogger.append(
            context.getString(R.string.runlog_task_stopped, status),
            if (result is StopResult.Success) LogLevel.INFO else LogLevel.ERROR
        )
        sessionLogger.endSession(status)
        notificationCenter.notifyTaskStopped()
        return result
    }

    suspend fun stopVirtualDisplay() {
        try {
            stopBackgroundMonitors()
            _displayResolution.value = defaultResolution
            withContext(Dispatchers.IO) {
                val service = RemoteServiceManager.getInstanceOrNull()
                    ?: return@withContext
                service.stopVirtualDisplay()
            }
        } finally {
            withContext(NonCancellable) {
                if (!gameMuteCoordinator.unmute()) {
                    Timber.w("Virtual display close did not restore managed game audio; retry pending")
                }
            }
        }
    }
}
