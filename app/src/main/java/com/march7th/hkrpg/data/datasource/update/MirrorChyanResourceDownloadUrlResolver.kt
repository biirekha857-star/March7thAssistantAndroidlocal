package com.march7th.hkrpg.data.datasource.update

import com.march7th.hkrpg.R
import com.march7th.hkrpg.constant.M7aApi
import com.march7th.hkrpg.data.api.CdkRequiredException
import com.march7th.hkrpg.data.api.MirrorChyanApiClient
import com.march7th.hkrpg.data.preferences.AppSettingsManager
import com.march7th.hkrpg.domain.service.update.resolver.ResourceDownloadUrlResolver
import com.march7th.hkrpg.utils.i18n.LocalizedException
import com.march7th.hkrpg.utils.i18n.uiTextOf

class MirrorChyanResourceDownloadUrlResolver(
    private val apiClient: MirrorChyanApiClient,
    private val appSettingsManager: AppSettingsManager
) : ResourceDownloadUrlResolver {

    override suspend fun resolve(currentVersion: String): Result<String> {
        val cdk = appSettingsManager.mirrorChyanCdk.value
        if (cdk.isBlank()) {
            return Result.failure(CdkRequiredException())
        }

        return apiClient.getLatest(
            M7aApi.MIRROR_CHYAN_RESOURCE,
            query = mapOf(
                "current_version" to currentVersion,
                "user_agent" to "MAA-Meow",
                "cdk" to cdk
            )
        ).map { data ->
            data.url ?: throw LocalizedException(uiTextOf(R.string.update_error_empty_download_url))
        }
    }
}
