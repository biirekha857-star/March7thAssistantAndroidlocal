package com.march7th.hkrpg.koin

import android.app.KeyguardManager
import android.content.Context
import android.os.PowerManager
import com.march7th.hkrpg.data.api.CopilotApiService
import com.march7th.hkrpg.data.api.ETagCacheManager
import com.march7th.hkrpg.data.api.GameDataReportService
import com.march7th.hkrpg.data.api.AnnouncementService
import com.march7th.hkrpg.data.preferences.AnnouncementStateStore
import com.march7th.hkrpg.data.api.HttpClientHelper
import com.march7th.hkrpg.data.api.M7aApiService
import com.march7th.hkrpg.data.api.MirrorChyanApiClient
import com.march7th.hkrpg.data.api.UpdateChecker
import com.march7th.hkrpg.data.api.YituliuApiService
import com.march7th.hkrpg.data.config.CorePathConfig
import com.march7th.hkrpg.data.datasource.AppDownloader
import com.march7th.hkrpg.data.datasource.AssetExtractor
import com.march7th.hkrpg.data.datasource.ResourceDownloader
import com.march7th.hkrpg.data.datasource.ZipExtractor
import com.march7th.hkrpg.data.datasource.update.MirrorChyanAppVersionChecker
import com.march7th.hkrpg.data.datasource.update.MirrorChyanResourceVersionChecker
import com.march7th.hkrpg.data.log.ApplicationLogWriter
import com.march7th.hkrpg.data.notification.NotificationSettingsManager
import com.march7th.hkrpg.data.notification.live.AospLiveUpdateDetector
import com.march7th.hkrpg.data.notification.live.AospPromotedDetector
import com.march7th.hkrpg.data.notification.live.FocusSequenceStore
import com.march7th.hkrpg.data.notification.live.HyperOsFocusDetector
import com.march7th.hkrpg.data.notification.live.LiveNotificationFactory
import com.march7th.hkrpg.data.notification.live.LivePublisherRouter
import com.march7th.hkrpg.data.notification.live.XmsfNetworkGate
import com.march7th.hkrpg.data.notification.provider.BarkProvider
import com.march7th.hkrpg.data.notification.provider.CustomWebhookProvider
import com.march7th.hkrpg.data.notification.provider.DingTalkProvider
import com.march7th.hkrpg.data.notification.provider.DiscordProvider
import com.march7th.hkrpg.data.notification.provider.DiscordWebhookProvider
import com.march7th.hkrpg.data.notification.provider.GotifyProvider
import com.march7th.hkrpg.data.notification.provider.KookProvider
import com.march7th.hkrpg.data.notification.provider.NotificationProvider
import com.march7th.hkrpg.data.notification.provider.QmsgProvider
import com.march7th.hkrpg.data.notification.provider.ServerChanProvider
import com.march7th.hkrpg.data.notification.provider.SmtpProvider
import com.march7th.hkrpg.data.notification.provider.TelegramProvider
import com.march7th.hkrpg.data.preferences.AppSettingsManager
import com.march7th.hkrpg.data.preferences.ConfigBackupManager
import com.march7th.hkrpg.data.preferences.TaskChainState
import com.march7th.hkrpg.data.preferences.UnlockGestureStore
import com.march7th.hkrpg.data.repository.CopilotRepository
import com.march7th.hkrpg.data.repository.DepotRepository
import com.march7th.hkrpg.data.repository.OperBoxRepository
import com.march7th.hkrpg.data.resource.ActivityManager
import com.march7th.hkrpg.data.resource.BackgroundImageStore
import com.march7th.hkrpg.data.resource.CopilotResourceProvider
import com.march7th.hkrpg.data.resource.ItemHelper
import com.march7th.hkrpg.data.resource.ItemIconLoader
import com.march7th.hkrpg.data.resource.ResourceDataManager
import com.march7th.hkrpg.data.resource.StageApCostHelper
import com.march7th.hkrpg.domain.launch.CountdownUI
import com.march7th.hkrpg.domain.launch.LaunchMutex
import com.march7th.hkrpg.domain.launch.LaunchPipeline
import com.march7th.hkrpg.domain.launch.LaunchRequest
import com.march7th.hkrpg.domain.launch.PlanSideTaskRunner
import com.march7th.hkrpg.domain.launch.StartTaskChainUseCase
import com.march7th.hkrpg.domain.notification.LiveSessionCoordinator
import com.march7th.hkrpg.domain.notification.LiveUpdatePublisher
import com.march7th.hkrpg.domain.service.AppAliveChecker
import com.march7th.hkrpg.domain.service.AppWatchdog
import com.march7th.hkrpg.domain.service.CopilotManager
import com.march7th.hkrpg.domain.service.CoreDataPusher
import com.march7th.hkrpg.domain.service.ExternalNotificationService
import com.march7th.hkrpg.domain.service.FightDropsRefresher
import com.march7th.hkrpg.domain.service.GameDataReporter
import com.march7th.hkrpg.domain.service.GameFpsReader
import com.march7th.hkrpg.domain.service.GameFpsWatcher
import com.march7th.hkrpg.domain.service.GameMuteCoordinator
import com.march7th.hkrpg.domain.service.LogExportService
import com.march7th.hkrpg.domain.service.TaskCompositionService
import com.march7th.hkrpg.domain.service.CoreEventNotifier
import com.march7th.hkrpg.domain.service.TaskNotificationCenter
import com.march7th.hkrpg.domain.service.CoreResourceLoader
import com.march7th.hkrpg.domain.service.CoreSessionLogger
import com.march7th.hkrpg.domain.service.OperBoxYituliuSync
import com.march7th.hkrpg.domain.service.RemoteAppAliveChecker
import com.march7th.hkrpg.domain.service.RemoteGameFpsReader
import com.march7th.hkrpg.domain.service.ResourceInitService
import com.march7th.hkrpg.domain.service.ScreenSaverController
import com.march7th.hkrpg.domain.service.TaskEndRegistry
import com.march7th.hkrpg.domain.service.ToolboxExportService
import com.march7th.hkrpg.domain.service.UnifiedStateDispatcher
import com.march7th.hkrpg.domain.service.UnlockGestureReader
import com.march7th.hkrpg.domain.service.WakeUnlockEngine
import com.march7th.hkrpg.domain.service.update.UpdateService
import com.march7th.hkrpg.domain.service.update.checker.AppVersionChecker
import com.march7th.hkrpg.domain.service.update.checker.ResourceVersionChecker
import com.march7th.hkrpg.core.callback.ConnectionInfoHandler
import com.march7th.hkrpg.core.callback.CopilotRuntimeStateStore
import com.march7th.hkrpg.core.callback.CoreCallbackDispatcher
import com.march7th.hkrpg.core.callback.CoreStateHolder
import com.march7th.hkrpg.core.callback.SubTaskHandler
import com.march7th.hkrpg.core.callback.TaskChainHandler
import com.march7th.hkrpg.core.callback.TaskChainStatusTracker
import com.march7th.hkrpg.core.callback.ToolboxResultCollector
import com.march7th.hkrpg.manager.PermissionManager
import com.march7th.hkrpg.manager.RemoteGameAudioAdapter
import com.march7th.hkrpg.manager.RemoteServiceManager
import com.march7th.hkrpg.manager.ShizukuReadinessProvider
import com.march7th.hkrpg.overlay.OverlayController
import com.march7th.hkrpg.overlay.OverlayViewModelOwner
import com.march7th.hkrpg.overlay.border.BorderOverlayManager
import com.march7th.hkrpg.overlay.screensaver.ScreenSaverOverlayManager
import com.march7th.hkrpg.presentation.navigation.MainTabNavigator
import com.march7th.hkrpg.schedule.LaunchIntentMapper
import com.march7th.hkrpg.schedule.data.ScheduleStrategyRepository
import com.march7th.hkrpg.schedule.service.CountdownUIImpl
import com.march7th.hkrpg.schedule.service.ScheduleAlarmManager
import com.march7th.hkrpg.schedule.service.ScheduleFailureReporter
import com.march7th.hkrpg.schedule.service.ScheduleTriggerHandler
import com.march7th.hkrpg.schedule.service.ScheduleTriggerLogger
import com.march7th.hkrpg.utils.CrashHandler
import com.march7th.hkrpg.utils.log.LogTreeHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import org.koin.android.ext.koin.androidApplication
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.bind
import org.koin.core.module.dsl.singleOf
import org.koin.core.qualifier.named
import org.koin.dsl.bind
import org.koin.dsl.module
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds
import com.march7th.hkrpg.domain.service.AchievementReporter

