package com.march7th.hkrpg.domain.usecase

import com.march7th.hkrpg.constant.Packages
import com.march7th.hkrpg.data.api.RedeemCodeService
import com.march7th.hkrpg.data.model.CollectingPreflightLogSink
import com.march7th.hkrpg.data.model.FightConfig
import com.march7th.hkrpg.data.model.LogLevel
import com.march7th.hkrpg.data.model.RedeemTaskConfig
import com.march7th.hkrpg.data.model.StartUpTaskConfig
import com.march7th.hkrpg.data.model.TaskChainNode
import com.march7th.hkrpg.data.model.TaskParamContext
import com.march7th.hkrpg.data.preferences.AppSettingsManager
import com.march7th.hkrpg.data.preferences.TaskChainState
import com.march7th.hkrpg.data.repository.DepotRepository
import com.march7th.hkrpg.data.repository.OperBoxRepository
import com.march7th.hkrpg.data.resource.ActivityManager
import com.march7th.hkrpg.data.resource.ItemHelper
import com.march7th.hkrpg.data.resource.ResourceDataManager
import com.march7th.hkrpg.data.resource.ServerTimezone
import com.march7th.hkrpg.domain.models.MallCreditFightAvailability
import com.march7th.hkrpg.domain.models.PlanSideTask
import com.march7th.hkrpg.domain.models.ReportOptions
import com.march7th.hkrpg.domain.service.FightDropsRefresher
import com.march7th.hkrpg.core.task.CoreTaskParams
import com.march7th.hkrpg.core.task.CoreTaskType
import com.march7th.hkrpg.domain.models.TaskFallbackChain
import com.march7th.hkrpg.core.task.TaskSlot
import com.march7th.hkrpg.utils.i18n.UiText
import kotlinx.coroutines.flow.first
import timber.log.Timber
import java.time.DayOfWeek

