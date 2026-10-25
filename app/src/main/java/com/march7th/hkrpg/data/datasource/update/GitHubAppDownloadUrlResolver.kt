package com.march7th.hkrpg.data.datasource.update

import com.march7th.hkrpg.R
import com.march7th.hkrpg.constant.M7aApi
import com.march7th.hkrpg.data.api.HttpClientHelper
import com.march7th.hkrpg.data.api.model.GitHubRelease
import com.march7th.hkrpg.data.model.update.UpdateChannel
import com.march7th.hkrpg.domain.service.update.resolver.AppDownloadUrlResolver
import com.march7th.hkrpg.utils.JsonUtils
import com.march7th.hkrpg.utils.i18n.LocalizedException
import com.march7th.hkrpg.utils.i18n.uiTextOf
import timber.log.Timber

class GitHubAppDownloadUrlResolver(
    private val httpClient: HttpClientHelper
) : AppDownloadUrlResolver {

    private val json = JsonUtils.common

    override suspend fun resolve(version: String, channel: UpdateChannel): Result<String> {
        return runCatching {
            val tag = if (version.startsWith("v", ignoreCase = true)) version else "v$version"
            val response = httpClient.get(M7aApi.appGitHubReleaseByTag(tag))

            if (!response.isSuccessful) {
                throw LocalizedException(
                    uiTextOf(R.string.update_error_github_api_failed, response.code)
                )
            }

            val release = json.decodeFromString<GitHubRelease>(response.body.string())

            val apkAsset = release.assets.firstOrNull { it.name.endsWith("universal.apk") }
                ?: throw LocalizedException(uiTextOf(R.string.update_error_github_no_apk))

            apkAsset.browserDownloadUrl
        }.onFailure { e ->
            Timber.e(e, "GitHub 获取 Release 失败: $version")
        }
    }
}
