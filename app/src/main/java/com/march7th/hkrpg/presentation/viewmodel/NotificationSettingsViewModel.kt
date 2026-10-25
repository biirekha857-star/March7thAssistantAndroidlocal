package com.march7th.hkrpg.presentation.viewmodel

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.march7th.hkrpg.data.notification.NotificationSettings
import com.march7th.hkrpg.data.notification.NotificationSettingsManager
import com.march7th.hkrpg.data.preferences.AppSettingsManager
import com.march7th.hkrpg.domain.notification.LiveCapability
import com.march7th.hkrpg.domain.notification.LiveSessionCoordinator
import com.march7th.hkrpg.domain.notification.LiveUpdatePublisher
import com.march7th.hkrpg.domain.service.ExternalNotificationService
import com.march7th.hkrpg.manager.PermissionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.march7th.hkrpg.domain.service.AchievementReporter

class NotificationSettingsViewModel(
    private val settingsManager: NotificationSettingsManager,
    private val notificationService: ExternalNotificationService,
    private val achievementReporter: AchievementReporter,
    private val livePublisher: LiveUpdatePublisher,
    private val liveCoordinator: LiveSessionCoordinator,
    private val permissionManager: PermissionManager,
    private val appSettingsManager: AppSettingsManager,
) : ViewModel() {

    companion object {
        val ALL_PROVIDER_IDS = setOf(
            "ServerChan",
            "Telegram",
            "Discord",
            "DingTalk",
            "KOOK",
            "Discord Webhook",
            "SMTP",
            "Bark",
            "Qmsg",
            "Gotify",
            "CustomWebhook",
        )
    }

    val settings: StateFlow<NotificationSettings> = settingsManager.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), NotificationSettings())

    val enabledProviders: StateFlow<Set<String>> = settingsManager.enabledProviderIds
        .map { it.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    val sendOnComplete: StateFlow<Boolean> = settingsManager.sendOnComplete
    val sendOnError: StateFlow<Boolean> = settingsManager.sendOnError
    val sendOnServiceDied: StateFlow<Boolean> = settingsManager.sendOnServiceDied
    val includeLogDetails: StateFlow<Boolean> = settingsManager.includeLogDetails

    private val _liveCapability = MutableStateFlow(livePublisher.capability)
    val liveCapability: StateFlow<LiveCapability> = _liveCapability.asStateFlow()

    val liveIslandXmsfBypass: StateFlow<Boolean> = appSettingsManager.liveIslandXmsfBypass

    init {
        // 旁路开关会改变后端选择，展示方式得跟着落盘值走，不能等下次 onResume
        viewModelScope.launch {
            appSettingsManager.liveIslandXmsfBypass.drop(1).collect {
                _liveCapability.value = livePublisher.capability
            }
        }
    }

    fun refreshLiveCapability() {
        permissionManager.refresh()
        _liveCapability.value = livePublisher.refreshCapability()
    }

    fun setLiveIslandXmsfBypass(enabled: Boolean) {
        viewModelScope.launch { appSettingsManager.setLiveIslandXmsfBypass(enabled) }
    }

    fun requestPostNotifications(context: Context) {
        viewModelScope.launch {
            permissionManager.requestNotification(context)
            refreshLiveCapability()
        }
    }

    fun openAppNotificationSettings(context: Context) {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    fun openPromotedSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= 36) {
            val promoted = Intent(Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val launched = runCatching { context.startActivity(promoted) }.isSuccess
            if (launched) return
        }
        openAppNotificationSettings(context)
    }

    fun sendLiveTest(title: String, content: String) {
        liveCoordinator.publishTest(title, content)
    }

    /**
     * 发**进度型**测试：验证状态栏胶囊 / 流体云的进度渲染。
     *
     * 与 [sendLiveTest]（结果型、一闪而过）互补 —— 只有进度型才能
     * 看出胶囊里的计数、进度条位置、未完成段颜色。
     */
    fun sendLiveProgressTest(title: String, content: String) {
        liveCoordinator.publishProgressTest(title, content)
    }

    fun updateSettings(transform: NotificationSettings.() -> NotificationSettings) {
        viewModelScope.launch {
            val current = settings.value
            settingsManager.updateSettings(current.transform())
        }
    }

    fun toggleProvider(id: String, enabled: Boolean) {
        viewModelScope.launch {
            val current = settings.value
            val providers = current.enabledProviders
                .split(",")
                .filter { it.isNotBlank() }
                .toMutableSet()
            if (enabled) providers.add(id) else providers.remove(id)
            settingsManager.updateSettings(current.copy(enabledProviders = providers.joinToString(",")))
            achievementReporter.reportNotificationProviders(providers, ALL_PROVIDER_IDS)
        }
    }

    fun sendTest(title: String, content: String) {
        notificationService.sendTest(title, content)
    }
}
