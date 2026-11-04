package com.march7th.hkrpg.presentation.viewmodel

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.march7th.hkrpg.BuildConfig
import com.march7th.hkrpg.R
import com.march7th.hkrpg.data.api.YituliuApiService
import com.march7th.hkrpg.data.api.message
import com.march7th.hkrpg.constant.DefaultDisplayConfig
import com.march7th.hkrpg.constant.OFFICIAL_SHIZUKU_PACKAGE
import com.march7th.hkrpg.data.model.update.UpdateChannel
import com.march7th.hkrpg.data.config.CorePathConfig
import com.march7th.hkrpg.data.preferences.AppSettingsManager
import com.march7th.hkrpg.data.preferences.ConfigBackupManager
import com.march7th.hkrpg.data.preferences.TaskChainState
import com.march7th.hkrpg.data.preferences.UnlockGestureStore
import com.march7th.hkrpg.data.resource.BackgroundImageStore
import com.march7th.hkrpg.data.resource.CustomTaskPackStore
import com.march7th.hkrpg.data.model.InstalledTaskPack
import com.march7th.hkrpg.data.resource.RemoteDownloadResult
import com.march7th.hkrpg.data.resource.RemoteIndexResult
import com.march7th.hkrpg.data.resource.RemoteTaskPack
import com.march7th.hkrpg.data.resource.RemoteTaskPackService
import com.march7th.hkrpg.data.resource.TaskPackInstallResult
import com.march7th.hkrpg.data.resource.ResourceDataManager
import com.march7th.hkrpg.domain.models.GestureRecordResult
import com.march7th.hkrpg.domain.models.GestureRecordStatus
import com.march7th.hkrpg.domain.models.CoreDataLocation
import com.march7th.hkrpg.domain.models.RemoteBackend
import com.march7th.hkrpg.domain.models.UnlockCredential
import com.march7th.hkrpg.domain.models.UnlockGesture
import com.march7th.hkrpg.domain.service.CoreDataPusher
import com.march7th.hkrpg.domain.service.TaskCompositionService
import com.march7th.hkrpg.domain.service.CoreResourceLoader
import com.march7th.hkrpg.domain.service.WakeUnlockEngine
import com.march7th.hkrpg.domain.state.TaskExecutionState
import com.march7th.hkrpg.domain.usecase.SwitchCoreDataLocationUseCase
import com.march7th.hkrpg.manager.PermissionManager
import com.march7th.hkrpg.manager.RemoteServiceManager
import com.march7th.hkrpg.utils.Misc
import com.march7th.hkrpg.utils.i18n.LocaleBootstrap.resolveSelectedLanguage
import com.march7th.hkrpg.utils.i18n.LocaleBootstrap.toLocaleList
import com.march7th.hkrpg.utils.i18n.UiText
import com.march7th.hkrpg.utils.i18n.uiTextDynamic
import com.march7th.hkrpg.utils.i18n.uiTextOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import kotlin.time.Duration.Companion.milliseconds
import com.march7th.hkrpg.domain.service.AchievementReporter

/** markdown 是 Mirror 酱下发的远端正文，没有资源可以支撑，不套 UiText */
data class ChangelogArchive(
    val version: String,
    val markdown: String,
)

