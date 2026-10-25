package com.march7th.hkrpg.data.notification.provider

import com.march7th.hkrpg.R
import com.march7th.hkrpg.data.api.HttpClientHelper
import com.march7th.hkrpg.data.notification.NotificationSettingsManager
import com.march7th.hkrpg.utils.JsonUtils
import com.march7th.hkrpg.utils.i18n.uiTextOf
import kotlinx.coroutines.flow.first
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import timber.log.Timber
import java.net.URI

class BarkProvider(
    private val httpClient: HttpClientHelper,
    private val settingsManager: NotificationSettingsManager
) : NotificationProvider {

    override val id = "Bark"

    override suspend fun send(title: String, content: String): NotificationSendResult {
        val settings = settingsManager.settings.first()
        val barkServer = settings.barkServer.takeIf { it.isNotEmpty() }
            ?: return NotificationSendResult.Failed(
                uiTextOf(R.string.notification_err_bark_server_empty)
            )
        val sendKey = settings.barkSendKey.takeIf { it.isNotEmpty() }
            ?: return NotificationSendResult.Failed(
                uiTextOf(R.string.notification_err_bark_key_empty)
            )

        val normalizedBase = barkServer.trimEnd('/') + "/"
        val url = URI.create(normalizedBase).resolve("push").toString()
        val body = JsonUtils.common.encodeToString(
            BarkRequest(
                title = title,
                body = content,
                deviceKey = sendKey,
            )
        )

        return runCatching {
            httpClient.post(url, body).use { response ->
                val responseBody = response.body.string()
                if (response.isSuccessful &&
                    JsonUtils.common.decodeFromString<BarkResponse>(responseBody).code == 200
                ) {
                    NotificationSendResult.Success
                } else {
                    Timber.w("Bark rejected: HTTP %d, body=%s", response.code, responseBody)
                    NotificationSendResult.Failed(
                        uiTextOf(R.string.notification_err_http_status, response.code),
                    )
                }
            }
        }.getOrElse {
            Timber.e(it, "Bark send failed")
            NotificationSendResult.Transient(uiTextOf(R.string.notification_err_network))
        }
    }

    @Serializable
    private data class BarkRequest(
        val title: String,
        val body: String,
        @SerialName("device_key") val deviceKey: String,
        val group: String = "MaaAssistantArknights",
        val icon: String = "https://cdn.jsdelivr.net/gh/MaaAssistantArknights/design@main/logo/maa-logo_256x256.png",
    )

    @Serializable
    private data class BarkResponse(
        val code: Int = -1,
    )
}
