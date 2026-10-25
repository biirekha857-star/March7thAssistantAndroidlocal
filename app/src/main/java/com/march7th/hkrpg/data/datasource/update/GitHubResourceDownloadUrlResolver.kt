package com.march7th.hkrpg.data.datasource.update

import com.march7th.hkrpg.constant.M7aApi
import com.march7th.hkrpg.domain.service.update.resolver.ResourceDownloadUrlResolver

class GitHubResourceDownloadUrlResolver : ResourceDownloadUrlResolver {

    override suspend fun resolve(currentVersion: String): Result<String> {
        return Result.success(M7aApi.GITHUB_RESOURCE)
    }
}
