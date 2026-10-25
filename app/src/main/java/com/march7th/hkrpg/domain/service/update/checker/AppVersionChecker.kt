package com.march7th.hkrpg.domain.service.update.checker

import com.march7th.hkrpg.data.model.update.UpdateChannel
import com.march7th.hkrpg.data.model.update.UpdateCheckResult

interface AppVersionChecker {
    suspend fun check(current: String, channel: UpdateChannel): UpdateCheckResult
}
