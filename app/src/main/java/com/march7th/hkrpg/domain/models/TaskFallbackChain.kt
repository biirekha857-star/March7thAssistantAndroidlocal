package com.march7th.hkrpg.domain.models

import com.march7th.hkrpg.data.model.LogLevel
import com.march7th.hkrpg.utils.i18n.UiText

/** 一个任务位的后备链 */
data class TaskFallbackChain(
    /** 按序尝试，首个 append 成功即停 */
    val candidates: List<TaskCandidate> = emptyList(),
    /** 全部候选失败后输出的尾部跳过原因 */
    val logsWhenExhausted: List<Pair<UiText, LogLevel>> = emptyList(),
) {
    val isEmpty: Boolean get() = candidates.isEmpty() && logsWhenExhausted.isEmpty()
}

/** 返回首个入队成功的任务 ID 与候选，全部失败返回 null */
inline fun TaskFallbackChain.appendFirstSuccessful(
    log: (UiText, LogLevel) -> Unit,
    append: (TaskCandidate) -> Int,
    onAppendFailed: (TaskCandidate) -> Unit,
): Pair<Int, TaskCandidate>? {
    for (c in candidates) {
        c.logsBefore.forEach { (text, level) -> log(text, level) }
        val id = append(c)
        if (id <= 0) {
            onAppendFailed(c)
            continue
        }
        c.logOnSuccess?.let { (text, level) -> log(text, level) }
        return id to c
    }
    logsWhenExhausted.forEach { (text, level) -> log(text, level) }
    return null
}
