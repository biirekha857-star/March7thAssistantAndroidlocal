package com.march7th.hkrpg.data.preferences

import com.march7th.hkrpg.data.model.TaskProfile
import com.march7th.hkrpg.data.notification.NotificationSettings
import com.march7th.hkrpg.domain.models.AppSettings
import com.march7th.hkrpg.schedule.model.ScheduleStrategy
import kotlinx.serialization.Serializable

@Serializable
data class ConfigBackup(
    val version: Int = 1,
    val exportedAt: String = "",
    val appSettings: AppSettings,
    val notificationSettings: NotificationSettings,
    val taskProfiles: List<TaskProfile>,
    val activeProfileId: String,
    val scheduleStrategies: List<ScheduleStrategy>
)
