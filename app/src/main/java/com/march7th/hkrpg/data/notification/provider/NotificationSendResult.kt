package com.march7th.hkrpg.data.notification.provider

import com.march7th.hkrpg.utils.i18n.UiText

sealed interface NotificationSendResult {
    data object Success : NotificationSendResult
    data class Failed(val message: UiText) : NotificationSendResult
    data class Transient(val message: UiText) : NotificationSendResult
}
