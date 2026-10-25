package com.march7th.hkrpg.data.datasource.update

import com.march7th.hkrpg.constant.M7aApi
import com.march7th.hkrpg.data.api.MirrorChyanApiClient
import com.march7th.hkrpg.data.api.MirrorChyanBizException
import com.march7th.hkrpg.data.datasource.ResourceDownloader
import com.march7th.hkrpg.data.model.update.UpdateCheckResult
import com.march7th.hkrpg.data.model.update.UpdateError
import com.march7th.hkrpg.data.model.update.UpdateInfo
import com.march7th.hkrpg.domain.service.update.checker.ResourceVersionChecker

class MirrorChyanResourceVersionChecker(
    private val apiClient: MirrorChyanApiClient
) : ResourceVersionChecker {

    override suspend fun check(currentVersion: String): UpdateCheckResult {
        val result = apiClient.getLatest(
            M7aApi.MIRROR_CHYAN_RESOURCE,
            query = mapOf(
                "current_version" to currentVersion,
                "user_agent" to "MAA-Meow"
            )
        )

        return result.fold(
            onSuccess = { data ->
                val remoteVersion = data.versionName
                if (remoteVersion.isEmpty() || ResourceDownloader.compareVersions(
                        currentVersion,
                        remoteVersion
                    ) >= 0
                ) {
                    UpdateCheckResult.UpToDate(currentVersion)
                } else {
                    UpdateCheckResult.Available(
                        UpdateInfo(
                            version = ResourceDownloader.formatVersionForDisplay(remoteVersion),
                            releaseNote = data.releaseNote
                        )
                    )
                }
            },
            onFailure = { e ->
                when (e) {
                    is MirrorChyanBizException -> UpdateCheckResult.Error(e.toUpdateError())
                    else -> UpdateCheckResult.Error(UpdateError.NetworkError(e.message))
                }
            }
        )
    }
}