class SettingsViewModel(
    private val app: Application,
    private val appSettingsManager: AppSettingsManager,
    private val permissionManager: PermissionManager,
    private val configBackupManager: ConfigBackupManager,
    private val taskChainState: TaskChainState,
    private val resourceDataManager: ResourceDataManager,
    private val resourceLoader: CoreResourceLoader,
    private val achievementReporter: AchievementReporter,
    private val backgroundImageStore: BackgroundImageStore,
    private val wakeUnlockEngine: WakeUnlockEngine,
    private val unlockGestureStore: UnlockGestureStore,
    private val coreDataPusher: CoreDataPusher,
    private val switchCoreDataLocation: SwitchCoreDataLocationUseCase,
    private val compositionService: TaskCompositionService,
    private val yituliuApiService: YituliuApiService,
    private val taskPackStore: CustomTaskPackStore,
    private val remoteTaskPackService: RemoteTaskPackService,
    private val corePathConfig: CorePathConfig,
) : ViewModel() {

    // ========== 导入导出 ==========

    /** 导入任务包时是否跳过免责声明 */
    val taskPackDisclaimerSkipped: StateFlow<Boolean> = appSettingsManager.taskPackDisclaimerSkipped

    fun setTaskPackDisclaimerSkipped(skipped: Boolean) {
        viewModelScope.launch { appSettingsManager.setTaskPackDisclaimerSkipped(skipped) }
    }


    private val _settingsMessage = MutableStateFlow<UiText?>(null)
    val settingsMessage: StateFlow<UiText?> = _settingsMessage.asStateFlow()

    private val _showRestartDialog = MutableStateFlow(false)
    val showRestartDialog: StateFlow<Boolean> = _showRestartDialog.asStateFlow()

    fun clearSettingsMessage() {
        _settingsMessage.value = null
    }

    fun dismissRestartDialog() {
        _showRestartDialog.value = false
    }

    fun confirmRestart() {
        _showRestartDialog.value = false
        Misc.restartApp(app)
    }

    fun exportConfig(outputStream: OutputStream) {
        viewModelScope.launch {
            try {
                configBackupManager.exportTo(outputStream)
                _settingsMessage.value = uiTextOf(R.string.settings_export_success)
            } catch (e: Exception) {
                Timber.e(e, "export config failed")
                _settingsMessage.value =
                    uiTextOf(R.string.settings_export_failed, e.message.orEmpty())
            }
        }
    }

    fun importConfig(inputStream: InputStream) {
        viewModelScope.launch {
            try {
                configBackupManager.importFrom(inputStream)
                _showRestartDialog.value = true
            } catch (e: Exception) {
                Timber.e(e, "import config failed")
                _settingsMessage.value =
                    uiTextOf(R.string.settings_import_failed, e.message.orEmpty())
            }
        }
    }

    // ========== 自定义任务包 ==========

    private val _taskPacks = MutableStateFlow<List<InstalledTaskPack>>(emptyList())
    val taskPacks: StateFlow<List<InstalledTaskPack>> = _taskPacks.asStateFlow()

    /**
     * 导入失败时的逐条错误。
     *
     * 用对话框整段展示而不是 Toast：校验一次会给出 5~10 条
     * 「第几步哪个字段错了」，Toast 根本读不完，用户只能反复试。
     */
    private val _taskPackErrors = MutableStateFlow<List<String>?>(null)
    val taskPackErrors: StateFlow<List<String>?> = _taskPackErrors.asStateFlow()

    fun dismissTaskPackErrors() {
        _taskPackErrors.value = null
    }

    fun refreshTaskPacks() {
        viewModelScope.launch {
            _taskPacks.value = withContext(Dispatchers.IO) {
                runCatching { taskPackStore.list() }.getOrDefault(emptyList())
            }
        }
    }

    fun importTaskPack(inputStream: InputStream) {
        viewModelScope.launch {
            val bytes = withContext(Dispatchers.IO) {
                runCatching { inputStream.use { it.readBytes() } }.getOrNull()
            }
            if (bytes == null) {
                _settingsMessage.value = uiTextOf(R.string.settings_taskpack_read_failed)
                return@launch
            }
            // 这里必须 catch Throwable，不能只依赖 install 内部的 try：
            // 校验器是 `object`，它的正则/常量在 `<clinit>` 里初始化，一旦写错
            // 抛出的是 ExceptionInInitializerError（Error 而非 Exception），
            // 会直接穿透到 UncaughtExceptionHandler 把整个 App 干掉。
            // 用户看到的应该是「导入失败 + 原因」，而不是闪退。
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    taskPackStore.install("taskpack", bytes, BuildConfig.VERSION_CODE)
                }.getOrElse { e ->
                    Timber.e(e, "import task pack crashed")
                    // 和校验器的报错风格保持一致：这个列表是直接当纯文本渲染的
                    TaskPackInstallResult.Invalid(
                        listOf(
                            "导入时 App 内部出错了：${e.javaClass.simpleName}: ${e.message.orEmpty()}\n" +
                                "这是 App 的 bug（不是任务包的问题），请把这段贴给开发者"
                        )
                    )
                }
            }
            when (result) {
                is TaskPackInstallResult.Success -> {
                    _taskPacks.value = withContext(Dispatchers.IO) {
                        runCatching { taskPackStore.list() }.getOrDefault(emptyList())
                    }
                    // 导入成功 → 顺手在任务链里放一个板块。
                    //
                    // 用户的心智是"导入一个包 = 任务列表里多一项"，不该还要自己
                    // 去「添加任务」里翻。addCustomNode 对同 packId 去重，所以
                    // 覆盖升级时不会叠出第二张卡，只是把节点还回来。
                    //
                    // 默认不勾选：跟其它任务一样，导入不等于要立刻跑。
                    withContext(Dispatchers.IO) {
                        runCatching {
                            taskChainState.addCustomNode(
                                result.pack.id,
                                result.pack.displayName,
                            )
                        }.onFailure { Timber.w(it, "add custom node after import failed") }
                    }
                    _settingsMessage.value = if (result.upgraded) {
                        uiTextOf(R.string.settings_taskpack_updated, result.pack.displayName)
                    } else {
                        uiTextOf(R.string.settings_taskpack_installed, result.pack.displayName)
                    }
                }

                is TaskPackInstallResult.Invalid -> {
                    _taskPackErrors.value = result.errors
                }
            }
        }
    }

    fun deleteTaskPack(pack: InstalledTaskPack) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { taskPackStore.delete(pack.id) }
            _taskPacks.value = withContext(Dispatchers.IO) {
                runCatching { taskPackStore.list() }.getOrDefault(emptyList())
            }
            _settingsMessage.value = uiTextOf(R.string.settings_taskpack_deleted, pack.displayName)
        }
    }

    // ========== 在线任务包（站点索引） ==========

    /** 站点索引里的包目录。索引拉不下来时保持空列表 + [remoteError]。 */
    private val _remotePacks = MutableStateFlow<List<RemoteTaskPack>>(emptyList())
    val remotePacks: StateFlow<List<RemoteTaskPack>> = _remotePacks.asStateFlow()

    private val _remoteLoading = MutableStateFlow(false)
    val remoteLoading: StateFlow<Boolean> = _remoteLoading.asStateFlow()

    /**
     * 索引拉取失败原因（验签不过 / 站点没部署 / 断网）。
     *
     * 用原文而不是编好的提示 —— 这些原因差别很大（"签名校验失败" 和
     * "站点上没这个文件"要做的处理完全不同），抹平了就没法排查。
     */
    private val _remoteError = MutableStateFlow<String?>(null)
    val remoteError: StateFlow<String?> = _remoteError.asStateFlow()

    /** 正在下载安装的包 id，用来在列表里显示进度并防止重复点击 */
    private val _installingPackId = MutableStateFlow<String?>(null)
    val installingPackId: StateFlow<String?> = _installingPackId.asStateFlow()

    fun clearRemoteError() {
        _remoteError.value = null
    }

    fun refreshRemotePacks() {
        if (_remoteLoading.value) return
        viewModelScope.launch {
            _remoteLoading.value = true
            _remoteError.value = null
            when (val res = remoteTaskPackService.fetchIndex()) {
                is RemoteIndexResult.Success -> {
                    _remotePacks.value = res.packs
                    if (res.fromFallback) {
                        Timber.w("任务包索引来自备用源（Cloudflare 站点可能不可用）")
                    }
                }

                is RemoteIndexResult.Failure -> {
                    _remotePacks.value = emptyList()
                    _remoteError.value = res.reason
                }
            }
            _remoteLoading.value = false
        }
    }

    /**
     * 从站点下载并安装一个任务包。
     *
     * 下载与 sha256 校验在 [RemoteTaskPackService] 里做，落盘沿用
     * [CustomTaskPackStore.install] —— 所以远程包和本地导入的包走**完全同一套**
     * 结构校验，远程身份不会降低标准。
     */
    fun installRemotePack(pack: RemoteTaskPack) {
        if (_installingPackId.value != null) return
        viewModelScope.launch {
            _installingPackId.value = pack.id
            try {
                when (val dl = remoteTaskPackService.downloadPack(pack, BuildConfig.VERSION_CODE)) {
                    is RemoteDownloadResult.Failure -> {
                        _settingsMessage.value =
                            uiTextDynamic("${pack.displayName}：${dl.reason}")
                    }

                    is RemoteDownloadResult.Success -> {
                        val result = withContext(Dispatchers.IO) {
                            taskPackStore.install(
                                sourceName = "${pack.id}.m7atask",
                                bytes = dl.bytes,
                                appVersionCode = BuildConfig.VERSION_CODE,
                            )
                        }
                        when (result) {
                            is TaskPackInstallResult.Success -> {
                                _taskPacks.value = withContext(Dispatchers.IO) {
                                    runCatching { taskPackStore.list() }.getOrDefault(emptyList())
                                }
                                _settingsMessage.value = if (result.upgraded) {
                                    uiTextOf(
                                        R.string.settings_taskpack_updated,
                                        result.pack.displayName,
                                    )
                                } else {
                                    uiTextOf(
                                        R.string.settings_taskpack_installed,
                                        result.pack.displayName,
                                    )
                                }
                            }

                            is TaskPackInstallResult.Invalid -> {
                                _taskPackErrors.value = result.errors
                            }
                        }
                    }
                }
            } finally {
                _installingPackId.value = null
            }
        }
    }

    // ========== 现有设置 ==========

    val debugMode: StateFlow<Boolean> = appSettingsManager.debugMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun setDebugMode(enabled: Boolean) {
        viewModelScope.launch {
            appSettingsManager.setDebugMode(enabled)
            achievementReporter.reportDebugModeChanged(enabled)
            val state = RemoteServiceManager.state.value
            if (state is RemoteServiceManager.ServiceState.Connected) {
                RemoteServiceManager.unbind()
            }
            if (enabled) {
                Misc.restartApp(app)
            }
        }
    }

    // ───────────────── 坐标取点 ─────────────────

    /** 取点对话框里显示的截图；null 表示对话框关着。 */
    private val _pickFrame = MutableStateFlow<Bitmap?>(null)
    val pickFrame: StateFlow<Bitmap?> = _pickFrame.asStateFlow()

    /**
     * 截一张当前游戏画面，交给「取坐标」对话框。
     *
     * ## 为什么用截图取点，而不是读用户的真实点击
     *
     * 游戏跑在**虚拟屏**（1920×1080）上，任务包里的 tap 用的就是这套坐标；
     * 而用户手指点的永远是**物理屏**（这台机器是 1264×2780）。两者宽高比
     * 不同（16:9 vs 20:9），游戏在两块屏上会把 UI 摆在不同的相对位置 ——
     * 归一化之后也对不上，照抄进任务包一定点偏。
     *
     * 截图是虚拟屏的原始帧（1920×1080），用户在这张图上点哪里，换算出来
     * 就直接是任务包能用的坐标，中间不需要任何跨屏映射。
     */
    fun captureForCoordinatePick() {
        viewModelScope.launch {
            val bitmap = withContext(Dispatchers.IO) { captureEngineFrame() }
            if (bitmap == null) {
                Toast.makeText(app, R.string.settings_coord_pick_failed, Toast.LENGTH_SHORT).show()
            } else {
                _pickFrame.value = bitmap
            }
        }
    }

    fun closeCoordinatePick() {
        _pickFrame.value = null
    }

    /** 让提权进程抓一帧再读回来。失败返回 null。 */
    private suspend fun captureEngineFrame(): Bitmap? = runCatching {
        val saved = RemoteServiceManager.useRemoteService(timeoutMs = 8_000L) { svc ->
            svc.captureFramePng(corePathConfig.coreDebugScreenshotsDir)
        } ?: return@runCatching null

        // ## 直接读文件，不要走 openCoreDebugFile
        //
        // 截图落在 App 自己的 external files 下
        // （`.../files/Maa/debug/screenshots/`），App 本来就能读。
        //
        // 而 `openCoreDebugFile` 查的是**提权进程的 core 目录**
        // （`/data/local/tmp/maameow/debug`）—— 两个地方！11-03 实测就是
        // 卡在这：截图明明存下来了，读取却永远返回 null，一路报"截图失败"。
        val file = File(saved)
        BitmapFactory.decodeFile(saved)?.let { bitmap ->
            // 取点用的临时截图，读出来就没用了，别在手机上越堆越多
            runCatching { file.delete() }
            return@runCatching bitmap
        }

        // core 真的分离、截图落在 /data/local/tmp 时，App 读不到，只能走 PFD
        val pfd = RemoteServiceManager.getInstanceOrNull()
            ?.openCoreDebugFile("screenshots/${file.name}")
            ?: return@runCatching null

        ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input ->
            BitmapFactory.decodeStream(input)
        }
    }.onFailure { Timber.w(it, "captureForCoordinatePick failed") }.getOrNull()

    val autoCheckUpdate: StateFlow<Boolean> = appSettingsManager.autoCheckUpdate
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            !BuildConfig.DEBUG
        )

    fun setAutoCheckUpdate(enabled: Boolean) {
        viewModelScope.launch {
            appSettingsManager.setAutoCheckUpdate(enabled)
        }
    }

    val autoDownloadUpdate: StateFlow<Boolean> = appSettingsManager.autoDownloadUpdate
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun setAutoDownloadUpdate(enabled: Boolean) {
        viewModelScope.launch {
            appSettingsManager.setAutoDownloadUpdate(enabled)
        }
    }

    val startupBackend: StateFlow<RemoteBackend> = appSettingsManager.startupBackend
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), RemoteBackend.SHIZUKU)

    fun setStartupBackend(backend: RemoteBackend) {
        viewModelScope.launch {
            permissionManager.setStartupBackend(backend)
        }
    }

    // ========== MaaCore 数据目录 ==========

    val coreDataLocation: StateFlow<CoreDataLocation> = appSettingsManager.coreDataLocation
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), appSettingsManager.coreDataLocation.value)

    private val _pendingCoreDataLocation = MutableStateFlow<CoreDataLocation?>(null)
    val pendingCoreDataLocation: StateFlow<CoreDataLocation?> = _pendingCoreDataLocation.asStateFlow()

    private val _showClearCoreDataDialog = MutableStateFlow(false)
    val showClearCoreDataDialog: StateFlow<Boolean> = _showClearCoreDataDialog.asStateFlow()

    fun requestCoreDataLocationChange(target: CoreDataLocation) {
        if (target == appSettingsManager.coreDataLocation.value) return
        _pendingCoreDataLocation.value = target
    }

    fun dismissCoreDataLocationChange() {
        _pendingCoreDataLocation.value = null
    }

    fun confirmCoreDataLocationChange() {
        val target = _pendingCoreDataLocation.value ?: return
        _pendingCoreDataLocation.value = null
        viewModelScope.launch { switchCoreDataLocation(target) }
    }

    private fun coreBusy(): Boolean {
        if (compositionService.state.value == TaskExecutionState.IDLE) return false
        _settingsMessage.value = uiTextOf(R.string.settings_core_data_clear_busy)
        return true
    }

    fun requestClearCoreData() {
        if (coreBusy()) return
        _showClearCoreDataDialog.value = true
    }

    fun dismissClearCoreData() {
        _showClearCoreDataDialog.value = false
    }

    /** 清完断开提权进程，避免 core 继续引用已删的文件；下次连接重解 */
    fun confirmClearCoreData() {
        _showClearCoreDataDialog.value = false
        // 对话框开着期间定时任务可能已启动
        if (coreBusy()) return
        viewModelScope.launch {
            val ok = coreDataPusher.clearRemote()
            if (ok) RemoteServiceManager.unbind()
            _settingsMessage.value = uiTextOf(
                if (ok) R.string.settings_core_data_clear_done else R.string.settings_core_data_clear_failed
            )
        }
    }

    val skipShizukuCheck: StateFlow<Boolean> = appSettingsManager.skipShizukuCheck
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun setSkipShizukuCheck(enabled: Boolean) {
        viewModelScope.launch {
            appSettingsManager.setSkipShizukuCheck(enabled)
        }
    }

    val shizukuLaunchPackage: StateFlow<String> = appSettingsManager.shizukuLaunchPackage
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), OFFICIAL_SHIZUKU_PACKAGE)

    val shizukuShortcutEnabled: StateFlow<Boolean> = appSettingsManager.shizukuShortcutEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun setShizukuShortcutEnabled(enabled: Boolean) {
        viewModelScope.launch {
            appSettingsManager.setShizukuShortcutEnabled(enabled)
        }
    }

    fun setShizukuLaunchPackage(packageName: String) {
        viewModelScope.launch {
            appSettingsManager.setShizukuLaunchPackage(packageName)
        }
    }

    val deployWithPause: StateFlow<Boolean> = appSettingsManager.deployWithPause
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun setDeployWithPause(enabled: Boolean) {
        viewModelScope.launch {
            appSettingsManager.setDeployWithPause(enabled)
        }
    }

    val reportToPenguin: StateFlow<Boolean> = appSettingsManager.reportToPenguin
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    fun setReportToPenguin(enabled: Boolean) {
        viewModelScope.launch { appSettingsManager.setReportToPenguin(enabled) }
    }

    val reportToYituliu: StateFlow<Boolean> = appSettingsManager.reportToYituliu
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    fun setReportToYituliu(enabled: Boolean) {
        viewModelScope.launch { appSettingsManager.setReportToYituliu(enabled) }
    }

    val penguinId: StateFlow<String> = appSettingsManager.penguinId
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    fun setPenguinId(id: String) {
        viewModelScope.launch { appSettingsManager.setPenguinId(id) }
    }

    val yituliuOpenApiToken: StateFlow<String> = appSettingsManager.yituliuOpenApiToken
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    fun setYituliuOpenApiToken(token: String) {
        _yituliuVerifyMessage.value = null
        viewModelScope.launch { appSettingsManager.setYituliuOpenApiToken(token) }
    }

    val operBoxUseYituliuApi: StateFlow<Boolean> = appSettingsManager.operBoxUseYituliuApi
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun setOperBoxUseYituliuApi(enabled: Boolean) {
        viewModelScope.launch { appSettingsManager.setOperBoxUseYituliuApi(enabled) }
    }

    /** [ok] 决定提示用什么颜色 */
    data class TokenVerifyMessage(val text: UiText, val ok: Boolean)

    private val _yituliuVerifyMessage = MutableStateFlow<TokenVerifyMessage?>(null)
    val yituliuVerifyMessage: StateFlow<TokenVerifyMessage?> = _yituliuVerifyMessage.asStateFlow()

    private val _yituliuVerifying = MutableStateFlow(false)
    val yituliuVerifying: StateFlow<Boolean> = _yituliuVerifying.asStateFlow()

    /** 只报结果，不自己打开干员识别开关 */
    fun verifyYituliuToken() {
        val token = appSettingsManager.yituliuOpenApiToken.value.trim()
        if (token.isEmpty()) {
            _yituliuVerifyMessage.value = TokenVerifyMessage(
                uiTextOf(R.string.settings_yituliu_token_empty),
                ok = false,
            )
            return
        }
        if (_yituliuVerifying.value) return
        viewModelScope.launch {
            _yituliuVerifying.value = true
            val result = yituliuApiService.fetchOperators(token)
            // 等待期间 Token 被改过就丢掉这次结果
            if (token != appSettingsManager.yituliuOpenApiToken.value.trim()) {
                _yituliuVerifying.value = false
                return@launch
            }
            _yituliuVerifyMessage.value = when (result) {
                is YituliuApiService.Result.Success -> TokenVerifyMessage(
                    text = if (result.operators.isEmpty()) {
                        uiTextOf(R.string.settings_yituliu_token_valid_no_data)
                    } else {
                        uiTextOf(R.string.settings_yituliu_token_valid, result.operators.size)
                    },
                    ok = true,
                )

                is YituliuApiService.Result.Failure -> TokenVerifyMessage(result.message(), ok = false)
            }
            _yituliuVerifying.value = false
        }
    }

    val forceFullscreenOnVirtualDisplay: StateFlow<Boolean> =
        appSettingsManager.forceFullscreenOnVirtualDisplay
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun setForceFullscreenOnVirtualDisplay(enabled: Boolean) {
        viewModelScope.launch {
            appSettingsManager.setForceFullscreenOnVirtualDisplay(enabled)
        }
    }

    val pipOnHome: StateFlow<Boolean> = appSettingsManager.pipOnHome
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    fun setPipOnHome(enabled: Boolean) {
        viewModelScope.launch { appSettingsManager.setPipOnHome(enabled) }
    }

    // ───────────────── 定时唤醒解锁 ─────────────────

    val wakeUnlockType: StateFlow<String> =
        appSettingsManager.wakeUnlockType
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "swipe")

    fun setWakeUnlockType(type: String) {
        viewModelScope.launch { appSettingsManager.setWakeUnlockType(type) }
    }

    val wakeCredential: StateFlow<String> =
        appSettingsManager.wakeCredential
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    fun setWakeCredential(credential: String) {
        viewModelScope.launch { appSettingsManager.setWakeCredential(credential) }
    }

    /** null=未测试，Testing=进行中，Done=已出结果 */
    sealed interface WakeTestState {
        data object Testing : WakeTestState
        data class Done(val result: WakeUnlockEngine.WakeResult) : WakeTestState
    }

    private val _wakeTestState = MutableStateFlow<WakeTestState?>(null)
    val wakeTestState: StateFlow<WakeTestState?> = _wakeTestState.asStateFlow()

    fun runWakeTest() {
        if (_wakeTestState.value == WakeTestState.Testing) return
        viewModelScope.launch {
            _wakeTestState.value = WakeTestState.Testing
            val type = appSettingsManager.wakeUnlockType.value
            val credential = UnlockCredential.of(
                type = type,
                pin = appSettingsManager.wakeCredential.value,
                gestureJson = if (type == UnlockCredential.TYPE_GESTURE) {
                    unlockGestureStore.readJson()
                } else {
                    ""
                },
            )
            _wakeTestState.value = WakeTestState.Done(wakeUnlockEngine.testUnlock(credential))
        }
    }

    fun clearWakeTestResult() {
        _wakeTestState.value = null
    }

    // ───────────────── 解锁手势录制 ─────────────────

    val unlockGesture: StateFlow<UnlockGesture?> = unlockGestureStore.gesture

    /** null=空闲；录制期间 App 在锁屏后面，只能靠轮询回收结果 */
    sealed interface GestureRecordState {
        /** 已发起，等提权进程锁屏 */
        data object Preparing : GestureRecordState
        data object Recording : GestureRecordState
        data class Done(val steps: Int) : GestureRecordState
        data class Failed(val result: WakeUnlockEngine.WakeResult) : GestureRecordState
    }

    private val _gestureRecordState = MutableStateFlow<GestureRecordState?>(null)
    val gestureRecordState: StateFlow<GestureRecordState?> = _gestureRecordState.asStateFlow()

    private var recordJob: Job? = null

    fun startGestureRecord() {
        if (recordJob?.isActive == true) return
        recordJob = viewModelScope.launch {
            _gestureRecordState.value = GestureRecordState.Preparing
            if (!wakeUnlockEngine.startGestureRecord(RECORD_TIMEOUT_MS)) {
                _gestureRecordState.value =
                    GestureRecordState.Failed(WakeUnlockEngine.WakeResult.IPC_FAILED)
                return@launch
            }
            _gestureRecordState.value = GestureRecordState.Recording
            awaitRecordResult()
        }
    }

    /** 进设置页时补一次：VM 若在锁屏期间被重建，轮询协程会一起没掉 */
    fun refreshGestureRecord() {
        if (recordJob?.isActive == true) return
        recordJob = viewModelScope.launch {
            val result = wakeUnlockEngine.pollGestureRecord() ?: return@launch
            if (result.status.isTerminal) {
                consume(result)
            } else if (result.status == GestureRecordStatus.RECORDING) {
                _gestureRecordState.value = GestureRecordState.Recording
                awaitRecordResult()
            }
        }
    }

    fun cancelGestureRecord() {
        recordJob?.cancel()
        recordJob = viewModelScope.launch {
            wakeUnlockEngine.cancelGestureRecord()
            // 远端取消后不会留下终态，界面直接收掉，别让用户以为按钮没反应
            _gestureRecordState.value =
                GestureRecordState.Failed(WakeUnlockEngine.WakeResult.RECORD_CANCELLED)
        }
    }

    fun clearGestureRecordState() {
        _gestureRecordState.value = null
    }

    fun clearGesture() {
        viewModelScope.launch { unlockGestureStore.clear() }
    }

    private suspend fun awaitRecordResult() {
        val deadline = SystemClock.elapsedRealtime() + RECORD_TIMEOUT_MS + RECORD_GRACE_MS
        // 先轮询再判超时：锁屏期间进程可能被冻结，解冻后这一轮仍要能把结果取回来
        while (true) {
            delay(RECORD_POLL_INTERVAL_MS.milliseconds)
            val result = wakeUnlockEngine.pollGestureRecord()
            // IDLE：oneway 的 start 还没落地，或远端重启过，继续等而不是当成已结束
            if (result != null && result.status.isTerminal) {
                consume(result)
                return
            }
            if (SystemClock.elapsedRealtime() >= deadline) break
        }
        _gestureRecordState.value =
            GestureRecordState.Failed(WakeUnlockEngine.WakeResult.RECORD_TIMEOUT)
    }

    private suspend fun consume(result: GestureRecordResult) {
        val gesture = result.gesture
        _gestureRecordState.value = if (
            result.status == GestureRecordStatus.DONE && gesture != null
        ) {
            unlockGestureStore.save(gesture)
            GestureRecordState.Done(gesture.steps.size)
        } else {
            GestureRecordState.Failed(WakeUnlockEngine.WakeResult.fromCode(result.errorCode))
        }
    }

    val updateChannel: StateFlow<UpdateChannel> = appSettingsManager.updateChannel
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UpdateChannel.STABLE)

    fun setUpdateChannel(channel: UpdateChannel) {
        viewModelScope.launch {
            appSettingsManager.setUpdateChannel(channel)
        }
    }

    val themeMode: StateFlow<AppSettingsManager.ThemeMode> = appSettingsManager.themeMode
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            AppSettingsManager.ThemeMode.WHITE
        )

    fun setThemeMode(mode: AppSettingsManager.ThemeMode) {
        viewModelScope.launch {
            appSettingsManager.setThemeMode(mode)
        }
    }

    val backgroundResolution: StateFlow<DefaultDisplayConfig.ResolutionPreference> =
        appSettingsManager.backgroundResolution
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                DefaultDisplayConfig.ResolutionPreference.P720
            )

    fun setBackgroundResolution(pref: DefaultDisplayConfig.ResolutionPreference) {
        viewModelScope.launch {
            appSettingsManager.setBackgroundResolution(pref)
        }
    }

    val language: StateFlow<AppSettingsManager.AppLanguage> = appSettingsManager.language
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            AppSettingsManager.AppLanguage.SYSTEM
        )

    fun setLanguage(lang: AppSettingsManager.AppLanguage) {
        viewModelScope.launch {
            val resolved = resolveSelectedLanguage(lang)
            appSettingsManager.setLanguage(resolved)
            AppCompatDelegate.setApplicationLocales(resolved.toLocaleList())
            resourceDataManager.refreshDisplayLanguage(
                clientType = taskChainState.clientType,
                displayLanguage = ResourceDataManager.displayLanguageCode(resolved)
            )
        }
    }

    // Android 特化任务覆盖
    val tasksOverrideEnabled: StateFlow<Boolean> = appSettingsManager.tasksOverrideEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun setTasksOverrideEnabled(enabled: Boolean) {
        viewModelScope.launch {
            appSettingsManager.setTasksOverrideEnabled(enabled)
            // 进程还活着，用 invalidate 保留资源档信息
            resourceLoader.invalidate()
        }
    }

    // ============ System Monet theme color ============
    val useSystemMonetColor: StateFlow<Boolean> = appSettingsManager.useSystemMonetColor
    fun setUseSystemMonetColor(enabled: Boolean) {
        viewModelScope.launch {
            appSettingsManager.setUseSystemMonetColor(enabled)
        }
    }

    // ============ Font Size Scale ============
    val fontSizeScale: StateFlow<Int> = appSettingsManager.fontSizeScale
    fun setFontSizeScale(scale: Int) {
        viewModelScope.launch {
            appSettingsManager.setFontSizeScale(scale)
        }
    }

    // 成就 Snackbar 提示开关
    val showAchievementSnackbar: StateFlow<Boolean> = appSettingsManager.showAchievementSnackbar
    fun setShowAchievementSnackbar(enabled: Boolean) {
        viewModelScope.launch {
            appSettingsManager.setShowAchievementSnackbar(enabled)
        }
    }

    // ============ 自定义图片背景 ============
    val customBackgroundEnabled: StateFlow<Boolean> = appSettingsManager.customBackgroundEnabled
    val customBackgroundImageAlpha: StateFlow<Int> = appSettingsManager.customBackgroundImageAlpha
    val customBackgroundScrim: StateFlow<Int> = appSettingsManager.customBackgroundScrim
    val customBackgroundBlur: StateFlow<Int> = appSettingsManager.customBackgroundBlur
    val backgroundImage: StateFlow<ImageBitmap?> = backgroundImageStore.imageBitmap
    val customBackgroundMonet: StateFlow<Boolean> = appSettingsManager.customBackgroundMonet

    fun setCustomBackgroundEnabled(enabled: Boolean) {
        viewModelScope.launch {
            appSettingsManager.setCustomBackgroundEnabled(enabled)
        }
    }

    /** 把选中的图片复制到缓存目录，返回文件路径；失败返回 null。 */
    suspend fun prepareBackgroundSource(uri: Uri): String? =
        backgroundImageStore.prepareSource(uri)

    /** 按 EXIF 方向解码裁剪源图片；失败返回 null。 */
    suspend fun decodeBackgroundSource(path: String): Bitmap? =
        backgroundImageStore.decodeSource(path)

    /** 保存裁剪结果并启用背景；返回是否成功。 */
    suspend fun saveCroppedBackground(bitmap: Bitmap): Boolean =
        backgroundImageStore.saveCropped(bitmap)

    /** 取消裁剪或保存完成后清理源图片缓存。 */
    fun discardBackgroundSource() {
        backgroundImageStore.clearSourceCache()
    }

    fun removeBackgroundImage() {
        viewModelScope.launch {
            backgroundImageStore.clear()
        }
    }

    fun setCustomBackgroundImageAlpha(value: Int) {
        viewModelScope.launch {
            appSettingsManager.setCustomBackgroundImageAlpha(value)
        }
    }

    fun setCustomBackgroundScrim(value: Int) {
        viewModelScope.launch {
            appSettingsManager.setCustomBackgroundScrim(value)
        }
    }

    fun setCustomBackgroundBlur(value: Int) {
        viewModelScope.launch {
            appSettingsManager.setCustomBackgroundBlur(value)
        }
    }

    fun setCustomBackgroundMonet(enabled: Boolean) {
        viewModelScope.launch {
            appSettingsManager.setCustomBackgroundMonet(enabled)
        }
    }

    // ========== 更新日志 ==========

    private val _showChangelog = MutableStateFlow(false)
    val showChangelog: StateFlow<Boolean> = _showChangelog.asStateFlow()

    /** 当前版本留档的更新日志；版本号对不上（如旁路装了新版）一律视为没有 */
    val currentChangelog: StateFlow<ChangelogArchive?> = combine(
        appSettingsManager.currentChangelogVersion,
        appSettingsManager.currentChangelogContent,
    ) { version, content ->
        if (version == BuildConfig.VERSION_NAME && content.isNotBlank()) {
            ChangelogArchive(version = version, markdown = content)
        } else {
            null
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun onShowChangelog() {
        _showChangelog.value = true
    }

    fun onDismissChangelog() {
        _showChangelog.value = false
    }

    private companion object {
        /** 与提权进程的录制超时对齐，留一段宽限防止两边同时判超时 */
        const val RECORD_TIMEOUT_MS = 90_000
        const val RECORD_GRACE_MS = 15_000
        const val RECORD_POLL_INTERVAL_MS = 1_000L
    }
}
