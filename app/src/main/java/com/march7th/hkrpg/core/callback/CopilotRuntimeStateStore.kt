package com.march7th.hkrpg.core.callback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Copilot 运行时状态共享存储：
 * 1) 是否出现过练度要求被忽略
 * 2) 作战成功事件计数（用于列表模式自动勾除/自动评价）
 * 3) 当前正在执行的作业在完整列表中的下标（用于跳过失败作业后正确归属成功事件）
 */
class CopilotRuntimeStateStore {
    private val _hasRequirementIgnored = MutableStateFlow(false)
    val hasRequirementIgnored: StateFlow<Boolean> = _hasRequirementIgnored.asStateFlow()

    private val _taskSuccessToken = MutableStateFlow(0L)
    val taskSuccessToken: StateFlow<Long> = _taskSuccessToken.asStateFlow()

    /** core 通过 CopilotListLoadTaskFileSuccess.id 回传的当前作业下标；-1 表示未知 */
    private val _currentCopilotIndex = MutableStateFlow(-1)
    val currentCopilotIndex: StateFlow<Int> = _currentCopilotIndex.asStateFlow()

    fun markRequirementIgnored() {
        _hasRequirementIgnored.value = true
    }

    fun resetRequirementIgnored() {
        _hasRequirementIgnored.value = false
    }

    fun markTaskSuccess() {
        _taskSuccessToken.update { it + 1L }
    }

    fun setCurrentCopilotIndex(index: Int) {
        _currentCopilotIndex.value = index
    }

    fun resetCurrentCopilotIndex() {
        _currentCopilotIndex.value = -1
    }
}
