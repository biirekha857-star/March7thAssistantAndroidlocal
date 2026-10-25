package com.march7th.hkrpg.core.callback

import android.content.Context
import android.content.res.Resources
import com.alibaba.fastjson2.JSONObject
import com.march7th.hkrpg.R
import com.march7th.hkrpg.data.achievement.AchievementRepository
import com.march7th.hkrpg.data.model.LogLevel
import com.march7th.hkrpg.data.preferences.TaskChainState
import com.march7th.hkrpg.data.repository.DepotRepository
import com.march7th.hkrpg.data.resource.ActivityManager
import com.march7th.hkrpg.data.resource.ResourceDataManager
import com.march7th.hkrpg.domain.service.TaskNotificationCenter
import com.march7th.hkrpg.domain.service.CoreSessionLogger
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Before
import org.junit.Test

/** 上游 v6.18.0-beta.1 黑流零件箱清理回调，对齐 WPF RoguelikeSettingsUserControlModel */
class SubTaskHandlerBlackFlowInventoryCleanupTest {

    private val pkg = "com.march7th.hkrpg"
    private val resources: Resources = mockk()
    private val context: Context = mockk {
        every { resources } returns this@SubTaskHandlerBlackFlowInventoryCleanupTest.resources
        every { packageName } returns pkg
    }
    private val sessionLogger: CoreSessionLogger = mockk(relaxed = true)

    private val handler = SubTaskHandler(
        applicationContext = context,
        statusTracker = mockk(relaxed = true),
        sessionLogger = sessionLogger,
        copilotRuntimeStateStore = mockk(relaxed = true),
        resourceDataManager = mockk<ResourceDataManager>(relaxed = true),
        toolboxResultCollector = mockk(relaxed = true),
        notificationCenter = mockk<TaskNotificationCenter>(relaxed = true),
        chainState = mockk<TaskChainState>(relaxed = true),
        activityManager = mockk<ActivityManager>(relaxed = true),
        achievementRepository = mockk<AchievementRepository>(relaxed = true),
        depotRepository = mockk<DepotRepository>(relaxed = true),
    )

    @Before
    fun setUp() {
        every { resources.getString(R.string.blackflow_inventory_cleanup_started) } returns "零件箱超载，开始清理"
        every { resources.getString(R.string.blackflow_inventory_cleanup_discarded, *anyVararg()) } answers {
            "已丢弃零件：${secondArg<Array<Any>>()[0]}"
        }
        every { resources.getString(R.string.blackflow_inventory_cleanup_completed) } returns "零件箱清理完成"
        every { resources.getString(R.string.blackflow_inventory_cleanup_failed) } returns "零件箱清理失败"
    }

    private fun cleanup(details: JSONObject) = handler.onSubTaskExtraInfo(
        JSONObject.of("taskchain", "Roguelike", "what", "BlackFlowInventoryCleanup", "details", details)
    )

    @Test
    fun started_logsWarning() {
        cleanup(JSONObject.of("status", "started"))
        verify { sessionLogger.append("零件箱超载，开始清理", LogLevel.WARNING) }
    }

    @Test
    fun discarded_logsPartName() {
        cleanup(JSONObject.of("status", "discarded", "name", "轴承"))
        verify { sessionLogger.append("已丢弃零件：轴承", LogLevel.INFO) }
    }

    @Test
    fun completed_logsSuccess() {
        cleanup(JSONObject.of("status", "completed"))
        verify { sessionLogger.append("零件箱清理完成", LogLevel.SUCCESS) }
    }

    @Test
    fun failed_logsError() {
        cleanup(JSONObject.of("status", "failed"))
        verify { sessionLogger.append("零件箱清理失败", LogLevel.ERROR) }
    }

    @Test
    fun unknownStatus_logsNothing() {
        cleanup(JSONObject.of("status", "something_new"))
        verify(exactly = 0) { sessionLogger.append(any<String>(), any()) }
    }
}
