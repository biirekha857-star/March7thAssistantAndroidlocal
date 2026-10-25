package com.march7th.hkrpg.schedule

import com.march7th.hkrpg.R
import com.march7th.hkrpg.data.preferences.AppSettingsManager
import com.march7th.hkrpg.domain.models.RunMode
import com.march7th.hkrpg.schedule.data.ScheduleStrategyRepository
import com.march7th.hkrpg.schedule.model.ExecutionResult
import com.march7th.hkrpg.schedule.service.ScheduleFailureReporter
import com.march7th.hkrpg.schedule.service.ScheduleTriggerLogger
import com.march7th.hkrpg.utils.i18n.uiTextOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.IOException

class ScheduleFailureReporterTest {
    private val logger = mockk<ScheduleTriggerLogger> {
        every { resolveMessage(any()) } returns "failed"
    }
    private val repository = mockk<ScheduleStrategyRepository>()
    private val settings = mockk<AppSettingsManager> {
        every { runMode } returns MutableStateFlow(RunMode.BACKGROUND)
    }
    private val reporter = ScheduleFailureReporter(logger, repository, settings)
    private val message = uiTextOf(R.string.schedule_log_app_init_failed, "timeout")

    @Test
    fun logWriteFailureStillRecordsResult() = runBlocking {
        every { logger.writeClosed(any(), any(), any(), any(), any(), any()) } throws IOException("no dir")
        coEvery { repository.recordExecutionResult(any(), any(), any(), any()) } returns Unit

        reporter.report("daily", "Daily", 123L, ExecutionResult.FAILED_START, message)

        coVerify(exactly = 1) {
            repository.recordExecutionResult("daily", ExecutionResult.FAILED_START, "failed", any())
        }
    }

    @Test
    fun recordFailureDoesNotEscape() = runBlocking {
        every { logger.writeClosed(any(), any(), any(), any(), any(), any()) } returns Unit
        coEvery { repository.recordExecutionResult(any(), any(), any(), any()) } throws IOException("disk full")

        reporter.report("daily", "Daily", 123L, ExecutionResult.FAILED_START, message)
    }
}
