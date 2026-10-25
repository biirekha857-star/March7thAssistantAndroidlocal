package com.march7th.hkrpg.domain.models

import com.march7th.hkrpg.data.model.LogLevel
import com.march7th.hkrpg.core.task.CoreTaskType
import com.march7th.hkrpg.utils.i18n.UiText

/** 主任务入队失败后的候选，由 Core 判定参数是否有效 */
data class TaskCandidate(
    val type: CoreTaskType,
    val params: String,
    val logName: UiText,
    /** 入队成功后才绑定，避免覆盖同一任务位的目标 */
    val dropTarget: DropTarget,
    /** 尝试本候选前输出的跳过原因 */
    val logsBefore: List<Pair<UiText, LogLevel>> = emptyList(),
    /** 候选入队成功后输出；主任务的同类日志在连接前随预检回放 */
    val logOnSuccess: Pair<UiText, LogLevel>? = null,
)
