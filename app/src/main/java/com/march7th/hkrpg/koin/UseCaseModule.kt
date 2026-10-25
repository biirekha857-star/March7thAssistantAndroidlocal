package com.march7th.hkrpg.koin

import com.march7th.hkrpg.data.config.CorePathConfig
import com.march7th.hkrpg.domain.usecase.AnalyzeTaskChainUseCase
import com.march7th.hkrpg.domain.usecase.CheckGameReadinessUseCase
import com.march7th.hkrpg.domain.usecase.PrepareTaskStartUseCase
import com.march7th.hkrpg.domain.usecase.SwitchCoreDataLocationUseCase
import com.march7th.hkrpg.manager.RemoteServiceManager
import com.march7th.hkrpg.utils.EyeProtectionDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module
import timber.log.Timber
import com.march7th.hkrpg.domain.service.AchievementReporter


val useCaseModule = module {
    factory { SwitchCoreDataLocationUseCase(get(), get()) }

    factory {
        AnalyzeTaskChainUseCase(
            taskChainState = get(),
            resourceDataManager = get(),
            activityManager = get(),
            depotRepository = get(),
            operBoxRepository = get(),
            itemHelper = get(),
            dropsRefresher = get(),
            appSettingsManager = get(),
            relocatePath = get<CorePathConfig>()::toCorePath,
        )
    }
    factory {
        CheckGameReadinessUseCase(
            appAliveChecker = get(),
            appSettings = get(),
            achievementReporter = get(),
            isPackageInstalled = { packageName ->
                withContext(Dispatchers.IO) {
                    try {
                        RemoteServiceManager.getInstanceOrNull()
                            ?.isPackageInstalled(packageName) ?: true
                    } catch (e: Exception) {
                        Timber.w(e, "isPackageInstalled check failed for %s", packageName)
                        true
                    }
                }
            },
            isEyeProtectionEnabled = { EyeProtectionDetector.isEyeProtectionEnabled(androidContext()) },
        )
    }
    factory {
        PrepareTaskStartUseCase(
            analyzeTaskChainUseCase = get(),
            checkGameReadiness = get(),
        )
    }
}
