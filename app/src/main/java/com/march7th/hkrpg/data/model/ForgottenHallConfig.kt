package com.march7th.hkrpg.data.model

import com.march7th.hkrpg.core.task.CoreTaskParams
import com.march7th.hkrpg.core.task.CoreTaskType
import com.march7th.hkrpg.utils.i18n.UiText
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 「忘却之庭 · 混沌回忆」任务的参数。
 *
 * # 这个任务做什么
 *
 * 对应 M7A 的 `tasks/challenge/memoryofchaos.py`：
 *
 * ```
 * 大厅 → 手机 → 指南 → 逐光捡金 → 忘却之庭 → 混沌回忆 → 传送
 *   → 逐层点进去打（第 7~12 层）
 *   → 打完回列表，领奖励
 * ```
 *
 * 每层最多 3 星，全场 36 星（12 层 × 3）。**已满 3 星的层默认跳过**，
 * 所以第二次跑同一期只会补没满的层。
 *
 * # 为什么不做「选角色」
 *
 * 上游 `select_characters()` 要按名字点头像（19 行 × 4 次滚动 + 回滚），
 * 手游上还要处理命途切换弹窗。第一版**直接使用玩家在游戏里已经配好的
 * 队伍**：混沌回忆的队伍配置是**按层保存**的，配过一次就一直有效。
 *
 * 这样做的代价是"没配过队伍就开不了打"（会停在配队页），
 * 但收益是不会因为选错人/选错命途把整层打废。等真机跑通基础链路之后
 * 再决定要不要补选人。
 */
@Serializable
data class ForgottenHallConfig(
    /** 固定 `"ForgottenHall"`（与 [TaskTypeInfo.FORGOTTEN_HALL.taskType] 对齐） */
    @SerialName("taskType")
    val taskType: String = "ForgottenHall",

    /**
     * 从第几层开始打。
     *
     * 默认 7 —— 混沌回忆前 6 层是「回忆」（常驻入门关，星琼一次性，
     * 而且早就打完了），真正每期刷新的是第 7~12 层。
     */
    val fromLevel: Int = 7,

    /** 打到第几层（含）。当前版本最高 12 层。 */
    val toLevel: Int = 12,

    /**
     * 已满星（3 星）的层是否跳过。
     *
     * 默认开 —— 重复打满星层**不产出任何奖励**，只是浪费时间。
     * 关掉它等于"强制重打一遍所有层"，用于复查。
     */
    val skipFullStar: Boolean = true,

    /**
     * 是否在战斗中自动打开「自动战斗」。
     *
     * 崩铁的战斗界面左下角有个「自动」开关，关着的时候会显示一个
     * 带斜杠的图标（`share/base/not_auto.png`）。打开它，战斗就交给
     * 游戏自己打，我们只负责等结算。
     *
     * ⚠️ 关掉它意味着**必须靠玩家手打**，自动化就断在这里了，
     * 所以默认开；留这个开关只是为了排查"自动战斗把队伍打崩"的情况。
     */
    val autoBattle: Boolean = true,

    /**
     * 单层战斗的时间上限（分钟）。
     *
     * 混沌回忆一层要打两个半场，满配队伍 3~6 分钟，练度低会更久。
     * 30 分钟和 M7A 的 `check_fight(30*60)` 一致。
     */
    val battleTimeoutMin: Int = 30,
) : TaskParamProvider {

    /** 规范化后的层数区间（防用户把 from/to 填反或超出 1~12）。 */
    fun levelRange(): IntRange {
        val a = fromLevel.coerceIn(MIN_LEVEL, MAX_LEVEL)
        val b = toLevel.coerceIn(MIN_LEVEL, MAX_LEVEL)
        return if (a <= b) a..b else b..a
    }

    override fun toTaskParams(ctx: TaskParamContext): List<CoreTaskParams> {
        val coreType = CoreTaskType.fromValue(taskType)
        if (coreType == null) {
            ctx.appendLog(UiText.Dynamic("未知的崩铁任务类型：$taskType"), LogLevel.ERROR)
            return emptyList()
        }
        val json = buildString {
            append("""{"fromLevel":${fromLevel.coerceIn(MIN_LEVEL, MAX_LEVEL)}""")
            append(""","toLevel":${toLevel.coerceIn(MIN_LEVEL, MAX_LEVEL)}""")
            append(""","skipFullStar":$skipFullStar""")
            append(""","autoBattle":$autoBattle""")
            append(""","battleTimeoutMin":${battleTimeoutMin.coerceIn(1, 120)}""")
            append("}")
        }
        return listOf(CoreTaskParams(coreType, json))
    }

    companion object {
        /** 混沌回忆最低层。 */
        const val MIN_LEVEL = 1

        /** 混沌回忆最高层（当前版本 12 层）。 */
        const val MAX_LEVEL = 12

        /** 一层满星数。 */
        const val STARS_PER_LEVEL = 3

        /** 全场总星数（12 层 × 3 星）。用于读「本期是否已满」。 */
        const val TOTAL_STARS = MAX_LEVEL * STARS_PER_LEVEL
    }
}
