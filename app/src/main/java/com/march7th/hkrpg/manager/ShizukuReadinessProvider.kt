package com.march7th.hkrpg.manager

import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.march7th.hkrpg.data.preferences.AppSettingsManager
import com.march7th.hkrpg.domain.models.RemoteBackend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

class ShizukuReadinessProvider(
    context: Context,
    private val appSettings: AppSettingsManager,
) : DefaultLifecycleObserver {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val refreshTrigger = MutableStateFlow(0)

    init {
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    override fun onResume(owner: LifecycleOwner) {
        refreshTrigger.update { it + 1 }
    }

    val state: StateFlow<ShizukuReadiness> = combine(
        RemoteAccessCoordinator.state,
        appSettings.shizukuLaunchPackage,
        appSettings.skipShizukuCheck,
        refreshTrigger,
    ) { remoteState, launchPackage, skipCheck, _ ->
        resolve(remoteState, launchPackage, skipCheck)
    }.stateIn(
        scope = scope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ShizukuReadiness(),
    )

    private suspend fun resolve(
        remoteState: RemoteAccessState,
        launchPackage: String,
        skipCheck: Boolean,
    ): ShizukuReadiness {
        val stage = when {
            // 用户已选择跳过：无需展示引导，也不必付出 checkStatus 的 IPC 开销
            skipCheck -> ShizukuReadinessStage.Ready

            remoteState.configuredBackend != RemoteBackend.SHIZUKU ->
                ShizukuReadinessStage.Ready

            // Sui 在启动时已 init：优先告知兼容性，避免被下面的 shizukuAvailable 抢占成 NeedAuth
            ShizukuManager.isSui ->
                ShizukuReadinessStage.SuiAvailable

            remoteState.shizukuGranted ->
                ShizukuReadinessStage.Ready

            remoteState.shizukuAvailable ->
                ShizukuReadinessStage.NeedAuth

            else -> {
                val status = withContext(Dispatchers.IO) {
                    ShizukuInstallHelper.checkStatus(appContext, launchPackage)
                }
                when (status) {
                    ShizukuInstallHelper.ShizukuStatus.SUI_AVAILABLE ->
                        ShizukuReadinessStage.SuiAvailable

                    ShizukuInstallHelper.ShizukuStatus.APP_NOT_RUNNING ->
                        ShizukuReadinessStage.NotRunning

                    else -> ShizukuReadinessStage.NotInstalled
                }
            }
        }
        return ShizukuReadiness(
            stage = stage,
            canSwitchToRoot = remoteState.rootAvailable,
        )
    }
}
