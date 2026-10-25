package com.march7th.hkrpg.domain.service

import com.march7th.hkrpg.domain.state.TaskExecutionState
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 状态边沿 → [TaskEndRegistry.Reason] 的映射，重点是手动停止与回调侧中止的区分
 */
class TaskEndRegistryTest {

    private lateinit var scope: CoroutineScope
    private lateinit var composition: TaskCompositionService
    private lateinit var registry: TaskEndRegistry

    private val state = MutableStateFlow(TaskExecutionState.IDLE)

    @Volatile
    private var stopOrigin = TaskCompositionService.StopOrigin.USER
    private val emitted = CopyOnWriteArrayList<TaskEndRegistry.Reason>()
    private val pendingSeen = CopyOnWriteArrayList<TaskEndRegistry.Reason>()

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        state.value = TaskExecutionState.IDLE
        stopOrigin = TaskCompositionService.StopOrigin.USER
        emitted.clear()
        pendingSeen.clear()
        composition = mockk(relaxed = true) {
            every { state } returns this@TaskEndRegistryTest.state
            every { lastStopOrigin } answers { stopOrigin }
        }
        registry = TaskEndRegistry(composition, scope).apply { start() }
        scope.launch { registry.taskEnded.collect { emitted.add(it) } }
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    /** StateFlow 合并，赋值间须留窗口 */
    private suspend fun drive(vararg states: TaskExecutionState) {
        for (s in states) {
            delay(150)
            state.value = s
        }
        delay(300)
    }

    @Test
    fun runningToIdle_isNatural() = runBlocking<Unit> {
        registry.armOnce { pendingSeen.add(it) }
        drive(TaskExecutionState.STARTING, TaskExecutionState.RUNNING, TaskExecutionState.IDLE)
        assertEquals(listOf(TaskEndRegistry.Reason.NATURAL), emitted)
        assertEquals(listOf(TaskEndRegistry.Reason.NATURAL), pendingSeen)
    }

    @Test
    fun runningToError_isNatural() = runBlocking<Unit> {
        drive(TaskExecutionState.RUNNING, TaskExecutionState.ERROR)
        assertEquals(listOf(TaskEndRegistry.Reason.NATURAL), emitted)
    }

    @Test
    fun userStop_isManual() = runBlocking<Unit> {
        drive(TaskExecutionState.RUNNING)
        // 运行中登记，避免 IDLE 下的补跑
        registry.armOnce { pendingSeen.add(it) }
        stopOrigin = TaskCompositionService.StopOrigin.USER
        drive(TaskExecutionState.STOPPING, TaskExecutionState.IDLE)
        assertEquals(listOf(TaskEndRegistry.Reason.MANUAL), emitted)
        assertEquals(listOf(TaskEndRegistry.Reason.MANUAL), pendingSeen)
    }

    @Test
    fun callbackStop_isAborted() = runBlocking<Unit> {
        drive(TaskExecutionState.RUNNING)
        // 运行中登记，避免 IDLE 下的补跑
        registry.armOnce { pendingSeen.add(it) }
        stopOrigin = TaskCompositionService.StopOrigin.CALLBACK
        drive(TaskExecutionState.STOPPING, TaskExecutionState.IDLE)
        assertEquals(listOf(TaskEndRegistry.Reason.ABORTED), emitted)
        assertEquals(listOf(TaskEndRegistry.Reason.ABORTED), pendingSeen)
    }

    @Test
    fun callbackStopToError_isAborted() = runBlocking<Unit> {
        drive(TaskExecutionState.RUNNING)
        stopOrigin = TaskCompositionService.StopOrigin.CALLBACK
        drive(TaskExecutionState.STOPPING, TaskExecutionState.ERROR)
        assertEquals(listOf(TaskEndRegistry.Reason.ABORTED), emitted)
    }

    @Test
    fun durationLimitStop_isDurationLimit() = runBlocking<Unit> {
        drive(TaskExecutionState.RUNNING)
        registry.armOnce { pendingSeen.add(it) }
        stopOrigin = TaskCompositionService.StopOrigin.RUN_DURATION_LIMIT
        drive(TaskExecutionState.STOPPING, TaskExecutionState.IDLE)
        assertEquals(listOf(TaskEndRegistry.Reason.DURATION_LIMIT), emitted)
        assertEquals(listOf(TaskEndRegistry.Reason.DURATION_LIMIT), pendingSeen)
    }

    @Test
    fun startingToIdle_emitsNothing() = runBlocking<Unit> {
        drive(TaskExecutionState.STARTING, TaskExecutionState.IDLE)
        assertEquals(emptyList<TaskEndRegistry.Reason>(), emitted)
    }
}