class AnalyzeTaskChainUseCase(
    private val taskChainState: TaskChainState,
    private val resourceDataManager: ResourceDataManager,
    private val activityManager: ActivityManager,
    private val depotRepository: DepotRepository,
    private val operBoxRepository: OperBoxRepository,
    private val itemHelper: ItemHelper,
    private val dropsRefresher: FightDropsRefresher,
    private val appSettingsManager: AppSettingsManager,
    /** 兑换码执行前现拉一次站点清单，用户不用先去面板点「同步」。 */
    private val redeemCodeService: RedeemCodeService,
    private val relocatePath: (String) -> String = { it },
) {
    /** 先等 depot/operBox 分片装载；config 的 toTaskParams 仍是非 suspend。 */
    suspend operator fun invoke(chain: List<TaskChainNode>): AnalyzeTaskChainResult {
        depotRepository.isLoaded.first { it }
        operBoxRepository.isLoaded.first { it }

        val nodes = chain.filter { it.enabled }.sortedBy { it.order }
        if (nodes.isEmpty()) {
            return AnalyzeTaskChainResult.Blocked(
                reason = AnalyzeTaskChainFailureReason.NO_TASK_SELECTED,
            )
        }

        // ## 删掉了「客户端类型冲突」检查
        //
        // 那个检查靠收集各节点的 `WakeUpConfig.clientType` 来判断 ——
        // 「开始唤醒」是方舟时代的任务，类型已从 [TaskTypeInfo] 移除，
        // `as? WakeUpConfig` 恒为 null，于是这里永远返回空列表、永不冲突。
        //
        // 而客户端类型现在本来就是**全局单值**
        // （设置页「客户端类型」→ `AppSettings.clientType`），
        // 不存在节点之间选得不一样的场景，这个检查失去了存在的前提。

        val info = MallCreditFightAvailability.resolve(nodes, activityManager)

        dropsRefresher.clear()

        val clientType = taskChainState.clientType
        val report = ReportOptions.of(
            clientType = clientType,
            reportToPenguin = appSettingsManager.reportToPenguin.value,
            reportToYituliu = appSettingsManager.reportToYituliu.value,
            penguinId = appSettingsManager.penguinId.value,
        )
        val log = CollectingPreflightLogSink()
        val fallbacks = mutableMapOf<TaskSlot, TaskFallbackChain>()
        val sideTasks = mutableListOf<PlanSideTask>()

        val serverDayOfWeek = ServerTimezone.getYjDayOfWeek(clientType)

        // ## 兑换码：执行前现拉一次站点清单
        //
        // 面板里保存的 `codes` 只是快照，可能已经放了一周。这里在展开任务前
        // 用一次带缓存的拉取（30 分钟内直接用本地文件）把快照换成最新的：
        // 用户加了兑换码任务就能直接跑，不必先去面板手动同步。
        //
        // 拉取失败（断网 / 站点没部署）时保留配置里原来的快照 ——
        // 清空会导致「有网时能兑、断网时一个都不兑」这种莫名其妙的行为。
        val siteCodes = if (nodes.any { it.config is RedeemTaskConfig }) {
            runCatching { redeemCodeService.load() }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
        if (siteCodes.isNotEmpty()) {
            Timber.tag("AnalyzeTaskChain").i("兑换码：站点清单 %d 个码", siteCodes.size)
        }

        val expanded = nodes.flatMap { node ->
            if (isSkippedByWeeklySchedule(node, serverDayOfWeek)) {
                return@flatMap emptyList()
            }
            val ctx = TaskParamContext(
                node = node,
                clientType = clientType,
                chainAllowsCreditFight = info.isAvailable,
                itemHelper = itemHelper,
                activityManager = activityManager,
                depotRepository = depotRepository,
                operBoxRepository = operBoxRepository,
                resourceDataManager = resourceDataManager,
                dropsRefresher = dropsRefresher,
                logSink = log,
                report = report,
                operBoxUseYituliuApi = appSettingsManager.operBoxUseYituliuApi.value,
                relocatePath = relocatePath,
            )
            val nodeConfig = when (val c = node.config) {
                is RedeemTaskConfig ->
                    if (siteCodes.isEmpty()) c else c.copy(codes = siteCodes.map { it.code })
                else -> c
            }
            val expandedNode = nodeConfig.toTaskParams(ctx).mapIndexed { index, task ->
                task.copy(slot = TaskSlot(node.id, index))
            }
            ctx.fallbacks.forEach { (index, candidates) ->
                fallbacks[TaskSlot(node.id, index)] = candidates
            }
            sideTasks += ctx.sideTasks
            expandedNode
        }
        val params = dropAdjacentDuplicateDepot(expanded)
        val logs = log.entries

        // 只剩旁路任务也算可执行，由启动侧走不起 Core 的轻量路径
        if (params.isEmpty() && sideTasks.isEmpty()) {
            return AnalyzeTaskChainResult.Blocked(
                reason = AnalyzeTaskChainFailureReason.NO_EXECUTABLE_TASKS,
                logs = logs,
            )
        }

        return AnalyzeTaskChainResult.Ready(
            TaskChainPlan(
                nodes = nodes,
                params = params,
                clientType = clientType,
                gamePackageName = Packages[clientType],
                // ## 修：原来认的是 `WakeUpConfig.startGameEnabled`
                //
                // 「开始唤醒」任务类型早已删除，启动游戏现在用 [StartUpTaskConfig]，
                // 所以 `as? WakeUpConfig` 恒为 null、`launchesGame` 恒为 false ——
                // 后果是任务链里明明勾着「启动游戏」，点开始还是弹
                // 「检测到游戏未运行」。
                //
                // 新模型没有"启用但不启动"的开关，节点存在即代表会启动游戏。
                launchesGame = nodes.any { it.config is StartUpTaskConfig },
                logs = logs,
                fallbacks = fallbacks,
                // 开了两个同类节点会攒出两份，并发进去只有一份能拿到锁
                sideTasks = sideTasks.distinct(),
            )
        )
    }

    /** 去掉相邻重复 DEPOT；中间有其它任务则保留（库存可能已变）。 */
    private fun dropAdjacentDuplicateDepot(params: List<CoreTaskParams>): List<CoreTaskParams> =
        params.filterIndexed { index, task ->
            index == 0 ||
                    task.type != CoreTaskType.DEPOT ||
                    params[index - 1].type != CoreTaskType.DEPOT
        }

    private fun isSkippedByWeeklySchedule(
        node: TaskChainNode,
        serverDayOfWeek: DayOfWeek
    ): Boolean {
        val config = node.config
        if (config is FightConfig && config.useWeeklySchedule) {
            if (config.weeklySchedule[serverDayOfWeek.name] == false) {
                Timber.d("WeeklySchedule: skip node '%s' on %s", node.name, serverDayOfWeek)
                return true
            }
        }
        return false
    }


}

data class TaskChainPlan(
    val nodes: List<TaskChainNode>,
    val params: List<CoreTaskParams>,
    val clientType: String,
    val gamePackageName: String?,
    val launchesGame: Boolean,
    val gameAliveBeforeStart: Boolean? = null,
    /** 预检日志，会话开始后由 Composition 回放。 */
    val logs: List<Pair<UiText, LogLevel>> = emptyList(),
    /** 任务位 → 后备候选（按序）；主任务 append 失败时才用到，目前只有库存保持「仅第一个」会产生 */
    val fallbacks: Map<TaskSlot, TaskFallbackChain> = emptyMap(),
    val sideTasks: List<PlanSideTask> = emptyList(),
)

enum class AnalyzeTaskChainFailureReason {
    NO_TASK_SELECTED,
    CONFLICTING_CLIENT_TYPES,
    NO_EXECUTABLE_TASKS,
}

sealed interface AnalyzeTaskChainResult {
    data class Ready(val plan: TaskChainPlan) : AnalyzeTaskChainResult

    data class Blocked(
        val reason: AnalyzeTaskChainFailureReason,
        val clientTypes: List<String> = emptyList(),
        val logs: List<Pair<UiText, LogLevel>> = emptyList(),
    ) : AnalyzeTaskChainResult
}
