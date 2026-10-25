package com.march7th.hkrpg.schedule

import com.march7th.hkrpg.data.preferences.AppSettingsManager
import com.march7th.hkrpg.domain.launch.LaunchPipeline
import com.march7th.hkrpg.domain.models.RunMode
import com.march7th.hkrpg.schedule.data.ScheduleStrategyRepository
import com.march7th.hkrpg.schedule.model.ExecutionResult
import com.march7th.hkrpg.schedule.model.ScheduleStrategy
import com.march7th.hkrpg.schedule.service.ScheduleAlarmManager
import com.march7th.hkrpg.schedule.service.ScheduleTriggerHandler
import com.march7th.hkrpg.schedule.service.ScheduleTriggerLogger
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class ScheduleTriggerHandlerTest {
    private val repository = mockk<ScheduleStrategyRepository>()
    private val alarms = mockk<ScheduleAlarmManager>(relaxed = true)
    private val pipeline = mockk<LaunchPipeline>()
    private val logger = mockk<ScheduleTriggerLogger>(relaxed = true)
    private val settings = mockk<AppSettingsManager> {
        every { runMode } returns MutableStateFlow(RunMode.BACKGROUND)
    }
    private val handler = ScheduleTriggerHandler(
        repository, alarms, pipeline, logger, settings,
        readTimeoutMs = 200L,
    )
    private val strategy = ScheduleStrategy(id = "daily", name = "Daily", profileId = "profile-1")
    private val scheduledTime = 123_000L

    @Test
    fun nextAlarmExistsWhileStartupIsStillWaiting_andIsNotRearmedOnCompletion() = runBlocking {
        coEvery { repository.getById(strategy.id) } returns strategy
        val startup = Job()
        val entered = CompletableDeferred<Unit>()
        every { pipeline.execute(any()) } answers {
            verify(exactly = 1) { alarms.scheduleNext(strategy, scheduledTime) }
            entered.complete(Unit)
            startup
        }
        val trigger = launch { handler.handle(strategy.id, scheduledTime) }
        withTimeout(2_000L) { entered.await() }
        assertTrue(trigger.isActive)
        // 启动期间禁用，收尾不得用旧策略重新注册
        coEvery { repository.getById(strategy.id) } returns strategy.copy(enabled = false)
        startup.complete()
        trigger.join()
        verify(exactly = 1) { alarms.scheduleNext(strategy, scheduledTime) }
    }

    @Test
    fun readFailureRetriesOriginalTriggerWithoutLaunching() = runBlocking {
        coEvery { repository.getById(strategy.id) } throws IOException("disk unavailable")
        handler.handle(strategy.id, scheduledTime, retryCount = 2)
        verify(exactly = 1) { alarms.scheduleRetry(strategy.id, scheduledTime, 2) }
        verify(exactly = 0) { pipeline.execute(any()) }
        verify { logger.writeClosed(any(), any(), scheduledTime, ExecutionResult.FAILED_START, any(), any()) }
    }

    @Test
    fun slowReadRetriesInsteadOfReportingMissingStrategy() = runBlocking {
        coEvery { repository.getById(strategy.id) } coAnswers { awaitCancellation() }
        withTimeout(2_000L) { handler.handle(strategy.id, scheduledTime) }
        verify(exactly = 1) { alarms.scheduleRetry(strategy.id, scheduledTime, 0) }
        verify { logger.writeClosed(any(), any(), scheduledTime, ExecutionResult.FAILED_START, any(), any()) }
    }

    @Test
    fun deletedAndDisabledStrategiesDoNotLaunchOrRetry() = runBlocking {
        coEvery { repository.getById(strategy.id) } returns null
        handler.handle(strategy.id, scheduledTime)
        coEvery { repository.getById(strategy.id) } returns strategy.copy(enabled = false)
        handler.handle(strategy.id, scheduledTime)
        verify(exactly = 0) { alarms.scheduleNext(any(), any()) }
        verify(exactly = 0) { alarms.scheduleRetry(any(), any(), any()) }
        verify(exactly = 0) { pipeline.execute(any()) }
    }

    @Test
    fun cancellationDoesNotScheduleRetry() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        coEvery { repository.getById(strategy.id) } coAnswers {
            entered.complete(Unit)
            awaitCancellation()
        }
        val trigger = launch { handler.handle(strategy.id, scheduledTime) }
        entered.await()
        trigger.cancelAndJoin()
        verify(exactly = 0) { alarms.scheduleRetry(any(), any(), any()) }
    }
}
