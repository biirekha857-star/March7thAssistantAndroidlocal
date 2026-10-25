package com.march7th.hkrpg.domain.launch

import com.march7th.hkrpg.R
import com.march7th.hkrpg.domain.models.PlanSideTask
import com.march7th.hkrpg.domain.service.TaskCompositionService
import com.march7th.hkrpg.domain.usecase.PrepareTaskStartUseCase
import com.march7th.hkrpg.domain.usecase.TaskStartContext
import com.march7th.hkrpg.domain.usecase.TaskStartDecision
import com.march7th.hkrpg.domain.usecase.TaskStartDecisionReason
import com.march7th.hkrpg.domain.usecase.TaskChainPlan
import com.march7th.hkrpg.domain.usecase.TaskStartMode
import com.march7th.hkrpg.schedule.model.ExecutionResult
import com.march7th.hkrpg.utils.i18n.uiTextDynamic
import com.march7th.hkrpg.utils.i18n.uiTextOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class StartTaskChainUseCaseTest {
    private val prepare = mockk<PrepareTaskStartUseCase>()
    private val sideTaskRunner = mockk<PlanSideTaskRunner>(relaxed = true)
    private val composition = mockk<TaskCompositionService>(relaxed = true)
    private val useCase = StartTaskChainUseCase(
        prepare = prepare,
        composition = composition,
        muteCoordinator = mockk(relaxed = true),
        achievements = mockk(relaxed = true),
        sessionLogger = mockk(relaxed = true),
        appSettingsManager = mockk(relaxed = true),
        appContext = mockk(relaxed = true),
        sideTaskRunner = sideTaskRunner,
    )

    private fun planWithoutCoreTask() = TaskChainPlan(
        nodes = emptyList(),
        params = emptyList(),
        clientType = "Official",
        gamePackageName = null,
        launchesGame = false,
        sideTasks = listOf(PlanSideTask.OPER_BOX_YITULIU),
    )

    @Test
    fun blockedDecision_logsLocalizedReasonInsteadOfEnumName() = runBlocking {
        val decision = TaskStartDecision.Blocked(TaskStartDecisionReason.GAME_NOT_ON_BACKGROUND_DISPLAY)
        coEvery { prepare(any(), any()) } returns decision

        val result = useCase(emptyList(), TaskStartContext(mode = TaskStartMode.SCHEDULED))

        assertEquals(
            StartTaskChainUseCase.Result.Failed(
                executionResult = ExecutionResult.FAILED_VALIDATION,
                message = uiTextOf(R.string.schedule_log_task_blocked, decision.message),
            ),
            result,
        )
    }

    @Test
    fun planWithoutCoreTask_waitsForSideTaskAndSkipsCore() = runBlocking {
        val message = uiTextDynamic("done")
        coEvery { prepare(any(), any()) } returns TaskStartDecision.Ready(planWithoutCoreTask())
        coEvery { sideTaskRunner.runWithoutCore(any(), any()) } returns
                PlanSideTaskRunner.Outcome(ok = true, message = message)

        val result = useCase(emptyList(), TaskStartContext(mode = TaskStartMode.SCHEDULED))

        assertEquals(StartTaskChainUseCase.Result.SuccessWithoutCore(message), result)
        // 等在这里才有前台服务与唤醒锁；Core 一次都不能起
        coVerify(exactly = 0) { composition.start(any(), any(), any(), any(), any()) }
    }

    @Test
    fun sideTaskFailure_isNotReportedAsStarted() = runBlocking {
        val message = uiTextDynamic("token empty")
        coEvery { prepare(any(), any()) } returns TaskStartDecision.Ready(planWithoutCoreTask())
        coEvery { sideTaskRunner.runWithoutCore(any(), any()) } returns
                PlanSideTaskRunner.Outcome(ok = false, message = message)

        val result = useCase(emptyList(), TaskStartContext(mode = TaskStartMode.SCHEDULED))

        assertEquals(
            StartTaskChainUseCase.Result.Failed(ExecutionResult.FAILED_START, message),
            result,
        )
    }
}
