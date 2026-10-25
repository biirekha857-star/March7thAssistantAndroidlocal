package com.march7th.hkrpg.domain.service.update.resolver

interface ResourceDownloadUrlResolver {
    suspend fun resolve(currentVersion: String): Result<String>
}
