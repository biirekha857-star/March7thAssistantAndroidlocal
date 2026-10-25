package com.march7th.hkrpg.domain.service.update.resolver

import com.march7th.hkrpg.data.model.update.UpdateChannel

interface AppDownloadUrlResolver {
    suspend fun resolve(version: String, channel: UpdateChannel): Result<String>
}
