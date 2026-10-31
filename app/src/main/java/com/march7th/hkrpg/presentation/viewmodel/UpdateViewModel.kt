package com.march7th.hkrpg.presentation.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alibaba.fastjson2.JSON
import com.march7th.hkrpg.BuildConfig
import com.march7th.hkrpg.R
import com.march7th.hkrpg.constant.M7aApi
import com.march7th.hkrpg.constant.M7aFiles.VERSION_FILE
import com.march7th.hkrpg.data.api.UpdateChecker
import com.march7th.hkrpg.data.config.CorePathConfig
import com.march7th.hkrpg.data.datasource.ResourceDownloader
import com.march7th.hkrpg.data.model.update.AppUpdatePrompt
import com.march7th.hkrpg.data.model.update.UpdateChannel
import com.march7th.hkrpg.data.model.update.UpdateCheckResult
import com.march7th.hkrpg.data.model.update.UpdateInfo
import com.march7th.hkrpg.data.model.update.UpdateProcessState
import com.march7th.hkrpg.data.model.update.UpdateSource
import com.march7th.hkrpg.data.preferences.AppSettingsManager
import com.march7th.hkrpg.domain.service.CoreResourceLoader
import com.march7th.hkrpg.domain.service.update.UpdateService
import com.march7th.hkrpg.utils.Misc
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okio.buffer
import okio.source
import timber.log.Timber
import java.io.File

