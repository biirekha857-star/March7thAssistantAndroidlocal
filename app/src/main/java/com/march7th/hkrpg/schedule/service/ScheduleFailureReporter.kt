package com.march7th.hkrpg.schedule.service

import com.march7th.hkrpg.data.preferences.AppSettingsManager
import com.march7th.hkrpg.schedule.data.ScheduleStrategyRepository
import com.march7th.hkrpg.schedule.model.ExecutionResult
import com.march7th.hkrpg.utils.i18n.UiText
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import timber.log.Timber

/** 未进启动流水线的失败上报：触发日志与策略结果各自容错 */
class ScheduleFailureReporter(
    private val triggerLogger: ScheduleTriggerLogger,
    private val repository: ScheduleStrategyRepository,
    private val appSettings: AppSettingsManager,
) {
    companion object {
        private const val RECORD_TIMEOUT_MS = 5_000L
    }

    suspend fun report(
        strategyId: String,
        strategyName: String,
        scheduledTimeMs: Long,
        result: ExecutionResult,
        message: UiText,
    ) = withContext(NonCancellable) {
        try {
            triggerLogger.writeClosed(
                strategyId = strategyId,
                strategyName = strategyName,
                scheduledTimeMs = scheduledTimeMs,
                result = result,
                message = message,
                runMode = appSettings.runMode.value.name,
            )
        } catch (e: Exception) {
            Timber.w(e, "Trigger log write failed: %s", strategyId)
        }
        try {
            withTimeout(RECORD_TIMEOUT_MS) {
                repository.recordExecutionResult(
                    strategyId = strategyId,
                    result = result,
                    message = triggerLogger.resolveMessage(message),
                )
            }
        } catch (e: Exception) {
            Timber.w(e, "Execution result record failed: %s", strategyId)
        }
    }
}
