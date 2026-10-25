package com.march7th.hkrpg.schedule.model

import kotlinx.serialization.Serializable

@Serializable
enum class ExecutionResult {
    STARTED,
    FAILED_VALIDATION,
    FAILED_START,
    FAILED_UI_LAUNCH,
    SKIPPED_BUSY,
    SKIPPED_LOCKED,
    CANCELLED,
}