@OptIn(FlowPreview::class)
class UpdateViewModel(
    private val appContext: Context,
    private val updateService: UpdateService,
    private val appSettingsManager: AppSettingsManager,
    private val maaResourceLoader: CoreResourceLoader,
    private val pathConfig: CorePathConfig,
    private val updateChecker: UpdateChecker,
) : ViewModel() {

    // ==================== 资源更新 ====================

    val resourceUpdateState = updateService.resourceProcessState

    private val _currentResourceVersion = MutableStateFlow("")
    val currentResourceVersion: StateFlow<String> = _currentResourceVersion.asStateFlow()

    val updateSource: StateFlow<UpdateSource> = appSettingsManager.updateSource

    val mirrorChyanCdk: StateFlow<String> = appSettingsManager.mirrorChyanCdk

    val updateChannel: StateFlow<UpdateChannel> = appSettingsManager.updateChannel

    private val _toastMessage = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val toastMessage: SharedFlow<String> = _toastMessage.asSharedFlow()

    // 检查状态
    private val _appChecking = MutableStateFlow(false)
    val appChecking: StateFlow<Boolean> = _appChecking.asStateFlow()

    private val _resourceChecking = MutableStateFlow(false)
    val resourceChecking: StateFlow<Boolean> = _resourceChecking.asStateFlow()

    // 检查结果（仅手动触发时写入）
    private val _appCheckResult = MutableStateFlow<UpdateCheckResult?>(null)
    val appCheckResult: StateFlow<UpdateCheckResult?> = _appCheckResult.asStateFlow()

    private val _resourceCheckResult = MutableStateFlow<UpdateCheckResult?>(null)
    val resourceCheckResult: StateFlow<UpdateCheckResult?> = _resourceCheckResult.asStateFlow()

    init {
        viewModelScope.launch {
            refreshResourceVersion()
        }
        mirrorChyanCdk
            .drop(1)
            .filter { it.isNotBlank() }
            .debounce(1000L)
            .onEach {
                updateService.checkAppUpdate(channel = updateChannel.value)
            }
            .launchIn(viewModelScope)
    }


    suspend fun refreshResourceVersion() {
        val raw = loadResourceVersion()
        _currentResourceVersion.value = ResourceDownloader.formatVersionForDisplay(raw)
    }

    private suspend fun loadResourceVersion(): String {
        val dir = pathConfig.resourceDir
        return runCatching {
            withContext(Dispatchers.IO) {
                File(dir, VERSION_FILE).takeIf { it.exists() }?.source()?.buffer()
                    ?.readUtf8()
                    ?.let { JSON.parseObject(it).getString("last_updated") }.orEmpty()
            }
        }.onFailure {
            Timber.w(it, "读取资源版本失败")
        }.getOrDefault("")

    }

    fun setUpdateSource(source: UpdateSource) {
        viewModelScope.launch {
            appSettingsManager.setUpdateSource(source)
        }
    }

    fun setMirrorChyanCdk(cdk: String) {
        viewModelScope.launch {
            appSettingsManager.setMirrorChyanCdk(cdk)
        }
    }

    fun checkResourceUpdate() {
        val currentState = resourceUpdateState.value
        if (_resourceChecking.value || currentState is UpdateProcessState.Downloading || currentState is UpdateProcessState.Extracting || currentState is UpdateProcessState.Installing) {
            return
        }
        viewModelScope.launch {
            _resourceChecking.value = true
            val currentVersion = loadResourceVersion()
            Timber.d("当前资源版本: $currentVersion, 下载源: ${updateSource.value}")
            _resourceCheckResult.value = updateService.checkResourceUpdate(currentVersion)
            _resourceChecking.value = false
        }
    }

    fun dismissResourceCheckResult() {
        _resourceCheckResult.value = null
    }

    fun confirmResourceDownload(source: UpdateSource = updateSource.value) {
        viewModelScope.launch {
            val file = File(pathConfig.resourceDir)

            val currentVersion = loadResourceVersion()
            val result = updateService.downloadResource(
                source = source,
                currentVersion = currentVersion,
                target = file
            )
            if (result.isSuccess) {
                refreshResourceVersion()
                maaResourceLoader.load()
            }
        }
    }


    // ==================== 启动检查 ====================

    private var hasCheckedOnStartup = false

    private val _appUpdatePrompt = MutableStateFlow<AppUpdatePrompt?>(null)

    /** 启动时的更新弹窗；`forced = true` 时不允许关闭。 */
    val appUpdatePrompt: StateFlow<AppUpdatePrompt?> = _appUpdatePrompt.asStateFlow()

    /**
     * 启动时检查更新，结果写进 [appUpdatePrompt]。
     *
     * # 数据源
     *
     * 用自有站点的更新清单（[UpdateChecker] 先读 `update.md`，失败回退 `update.json`），
     * **不是** MirrorChyan ——
     * 那条链路要 CDK，没订阅的用户永远收不到更新提示。清单里同时带
     * 更新要点、下载地址和强制标记，正好是弹窗需要的三样东西。
     *
     * # 强制更新（"死更新"）
     *
     * 两道闸：
     *
     * 1. **服务端**：`update.md` 里 `forceUpdate: true`，或者本机
     *    versionCode 低于 `minSupportedVersionCode`；
     * 2. **本地**：一旦见过强制标记就写进 [AppSettingsManager.forcedUpdateFloor]。
     *    之后每次启动**先看本地锁**，断网、关掉「自动检查更新」都拦得住。
     *
     * 升级到不低于下限后自动解锁，不会把用户永久挡在门外。
     */
    fun checkAppUpdateOnStartup() {
        if (hasCheckedOnStartup) return
        hasCheckedOnStartup = true

        viewModelScope.launch {
            val current = BuildConfig.VERSION_CODE
            val floor = appSettingsManager.forcedUpdateFloor.value
            val lockedLocally = floor > current

            // 已经升过，解锁 —— 否则升级完还会一直被拦
            if (floor != 0 && !lockedLocally) {
                appSettingsManager.clearForcedUpdateFloor()
            }

            val autoCheck = appSettingsManager.autoCheckUpdate.value
            // 本地锁优先：即便用户关了自动检查，也要去问一次
            if (!autoCheck && !lockedLocally) {
                Timber.d("启动更新检查已关闭，跳过")
                return@launch
            }

            val result = runCatching { updateChecker.check() }
                .getOrElse { UpdateChecker.Result.NONE }
            val manifest = result.manifest

            // 服务端要求强制 → 记住下限（只升不降，避免清单回滚时把锁抹掉）
            val forcedByServer = manifest != null &&
                (manifest.forceUpdate || current < manifest.minSupportedVersionCode)
            if (forcedByServer && manifest.versionCode > floor) {
                appSettingsManager.setForcedUpdateFloor(manifest.versionCode)
                Timber.w("收到强制更新要求，本地锁定 versionCode >= ${manifest.versionCode}")
            }

            val prompt = when {
                manifest != null -> AppUpdatePrompt(
                    versionName = manifest.versionName,
                    currentVersion = BuildConfig.VERSION_NAME,
                    changelog = manifest.changelog,
                    downloadUrl = manifest.apkUrl,
                    forced = forcedByServer,
                )

                // 在线清单拿不到，但本地锁还在 —— 不显示就等于放行，必须拦
                lockedLocally -> AppUpdatePrompt.forcedOffline(
                    currentVersion = BuildConfig.VERSION_NAME,
                    downloadUrl = M7aApi.APP_GITHUB_RELEASES_LATEST,
                )

                else -> null
            }

            if (prompt != null) {
                Timber.i(
                    "启动更新提示：${prompt.versionName.ifEmpty { "未知版本" }}" +
                        if (prompt.forced) " [强制，不可跳过]" else "",
                )
            }
            _appUpdatePrompt.value = prompt
        }
    }

    /**
     * 关闭更新弹窗。
     *
     * 强制更新时**直接忽略** —— 这是"死更新"的最后一环：按钮不给、
     * 点外面不关、返回键也不关，就算误触发也会被这里挡住。
     */
    fun dismissAppUpdatePrompt() {
        val prompt = _appUpdatePrompt.value ?: return
        if (prompt.forced) {
            Timber.w("强制更新不允许跳过，忽略关闭请求")
            return
        }
        _appUpdatePrompt.value = null
    }

    /** 打开下载页（浏览器）。 */
    fun openAppUpdateDownload() {
        val url = _appUpdatePrompt.value?.downloadUrl
            ?.takeIf { it.isNotBlank() }
            ?: M7aApi.APP_GITHUB_RELEASES_LATEST
        Misc.openUriSafely(appContext, url)
    }

    fun reset() {
        updateService.resetResourceProcess()
    }

    // ==================== App 更新 ====================

    val appUpdateState = updateService.appProcessState

    val currentAppVersion: String = BuildConfig.VERSION_NAME

    fun checkAppUpdate() {
        val currentState = appUpdateState.value
        if (_appChecking.value || currentState is UpdateProcessState.Downloading || currentState is UpdateProcessState.Installing) {
            return
        }
        viewModelScope.launch {
            _appChecking.value = true
            Timber.i("检查 App 更新 (MirrorChyan)")
            val result = updateService.checkAppUpdate(channel = updateChannel.value)
            saveAppChangelog((result as? UpdateCheckResult.Available)?.info)
            _appCheckResult.value = result
            _appChecking.value = false
        }
    }

    fun dismissAppCheckResult() {
        _appCheckResult.value = null
    }

    fun confirmAppDownload(version: String, source: UpdateSource = updateSource.value) {
        Timber.i("确认下载 App 更新: version=$version")
        lastAppDownloadVersion = version
        viewModelScope.launch {
            updateService.downloadApp(
                source = source,
                version = version,
                channel = updateChannel.value
            )
        }
    }

    fun resetAppUpdate() {
        updateService.resetAppProcess()
    }

    // ==================== 取消下载 / 下载中换源 ====================

    private var lastAppDownloadVersion: String? = null

    fun cancelAppDownload() {
        viewModelScope.launch {
            updateService.cancelAppDownload()
            _toastMessage.tryEmit(appContext.getString(R.string.update_toast_download_canceled))
        }
    }

    fun cancelResourceDownload() {
        viewModelScope.launch {
            updateService.cancelResourceDownload()
            _toastMessage.tryEmit(appContext.getString(R.string.update_toast_download_canceled))
        }
    }

    fun switchSourceAndRestartDownload(source: UpdateSource) {
        val appDownloading = appUpdateState.value is UpdateProcessState.Downloading
        val resourceDownloading = resourceUpdateState.value is UpdateProcessState.Downloading
        viewModelScope.launch {
            appSettingsManager.setUpdateSource(source)
            // 重下用传入的 source，不读 updateSource.value：DataStore 回灌是异步的
            when {
                appDownloading -> {
                    updateService.cancelAppDownload()
                    lastAppDownloadVersion?.let { confirmAppDownload(it, source) }
                }

                resourceDownloading -> {
                    updateService.cancelResourceDownload()
                    confirmResourceDownload(source)
                }
            }
        }
    }

    // ==================== 更新公告 ====================

    private val _changelogDialog = MutableStateFlow<String?>(null)
    val changelogDialog: StateFlow<String?> = _changelogDialog.asStateFlow()

    fun checkPendingChangelog() {
        val version = appSettingsManager.pendingChangelogVersion.value.removePrefix("v")
        val content = appSettingsManager.pendingChangelogContent.value
        val isNewVersion = version == BuildConfig.VERSION_NAME
        if (version.isNotEmpty() && content.isNotEmpty() && isNewVersion) {
            _changelogDialog.value = content
            // 确认已装上就立刻留档，用户直接划走弹窗也能在「关于」里回看
            viewModelScope.launch {
                appSettingsManager.promotePendingChangelog()
            }
        }
    }

    fun dismissChangelog() {
        _changelogDialog.value = null
    }

    private fun saveAppChangelog(appInfo: UpdateInfo?) {
        if (appInfo == null || appInfo.releaseNote.isNullOrBlank()) return
        viewModelScope.launch {
            appSettingsManager.savePendingChangelog(appInfo.version, appInfo.releaseNote)
        }
    }
}
