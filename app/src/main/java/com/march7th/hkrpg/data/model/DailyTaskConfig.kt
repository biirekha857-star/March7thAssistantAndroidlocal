package com.march7th.hkrpg.data.model

import com.march7th.hkrpg.core.task.CoreTaskParams
import com.march7th.hkrpg.core.task.CoreTaskType
import com.march7th.hkrpg.utils.i18n.UiText
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 每日实训任务的参数。
 *
 * # 设计：**用户自己勾选要做哪几项**
 *
 * ## 为什么不再自动识别任务列表
 *
 * 早期版本想复刻原项目「OCR 扫描任务卡片 → 算分 → 补到 500」那套，
 * 但实测在手游玩法下**极不可靠**：
 *
 * | 问题 | 实测证据 |
 * |---|---|
 * | OCR 拆字跨卡片 | `"累计消耗120点开拓"@727` + `"力"@608`（「力」飞到隔壁卡片） |
 * | 文字误识 | 「差分宇宙」被认成「**差分字画**」 |
 * | 卡片布局随文字长度变化 | 固定宽度分桶全乱 |
 *
 * 结果是：明明 400/500 还有任务可做，却扫不出未完成项，任务白跑。
 *
 * ## 现在的做法
 *
 * 把「今天要做哪几项」交给**用户**。用户自己看游戏里的实训页，
 * 在这里勾上，程序照着做 —— 零识别的风险。
 *
 * 最后统一领奖（对应游戏里的「领取」按钮）。
 *
 * # 和清体力一样，去掉历战余响
 *
 * 「消耗体力」这一项复用清体力的刷本逻辑，但**排除历战余响** ——
 * 周本每周只有 3 次（90 体力），凑不到 120，且不值得拿来刷日常。
 */
@Serializable
data class DailyTaskConfig(
    /** 固定 `"Daily"`（与 [TaskTypeInfo.DAILY.taskType] 对齐） */
    @SerialName("taskType")
    val taskType: String = "Daily",

    // ==================== 子项开关 ====================
    //
    // 每项对应一条游戏内实训。用户按当天实际情况勾选。
    // 分值写在注释里，方便用户自己算够不够 500。

    /** 派遣委托或收取1次委托奖励（+100） */
    val doDispatch: Boolean = true,

    /** 拍照1次（+100） */
    val doPhoto: Boolean = true,

    /** 使用1次「万能合成机」（+100） */
    val doSynthesize: Boolean = true,

    /** 累计施放2次秘技（+200） */
    val doTechnique: Boolean = true,

    /** 累计消耗120点开拓力（+200）—— 复用清体力刷本 */
    val doPower: Boolean = false,

    // ==================== 刷本参数（仅 doPower 生效）====================

    /**
     * 刷哪个副本类型来消耗体力。
     *
     * 取值是 [PowerInstance.wireName]（游戏内 UI 原文，OCR 匹配目标）。
     * **历战余响不在候选里**（见类注释）。
     */
    val instance: String = PowerInstance.CALYX_CRIMSON.wireName,

    // ==================== 领奖 ====================

    /**
     * 做完上述项目后，是否点「领取」把活跃度奖励收掉。
     *
     * 默认开 —— 这是本任务最主要的收益来源。
     */
    val doClaim: Boolean = true,
) : TaskParamProvider {

    /** 消耗体力目标的固定值（对应游戏里「累计消耗 120 点开拓力」）。 */
    val targetPower: Int get() = TARGET_POWER

    /** 刷本候选（**排除历战余响**）。 */
    fun candidates(): List<PowerInstance> =
        PowerInstance.entries.filter { it != PowerInstance.ECHO_OF_WAR }

    /** 消耗 120 体力需要刷几次（向下取整，至少 1 次）。 */
    fun runsForTarget(inst: PowerInstance): Int =
        (TARGET_POWER / inst.powerPerRun).coerceAtLeast(1)

    override fun toTaskParams(ctx: TaskParamContext): List<CoreTaskParams> {
        val coreType = CoreTaskType.fromValue(taskType)
        if (coreType == null) {
            ctx.appendLog(UiText.Dynamic("未知的崩铁任务类型：$taskType"), LogLevel.ERROR)
            return emptyList()
        }
        val json = buildString {
            append("""{"doDispatch":$doDispatch""")
            append(""","doPhoto":$doPhoto""")
            append(""","doSynthesize":$doSynthesize""")
            append(""","doTechnique":$doTechnique""")
            append(""","doPower":$doPower""")
            append(""","doClaim":$doClaim""")
            append(""","instance":""")
            append('"').append(instance.replace("\\", "\\\\").replace("\"", "\\\"")).append('"')
            append(""","targetPower":$TARGET_POWER""")
            append("}")
        }
        return listOf(CoreTaskParams(coreType, json))
    }

    companion object {
        /**
         * 「累计消耗开拓力」的目标值。
         *
         * 游戏里的原文就是「累计消耗 **120** 点开拓力」，写死。
         */
        const val TARGET_POWER = 120
    }
}
