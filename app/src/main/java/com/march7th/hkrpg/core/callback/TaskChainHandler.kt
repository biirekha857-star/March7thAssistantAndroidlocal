package com.march7th.hkrpg.core.callback

import android.content.Context
import com.alibaba.fastjson2.JSONObject
import com.march7th.hkrpg.R
import com.march7th.hkrpg.data.model.LogLevel
import com.march7th.hkrpg.data.model.TaskTypeInfo
import com.march7th.hkrpg.data.preferences.TaskChainState
import com.march7th.hkrpg.domain.service.FightDropsRefresher
import com.march7th.hkrpg.domain.service.TaskNotificationCenter
import com.march7th.hkrpg.domain.service.CoreSessionLogger
import com.march7th.hkrpg.utils.i18n.resolve
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import com.march7th.hkrpg.domain.service.AchievementReporter

/**
 * 处理 TaskChain 级别回调（msg 10000-10004 + AllTasksCompleted=3）
 */
class TaskChainHandler(
    applicationContext: Context,
    private val sessionLogger: CoreSessionLogger,
    private val statusTracker: TaskChainStatusTracker,
    private val notificationCenter: TaskNotificationCenter,
    private val subTaskHandler: SubTaskHandler,
    private val taskChainState: TaskChainState,
    private val achievementReporter: AchievementReporter,
    private val dropsRefresher: FightDropsRefresher,
) {
    // 回调路径用于 suspend 的 TaskChainState 更新；独立于任一生命周期
    private val callbackScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val resources = applicationContext.resources
    private val packageName = applicationContext.packageName
    private val appContext = applicationContext

    /**
     * TaskChainStart (10001): 任务链开始
     */
    fun onTaskChainStart(details: JSONObject) {
        val taskId = details.getIntValue("taskid", 0)
        subTaskHandler.clearThemeTarget(taskId)
        statusTracker.updateStatus(taskId, TaskRunStatus.IN_PROGRESS)

        refreshDropsIfNeeded(taskId)

        val taskName = resolveTaskName(details)
        sessionLogger.append("${str("StartTask")}$taskName", LogLevel.TRACE)
    }

    private fun clearSessionScopedState() {
        statusTracker.clear()
        dropsRefresher.clear()
    }

    /**
     * 本轮主任务队列出错的任务名，与 resolveTaskName 同源；statusTracker 已按 taskId 记过，不另起登记
     * slot 由 Analyze 注入，链外路径（工具箱 / 作业 / 牛杂）为 null，各有独立归属，不计入
     */
    private fun failedTaskNames(): List<String> =
        statusTracker.tasks.value
            .filter { it.slot != null && it.status == TaskRunStatus.ERROR }
            .map { it.logName?.resolve(appContext) ?: str(it.taskChain) }

    /**
     * 把内核传回的任务类型解析成显示名。
     *
     * ## ⚠️ 必须先查崩铁任务表，再退回 `maa_` 前缀
     *
     * 内核回调里的 `taskchain` 是**任务类型字符串**（`StartUp` / `Mail` /
     * `Power` / `CloseDown`），外壳要把它翻成中文显示。
     *
     * 原来只有一条路径：[str] 拼 `maa_` 前缀查资源。但那套前缀是
     * **明日方舟**的命名空间，于是撞车了：
     *
     * ```
     * str("Power") -> 查 maa_power -> 命中「发电站」（方舟基建任务）
     * ```
     *
     * 结果崩铁的「清体力」在日志里显示成「发电站」，而且因为
     * [CoreStringRes] 把结果缓存了，用户会一直看到这个名字。
     *
     * 所以现在优先用 [TaskTypeInfo]（崩铁任务表）解析，
     * 命中就返回；没命中才退回原来的 `maa_` 方案（兼容旧代码路径）。
     */
    private fun resolveTaskName(details: JSONObject): String {
        statusTracker.getLogName(details.getIntValue("taskid", 0))?.resolve(appContext)
            ?.let { return it }

        val chain = details.getString("taskchain") ?: "Unknown"

        // ① 崩铁任务：按 taskType 精确匹配
        TaskTypeInfo.entries
            .firstOrNull { it.taskType == chain }
            ?.let { return it.defaultName(appContext) }

        // ② 兜底：旧的 `maa_` 前缀方案（方舟遗留路径）
        return str(chain)
    }

    private fun refreshDropsIfNeeded(taskId: Int) {
        val outcome = dropsRefresher.onTaskStarted(taskId)
        val (logLabel, applied) = when (outcome) {
            FightDropsRefresher.RefreshOutcome.Skipped -> return
            is FightDropsRefresher.RefreshOutcome.Sufficient -> {
                sessionLogger.append(
                    appContext.getString(
                        R.string.runlog_depot_plan_inventory_enough,
                        outcome.logLabel,
                        outcome.dropName,
                        outcome.current,
                        outcome.target,
                    ),
                    LogLevel.INFO,
                )
                outcome.logLabel to outcome.applied
            }

            is FightDropsRefresher.RefreshOutcome.Updated -> {
                sessionLogger.append(
                    appContext.getString(
                        R.string.runlog_depot_plan_inventory_insufficient,
                        outcome.logLabel,
                        outcome.dropName,
                        outcome.current,
                        outcome.target,
                        outcome.need,
                    ),
                    LogLevel.INFO,
                )
                outcome.logLabel to outcome.applied
            }

            is FightDropsRefresher.RefreshOutcome.SanityInsufficient -> {
                sessionLogger.append(
                    appContext.getString(
                        R.string.runlog_depot_plan_sanity_insufficient,
                        outcome.logLabel,
                        outcome.estimatedSanity,
                        outcome.apCost,
                    ),
                    LogLevel.INFO,
                )
                outcome.logLabel to outcome.applied
            }
        }
        if (!applied) {
            sessionLogger.append(
                appContext.getString(R.string.runlog_depot_set_params_failed, logLabel),
                LogLevel.WARNING,
            )
        }
    }

    /**
     * TaskChainError (10000): 任务链错误
     */
    fun onTaskChainError(details: JSONObject) {
        val taskId = details.getIntValue("taskid", 0)
        subTaskHandler.clearThemeTarget(taskId)
        statusTracker.updateStatus(taskId, TaskRunStatus.ERROR)

        val taskchain = details.getString("taskchain") ?: "Unknown"
        val taskName = resolveTaskName(details)
        // details.error 为 Core 侧 TaskExceptionKind 名（如 OutOfMemory），普通识别错误无此字段
        val kind = exceptionKind(details)

        // ⚠️ **必须把失败原因写进日志**。
        //
        // 原来这里只输出「任务出错:清体力」，把 details.error 里的具体
        // 原因（比如"没找到副本类型"、"无法开始挑战"）整个丢掉了 ——
        // 用户拿着日志根本看不出卡在哪一步，只能靠猜。
        //
        // 崩铁内核（StarRailLocalEngine）会把每一步的失败原因放进
        // `error`，现在原样展示出来。
        val message = when {
            kind == "OutOfMemory" -> str("OutOfMemoryError", taskName)
            !kind.isNullOrBlank() && kind != "NONE" ->
                "${str("TaskError")}$taskName —— $kind"
            else -> "${str("TaskError")}$taskName"
        }
        sessionLogger.append(message, LogLevel.ERROR)
        notificationCenter.notifyTaskError(taskName)
        callbackScope.launch {
        }
    }

    /**
     * TaskChainCompleted (10002): 任务链完成
     */
    fun onTaskChainCompleted(details: JSONObject) {
        val taskId = details.getIntValue("taskid", 0)
        subTaskHandler.clearThemeTarget(taskId)
        statusTracker.updateStatus(taskId, TaskRunStatus.COMPLETED)
        dropsRefresher.onTaskCompleted(taskId)

        val taskchain = details.getString("taskchain") ?: "Unknown"
        val taskName = resolveTaskName(details)
        sessionLogger.append("${str("CompleteTask")}$taskName", LogLevel.SUCCESS)

        if (taskchain == "Infrast") {
            val nodeId = statusTracker.getNodeId(taskId)
            if (nodeId != null) {
                callbackScope.launch {
                    val result = taskChainState.incrementCustomInfrastPlanSelect(nodeId)
                        ?: return@launch
                    val (newIndex, newName) = result
                    sessionLogger.append(
                        str("CustomInfrastPlanIndexAutoSwitch"),
                        LogLevel.MESSAGE
                    )
                    sessionLogger.append(
                        newName ?: "Plan ${('A' + newIndex)}",
                        LogLevel.MESSAGE
                    )
                }
            }
        }
    }

    /**
     * TaskChainExtraInfo (10003): 任务链额外信息
     */
    fun onTaskChainExtraInfo(details: JSONObject) {
        when (val what = details.getString("what")) {
            "RoutingRestart" -> {
                val why = details.getString("why")
                if (why == "TooManyBattlesAhead") {
                    val cost = details.getString("node_cost") ?: "?"
                    sessionLogger.append(
                        str("RoutingRestartTooManyBattles", cost),
                        LogLevel.WARNING
                    )
                } else {
                    Timber.d("TaskChainExtraInfo RoutingRestart with unhandled why=$why")
                }
            }

            else -> {
                Timber.d("TaskChainExtraInfo unhandled what=$what, details=$details")
            }
        }
    }

    /**
     * TaskChainStopped (10004): 任务链停止（用户手动停止）
     */
    fun onTaskChainStopped() {
        clearSessionScopedState()
        sessionLogger.append(str("TaskStopped"), LogLevel.INFO)
        achievementReporter.reportTaskStopped()
        callbackScope.launch {
        }
    }

    /**
     * AllTasksCompleted (3): 所有任务完成
     * 附带任务总耗时和理智恢复时间信息
     */
    fun onAllTasksCompleted(asStopped: Boolean = false) {
        // 名单挂在 statusTracker 上，clearSessionScopedState 会清掉，先取快照
        val failedTaskNames = failedTaskNames()
        clearSessionScopedState()

        // 手动停止本就不是「全部完成」，标题不跟着改，但出错清单仍然给出
        val hasTaskErrors = failedTaskNames.isNotEmpty()
        val sb = StringBuilder(
            str(if (hasTaskErrors && !asStopped) "TaskCompletedWithErrors" else "AllTasksComplete", "")
        )

        // 任务总耗时
        val startMillis = sessionLogger.sessionStartTimeMillis
        if (startMillis > 0) {
            val elapsed = System.currentTimeMillis() - startMillis
            achievementReporter.reportAllTasksCompleted(elapsed)
            val h = elapsed / 3_600_000
            val m = (elapsed % 3_600_000) / 60_000
            val s = (elapsed % 60_000) / 1_000
            val timeStr = buildString {
                if (h > 0) append("${h}h ")
                if (h > 0 || m > 0) append("${m}m ")
                append("${s}s")
            }
            sb.append(" ($timeStr)")
        } else {
            achievementReporter.reportAllTasksCompleted()
        }

        // 理智恢复时间
        val snapshot = subTaskHandler.lastSanitySnapshot
        if (snapshot != null) {
            sb.append("\n")
            sb.append(str("CurrentSanity", snapshot.current, snapshot.max))

            if (snapshot.current < snapshot.max) {
                val recoveryMinutes = (snapshot.max - snapshot.current) * 6L
                val recoveryMillis = snapshot.reportTimeMillis + recoveryMinutes * 60_000
                val recoveryTime = Instant.ofEpochMilli(recoveryMillis)
                    .atZone(ZoneId.systemDefault())
                    .toLocalDateTime()
                val remainMinutes = ((recoveryMillis - System.currentTimeMillis()) / 60_000)
                    .coerceAtLeast(0)
                val rh = remainMinutes / 60
                val rm = remainMinutes % 60
                val remainStr = buildString {
                    if (rh > 0) append("${rh}h ")
                    append("${rm}m")
                }

                sb.append("\n")
                sb.append(
                    str(
                        "SanityRecovery",
                        recoveryTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")),
                        remainStr
                    )
                )
                // TODO: 延迟定时提醒（理智恢复前 6 分钟推送通知）
            }
        }

        val message = sb.toString()
        if (hasTaskErrors && !asStopped) {
            // 只标红标题行，理智报告留在下一段默认色（对齐上游 SplitTaskCompletionLog）
            sessionLogger.append(message.substringBefore('\n'), LogLevel.ERROR)
            message.substringAfter('\n', "")
                .takeIf { it.isNotBlank() }
                ?.let { sessionLogger.append(it, LogLevel.MESSAGE) }
        } else {
            sessionLogger.append(message, if (asStopped) LogLevel.INFO else LogLevel.SUCCESS)
        }

        val errorSummary = failedTaskNames
            .takeIf { it.isNotEmpty() }
            ?.joinToString("\n", prefix = str("TaskErrorSummaryTitle") + "\n")
        errorSummary?.let { sessionLogger.append(it, LogLevel.ERROR) }
        if (!asStopped) {
            // 出错时保留完成上下文（用时/理智）再附清单，避免通知正文只剩清单
            notificationCenter.notifyAllTasksCompleted(
                errorSummary?.let { "$message\n$it" } ?: message
            )
        }

        callbackScope.launch {
            taskChainState.clearRecruitUseExpeditedFlags()
        }
    }

    /** Core 写在 details.details.error；WPF 读的是根级 error，两处都兼容 */
    private fun exceptionKind(details: JSONObject): String? =
        details.getJSONObject("details")?.getString("error") ?: details.getString("error")

    /**
     * 辅助方法：获取 i18n 字符串（无参数）
     */
    private fun str(key: String): String {
        return CoreStringRes.getString(resources, packageName, key)
    }

    /**
     * 辅助方法：获取 i18n 字符串（带参数）
     */
    private fun str(key: String, vararg args: Any): String {
        return CoreStringRes.getString(resources, packageName, key, *args)
    }
}
