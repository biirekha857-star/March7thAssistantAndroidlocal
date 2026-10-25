package com.march7th.hkrpg.data.notification.provider

interface NotificationProvider {
    val id: String
    suspend fun send(title: String, content: String): NotificationSendResult
}
