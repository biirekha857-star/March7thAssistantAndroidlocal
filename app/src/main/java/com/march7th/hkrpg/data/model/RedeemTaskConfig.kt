package com.march7th.hkrpg.data.model

import com.march7th.hkrpg.core.task.CoreTaskParams
import com.march7th.hkrpg.core.task.CoreTaskType
import com.march7th.hkrpg.utils.i18n.UiText
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 「兑换码」任务参数。
 *
 * 兑换码**不在内核里拉取**，而是由主进程（[com.march7th.hkrpg.data.api.RedeemCodeService]）
 * 从站点拿到、写进这里的 [codes]，执行时原样传给内核。
 *
 * ## 为什么这么分层
 *
 * 内核跑在特权进程，网络、缓存目录、语言环境都和普通 App 不一样；
 * 而"今天有哪些码、哪些过期了"是个纯数据问题，放在有 UI 的一侧更合适：
 *
 * - 用户在配置面板里能**看见**今天要兑哪几个码，可以手动删掉不想兑的；
 * - 站点临时挂了也不影响执行（码已经在参数里）；
 * - 内核只管"点开兑换码界面、输入、确认"，不碰网络。
 */
@Serializable
data class RedeemTaskConfig(
    /** 固定 `"Redeem"`（与 [TaskTypeInfo.REDEEM.taskType] 对齐）。 */
    @SerialName("taskType")
    val taskType: String = "Redeem",

    /** 本次要兑换的码（通常来自站点 `codes.md` 的未过期项）。 */
    val codes: List<String> = emptyList(),

    /**
     * 用户自己加的码。
     *
     * 单独一个字段，不并进 [codes]：站点的快照每次执行前都会刷新，
     * 混在一起会被覆盖掉。自定义码只受用户自己控制，站点挂了也还在。
     */
    val customCodes: List<String> = emptyList(),

    /**
     * 用户在面板里手动取消勾选的码。
     *
     * 记「不要哪些」而不是「要哪些」：站点每天可能加新码，
     * 记白名单的话新码永远进不来。
     */
    val disabledCodes: List<String> = emptyList(),

    /**
     * 是否跳过本地记录里已经成功兑过的码。
     *
     * 兑换码是一次性的，重复输入只会拿到「该兑换码已被使用」。
     * 默认开：同一个码兑成功后写进 DataStore，下次直接不发。
     */
    val skipUsed: Boolean = true,
) : TaskParamProvider {

    /** 站点快照 + 自定义码，去掉取消勾选的和重复的。 */
    fun effectiveCodes(): List<String> {
        val off = disabledCodes.map { it.trim().uppercase() }.toSet()
        return (codes + customCodes)
            .map { it.trim().uppercase() }
            .filter { it.isNotEmpty() && it !in off }
            .distinct()
    }

    override fun toTaskParams(ctx: TaskParamContext): List<CoreTaskParams> {
        val coreType = CoreTaskType.fromValue(taskType)
        if (coreType == null) {
            ctx.appendLog(UiText.Dynamic("未知的崩铁任务类型：$taskType"), LogLevel.ERROR)
            return emptyList()
        }
        val effective = effectiveCodes()
        if (effective.isEmpty()) {
            // 区分「站点没给到码」和「用户全取消了」—— 两者在日志里长得一样，
            // 排查时却完全是两码事（前者去看站点，后者去看配置）。
            val reason = if (codes.isEmpty() && customCodes.isEmpty()) {
                "兑换码清单为空：站点 codes.md 没取到（没部署/被换成首页），或码全部已过期；本次跳过"
            } else {
                "所有兑换码都被取消勾选了（站点 ${codes.size} 个 / 自定义 ${customCodes.size} 个）；本次跳过"
            }
            ctx.appendLog(UiText.Dynamic(reason), LogLevel.WARNING)
            return emptyList()
        }

        // ## 用序列化库拼，不再手写字符串
        //
        // 手写版本漏了列表的收尾 `]`，拼出来是
        // `{"codes":["A","B","skipUsed":true}` —— 内核那边按 `"codes":[...]`
        // 找数组，找不到就当成"没有码"，整个任务直接失败。字符串拼接这种
        // 低级错误一旦写进参数，日志里看起来只像"站点没给码"，极难定位。
        val json = buildJsonObject {
            put("codes", buildJsonArray { effective.forEach { add(JsonPrimitive(it)) } })
            put("skipUsed", skipUsed)
        }.toString()

        return listOf(CoreTaskParams(coreType, json))
    }
}
