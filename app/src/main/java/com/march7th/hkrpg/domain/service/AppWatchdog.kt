package com.march7th.hkrpg.domain.service

import android.os.SystemClock
import androidx.annotation.VisibleForTesting
import com.march7th.hkrpg.constant.Packages
import com.march7th.hkrpg.data.preferences.TaskChainState
import com.march7th.hkrpg.domain.service.AppWatchdog.Companion.MAX_REPIN_ATTEMPTS
import com.march7th.hkrpg.domain.service.AppWatchdog.Companion.REPIN_GRACE_MS
import com.march7th.hkrpg.remote.AppAliveStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber

class AppWatchdog(
    private val chainState: TaskChainState,
    private val appAliveChecker: AppAliveChecker,
) {
    enum class WatchdogState {
        IDLE,
        WATCHING,
        APP_DIED,
    }

    companion object {
        private const val POLL_INTERVAL_MS = 5000L

        /** 连续多少次判定「死亡」才真的上报（避免瞬时误判）。 */
        private const val DEAD_CONFIRM_COUNT = 2

        /** 首次发现离开虚拟屏后，等待再拉回，避开登录/SDK 瞬态漂移 */
        @VisibleForTesting
        internal const val REPIN_GRACE_MS = 5_000L

        // 连续拉回失败的上限：超过后停止重试（拉回兜底会重投放启动 intent，
        // 无限重试等于每个 tick 都向游戏投放一次启动请求）
        @VisibleForTesting
        internal const val MAX_REPIN_ATTEMPTS = 3
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(WatchdogState.IDLE)
    val state: StateFlow<WatchdogState> = _state.asStateFlow()

    private val _appDiedEvent = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val appDiedEvent: SharedFlow<String> = _appDiedEvent.asSharedFlow()

    // 游戏窗口离开虚拟显示器且连续拉回失败达到上限（每次漂移只上报一次）
    private val _displayDriftEvent = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val displayDriftEvent: SharedFlow<String> = _displayDriftEvent.asSharedFlow()

    private var watchJob: Job? = null

    /** 连续检测到「游戏已死」的次数。 */
    private var deadStreak = 0
    private var driftNotified = false

    // 本次漂移中连续拉回失败的次数；回到虚拟屏或拉回成功后清零
    private var driftRepinAttempts = 0

    /**
     * 游戏离开虚拟显示器的首次检测时间戳（[SystemClock.elapsedRealtime]，单调时钟，
     * 不受 NTP 校时/手动改时间影响）。
     * 仅在漂移开关开启时使用，用于实现「延迟 N 秒后再拉回」的宽限期，
     * 避免游戏启动后的 SDK 登录/鉴权弹窗被拉回打断。
     */
    private var driftFirstSeenMs: Long = 0L

    @VisibleForTesting
    internal var clock: () -> Long = { SystemClock.elapsedRealtime() }

    fun startWatching() {
        stopWatching()
        driftNotified = false
        driftFirstSeenMs = 0L
        driftRepinAttempts = 0
        deadStreak = 0

        val clientType = chainState.clientType
        val packageName = Packages[clientType]
        if (packageName == null) {
            Timber.w(
                "AppWatchdog: cannot resolve package name for clientType=%s, skipping",
                clientType
            )
            return
        }

        Timber.i("AppWatchdog: start watching %s", packageName)
        _state.value = WatchdogState.WATCHING

        watchJob = scope.launch {
            while (isActive) {
                delay(POLL_INTERVAL_MS)
                val appAliveStatus = checkAppAliveStatus(packageName)
                if (!isActive) {
                    return@launch
                }
                when (appAliveStatus) {
                    AppAliveStatus.ALIVE -> {
                        deadStreak = 0
                        checkDisplayPinned(packageName)
                    }
                    AppAliveStatus.UNKNOWN -> {
                        Timber.w(
                            "AppWatchdog: unable to determine whether %s is alive",
                            packageName
                        )
                    }

                    AppAliveStatus.DEAD -> {
                        // ## 连续 2 次判死才上报
                        //
                        // 单次 `pidof` 探测可能因为瞬时失败（fork 压力、
                        // 进程刚好在重启、子进程输出阻塞…）给出「不存在」
                        // 的假信号。实测就踩过：领邮件任务一开始，
                        // watchdog 报「游戏已死」并中止了整个任务链，
                        // 但游戏明明好好地在跑。
                        //
                        // 所以第一次 DEAD 只记一笔，**隔一个轮询周期再确认**；
                        // 仍然 DEAD 才认为真的死了。
                        deadStreak++
                        if (deadStreak < DEAD_CONFIRM_COUNT) {
                            Timber.w(
                                "AppWatchdog: app %s reported dead (%d/%d), will re-check",
                                packageName, deadStreak, DEAD_CONFIRM_COUNT
                            )
                        } else {
                            Timber.w("AppWatchdog: app %s is no longer alive (confirmed)", packageName)
                            _state.value = WatchdogState.APP_DIED
                            _appDiedEvent.tryEmit(packageName)
                            return@launch
                        }
                    }

                    else -> {
                        Timber.w(
                            "AppWatchdog: unexpected app status %s for %s",
                            appAliveStatus,
                            packageName
                        )
                    }
                }
            }
        }
    }

    fun stopWatching() {
        watchJob?.cancel()
        watchJob = null
        _state.value = WatchdogState.IDLE
    }

    private suspend fun checkAppAliveStatus(packageName: String): Int {
        return appAliveChecker.isAppAlive(packageName)
    }

    /**
     * 后台模式下部分 ROM（如 One UI）会把游戏从虚拟屏挪回主屏，导致识别与真实画面分离。
     * 默认始终自动拉回：
     * - 首次发现离开虚拟屏后等待 [REPIN_GRACE_MS]，让 SDK 登录/鉴权瞬态自行回落
     * - 连续拉回失败 [MAX_REPIN_ATTEMPTS] 次后上报并停止重试
     * - 游戏回到虚拟屏后计数清零，下一次漂移重新处理
     */
    @VisibleForTesting
    internal suspend fun checkDisplayPinned(packageName: String) {
        val onDisplay = appAliveChecker.isAppOnBackgroundDisplay(packageName) ?: return
        if (onDisplay) {
            driftNotified = false
            driftFirstSeenMs = 0L
            driftRepinAttempts = 0
            return
        }

        if (driftRepinAttempts >= MAX_REPIN_ATTEMPTS) {
            return
        }

        val nowMs = clock()
        if (driftFirstSeenMs == 0L) {
            driftFirstSeenMs = nowMs
            Timber.i(
                "AppWatchdog: app %s left the virtual display, will repin in %d ms (grace period)",
                packageName, REPIN_GRACE_MS
            )
            return
        }
        if (nowMs - driftFirstSeenMs < REPIN_GRACE_MS) {
            return
        }

        Timber.w(
            "AppWatchdog: app %s drifted for %d ms, trying to move it back (attempt %d/%d)",
            packageName, nowMs - driftFirstSeenMs, driftRepinAttempts + 1, MAX_REPIN_ATTEMPTS
        )
        if (appAliveChecker.moveAppToBackgroundDisplay(packageName) == true) {
            Timber.i("AppWatchdog: app %s moved back to the virtual display", packageName)
            driftFirstSeenMs = 0L
            driftRepinAttempts = 0
            return
        }
        driftRepinAttempts++
        if (driftRepinAttempts >= MAX_REPIN_ATTEMPTS && !driftNotified) {
            driftNotified = true
            _displayDriftEvent.tryEmit(packageName)
        }
    }
}
