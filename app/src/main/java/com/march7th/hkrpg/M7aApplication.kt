package com.march7th.hkrpg

import android.app.Application
import com.march7th.hkrpg.data.config.CorePathConfig
import com.march7th.hkrpg.data.datasource.AppDownloader
import com.march7th.hkrpg.data.model.LogLevel
import com.march7th.hkrpg.data.preferences.AppSettingsManager
import com.march7th.hkrpg.data.repository.DepotRepository
import com.march7th.hkrpg.data.repository.OperBoxRepository
import com.march7th.hkrpg.domain.service.CoreSessionLogger
import com.march7th.hkrpg.domain.service.GameMuteCoordinator
import com.march7th.hkrpg.domain.service.TaskEndRegistry
import com.march7th.hkrpg.domain.service.UnifiedStateDispatcher
import com.march7th.hkrpg.koin.appModule
import com.march7th.hkrpg.koin.floatingWindowModule
import com.march7th.hkrpg.koin.useCaseModule
import com.march7th.hkrpg.koin.viewModelModule
import com.march7th.hkrpg.manager.RemoteServiceManager
import com.march7th.hkrpg.overlay.OverlayController
import com.march7th.hkrpg.schedule.data.ScheduleStrategyRepository
import com.march7th.hkrpg.schedule.service.ScheduleAlarmManager
import com.march7th.hkrpg.third.Ln
import com.march7th.hkrpg.utils.CrashHandler
import com.march7th.hkrpg.utils.EyeProtectionDetector
import com.march7th.hkrpg.utils.i18n.LocaleBootstrap
import com.march7th.hkrpg.utils.log.LogTreeHolder
import timber.log.Timber
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import org.koin.core.logger.Level
import java.util.concurrent.atomic.AtomicBoolean

class M7aApplication : Application() {

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val initialization = CompletableDeferred<Unit>()
    private val appSettingsManager: AppSettingsManager by inject()
    private val pathConfig: CorePathConfig by inject()
    private val crashHandler: CrashHandler by inject()
    private val coreSessionLogger: CoreSessionLogger by inject()
    private val unifiedStateDispatcher: UnifiedStateDispatcher by inject()
    private val taskEndRegistry: TaskEndRegistry by inject()
    private val gameMuteCoordinator: GameMuteCoordinator by inject()
    private val overlayController: OverlayController by inject()
    private val appDownloader: AppDownloader by inject()
    private val treeHolder: LogTreeHolder by inject()
    private val scheduleRepository: ScheduleStrategyRepository by inject()
    private val scheduleAlarmManager: ScheduleAlarmManager by inject()
    private val depotRepository: DepotRepository by inject()
    private val operBoxRepository: OperBoxRepository by inject()

    suspend fun awaitReady() = initialization.await()

    override fun onCreate() {
        super.onCreate()
        val app = this
        startKoin {
            androidLogger(if (BuildConfig.DEBUG) Level.DEBUG else Level.NONE)
            androidContext(app)
            modules(appModule, useCaseModule, viewModelModule, floatingWindowModule)
        }
        // 不等设置读盘，冷启动 receiver / FGS 的日志与崩溃才接得住
        treeHolder.setup()
        crashHandler.init(this)
        val eyeProtection = EyeProtectionDetector.detect(this)
        Timber.i("isEyeProtectionEnabled: %s (source=%s)", eyeProtection.isEnabled, eyeProtection.source)

        applicationScope.launch(Dispatchers.Main) {
            appSettingsManager.awaitLoaded()
            LocaleBootstrap.applyPersisted(appSettingsManager)
            postCreateApplication()
            initialization.complete(Unit)
        }.invokeOnCompletion { cause ->
            if (cause != null) initialization.completeExceptionally(cause)
        }
    }

    private fun postCreateApplication() {
        RemoteServiceManager.initialize(this, appSettingsManager, pathConfig)
        // 让引擎内部日志（Ln）也进 App 的「日志」面板
        installEngineLogBridge()
        overlayController.setup()
        unifiedStateDispatcher.start()
        taskEndRegistry.start()
        gameMuteCoordinator.startAutoRestore()
        depotRepository.start()
        operBoxRepository.start()
        cleanCachedUpdateApks()
        applicationScope.launch { crashHandler.cleanOldCrashLogs() }
        doSyncScheduleAlarms()
    }

    /**
     * 把引擎内部的 `Ln` 日志接进 App 的会话日志。
     *
     * ## 为什么需要
     *
     * `Ln` 原来只写 logcat + 进程 stdout，手机用户**都看不到**。
     * 于是 App 的「日志」面板里只有外壳的框架日志：
     *
     * ```
     * 开始任务:清体力
     * 任务出错:清体力            ← 为什么失败？完全不知道
     * ```
     *
     * 接上之后能直接看到引擎每一步：
     *
     * ```
     * PowerTask: 开拓力=44  后备开拓力=null
     * PowerTask: 点「+」按钮 @(1806,834) 共 5 次
     * PowerTask: 挑战次数已设为 6
     * ScreenNavigator: 命中 screen/main.png (0.918) -> (53,100)
     * ```
     *
     * ## 只转发任务运行期的日志
     *
     * 引擎日志量很大（每次取帧、每次 OCR 都有），全量写进会话日志会
     * 让文件暴涨。这里只在**有会话进行中**时转发 ——
     * [CoreSessionLogger] 没有 active session 时 append 本身是空操作，
     * 所以不需要额外判断。
     */
    private fun installEngineLogBridge() {
        // ## 引擎日志（Ln.*）→ UI 日志面板
        //
        // `Ln` 是引擎内部的日志门面（PowerTask / ScreenNavigator /
        // OcrEngine 全往这里打）。它原本只写 logcat，手机上看不到。
        //
        // ## ⚠️ 只在「调试模式」开启时才显示
        //
        // 引擎日志量很大（每次取帧、每次 OCR 都打），正常使用时会把
        // 框架日志冲掉，反而看不清任务进度。所以：
        //
        // - 调试模式**开** → 引擎日志进 UI（排查问题用）
        // - 调试模式**关** → 只写 logcat（默认，界面干净）
        //
        // 开关在「设置 → 调试模式」，改完立即生效，不用重启。
        val debug = AtomicBoolean(appSettingsManager.debugMode.value)

        Ln.setSink { level, message ->
            if (debug.get()) {
                val logLevel = when (level) {
                    Ln.Level.ERROR -> LogLevel.ERROR
                    Ln.Level.WARN -> LogLevel.WARNING
                    Ln.Level.INFO -> LogLevel.INFO
                    else -> LogLevel.TRACE
                }
                coreSessionLogger.append(message, logLevel)
            }
        }

        applicationScope.launch {
            appSettingsManager.debugMode.collect { on ->
                if (debug.getAndSet(on) != on) {
                    Timber.i("Engine log bridge: debug=%s", on)
                }
            }
        }
        Timber.d("Engine log bridge installed (debug-gated)")
    }

    private fun cleanCachedUpdateApks() {
        applicationScope.launch {
            appDownloader.cleanInstalledApks()
        }
    }

    // BootReceiver 依赖 ACTION_MY_PACKAGE_REPLACED / BOOT_COMPLETED 恢复闹钟，
    // 但国产 ROM 在自启动未开启时会拦截该广播，导致闹钟丢失后无法恢复。
    // 每次应用启动时执行一次幂等同步，作为兜底保障。
    private fun doSyncScheduleAlarms() {
        applicationScope.launch {
            scheduleRepository.isLoaded.filter { it }.first()
            scheduleAlarmManager.rescheduleAll(scheduleRepository.strategies.value)
        }
    }
}
