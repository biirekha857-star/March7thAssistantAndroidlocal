package com.march7th.hkrpg.data.model.update

import com.march7th.hkrpg.R
import com.march7th.hkrpg.data.model.update.UpdateError.MirrorchyanBizError.InvalidArch
import com.march7th.hkrpg.data.model.update.UpdateError.MirrorchyanBizError.InvalidChannel
import com.march7th.hkrpg.data.model.update.UpdateError.MirrorchyanBizError.InvalidOs
import com.march7th.hkrpg.data.model.update.UpdateError.MirrorchyanBizError.KeyBlocked
import com.march7th.hkrpg.data.model.update.UpdateError.MirrorchyanBizError.KeyExpired
import com.march7th.hkrpg.data.model.update.UpdateError.MirrorchyanBizError.KeyInvalid
import com.march7th.hkrpg.data.model.update.UpdateError.MirrorchyanBizError.KeyMismatched
import com.march7th.hkrpg.data.model.update.UpdateError.MirrorchyanBizError.ResourceNotFound
import com.march7th.hkrpg.data.model.update.UpdateError.MirrorchyanBizError.ResourceQuotaExhausted
import com.march7th.hkrpg.utils.i18n.UiText
import com.march7th.hkrpg.utils.i18n.uiTextDynamicOr
import com.march7th.hkrpg.utils.i18n.uiTextOf

/**
 * 更新错误类型。
 *
 * 文案统一以 [UiText] 承载（固定文案走资源 id，服务器/系统返回的动态信息走 [uiTextDynamicOr]），
 * 由 UI 层在展示时 resolve，避免在 data 层拼接最终展示串。
 */
sealed class UpdateError : Message {

    data class NetworkError(val detail: String? = null) : UpdateError() {
        override val text: UiText =
            uiTextDynamicOr(detail, R.string.update_error_network)
    }

    data class UnknownError(
        override val text: UiText,
        val code: Int = -1,
        val throwable: Throwable? = null
    ) : UpdateError()

    /** Mirrorchyan业务错误 */
    sealed class MirrorchyanBizError(
        override val text: UiText
    ) : UpdateError() {
        // 403 - CDK
        data object KeyExpired :
            MirrorchyanBizError(uiTextOf(R.string.update_error_key_expired))

        data object KeyInvalid :
            MirrorchyanBizError(uiTextOf(R.string.update_error_key_invalid))

        data object ResourceQuotaExhausted :
            MirrorchyanBizError(uiTextOf(R.string.update_error_quota_exhausted))

        data object KeyMismatched :
            MirrorchyanBizError(uiTextOf(R.string.update_error_key_mismatched))

        data object KeyBlocked :
            MirrorchyanBizError(uiTextOf(R.string.update_error_key_blocked))

        // 404
        data object ResourceNotFound :
            MirrorchyanBizError(uiTextOf(R.string.update_error_resource_not_found))

        // 400
        data object InvalidOs :
            MirrorchyanBizError(uiTextOf(R.string.update_error_invalid_os))

        data object InvalidArch :
            MirrorchyanBizError(uiTextOf(R.string.update_error_invalid_arch))

        data object InvalidChannel :
            MirrorchyanBizError(uiTextOf(R.string.update_error_invalid_channel))

    }

    data object CdkRequired : UpdateError() {
        override val text: UiText = uiTextOf(R.string.update_error_cdk_required)
    }

    companion object {
        fun fromCode(bizCode: Int, message: String?, throwable: Throwable? = null): UpdateError =
            when (bizCode) {
                7001 -> KeyExpired
                7002 -> KeyInvalid
                7003 -> ResourceQuotaExhausted
                7004 -> KeyMismatched
                7005 -> KeyBlocked
                8001 -> ResourceNotFound
                8002 -> InvalidOs
                8003 -> InvalidArch
                8004 -> InvalidChannel
                500 -> UnknownError(
                    uiTextOf(R.string.update_error_service_unavailable),
                    bizCode,
                    throwable
                )

                -1 -> UnknownError(
                    uiTextOf(R.string.update_error_empty_data),
                    bizCode,
                    throwable
                )

                else -> UnknownError(
                    uiTextDynamicOr(message, R.string.update_error_unknown),
                    bizCode,
                    throwable
                )
            }
    }
}
