package com.march7th.hkrpg.starrail

/**
 * 崩铁任务注册表。
 *
 * `type` 的取值必须与 [com.march7th.hkrpg.core.task.CoreTaskType] 的 `value` 对齐，
 * 因为外壳是拿那个枚举的字符串调 `AppendTask(type, params)` 的。
 *
 * ## 当前可执行任务
 *
 * | type | 说明 | 进 UI |
 * |---|---|---|
 * | `StartUp` | 启动游戏并推进到大厅 | ❌ 隐藏，但**默认必选** |
 * | `Mail` | 领邮件 | ✅ |
 * | `Power` | 清体力（刷副本） | ✅ |
 * | `Daily` | 每日实训（识别+领奖） | ✅ |
 * | `CloseDown` | 关闭游戏 | ✅ |
 *
 * **「每日实训」和「成就」已删除**：它们都依赖 OCR 识别进度数字
 * （`X/Y` 格式），而那套方案还没在手游上落地，留着只会点了就失败。
 *
 * ## 「启动游戏」为什么不在列表里
 *
 * 它被设为**默认必选且不出现在任务列表 UI**（见 [ALL] 的说明）。
 * 理由：任何崩铁任务的前置都是"游戏在前台/大厅"，用户没必要
 * 每次手动勾一次；而且忘了勾就会导致后续任务对着标题页空转。
 */
object StarRailTaskRegistry {

    /**
     * 任务定义。
     *
     * @param type 传给 `AppendTask` 的字符串（**必须与 CoreTaskType.value 对齐**）
     * @param label 中文名（对应 M7A 里的名字）
     * @param source M7A 里的来源文件（便于比对语义）
     * @param verified 是否已在本项目实机验证过
     * @param hidden 是否在任务列表 UI 中隐藏（仍可被默认链引用）
     */
    data class TaskDef(
        val type: String,
        val label: String,
        val source: String,
        val verified: Boolean,
        val needOcr: Boolean = false,
        val hidden: Boolean = false,
    )

    /**
     * 全部可执行任务。
     *
     * [StartUp] 标记为 `hidden = true`：不进 UI 列表，但**默认选中**，
     * 由任务链构建时自动插到最前面。
     */
    val ALL: List<TaskDef> = listOf(
        TaskDef(
            type = "StartUp",
            label = "启动游戏",
            source = "tasks/game/starrailcontroller.py",
            verified = true,
            hidden = true,
        ),
        // ## 云游戏启动
        //
        // 和「启动游戏」并列，让用户自己选：
        //   · 用本地客户端  → 选「启动游戏」
        //   · 用云游戏客户端 → 选「启动云游戏」
        //
        // 云游戏包名 `com.miHoYo.cloudgames.hkrpg`，启动流程完全不同，
        // 所以单独一个任务而不是在 StartUp 里加开关。
        TaskDef(
            type = "CloudGameStartUp",
            label = "启动云游戏",
            source = "tasks/game/cloudgame.py",
            // ⚠️ 尚未实机验证：启动步骤等实测确认后再改成 true
            verified = false,
            needOcr = true,
        ),
        TaskDef("Mail", "邮件", "tasks/reward/mail.py", true),
        TaskDef("Power", "清体力", "tasks/power/power.py", false, needOcr = true),
        TaskDef("Daily", "每日实训", "tasks/daily/daily.py", false, needOcr = true),
        TaskDef("CloseDown", "关闭游戏", "tasks/game/starrailcontroller.py", true),
    )

    /**
     * 默认必选的任务（不进 UI，但每次执行都会带上）。
     *
     * 目前就是「启动游戏」：不先把游戏推进到大厅，后面的任务
     * 全都无从谈起。
     */
    val DEFAULT_ALWAYS: List<String> = listOf("StartUp")

    /**
     * **不进 UI** 的调试任务。
     *
     * `Dump` 把虚拟屏当前帧导出到 `{coreRoot}/debug/screenshots/`。
     * 移植期要靠它采模板 —— 虚拟屏的画面抓不到（`screencap -d` 不支持 VD），
     * 而模板必须按采集分辨率裁，只能从引擎内部导出。
     */
    val DEBUG_ONLY: List<TaskDef> = listOf(
        TaskDef("Dump", "导出画面", "debug", false),
    )

    /** UI 上能勾选的任务（排除 hidden 与 debug-only）。 */
    val SELECTABLE: List<TaskDef> = ALL.filter { !it.hidden }

    private val byType: Map<String, TaskDef> = (ALL + DEBUG_ONLY).associateBy { it.type }

    /** `AppendTask` 收到未知类型时要能明确拒绝，而不是静默跑空。 */
    fun isKnown(type: String): Boolean = byType.containsKey(type)

    fun find(type: String): TaskDef? = byType.get(type)

    /** 已实机验证过的任务（UI 上可以标个勾）。 */
    fun verified(): List<TaskDef> = ALL.filter { it.verified }
}