val appModule = module {


    singleOf(::CrashHandler)
    single {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    singleOf(::HttpClientHelper)

    // ==================== 站点内容：公告 + 更新检查 ====================
    //
    // 两块内容都从自己的静态站（`M7aApi.SITE_BASE`，Cloudflare Pages）拉取：
    //
    // ```
    // https://m7a.pages.dev/announcement_zh.md
    // https://m7a.pages.dev/update.json
    // ```
    //
    // 站点尚未部署时两者都会失败，但**都做了静默降级**：
    //   · 公告 → 退回 APK 内置的 `assets/announcement/*.md`
    //   · 更新 → 直接不提示
    //
    // 所以部署与否都不影响 App 正常使用。
    singleOf(::AnnouncementService)
    singleOf(::AnnouncementStateStore)
    singleOf(::UpdateChecker)
    single<GameDataReporter> {
        GameDataReportService(
            appContext = androidContext(),
            httpClient = get(),
            appSettings = get(),
            taskChainState = get(),
            sessionLogger = get(),
        )
    }
    singleOf(::ETagCacheManager)
    singleOf(::M7aApiService)
    singleOf(::YituliuApiService)
    singleOf(::PermissionManager)
    singleOf(::ShizukuReadinessProvider)
    singleOf(::MainTabNavigator)


    single { AppSettingsManager(androidContext()) }
    single { UnlockGestureStore(get()) } bind UnlockGestureReader::class
    singleOf(::BackgroundImageStore)
    singleOf(::AchievementReporter)
    single { ScheduleStrategyRepository(androidContext()) }
    singleOf(::ScheduleTriggerLogger)
    singleOf(::ScheduleFailureReporter)
    singleOf(::ScheduleAlarmManager)
    single { ScheduleTriggerHandler(get(), get(), get(), get(), get()) }
    singleOf(::LaunchMutex)
    singleOf(::StartTaskChainUseCase)
    single(named("launchPipeline")) {
        CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
    single<CountdownUI> {
        CountdownUIImpl(
            overlayController = get(),
            onUserEvent = { event -> get<LaunchPipeline>().submit(event) },
        )
    }
    single {
        val appContext = get<Context>()
        LaunchPipeline(
            scope = get(named("launchPipeline")),
            mutex = get(),
            appSettingsManager = get(),
            wakeUnlockEngine = get(),
            unlockGestures = get(),
            chainState = get(),
            compositionService = get(),
            triggerLogger = get(),
            scheduleRepository = get(),
            startTaskChain = get(),
            countdownUI = get(),
            screenSaver = get(),
            taskEndRegistry = get(),
            keyguardLocked = {
                val km = appContext.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
                km.isKeyguardLocked
            },
            deviceLocked = {
                val km = appContext.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
                km.isDeviceLocked
            },
            screenInteractive = {
                val pm = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
                pm.isInteractive
            },
            activityLauncher = { request: LaunchRequest ->
                withTimeoutOrNull(10.seconds) {
                    runCatching {
                        RemoteServiceManager.useRemoteService(timeoutMs = 8_000L) {
                            it.startActivity(LaunchIntentMapper.toShowIntent(appContext, request))
                        }
                    }.getOrDefault(false)
                } ?: false
            },
        )
    }
    singleOf(::TaskChainState)
    singleOf(::ConfigBackupManager)
    singleOf(::CorePathConfig)
    singleOf(::CoreDataPusher)
    singleOf(::ResourceDownloader)
    singleOf(::AppDownloader)
    singleOf(::ZipExtractor)
    singleOf(::AssetExtractor)

    // MirrorChyan API Client
    singleOf(::MirrorChyanApiClient)

    // Version Checkers
    single<AppVersionChecker> { MirrorChyanAppVersionChecker(get(), get()) }
    single<ResourceVersionChecker> { MirrorChyanResourceVersionChecker(get()) }

    singleOf(::UpdateService)

    singleOf(::ResourceInitService)
    singleOf(::CoreResourceLoader)
    singleOf(::CoreSessionLogger)

    // 外部通知
    singleOf(::NotificationSettingsManager)
    single { ServerChanProvider(get(), get()) } bind NotificationProvider::class
    single { TelegramProvider(get(), get()) } bind NotificationProvider::class
    single { DiscordProvider(get(), get()) } bind NotificationProvider::class
    single { DingTalkProvider(get(), get()) } bind NotificationProvider::class
    single { KookProvider(get(), get()) } bind NotificationProvider::class
    single { DiscordWebhookProvider(get(), get()) } bind NotificationProvider::class
    single { SmtpProvider(get()) } bind NotificationProvider::class
    single { BarkProvider(get(), get()) } bind NotificationProvider::class
    single { QmsgProvider(get(), get()) } bind NotificationProvider::class
    single { GotifyProvider(get(), get()) } bind NotificationProvider::class
    single { CustomWebhookProvider(get(), get()) } bind NotificationProvider::class
    single { ExternalNotificationService(get(), get(), getAll()) }

    // 通知 / 实况
    singleOf(::LiveNotificationFactory)
    singleOf(::AospPromotedDetector)
    singleOf(::AospLiveUpdateDetector)
    singleOf(::HyperOsFocusDetector)
    singleOf(::FocusSequenceStore)
    singleOf(::XmsfNetworkGate)
    singleOf(::LivePublisherRouter)
    single<LiveUpdatePublisher> { get<LivePublisherRouter>() }
    singleOf(::LiveSessionCoordinator)
    singleOf(::CoreEventNotifier)
    singleOf(::TaskNotificationCenter)

    // 仓库 / 干员箱持久化（按配置档分片）
    single { DepotRepository.create(get(), get()) }
    single { OperBoxRepository.create(get(), get()) }

    // 回调处理链
    singleOf(::ConnectionInfoHandler)
    singleOf(::CopilotRuntimeStateStore)
    singleOf(::ToolboxResultCollector)
    singleOf(::OperBoxYituliuSync)
    single { PlanSideTaskRunner(androidContext(), get(), get()) }
    singleOf(::TaskChainStatusTracker)
    singleOf(::FightDropsRefresher)
    singleOf(::TaskChainHandler)
    singleOf(::SubTaskHandler)
    single<AppAliveChecker> { RemoteAppAliveChecker() }
    singleOf(::AppWatchdog)
    single<GameFpsReader> { RemoteGameFpsReader() }
    single { GameFpsWatcher(reader = get(), sessionLogger = get(), context = androidApplication()) }
    singleOf(::TaskCompositionService)
    single<CoreStateHolder> { get<TaskCompositionService>() }
    single { GameMuteCoordinator(get(), RemoteGameAudioAdapter) }
    singleOf(::CoreCallbackDispatcher)

    // 定时唤醒 + 解锁
    singleOf(::WakeUnlockEngine)

    singleOf(::UnifiedStateDispatcher)
    // scope 走构造默认值，singleOf 会试图解析它
    single { TaskEndRegistry(compositionService = get()) }
    singleOf(::LogExportService)
    singleOf(::ToolboxExportService)


    singleOf(::BorderOverlayManager)
    singleOf(::ScreenSaverOverlayManager) { bind<ScreenSaverController>() }
    singleOf(::OverlayViewModelOwner)
    singleOf(::OverlayController)


    singleOf(::ItemHelper)
    singleOf(::StageApCostHelper)
    singleOf(::ItemIconLoader)
    singleOf(::ActivityManager)
    singleOf(::ResourceDataManager)
    // Copilot (自动战斗)
    singleOf(::CopilotApiService)
    singleOf(::CopilotRepository)
    singleOf(::CopilotManager)
    singleOf(::CopilotResourceProvider)
    singleOf(::ApplicationLogWriter)
    singleOf(::LogTreeHolder)

    // 前台模式自动任务
}
