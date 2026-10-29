package com.march7th.hkrpg.data.model

import android.content.Context
import androidx.annotation.StringRes
import com.march7th.hkrpg.R
import com.march7th.hkrpg.core.task.CoreTaskParams
import com.march7th.hkrpg.core.task.CoreTaskType
import com.march7th.hkrpg.utils.i18n.UiText

/**
 * 崩铁任务类型（UI 层）。
 *
 * 与内核侧的 [com.march7th.hkrpg.starrail.StarRailTaskRegistry] 一一对应：
 * `taskType` 就是要传给 `AppendTask` 的字符串。
 *
 * ## 当前任务
 *
 * | 枚举 | 说明 | 是否进 UI 列表 |
 * |---|---|---|
 * | [START_UP] | 启动游戏并推进到大厅 | ❌ 隐藏，但**默认必选** |
 * | [MAIL] | 领邮件 | ✅ |
 * | [POWER] | 清体力（刷副本消耗开拓力） | ✅ |
 * | [CLOSE_DOWN] | 关闭游戏 | ✅ |
 *
 * **「每日实训」和「成就」已删除**：它们都依赖 OCR 识别进度数字，
 * 而那套方案还没在手游上落地，留着只会点了就失败。
 *
 * **「启动游戏」隐藏但默认必选**：任何任务的前置都是"游戏在大厅"，
 * 让用户手动勾既啰嗦又容易漏；漏了后续任务就会对着标题页空转。
 *
 * > 原明日方舟的枚举（WAKE_UP/RECRUITING/BASE/COMBAT/MALL/…）已移除，
 * > 相关参数类型（WakeUpConfig/RecruitConfig/…）保留在代码里，
 * > 供将来若要并行支持方舟时复用。
 */
enum class TaskTypeInfo(
    /** 传给内核 `AppendTask` 的类型字符串，必须与 StarRailTaskRegistry 对齐 */
    val taskType: String,
    @param:StringRes val nameRes: Int,
    val defaultConfig: () -> TaskParamProvider,
    /**
     * 是否在任务列表 UI 中隐藏。
     *
     * ⚠️ **隐藏 ≠ 不加入任务链**：[START_UP] 就是隐藏进"添加任务"选择器，
     * 但必须出现在链里且强制启用（否则单勾"邮件"时引擎会对着标题页
     * 找邮箱，必然失败）。
     *
     * ## 为什么删掉了 inDefaultChain
     *
     * 原来有个 `inDefaultChain` 开关，把 POWER/CLOSE_DOWN 排除在默认链外。
     * 但**默认链是任务列表 UI 的唯一来源** —— 不在链里的任务界面上根本
     * 看不见，用户也没法手动添加。结果就是"清体力"从界面上消失了。
     *
     * 而"默认不勾选"这件事已经由 `enabled = hidden` 保证了：
     * 非隐藏任务默认全部未勾选，必须用户主动勾才会跑。
     * 所以那个开关纯属多余，只会制造"任务消失"的坑。
     */
    val hidden: Boolean = false,
) {
    START_UP(
        taskType = "StartUp",
        nameRes = R.string.task_type_start_up,
        defaultConfig = { StarRailTaskConfig("StartUp") },
        hidden = true,
    ),
    MAIL(
        taskType = "Mail",
        nameRes = R.string.task_type_mail,
        defaultConfig = { StarRailTaskConfig("Mail") },
    ),
    POWER(
        taskType = "Power",
        nameRes = R.string.task_type_power,
        defaultConfig = { PowerTaskConfig() },
    ),
    DAILY(
        taskType = "Daily",
        nameRes = R.string.task_type_daily,
        defaultConfig = { DailyTaskConfig() },
    ),
    CLOSE_DOWN(
        taskType = "CloseDown",
        nameRes = R.string.task_type_close_down,
        defaultConfig = { StarRailTaskConfig("CloseDown") },
    );

    fun defaultName(context: Context): String = context.getString(nameRes)
}

/**
 * 崩铁任务的参数。
 *
 * 目前所有崩铁任务都不需要参数（邮件/每日实训/成就都是"一键领取"语义），
 * 只需要把 [type] 透传给内核的 `AppendTask`。
 *
 * 将来需要参数时（如"清体力"选副本、"模拟宇宙"选命途），在这里扩展字段。
 */
/**
 * 崩铁任务的参数。
 *
 * 目前所有崩铁任务都不需要参数（邮件是"一键领取"语义），
 * 只需要把 [type] 透传给内核的 `AppendTask`。
 *
 * 将来需要参数时（如"清体力"选副本、"模拟宇宙"选命途），在这里扩展字段。
 *
 * ## ⚠️ 为什么用 `@SerialName("taskType")` 而不是直接叫 `type`
 *
 * [TaskParamProvider] 是**多态序列化**的基类，kotlinx.serialization 会给
 * JSON 加一个名为 `type` 的**判别器字段**用来记录实际类型。
 *
 * 如果本类也有一个叫 `type` 的属性，两者就会撞名，序列化时直接抛：
 * ```
 * JsonEncodingException: Class 'StarRailTaskConfig' cannot be serialized as
 * base class 'TaskParamProvider' because it has property name that conflicts
 * with JSON class discriminator 'type'.
 * ```
 * 后果是**任务链永远存不进 DataStore**（勾选状态一重启就没了），
 * 而且只在写盘时炸，编译期完全看不出来。
 *
 * 所以这里把 JSON 里的字段名改成 `taskType`，Kotlin 侧仍叫 `type`。
 * 注意：改了 JSON 名会影响**旧存档的兼容性** —— 但旧版本从来没存成功过
 * （一直抛异常），所以不存在需要兼容的数据。
 */
@kotlinx.serialization.Serializable
data class StarRailTaskConfig(
    @kotlinx.serialization.SerialName("taskType")
    val type: String,
) : TaskParamProvider {
    override fun toTaskParams(ctx: TaskParamContext): List<CoreTaskParams> {
        val maaType = CoreTaskType.fromValue(type)
        if (maaType == null) {
            ctx.appendLog(UiText.Dynamic("未知的崩铁任务类型：$type"), LogLevel.ERROR)
            return emptyList()
        }
        // 崩铁任务当前都不需要参数，给个空对象即可
        return listOf(CoreTaskParams(maaType, "{}"))
    }
}

/**
 * 取任务配置对应的类型字符串。
 *
 * 崩铁任务用 [StarRailTaskConfig]（自带 `type` 字段）；明日方舟遗留的
 * 配置类型没有这个字段，此时退回"按配置类名反查 [TaskTypeInfo]"。
 *
 * 用于判断某个节点是不是隐藏任务（如「启动游戏」），
 * 从而决定要不要在任务列表里显示。
 */
fun TaskParamProvider.taskTypeOrNull(): String? {
    if (this is StarRailTaskConfig) return type
    if (this is PowerTaskConfig) return taskType
    return TaskTypeInfo.entries
        .firstOrNull { it.defaultConfig()::class == this::class }
        ?.taskType
}
