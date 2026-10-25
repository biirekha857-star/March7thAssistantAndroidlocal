package com.march7th.hkrpg.core.task

import com.march7th.hkrpg.utils.i18n.UiText

/** AsstAppendTask 票根；[slot] 由 Analyze 注入，链外路径为 null。 */
data class CoreTaskParams(
    val type: CoreTaskType,
    val params: String,
    val slot: TaskSlot? = null,
    val logName: UiText? = null,
)
