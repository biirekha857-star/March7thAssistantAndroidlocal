package com.march7th.hkrpg.data.datasource.update

import com.march7th.hkrpg.BuildConfig
import com.march7th.hkrpg.R
import com.march7th.hkrpg.constant.M7aApi
import com.march7th.hkrpg.data.api.CdkRequiredException
import com.march7th.hkrpg.data.api.MirrorChyanApiClient
import com.march7th.hkrpg.data.model.update.UpdateChannel
import com.march7th.hkrpg.data.preferences.AppSettingsManager
import com.march7th.hkrpg.domain.service.update.resolver.AppDownloadUrlResolver
import com.march7th.hkrpg.utils.i18n.LocalizedException
import com.march7th.hkrpg.utils.i18n.uiTextOf

class MirrorChyanAppDownloadUrlResolver(
    private val apiClient: MirrorChyanApiClient,
    private val appSettingsManager: AppSettingsManager
) : AppDownloadUrlResolver {

    override suspend fun resolve(version: String, channel: UpdateChannel): Result<String> {
        val cdk = appSettingsManager.mirrorChyanCdk.value
        if (cdk.isBlank()) {
            return Result.failure(CdkRequiredException())
        }

        return apiClient.getLatest(
            M7aApi.MIRROR_CHYAN_APP_RESOURCE,
            query = mapOf(
                "current_version" to BuildConfig.VERSION_NAME,
                "user_agent" to "MAA-Meow",
                "os" to "android",
                "channel" to channel.value,
                "cdk" to cdk
            )
        ).map { data ->
            data.url ?: throw LocalizedException(uiTextOf(R.string.update_error_empty_download_url))
        }
    }
}
