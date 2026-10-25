package com.march7th.hkrpg.data.datasource.update

import com.march7th.hkrpg.constant.M7aApi
import com.march7th.hkrpg.data.api.MirrorChyanApiClient
import com.march7th.hkrpg.data.api.MirrorChyanBizException
import com.march7th.hkrpg.data.datasource.AppDownloader
import com.march7th.hkrpg.data.model.update.UpdateChannel
import com.march7th.hkrpg.data.model.update.UpdateCheckResult
import com.march7th.hkrpg.data.model.update.UpdateError
import com.march7th.hkrpg.data.model.update.UpdateInfo
import com.march7th.hkrpg.data.preferences.AppSettingsManager
import com.march7th.hkrpg.domain.service.update.checker.AppVersionChecker

class MirrorChyanAppVersionChecker(
    private val apiClient: MirrorChyanApiClient,
    private val appSettings: AppSettingsManager
) : AppVersionChecker {

    override suspend fun check(
        current: String,
        channel: UpdateChannel,
    ): UpdateCheckResult {
        val cdk = appSettings.mirrorChyanCdk.value
        val query = mapOf(
            "current_version" to current,
            "user_agent" to "MAA-Meow",
            "os" to "android",
            "channel" to channel.value,
        ).let {
            if (cdk.length == 24) it + mapOf("cdk" to cdk) else it
        }
        val result = apiClient.getLatest(
            M7aApi.MIRROR_CHYAN_APP_RESOURCE,
            query = query,
            fetchVersion = true
        )

        return result.fold(
            onSuccess = { data ->
                val remoteVersion = data.versionName
                if (remoteVersion.isEmpty() || AppDownloader.compareVersions(
                        current,
                        remoteVersion
                    ) >= 0
                ) {
                    UpdateCheckResult.UpToDate(current)
                } else {
                    UpdateCheckResult.Available(
                        UpdateInfo(
                            version = remoteVersion,
                            releaseNote = data.releaseNote
                        )
                    )
                }
            },
            onFailure = { e ->
                when (e) {
                    is MirrorChyanBizException -> UpdateCheckResult.Error(e.toUpdateError())
                    else -> UpdateCheckResult.Error(
                        UpdateError.NetworkError(e.message)
                    )
                }
            }
        )
    }
}
