package com.march7th.hkrpg.core.callback

import com.march7th.hkrpg.core.task.TaskSlot
import com.march7th.hkrpg.utils.i18n.UiText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class TaskRunStatus {
    PENDING,
    IN_PROGRESS,
    COMPLETED,
    ERROR
}

data class TaskRunInfo(
    val taskId: Int,
    val taskChain: String,
    val status: TaskRunStatus,
    val slot: TaskSlot? = null,
    val logName: UiText? = null,
)

/** taskId → 运行状态；[tasks] 供 UI。 */
class TaskChainStatusTracker {

    private val _tasks = MutableStateFlow<List<TaskRunInfo>>(emptyList())
    val tasks: StateFlow<List<TaskRunInfo>> = _tasks.asStateFlow()

    private val registry = LinkedHashMap<Int, TaskRunInfo>()

    fun register(taskId: Int, taskChain: String, slot: TaskSlot? = null, logName: UiText? = null) {
        registry[taskId] = TaskRunInfo(taskId, taskChain, TaskRunStatus.PENDING, slot, logName)
        emit()
    }

    fun updateStatus(taskId: Int, status: TaskRunStatus) {
        registry.computeIfPresent(taskId) { _, info -> info.copy(status = status) }
        emit()
    }

    fun getNodeId(taskId: Int): String? = registry[taskId]?.slot?.nodeId

    fun getLogName(taskId: Int): UiText? = registry[taskId]?.logName

    fun clear() {
        registry.clear()
        emit()
    }

    private fun emit() {
        _tasks.value = registry.values.toList()
    }
}
