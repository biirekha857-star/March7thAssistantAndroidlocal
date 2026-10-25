package com.march7th.hkrpg.domain.service.update.checker

import com.march7th.hkrpg.data.model.update.UpdateCheckResult

interface ResourceVersionChecker {
    suspend fun check(currentVersion: String): UpdateCheckResult
}
