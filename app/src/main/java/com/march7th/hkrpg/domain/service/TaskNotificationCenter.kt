package com.march7th.hkrpg.domain.service

import android.content.Context
import com.march7th.hkrpg.R
import com.march7th.hkrpg.data.notification.NotificationSettingsManager
import com.march7th.hkrpg.domain.notification.LiveCategory
import com.march7th.hkrpg.domain.notification.LiveNotifyIds
import com.march7th.hkrpg.domain.notification.LiveSession
import com.march7th.hkrpg.domain.notification.LiveSessionCoordinator
import kotlinx.coroutines.flow.StateFlow

/** 聚合实况结果、队列卡片和外推 */
class TaskNotificationCenter(
    context: Context,
    private val eventNotifier: CoreEventNotifier,
    private val externalService: ExternalNotificationService,
    private val settings: NotificationSettingsManager,
    private val liveCoordinator: LiveSessionCoordinator,
) {
    private val appContext = context.applicationContext

    fun notifyAllTasksCompleted(summary: String) {
        val title = appContext.getString(R.string.notification_event_all_tasks_completed)
        publishResult(title, summary, timeoutSec = 120)
        if (settings.sendOnComplete.value) {
            externalService.sendWithLogs("所有任务已完成", summary)
        }
    }

    fun notifyTaskStopped() {
        val title = appContext.getString(R.string.notification_event_task_stopped)
        val text = appContext.getString(R.string.notification_event_task_stopped_text)
        publishResult(title, text, timeoutSec = 15)
    }

    fun notifyTaskError(taskName: String) {
        eventNotifier.notifyTaskError(taskName)
        pushExternal(settings.sendOnError, "任务出错", "任务链 $taskName 执行失败")
    }

    fun notifyStartFailed(message: String) {
        val title = appContext.getString(R.string.notification_event_task_error)
        publishResult(title, message, timeoutSec = 30, isError = true)
    }

    /** [sendExternal] 掉线这类要外推的场景置真，走任务出错开关 */
    fun notifySubTaskFailure(message: String, sendExternal: Boolean = false) {
        eventNotifier.notifySubTaskFailure(message)
        if (sendExternal) {
            pushExternal(settings.sendOnError, message, message)
        }
    }

    fun notifyHandoverRequired(title: String, content: String) {
        eventNotifier.notifyEvent(title, content)
        pushExternal(settings.sendOnComplete, title, content)
    }

    /**
     * 画面异常自动修复提醒 —— **提醒，不是报错**。
     *
     * ## 为什么单独开一个方法
     *
     * 这类问题（部分 ROM 把游戏的 size-compat 缩放系数和窗口尺寸算叉了，
     * 导致画面被放大裁切、UI 整个跑到框外）**引擎能自愈**：
     * 触发一次主屏往返，逼系统重算布局，通常 2 秒内恢复。
     *
     * 既然自愈了，就不该走 [notifyTaskError] —— 那会把任务标成失败，
     * 用户看到红的会以为白跑了。也不该只写一行 log：这是**设备/ROM 级**
     * 的兼容问题，不主动说一声，用户永远不知道，我们也就永远收不到反馈。
     *
     * 所以用 `isError = false` 的浮出卡片：会弹一下、60 秒后自动消失，
     * 点开能看到完整说明。
     *
     * @param repaired 自愈是否成功。失败时措辞改为"可能失败"，但**仍不标红** ——
     *   真正的失败会由启动超时那条链路报出来，不必在这里重复报错。
     */
    fun notifyScreenAnomaly(repaired: Boolean, waitedSec: Int) {
        val titleRes =
            if (repaired) R.string.notification_event_screen_anomaly
            else R.string.notification_event_screen_anomaly_failed
        val textRes =
            if (repaired) R.string.notification_event_screen_anomaly_text
            else R.string.notification_event_screen_anomaly_failed_text
        publishResult(
            title = appContext.getString(titleRes),
            text = appContext.getString(textRes, waitedSec),
            timeoutSec = 60,
        )
    }

    fun notifyRecruitSpecialTag(tag: String) {
        eventNotifier.notifyRecruitSpecialTag(tag)
    }

    fun notifyRecruitRobotTag(tag: String) {
        eventNotifier.notifyRecruitRobotTag(tag)
    }

    fun notifyRecruitHighRarity(level: Int) {
        eventNotifier.notifyRecruitHighRarity(level)
    }

    fun notifyServiceDied() {
        val title = appContext.getString(R.string.notification_event_service_died)
        val text = appContext.getString(R.string.notification_event_service_died_text)
        // 服务可能在空闲期挂掉，不能占用本轮运行的一次性结果闸门
        liveCoordinator.publishEvent(resultSession(title, text, timeoutSec = 180, isError = true))
        pushExternal(settings.sendOnServiceDied, "服务异常", "任务服务意外终止")
    }

    /** 按对应开关外推 */
    private fun pushExternal(gate: StateFlow<Boolean>, title: String, content: String) {
        if (gate.value) {
            externalService.send(title, content)
        }
    }

    private fun publishResult(
        title: String,
        text: String,
        timeoutSec: Int,
        isError: Boolean = false,
    ) {
        liveCoordinator.publishResult(
            liveCoordinator.currentToken(),
            resultSession(title, text, timeoutSec, isError),
        )
    }

    private fun resultSession(
        title: String,
        text: String,
        timeoutSec: Int,
        isError: Boolean,
    ) = LiveSession(
        sessionId = LiveNotifyIds.RESULT_SESSION,
        category = LiveCategory.RESULT,
        title = title,
        text = text,
        capsuleText = title,
        // 不设 ongoing：Android 13 及以下划不掉，进程被杀后会一直挂着
        ongoing = false,
        firstFloat = true,
        timeoutSec = timeoutSec,
        isError = isError,
    )
}
