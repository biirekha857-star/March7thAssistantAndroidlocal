package com.march7th.hkrpg.domain.launch

import android.content.Context
import com.march7th.hkrpg.R
import com.march7th.hkrpg.data.model.TaskChainNode
import com.march7th.hkrpg.data.preferences.AppSettingsManager
import com.march7th.hkrpg.domain.service.GameMuteCoordinator
import com.march7th.hkrpg.domain.service.TaskCompositionService
import com.march7th.hkrpg.domain.service.CoreSessionLogger
import com.march7th.hkrpg.domain.service.resolveStartResultMessage
import com.march7th.hkrpg.domain.usecase.PrepareTaskStartUseCase
import com.march7th.hkrpg.domain.usecase.TaskStartContext
import com.march7th.hkrpg.domain.usecase.TaskStartDecision
import com.march7th.hkrpg.schedule.model.ExecutionResult
import com.march7th.hkrpg.utils.i18n.UiText
import com.march7th.hkrpg.utils.i18n.uiTextOf
import com.march7th.hkrpg.domain.service.AchievementReporter

/**
 * 任务链启动尾部：prepare + mute + composition.start + achievement + 可选 schedule 会话日志
 * 手动与自动化共用；SCHEDULED 不会产生 RequiresConfirmation
 */
class StartTaskChainUseCase(
    private val prepare: PrepareTaskStartUseCase,
    private val composition: TaskCompositionService,
    private val muteCoordinator: GameMuteCoordinator,
    private val achievements: AchievementReporter,
    private val sessionLogger: CoreSessionLogger,
    private val appSettingsManager: AppSettingsManager,
    private val appContext: Context,
    private val sideTaskRunner: PlanSideTaskRunner,
) {
    sealed interface Result {
        data object Success : Result

        /** 只跑了旁路任务，Core 根本没起 */
        data class SuccessWithoutCore(val message: UiText?) : Result
        data class Failed(
            val executionResult: ExecutionResult,
            val message: UiText,
        ) : Result
    }

    suspend operator fun invoke(
        chain: List<TaskChainNode>,
        context: TaskStartContext,
        scheduleLabel: String? = null,
    ): Result {
        val plan = when (val decision = prepare(chain, context)) {
            is TaskStartDecision.Ready -> decision.plan
            is TaskStartDecision.Blocked -> {
                return Result.Failed(
                    executionResult = ExecutionResult.FAILED_VALIDATION,
                    message = uiTextOf(R.string.schedule_log_task_blocked, decision.message),
                )
            }

            is TaskStartDecision.RequiresConfirmation -> {
                return Result.Failed(
                    executionResult = ExecutionResult.FAILED_VALIDATION,
                    message = uiTextOf(R.string.schedule_log_task_needs_confirmation),
                )
            }
        }

        if (plan.params.isEmpty()) {
            // 不起 Core，但必须等在这里：前台服务与唤醒锁只活到本方法返回
            val outcome = sideTaskRunner.runWithoutCore(plan.sideTasks)
            return when {
                outcome == null || outcome.ok -> Result.SuccessWithoutCore(outcome?.message)
                else -> Result.Failed(ExecutionResult.FAILED_START, outcome.message)
            }
        }

        // 必须先于静音，换进程会让旧进程收尾时解除静音
        composition.prepareResources(plan.clientType)

        if (appSettingsManager.muteOnGameLaunch.value) {
            muteCoordinator.mute(plan.clientType)
        }

        val startResult = composition.start(
            tasks = plan.params,
            clientType = plan.clientType,
            preflightLogs = plan.logs,
            fallbacks = plan.fallbacks,
        ) {
            if (scheduleLabel != null) {
                sessionLogger.appendAndWait(
                    appContext.getString(
                        R.string.task_start_triggered_by_schedule,
                        scheduleLabel,
                    ),
                )
            }
        }

        return when (startResult) {
            is TaskCompositionService.StartResult.Success -> {
                // 晚于 startSession 发出，拉取日志才有会话接住
                sideTaskRunner.launch(plan.sideTasks)
                achievements.reportTaskStarted(
                    taskCount = plan.params.size,
                    launchesGame = plan.launchesGame,
                    gameAliveBeforeStart = plan.gameAliveBeforeStart,
                )
                Result.Success
            }

            else -> Result.Failed(
                executionResult = ExecutionResult.FAILED_START,
                message = resolveStartResultMessage(startResult)
                    ?: uiTextOf(R.string.task_start_error_start_failed),
            )
        }
    }
}
