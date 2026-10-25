package com.march7th.hkrpg.schedule.service

import com.march7th.hkrpg.R
import com.march7th.hkrpg.data.preferences.AppSettingsManager
import com.march7th.hkrpg.domain.launch.LaunchPipeline
import com.march7th.hkrpg.schedule.LaunchIntentMapper
import com.march7th.hkrpg.schedule.data.ScheduleStrategyRepository
import com.march7th.hkrpg.schedule.model.ExecutionResult
import com.march7th.hkrpg.utils.i18n.uiTextOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import timber.log.Timber

/** 调度恢复先于启动，失败不拖断后续闹钟 */
class ScheduleTriggerHandler(
    private val repository: ScheduleStrategyRepository,
    private val alarmManager: ScheduleAlarmManager,
    private val launchPipeline: LaunchPipeline,
    private val triggerLogger: ScheduleTriggerLogger,
    private val appSettings: AppSettingsManager,
    private val readTimeoutMs: Long = DEFAULT_READ_TIMEOUT_MS,
) {
    companion object {
        const val DEFAULT_READ_TIMEOUT_MS = 5_000L
    }

    suspend fun handle(strategyId: String, scheduledTimeMs: Long, retryCount: Int = 0) {
        val strategy = try {
            withTimeout(readTimeoutMs) { repository.getById(strategyId) }
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            if (e is CancellationException && e !is TimeoutCancellationException) throw e
            val retryScheduled = alarmManager.scheduleRetry(strategyId, scheduledTimeMs, retryCount)
            Timber.w(
                e,
                "Schedule config unavailable: %s, attempt=%d, retry=%s",
                strategyId,
                retryCount,
                retryScheduled,
            )
            triggerLogger.writeClosed(
                strategyId = strategyId,
                strategyName = strategyId,
                scheduledTimeMs = scheduledTimeMs,
                result = ExecutionResult.FAILED_START,
                message = uiTextOf(R.string.schedule_log_data_unavailable),
                runMode = appSettings.runMode.value.name,
            )
            return
        }

        if (strategy == null) {
            Timber.w("Schedule strategy missing: %s", strategyId)
            triggerLogger.writeClosed(
                strategyId = strategyId,
                strategyName = strategyId,
                scheduledTimeMs = scheduledTimeMs,
                result = ExecutionResult.FAILED_VALIDATION,
                message = uiTextOf(R.string.schedule_log_strategy_missing),
                runMode = appSettings.runMode.value.name,
            )
            return
        }
        if (!strategy.enabled) {
            Timber.i("Skip disabled schedule: %s", strategyId)
            return
        }

        // 本次启动挂起或进程被杀，也保留下次闹钟
        alarmManager.scheduleNext(strategy, scheduledTimeMs)
        val request = LaunchIntentMapper.fromStrategy(strategy, scheduledTimeMs)
        launchPipeline.execute(request).join()
        Timber.i("Schedule pipeline finished: %s", request.requestId)
    }
}
