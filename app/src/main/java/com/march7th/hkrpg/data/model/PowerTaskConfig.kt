package com.march7th.hkrpg.data.model

import com.march7th.hkrpg.core.task.CoreTaskParams
import com.march7th.hkrpg.core.task.CoreTaskType
import com.march7th.hkrpg.utils.i18n.UiText
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 崩铁副本类型（清体力用）。
 *
 * 与 M7A `tasks/power/power.py` 的 `instances_power` / `attempts_per_run_max`
 * 两表一一对应。**体力消耗和单次最大连打都必须与游戏内一致** ——
 * 算错了会导致「以为还能刷一轮，结果体力不够」，任务在副本门口空转。
 *
 * | 类型 | 体力/次 | 单次最大连打 |
 * |---|---|---|
 * | 拟造花萼（金） | 10 | 24 |
 * | 拟造花萼（赤） | 10 | 24 |
 * | 凝滞虚影 | 30 | 8 |
 * | 侵蚀隧洞 | 40 | 6 |
 * | 饰品提取 | 40 | 6 |
 * | 历战余响 | 30 | 3 |
 *
 * ## 为什么不做成 enum 存名字
 *
 * `wireName` 是**游戏内 UI 上的中文原文**，后续要用 OCR 去点它。
 * 所以这个字符串必须逐字一致，不能翻译、不能改标点
 * （注意括号是全角 `（）`，不是半角）。
 */
enum class PowerInstance(
    /** 游戏内 UI 上显示的名字（OCR 匹配目标） */
    val wireName: String,
    /** 单次挑战消耗的开拓力 */
    val powerPerRun: Int,
    /** 一次「连续挑战」最多能打几次 */
    val maxAttemptsPerRun: Int,
) {
    CALYX_GOLDEN("拟造花萼（金）", 10, 24),
    CALYX_CRIMSON("拟造花萼（赤）", 10, 24),
    STAGNANT_SHADOW("凝滞虚影", 30, 8),
    CORROSION_CAVERN("侵蚀隧洞", 40, 6),
    ORNAMENT_EXTRACTION("饰品提取", 40, 6),
    ECHO_OF_WAR("历战余响", 30, 3),
    ;

    companion object {
        fun fromWireName(name: String): PowerInstance? =
            entries.firstOrNull { it.wireName == name }

        /** UI 上的默认选项：金花（体力消耗最低、最常用）。 */
        val DEFAULT: PowerInstance = CALYX_GOLDEN
    }
}

/**
 * 清体力任务的参数。
 *
 * ## 为什么单独建一个类，而不是继续用 [StarRailTaskConfig]
 *
 * [StarRailTaskConfig] 只有 `type` 一个字段（"一键领取"语义的任务够用），
 * 但清体力必须在 UI 上选"刷哪个本、刷几次"，所以需要额外字段。
 *
 * ## ⚠️ 字段名不能叫 `type`
 *
 * 与 [StarRailTaskConfig] 同样的原因：[TaskParamProvider] 是多态序列化基类，
 * kotlinx.serialization 会用 `type` 当**判别器**。这里字段叫
 * [taskType] / [instance] / [maxRuns]，都避开了判别器名。
 */
@Serializable
data class PowerTaskConfig(
    /** 固定 `"Power"`（与 [TaskTypeInfo.POWER.taskType] 对齐） */
    @SerialName("taskType")
    val taskType: String = "Power",

    /** 刷哪个副本类型（游戏内 UI 原文，OCR 匹配目标） */
    val instance: String = PowerInstance.DEFAULT.wireName,

    /**
     * 具体子副本名（列表里要点的那个，如「藏珍之蕾·毁灭之径」）。
     *
     * **留空 = 由内核用该类型的默认副本**。
     * 之所以不放到 UI 上当必填项：子副本名太长且随版本变化，
     * 让用户手打容易打错一个字就整轮失败；留空更稳。
     */
    val instanceName: String = "",

    /**
     * 是否让游戏开启「快捷补充开拓力」。
     *
     * ## 这是什么
     *
     * 崩铁新版本功能：开启后打副本时，**当前开拓力不够会自动从
     * 后备开拓力、燃料里扣**。
     *
     * 消耗优先级：**开拓力 → 后备开拓力 → 燃料**，
     * **不会消耗星琼**（这点由游戏保证）。
     *
     * ## 为什么默认 false
     *
     * 后备开拓力和燃料都是**不可再生**的稀缺资源。开着它跑自动任务，
     * 一轮下来可能把玩家攒了几个月的储备打光。
     *
     * 必须用户显式打开才生效 —— 这是"默认安全"原则。
     */
    val allowRefill: Boolean = false,

    /**
     * 最多刷几次（**0 = 用完当前体力**）。
     *
     * 这是"挑战次数"（每挑战一次扣一次体力），不是"连续挑战轮数"。
     *
     * ⚠️ 开启 [allowRefill] 后，这个次数**不再受当前体力限制** ——
     * 体力不够时游戏会自动补，所以设多少就打多少（直到补充资源耗尽）。
     */
    val maxRuns: Int = 0,

    /**
     * 是否允许吃燃料/后备开拓力补体力。
     *
     * @deprecated 用 [allowRefill] 代替。
     *
     * 保留这个字段是因为**旧配置里已经有它**，直接删掉会让
     * kotlinx.serialization 反序列化失败（missing field 视配置而定，
     * 这里用默认值兜底更安全）。
     */
    @Deprecated("Use allowRefill")
    val useFuel: Boolean = false,
) : TaskParamProvider {
    override fun toTaskParams(ctx: TaskParamContext): List<CoreTaskParams> {
        val maaType = CoreTaskType.fromValue(taskType)
        if (maaType == null) {
            ctx.appendLog(UiText.Dynamic("未知的崩铁任务类型：$taskType"), LogLevel.ERROR)
            return emptyList()
        }
        // 参数序列化成 JSON 字符串下发给内核。
        // 内核侧 StarRailTasks.power() 会解析它。
        val json = buildString {
            append("""{"instance":""")
            append('"').append(instance.replace("\\", "\\\\").replace("\"", "\\\"")).append('"')
            append(""","instanceName":""")
            append('"').append(instanceName.replace("\\", "\\\\").replace("\"", "\\\"")).append('"')
            append(""","maxRuns":$maxRuns""")
            append(""","allowRefill":$allowRefill""")
            append(""","useFuel":${allowRefill || useFuel}""")
            append("}")
        }
        return listOf(CoreTaskParams(maaType, json))
    }
}
