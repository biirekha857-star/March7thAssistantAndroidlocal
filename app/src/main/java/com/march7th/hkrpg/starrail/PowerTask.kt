package com.march7th.hkrpg.starrail
import android.os.SystemClock
import com.march7th.hkrpg.starrail.engine.ImageUtils
import com.march7th.hkrpg.starrail.engine.OcrEngine
import com.march7th.hkrpg.starrail.engine.ScreenNavigator
import com.march7th.hkrpg.starrail.engine.ShellAutomation
import com.march7th.hkrpg.third.Ln
import org.opencv.core.Rect

/**
 * 清体力（开拓力 / Trailblaze Power）。对应 M7A 的
 * `tasks/power/power.py` + `tasks/power/instance.py`。
 *
 * # 设计原则：一切"按名字选"的地方都走 OCR
 *
 * ## 为什么不再逐个校准坐标
 *
 * 「生存索引」页面上，副本类型在第几行、目标副本在列表第几个、
 * 「传送」按钮在哪，全都**不固定**：
 *
 * - 副本列表随账号解锁进度变化；
 * - 列表会滚动，滚动位置不可预测；
 * - 同名文字在页面上可能出现多次（标题 + 列表项）；
 * - 换设备/换分辨率后像素坐标整体失效。
 *
 * 这些恰恰是 OCR 擅长、而硬编码坐标**永远做不对**的事。
 * 反过来说，**位置固定**的东西（返回、关闭、确定、+ 号）继续走
 * 模板匹配 —— 更快也更稳，没必要上 OCR。
 *
 * 所以这个类的分工是：
 *
 * | 动作 | 手段 |
 * |---|---|
 * | 进生存索引 | 模板（`share/menu/guide.png`）+ 坐标兜底 |
 * | 选副本类型 | **OCR 点文字** |
 * | 选副本 → 传送 | **OCR**：「传送/进入/追踪」离副本名最近的那个 |
 * | 设连续挑战次数 | 模板（`screen/guide/plus.png`） |
 * | 开打 | **OCR 点「开始挑战」/「挑战」** |
 * | 等结算 | 模板（`fight_again.png` / `fight_fail.png`） |
 * | 连续挑战 | 模板（`fight_again.png`） |
 * | 退出 | 模板（`fight_exit.png`）+ 逐级返回 |
 *
 * # 安全阀（比功能更重要）
 *
 * 清体力会**真实消耗不可再生资源**。所以：
 *
 * 1. **找不到目标就不点**：OCR 没命中就停，绝不"随便点一个"；
 * 2. **次数取小不取大**：体力够几次打几次，且不超过副本连打上限；
 * 3. **攻击类文字绝不误点**：只有明确命中「开始挑战/挑战」才开打，
 *    传送按钮若是「追踪」立即判定未解锁并停下；
 * 4. **补充资源默认关闭**：后备开拓力/燃料只在用户显式打开
 *    「快捷补充开拓力」时才消耗（`allowRefill = true`）；
 *    游戏保证**不会动星琼**；
 * 5. 每一步都写日志，失败时能看出卡在哪一句。
 */
internal class PowerTask(
    private val auto: ShellAutomation,
    private val nav: ScreenNavigator,
    private val config: PowerParams,
    /**
     * 外部取消信号（用户点了「停止任务」）。
     *
     * ## 为什么需要它
     *
     * 原来的实现里，「停止」只是把引擎的 `running` 标志设为 false，
     * 而任务线程**只在任务之间**检查那个标志：
     *
     * ```kotlin
     * for (task in tasks) {
     *     if (!running.get()) break      // 只在这里检查
     *     impl.run(task.type)            // 但这一步要跑好几分钟
     * }
     * ```
     *
     * 清体力一次要跑几分钟（进本、连打、等结算、退出），
     * 中途点停止根本拦不住 —— 用户实测反馈"点了停止它还在操作"。
     *
     * 所以把取消信号传进来，在**每个等待循环**里检查，
     * 一旦取消就立刻收手（[StoppedException] 抛出）。
     */
    private val isCancelled: () -> Boolean = { false },
) {

    companion object {
        private const val TAG = "PowerTask"

        /** 等待页面加载的上限。 */
        private const val PAGE_TIMEOUT_SEC = 60

        /** 战斗（含结算动画）等待上限。M7A 用 1800s。 */
        /**
         * 战斗（含结算动画）等待上限。M7A 用 1800s。
         *
         * 注意这是**单轮**的上限。一轮内游戏可能连打 24 场，
         * 按每场 1~2 分钟算，1800s 够用；如果玩家队伍很弱导致
         * 单场打很久，这个值需要往上调。
         */
        private const val FIGHT_TIMEOUT_SEC = 1800

        /**
         * 战斗画面**完全无变化**多久就判定「战斗没开始」。
         *
         * 正常战斗每帧都在动（技能特效、血条、伤害数字），
         * 画面静止 60 秒基本只有一种可能：根本没进战斗 ——
         * 比如点「开始挑战」没点中，画面还停在队伍配置页。
         *
         * 实测踩过：这种情况原来会一直等 FIGHT_TIMEOUT_SEC（30 分钟），
         * 用户看到的是任务卡死。
         */
        private const val FIGHT_STALL_TIMEOUT_SEC = 60

        /**
         * 最多分几轮。
         *
         * ## 为什么需要上限
         *
         * 体力 240 / 侵蚀隧洞单次 40 = 6 次，而隧洞连打上限也是 6，
         * 刚好一轮打完。但如果是"体力溢出 + 上限较低"的组合，
         * 理论上可能需要很多轮。
         *
         * 上限 8 轮已经远超正常需求（8 × 最少 3 次 = 24 次），
         * 设它是为了防止**逻辑出错时无限刷本**把体力全打光。
         */
        private const val MAX_ROUNDS = 8

        /** 「快捷补充开拓力」开启状态的模板（橙色胶囊）。 */
        private const val TEMPLATE_SWITCH_ON = "share/power/trailblaze_power/refill_switch_on.png"

        /** 开关模板匹配阈值。 */
        private const val SWITCH_THRESHOLD = 0.85

        /** 「快捷补充开拓力」字样（OCR 定位用）。 */
        private const val TEXT_REFILL = "快捷补充开拓力"

        /** 开关中心相对字样的横向偏移（1920 基准，实测约 +100px）。 */
        private const val SWITCH_OFFSET_X = 100f

        // ==================== 体力与补充 ====================

        /** 「快捷补充开拓力」**关闭**状态的模板（深色胶囊 + 圆点在左）。 */
        private const val TEMPLATE_SWITCH_OFF = "power/switch_off.png"

        /** 关闭模板的匹配阈值。 */
        private const val SWITCH_OFF_THRESHOLD = 0.85

        /** 点一次开关后等多久再复查（游戏切换动画）。 */
        private const val SWITCH_TOGGLE_SETTLE_MS = 500L

        /** 尝试开启开关的最大次数。 */
        private const val MAX_SWITCH_TOGGLE_TRIES = 3


        /**
         * 开关的搜索区（副本入口页右侧面板，「快捷补充开拓力」那一行）。
         *
         * 实测关闭状态的开关在 x 0.930~0.973、y 0.706~0.746。
         * 四周留余量应对不同副本/难度下的轻微偏移。
         */
        private val CROP_REFILL_SWITCH = ScreenNavigator.NormRect(
            0.88f, 0.66f, 0.12f, 0.10f,
        )

        /**
         * 连点「+」/「−」的间隔（毫秒）。
         *
         * 350 太慢（23 次要 8 秒），150 有丢点击风险，取 200。
         * 上层 [setAttempts] 会读回验证，丢点击会触发重试兜底。
         */
        private const val TAP_INTERVAL_MS = 200L

        // ---- 次数按钮的模板（白底黑符号，深色面板上对比度极高）----

        /** 「+」按钮模板。 */
        // ==================== 自由移动摇杆 ====================
        //
        // 差分宇宙进入副本后是**自由移动场景**（不像其它副本直接进战斗）。
        // 左下角有个虚拟摇杆：
        //
        // ```
        //        ▲          ← 上三角（前）
        //     ◄  ○  ►       ← 左 / 圆心 / 右
        //        ▼          ← 下三角（后）
        // ```
        //
        // 好消息：**不需要拖动**，长按某个三角就等于朝那个方向走。
        // 实测坐标（1920×1080）：
        //   圆心 (372, 839)  上 (372, 807)  下 (372, 870)
        //   左 (339, 839)    右 (405, 839)

        /** 向前移动的长按时长。 */
        private const val WALK_FORWARD_MS = 3_500L

        /** 摇杆圆心。 */
        private const val JOY_CX = 372
        private const val JOY_CY = 839

        /** 上三角（向前移动）—— 取三角上半部，确保长按落在三角上。 */
        private const val JOY_UP_X = 374
        private const val JOY_UP_Y = 800

        /**
         * **攻击键**（右下角拳头按钮）。
         *
         * ⚠️ 严禁用任何识别 —— **每个角色的攻击键外观都不同**
         * （拳头/剑/法杖…），模板匹配和 OCR 全都不可靠。
         * 位置本身是固定的（所有角色一样），直接写死坐标。
         *
         * 实测 (1920×1080)：圆形按钮 x 1512~1676、y 750~890，
         * 中心 (1594, 820)。旁边右边那个是冲刺键，别点错。
         */
        private const val ATK_BTN_X = 1594
        private const val ATK_BTN_Y = 820

        /** 下三角（向后）。 */
        private const val JOY_DOWN_X = 372
        private const val JOY_DOWN_Y = 870

        /** 左三角。 */
        private const val JOY_LEFT_X = 339
        private const val JOY_LEFT_Y = 839

        /** 右三角。 */
        private const val JOY_RIGHT_X = 405
        private const val JOY_RIGHT_Y = 839

        private const val TEMPLATE_BTN_PLUS = "power/btn_plus.png"

        /** 「−」按钮模板。 */
        private const val TEMPLATE_BTN_MINUS = "power/btn_minus.png"

        /**
         * 次数按钮的搜索区（副本入口页右侧面板下半部分）。
         *
         * 纵向给到 0.72~0.90 是故意的 —— 实测这一行会随内容浮动：
         * 有「活动期间双倍奖励」黄条时 y≈0.80，没黄条时 y≈0.73。
         * 给足余量让模板匹配自己去里面找。
         */
        /**
         * **副本页特征文字**所在的区域（判断"是否已进入副本"用）。
         *
         * 副本页的版式是：左边立绘/列表、右边信息面板、底部按钮。
         * 所有能用来判态的文字都在**右侧 + 底部**这一带：
         *
         * ```
         * 开始挑战 / 挑战 / 难度 / 推荐队伍 / 挑战次数 / 自动战斗
         * ```
         *
         * 左侧那一半（立绘、副本名列表）与判态无关，
         * 全屏 OCR 等于白扫一半面积。
         *
         * 取 x 0.48~1.00（右半屏）、y 0.25~1.00（去掉顶部数值条）。
         */
        /**
         * **差分宇宙图标** —— 「位面饰品提取」页独有的锚点。
         *
         * 就是底部那个白底圆形 + 黑色漩涡的图标（84×84）。
         * 纯图形、位置固定、特征鲜明，比 OCR 认字可靠得多。
         *
         * 用户实测：用这个图标判断"是否已进入副本页"最稳。
         */
        private const val TEMPLATE_DIFF_UNIVERSE = "power/anchor_diff_universe.png"

        /** 差分宇宙图标的搜索区（底部偏左）。 */
        private val CROP_DIFF_ANCHOR = ScreenNavigator.NormRect(
            0.36f, 0.84f, 0.12f, 0.14f,
        )

        private val CROP_INSTANCE_FEATURES = ScreenNavigator.NormRect(
            0.48f, 0.25f, 0.52f, 0.75f,
        )

        private val CROP_SLIDER_ROW = ScreenNavigator.NormRect(
            0.62f, 0.72f, 0.38f, 0.18f,
        )

        /** 连续失败几次就放弃（对齐原项目 power.py:171）。 */
        private const val FAILED_LIMIT = 3

        /**
         * 「挑战次数」那一行两端按钮的横向位置（归一化，实测值）。
         *
         * 整行布局（1920×1080）：
         * ```
         *   −   1  ●────────  24   (+)
         * 0.660              0.905  0.941
         * ```
         */
        private const val PLUS_X_RATIO = 0.941f
        private const val MINUS_X_RATIO = 0.660f

        /** 单帧 OCR 之后等待界面稳定的时间。 */
        private const val SETTLE_MS = 900L

        /**
         * 副本类型列表的裁剪范围（归一化）。
         *
         * M7A `instance.py:91` 原值 `(262/1920, 289/1080, 422/1920, 624/1080)`，
         * 按 **left, top, width, height** 解释。
         * 注意 `422/1920 ≈ 0.22` 是**宽度**，不是右边界。
         */
        private val CROP_INSTANCE_TYPE = ScreenNavigator.NormRect(
            155f / 1920f, 280f / 1080f, 500f / 1920f, 600f / 1080f,
        )

        /**
         * 副本列表的裁剪范围（M7A `instance.py:103`）。
         *
         * ## ⚠️ 宽度必须覆盖到「进入」按钮的右边缘
         *
         * 原值宽度 `980/1920`，右边界只到 x≈1666。而**历战余响**页
         * 的「进入」按钮在 x 1570~1740 —— 右半截被切掉了，OCR 读到
         * 残缺的「进入」或者干脆读不到，于是"周本无法导航"。
         *
         * 加到 `1180/1920`（右边界 x≈1866）后完整覆盖。
         */
        private val CROP_INSTANCE_LIST = ScreenNavigator.NormRect(
            686f / 1920f, 287f / 1080f, 1180f / 1920f, 660f / 1080f,
        )

        /**
         * 体力条区域（**右上角**）。用于读「当前体力 / 上限」。
         *
         * ## 实测坐标（1920×1080 结算帧量的）
         *
         * 右上角显示：`[开拓力图标] 793   [星琼图标] 566/300 [+]`
         *
         * **开拓力是左边那个 `793`，不是 `566/300`**（后者是星琼/货币）。
         * 两个数字挤在一起，只裁开拓力那块：
         * x ≈ 1310..1470，y ≈ 20..80 → 归一化 (0.68, 0.018, 0.084, 0.058)
         *
         * ## 为什么原来读不到
         *
         * M7A 的 `(1588/1920, 35/1080, 198/1080, 56/1080)` 是 **PC 版**布局，
         * 而且它的宽度单位写成 `/1080` 本身就不自洽。
         * 手游上这个位置已经换成了别的数值，OCR 自然读不出 `X/300` 格式。
         *
         * ## 注意
         *
         * 手游体力的显示格式是**纯数字**（`793`）或者 `793/240`，
         * 不一定是 M7A 假设的 `X/300`。所以 [readCurrentPower] 里
         * 对两种格式都要处理。
         */

        /** 「开始挑战」按钮区域（M7A `instance.py:258`）。 */
        private val CROP_START_FIGHT = ScreenNavigator.NormRect(
            1615f / 1920f, 956f / 1080f, 243f / 1920f, 55f / 1080f,
        )

        /** 通用「挑战」按钮区域（M7A `instance.py:352`）。 */
        private val CROP_CHALLENGE = ScreenNavigator.NormRect(
            1518f / 1920f, 960f / 1080f, 334f / 1920f, 61f / 1080f,
        )

        /**
         * **队伍配置页**的底部按钮排。
         *
         * ## 实测（在虚拟屏 1920×1080 的真实截图上量的）
         *
         * 手游崩铁进副本后先到队伍配置页，底部横排三个按钮：
         *
         * | 按钮 | 中心 x | y 范围 |
         * |---|---|---|
         * | 常用队伍 | ≈ 200 | 845..915 |
         * | 快速编队 | ≈ 1080 | 845..915 |
         * | **开始挑战** | **≈ 1490** | **845..915** |
         *
         * ## 之前错在哪
         *
         * 一开始照抄 M7A **PC 版**的 `(1615/1920, 956/1080, 243/1920, 55/1080)`，
         * y 落在 0.885~0.936；而手游实际在 **0.78~0.85**，差一百多像素，
         * 所以怎么都搜不到「开始挑战」，表现为"卡在队伍页不动"。
         *
         * 这里覆盖整排按钮（x 0.02~0.98），后面按钮怎么挪都能命中。
         */
        private val CROP_TEAM_PAGE = ScreenNavigator.NormRect(
            0.02f, 0.70f, 0.98f, 0.30f,
        )

        /** 连续挑战「+」按钮区域（M7A `instance.py:249/336`）。 */
        /**
         * 「挑战次数」那一行（含 − 数字 + 滑块）的搜索区域。
         *
         * ## 实测布局（1920×1080 副本入口页）
         *
         * ```
         *  快捷补充开拓力                    [开]
         *  ────────────────────────────────────
         *  (i)  挑战次数 1  ●───────  8      (+)
         *                    ↑ 滑块
         *  ────────────────────────────────────
         *        [2] [28]        ← 消耗预览（体力不够时展开）
         *  ────────────────────────────────────
         *  [ ×30 ]         挑战
         * ```
         *
         * 归一化后这一行大约在 y 0.47~0.56、x 0.62~1.0。
         *
         * ## ⚠️ 为什么要给这么宽的区域
         *
         * 用户实测反馈：**体力不够时，下方会展开"自动消耗"的详细说明，
         * 把整块内容往上顶**。所以「挑战次数」这一行的 y 会在
         * 0.42 ~ 0.56 之间浮动，不能写死一个窄条。
         */
        private val CROP_ATTEMPTS = ScreenNavigator.NormRect(
            0.58f, 0.62f, 0.42f, 0.26f,
        )


        /**
         * 「开始挑战」按钮的候选文字。
         *
         * ⚠️ **只放完整的按钮文字，不要放「挑战」这种子串**。
         *
         * 实测踩过：原来写的是 `listOf("开始挑战", "挑战")`，
         * 而匹配用的是 `contains`，于是「**挑战次数3**」这行字
         * 也命中了「挑战」—— 结果点在次数文字上，战斗根本没开始。
         */
        private val START_TEXTS = listOf("开始挑战", "快速编队", "挑战")

        /**
         * 排除词：任何包含这些内容的 OCR 结果都不算「开始挑战」按钮。
         *
         * 「挑战次数 N」那一行里也有「挑战」两个字，必须排除掉。
         */
        private val START_EXCLUDE = listOf("挑战次数", "快捷补充", "开拓力", "可能", "获取", "介绍")

        /**
         * 队伍配置页「开始挑战」按钮的中心（归一化）。
         *
         * 实测 1920×1080 下按钮文字中心在 (1615, 978) —— 即 (0.8411, 0.9056)。
         * 之前用 (1520,963) 点在了按钮左边缘外侧，点击不生效。
         */
        private const val START_BTN_X_RATIO = 0.8411f
        private const val START_BTN_Y_RATIO = 0.9056f

        /**
         * 副本入口页「挑战」按钮的中心（归一化）。
         *
         * 实测 1920×1080 下「挑战」两个字在 (1585~1645, 970~1005)，
         * 中心 (1615, 987) —— 即 (0.8411, 0.9139)。
         *
         * 点完它才切到队伍配置页，之后才能点「开始挑战」。
         */
        private const val CHALLENGE_BTN_X_RATIO = 0.8411f
        private const val CHALLENGE_BTN_Y_RATIO = 0.9139f

        /** 点完「挑战」后等队伍配置页出现的时间。 */
        private const val CHALLENGE_TRANSITION_MS = 1_500L

        /**
         * 副本列表里「进入」按钮的文字。
         *
         * 生存索引左侧列表每行右侧有个「进入」按钮，点它才传送。
         * 未解锁的副本显示「追踪」。
         */
        private val ENTER_TEXTS = listOf("进入", "追踪")


        /**
         * 各副本类型单次可设的最大连续挑战次数（对齐游戏内上限，
         * 取自 M7A `power.py` 的 `attempts_per_run_max`）。
         */
        private val MAX_ATTEMPTS = mapOf(
            "拟造花萼（金）" to 24,
            "拟造花萼（赤）" to 24,
            "凝滞虚影" to 8,
            "侵蚀隧洞" to 6,
            "饰品提取" to 6,
            "历战余响" to 3,
        )
    }

    /** 任务参数（由外壳序列化下发）。 */
    data class PowerParams(
        val instance: String,
        val maxRuns: Int,
        /**
         * 是否允许消耗后备开拓力 / 燃料来补体力。
         *
         * 对应游戏里的「快捷补充开拓力」开关。开着时设多少打多少
         * （体力不够游戏自动补）；关着时体力打完就停。
         */
        val allowRefill: Boolean,
        /** 具体副本名（列表里要点的那个）；空则用副本类型的默认值。 */
        val instanceName: String = "",
    )

    /** 执行结果。 */
    data class Result(val ok: Boolean, val detail: String)

    /** 单次运行里累积的说明（写进日志和任务结果）。 */
    private val notes = ArrayList<String>()

    /** 用户取消时抛出，一路冒泡到 [run] 外层统一处理。 */
    public class Stopped(val partial: String = "") : ScreenNavigator.StopRequested()

    /** 已完成的挑战次数（取消时用于生成"已完成 N 次"的说明）。 */
    @Volatile
    private var completedCount = 0

    /**
     * 检查是否已被取消；是就抛 [Stopped]。
     *
     * ⚠️ 每个**耗时等待**的地方都要调一次（战斗轮询、界面等待、
     * OCR 重试…），否则用户点停止后仍会继续操作游戏。
     */
    /**
     * **可中断的等待**：分段 sleep，每段都检查取消。
     *
     * ## 为什么必须这样
     *
     * 原来是 `SystemClock.sleep(5000)` 这种一把梭的睡法 ——
     * 用户点「停止任务」时如果正卡在这 5 秒里，程序**完全感知不到**，
     * 必须等 sleep 结束才检查，表现就是"有时候能停、有时候停不了"。
     *
     * 实测反馈就是这个症状：停止**有概率生效**，不是 100%。
     *
     * 现在拆成 200ms 一段，最多 200ms 就能响应取消。
     */
    private fun sleepInterruptible(totalMs: Long, stepMs: Long = 200L) {
        val end = SystemClock.uptimeMillis() + totalMs
        while (SystemClock.uptimeMillis() < end) {
            checkCancelled()
            val left = end - SystemClock.uptimeMillis()
            SystemClock.sleep(minOf(stepMs, left).coerceAtLeast(1))
        }
    }

    private fun checkCancelled() {
        if (isCancelled()) {
            throw Stopped(
                if (completedCount > 0) "（已完成 $completedCount 次）" else "",
            )
        }
    }

    private fun note(s: String) {
        notes += s
        Ln.i("$TAG: $s")
    }

    // ==================== 主流程 ====================

    fun run(): Result {
        Ln.i("$TAG: 开始清体力 instance=${config.instance} name=${config.instanceName} maxRuns=${config.maxRuns} allowRefill=${config.allowRefill}")

        if (!OcrEngine.isReady) {
            return Result(
                false,
                "OCR 引擎未就绪 —— 清体力必须按文字选副本，无 OCR 无法保证点到正确的副本（本次未消耗体力）",
            )
        }

        val def = PowerInstanceTable.of(config.instance)
            ?: return Result(false, "未知的副本类型「${config.instance}」")

        // 1) 回大厅
        if (!nav.isAt(ScreenNavigator.Screen.MAIN)) {
            note("不在大厅，先返回主界面")
            if (!nav.closeCurrent()) return Result(false, "无法回到主界面")
        }

        val cost = def.powerPerRun
        val cap = MAX_ATTEMPTS[def.wireName] ?: def.maxAttemptsPerRun

        // 2) 目标次数在**进入副本页之后**再算
        //
        // ## ⚠️ 为什么不能在这里读体力
        //
        // 实测：**大厅界面上根本没有体力数字**，只有进了副本入口页
        // （生存索引点进具体副本后那一屏）右上角才显示
        // `[雪花] 544  [紫液] 5/300`。
        //
        // 早期在这里读，结果是两个区域 OCR 全空 → 读不到 → 只能按
        // 最保守的 1 次打，白白浪费体力。
        //
        // 所以流程改成：
        //   进副本页 → 读体力/后备开拓力 → 设次数 → 开打
        //
        // 这里只记下配置，真正的次数在 [runOneRound] 里算。
        note("单次消耗 $cost 体力，连打上限 $cap（目标次数将在进入副本页后按体力计算）")

        // 3) 主循环 —— 对齐 March7thAssistant `power.py:172`
        //
        // ## 原项目的真实结构（我之前理解错了）
        //
        // ```python
        // while failed_runs < FAILED_LIMIT:      # 循环**只为失败重试**
        //     power = Power.get()                 # 每次重新读体力
        //     attempts = power // instance_power_min
        //     if attempts == 0: break             # 体力不够就退出
        //     if planned_attempts > 0:
        //         attempts = min(attempts, planned_attempts - executed_attempts)
        //     full_runs = attempts // attempts_per_run
        //     if full_runs >= 1: Instance.run(..., full_runs)
        //     remain_attempts = attempts % attempts_per_run
        //     if remain_attempts >= 1: Instance.run(..., 1)
        //     break                               # ← 成功就退出，不循环
        // ```
        //
        // 关键：**只进一次本**。`attempts_per_run` 是游戏内连打上限（花萼 24），
        // `full_runs` 是"能打几个满轮连打"，不是"分几次进本"。
        //
        // 我之前自己发明的"多轮循环"是错的 —— 打完 24 次后还会傻乎乎
        // 开第 2 轮，而那时体力根本不够。
        var completed = 0
        var instanceName = ""
        var stopReason: String? = null
        var failedRounds = 0
        var lastReadPower: Int? = null

        while (failedRounds < FAILED_LIMIT) {
            checkCancelled()

            val outcome = runOneRound(def, config.maxRuns)
            instanceName = outcome.instanceName ?: instanceName
            outcome.readPower?.let { lastReadPower = it }

            when (outcome.status) {
                RoundStatus.OK -> {
                    completed = outcome.done
                    completedCount = completed
                    return Result(
                        true,
                        "副本「$instanceName」完成 ${outcome.done} 次挑战" +
                            (lastReadPower?.let { "（初始开拓力 $it）" } ?: "") +
                            tail(),
                    )
                }

                RoundStatus.NO_POWER -> {
                    stopReason = outcome.detail ?: "开拓力不足"
                    return Result(false, "未开始挑战：$stopReason${tail()}")
                }

                RoundStatus.BAG_FULL -> {
                    return Result(
                        false,
                        "副本「$instanceName」因**遗器背包已满**停止。" +
                            "请先在游戏里清理遗器（或打开自动分解）再重试${tail()}",
                    )
                }

                RoundStatus.FAILED -> {
                    failedRounds++
                    if (failedRounds >= FAILED_LIMIT) {
                        return Result(
                            false,
                            "副本「$instanceName」连续失败 $failedRounds 次" +
                                (outcome.detail?.let { "：$it" } ?: "") + tail(),
                        )
                    }
                    note("执行失败，重试（$failedRounds/$FAILED_LIMIT）：${outcome.detail ?: "未知原因"}")
                }
            }
        }

        return Result(
            completed > 0,
            "副本「$instanceName」完成 $completed 次挑战" +
                (lastReadPower?.let { "（初始开拓力 $it）" } ?: "") +
                tail(),
        )
    }

    /** 单轮执行结果。 */
    private enum class RoundStatus {
        /** 本轮按计划打完了 */
        OK,

        /** 体力中途耗尽（属正常，不算失败） */
        NO_POWER,

        /** 遗器背包满（需要人工清理） */
        BAG_FULL,

        /** 其它失败（找不到副本、开不了打、战斗失败…） */
        FAILED,
    }

    private data class RoundOutcome(
        val status: RoundStatus,
        val done: Int = 0,
        val instanceName: String? = null,
        /** 失败/提前结束时给用户看的具体原因 */
        val detail: String? = null,
        /** 本轮进副本时读到的开拓力（供主循环估算剩余可用次数） */
        val readPower: Int? = null,
    )

    /**
     * 执行一轮：进副本 → 设次数 → 开打 → 等结算 → 退出。
     *
     * ## 为什么一轮最多 [cap] 次
     *
     * 崩铁「连续挑战」有上限（花萼 24 / 凝滞虚影 8 / 隧洞 6）。
     * 想打超过上限就必须分多轮，每轮重新进副本。
     */
    private fun runOneRound(def: PowerInstanceTable.Entry, thisRound: Int): RoundOutcome {
        // ① 回大厅（上一轮退出后可能还在别的界面）
        if (!nav.isAt(ScreenNavigator.Screen.MAIN)) {
            if (!nav.closeCurrent()) {
                note("无法回到主界面，本轮放弃")
                return RoundOutcome(RoundStatus.FAILED, detail = "无法回到主界面")
            }
        }

        // ② 导航到生存索引
        if (!nav.gotoGuide3()) {
            note("导航到生存索引失败")
            return RoundOutcome(RoundStatus.FAILED, detail = "导航到生存索引失败")
        }

        // ③ 选副本类型
        if (!selectInstanceType(def)) {
            note("没找到副本类型「${def.wireName}」")
            return RoundOutcome(RoundStatus.FAILED, detail = "没找到副本类型「${def.wireName}」")
        }

        // ④ 选具体副本并传送
        val instanceName = selectAndEnterInstance(def)
        if (instanceName == null) {
            note("未能进入副本")
            return RoundOutcome(RoundStatus.FAILED, detail = "未能进入副本「${def.defaultInstanceName}」")
        }

        // ⑥ 不做体力识别 —— 设定多少就打多少
        //
        // ## 为什么砍掉体力识别
        //
        // 曾经为了"体力不够自动开补充"，写过一整套：
        // 读开拓力 → 读后备开拓力 → 加起来判断 → 开「快捷补充开拓力」开关。
        //
        // 实测这条路踩的坑太多：
        // - 不同副本页顶部数字个数不同（饰品提取多一个「沉浸器 0/12」），
        //   整排数值左移，固定裁剪区必然读串；
        // - OCR 时好时坏，`0/12` 的 `0` 被当成开拓力 → 判定"体力不足"
        //   → 任务无限退出重试，反而更糟。
        //
        // 而**游戏本身对体力有保护**：不够时点「挑战」会弹提示，
        // 不会真的多扣资源。所以让用户自己在游戏里管体力更简单可靠。
        //
        // 现在的规则非常直白：
        // ```
        // 用户设了 N 次 → 把次数设成 N → 点挑战 → 打
        // ```
        note("按设定执行 ${thisRound} 次（不做体力识别）")

        val refillOn = false

        // ⑦ 目标次数
        //
        // ## ⚠️ `maxRuns = 0` 表示「全部打完」，不是「打 0 次」
        //
        // UI 上次数滑块拉到最左是 0，文案是「用完体力」。
        // 早期直接把 `thisRound = 0` 当目标次数用，结果批次拆出来是
        // **空列表** → 一次都不打 → 15 秒就报"完成 0 次"，
        // 用户看到的是"根本没打就结束了"。
        //
        // 现在：0 视为"尽可能多打"，用一个足够大的数（游戏上限 cap）。
        // 真正的体力截断交给游戏自己（不够时点挑战会弹提示）。
        val capHere = MAX_ATTEMPTS[def.wireName] ?: def.maxAttemptsPerRun
        val roundTarget = if (thisRound <= 0) capHere else thisRound
        if (thisRound <= 0) {
            note("目标：全部打完（按单次上限 $capHere 次执行）")
        } else {
            note("目标 $roundTarget 次")
        }


        // ⑧ 按原项目的方式**拆成多批**执行
        //
        // ## 为什么必须拆
        //
        // 游戏内「连续挑战」有硬上限（花萼 24、凝滞虚影 8、隧洞 6）。
        // 如果体力够打 30 次花萼，一次设不了 30 —— 只能：
        //
        // ```python
        // full_runs = attempts // attempts_per_run      # 30 // 24 = 1 个满轮
        // remain    = attempts %  attempts_per_run      # 30 %  24 = 6 个零头
        // ```
        //
        // 先打 1 个满轮（24 次），退出后重新进本再打 6 次。
        // 这正是原项目 `power.py:220-238` 的做法。
        //
        // ⚠️ 注意：**同一轮里不重复"进本"**，而是打满一轮后
        // 若还有余量，才再进一次本打零头。所以最多 2 次进本。
        val cap = MAX_ATTEMPTS[def.wireName] ?: def.maxAttemptsPerRun
        val batches = buildList {
            var left = roundTarget
            while (left > 0) {
                val n = minOf(left, cap)
                add(n)
                left -= n
                // 防御：万一 cap 是 0 会死循环
                if (n <= 0) break
            }
        }
        note("要打 $roundTarget 次，按连打上限 $cap 拆成 ${batches.size} 批：$batches")

        var totalDone = 0
        for ((idx, batch) in batches.withIndex()) {
            checkCancelled()
            if (idx > 0) {
                // 第二批起要重新进本
                note("—— 第 ${idx + 1} 批：$batch 次（需重新进本）——")

                // ## 先试着从当前界面「就地」回生存索引
                //
                // 原项目 `power.py` 靠 `screen.change_to('guide3')` 做**图寻路**：
                // 只要能抵达目标界面就行，走最短路径。
                //
                // 我们之前是"一律退回大厅 → 重开手机 → 指南 → 生存索引"，
                // 多绕两三步。而实测常见情况是：刚打完副本退出后还停在
                // **指南页**（副本页的关闭按钮就是退到这一层），
                // 这时直接点顶部 tab 切回「生存索引」就够了。
                //
                // 顺序：
                //   ① 已在指南的某个 tab → 直接切 tab
                //   ② 都不行 → 退回大厅走完整流程
                if (!nav.isAt(ScreenNavigator.Screen.MAIN)) {
                    if (nav.guideTab() != null) {
                        note("仍在指南页，直接切回「生存索引」")
                        nav.switchGuideTab(ScreenNavigator.GuideTab.SURVIVAL)
                    } else {
                        nav.closeCurrent()
                    }
                }

                if (!nav.gotoGuide3() || !selectInstanceType(def)) {
                    note("重新进本失败，停止")
                    break
                }
                val again = selectAndEnterInstance(def) ?: run {
                    note("重新进本失败，停止")
                    break
                }
                note("再次进入副本「$again」")
            }

            // ⑨ 设连打次数
            val attemptsSet = if (batch > 1) setAttempts(batch, def) else true
            val actualPlanned = if (attemptsSet) batch else 1
            if (!attemptsSet) note("设置连续挑战次数失败，本批只打 1 次")

            // ⑩ 开打
            if (!startFight(isDiffUniverse = def.wireName.contains("饰品提取"))) {
                exitInstance()
                return RoundOutcome(
                    RoundStatus.FAILED,
                    done = totalDone,
                    instanceName = instanceName,
                    detail = "无法开始挑战",
                )
            }

            // ⑪ 等结算
            val settled = waitUntilSettled()
            if (settled != SettleResult.DONE) {
                exitInstance()
                val status = when (settled) {
                    SettleResult.BAG_FULL -> RoundStatus.BAG_FULL
                    SettleResult.TIMEOUT, SettleResult.FAILED -> RoundStatus.FAILED
                    SettleResult.DONE -> RoundStatus.OK
                }
                return RoundOutcome(status, done = totalDone, instanceName = instanceName)
            }

            // ⑫ 退出副本
            exitInstance()
            totalDone += actualPlanned
            note("本批完成 $actualPlanned 次（累计 $totalDone/$roundTarget）")
        }

        return RoundOutcome(
            RoundStatus.OK,
            done = totalDone,
            instanceName = instanceName,
        )
    }

    // ==================== 自由移动（差分宇宙）====================

    /** 移动方向。 */
    private enum class MoveDir(val x: Int, val y: Int, val label: String) {
        UP(JOY_UP_X, JOY_UP_Y, "前"),
        DOWN(JOY_DOWN_X, JOY_DOWN_Y, "后"),
        LEFT(JOY_LEFT_X, JOY_LEFT_Y, "左"),
        RIGHT(JOY_RIGHT_X, JOY_RIGHT_Y, "右"),
    }

    /**
     * 朝指定方向**长按摇杆**，持续 [durationMs]。
     *
     * 摇杆不需要拖动 —— 长按对应方向的三角就会持续朝那个方向走。
     */
    private fun move(dir: MoveDir, durationMs: Long = 1_500L): Boolean {
        note("移动：${dir.label}（长按 ${durationMs}ms）@(${dir.x},${dir.y})")
        val ok = auto.longPress(dir.x, dir.y, durationMs)
        sleepInterruptible(300)
        return ok
    }

    /**
     * **向前走一小段** —— 差分宇宙进入副本后的第一步。
     *
     * 用户实测：点完「开始挑战」后会进入自由移动场景，
     * 需要**向前移动**才能触发战斗。
     */
    fun walkForward(durationMs: Long = 1_500L): Boolean = move(MoveDir.UP, durationMs)

    /** 累积说明，拼进结果字符串。 */
    private fun tail(): String =
        if (notes.isEmpty()) "" else notes.joinToString("；", prefix = "。过程：")

    // ==================== 副本类型 ====================

    /**
     * 在生存索引左侧列表里点选副本类型。
     *
     * 「凝滞虚影」这类在默认视口里可能看不到，需要往下滚
     * （M7A `instance.py:135-153` 也是"找不到就滚再找"）。
     *
     * ⚠️ 滚到底仍然没找到就如实失败 —— 不点"最近的那个"。
     */
    private fun selectInstanceType(def: PowerInstanceTable.Entry): Boolean {
        val direct = trySelectInstanceType(def.wireName)
        if (direct) {
            note("已选副本类型「${def.wireName}」")
            return true
        }

        note("未直接命中「${def.wireName}」，滚动列表再试")
        repeat(4) { i ->
            checkCancelled()
            nav.swipeUpInList()
            if (trySelectInstanceType(def.wireName)) {
                note("滚动 ${i + 1} 次后选中「${def.wireName}」")
                return true
            }
        }

        // 可能之前滚过头了，滚回去再试
        repeat(4) {
            checkCancelled()
            nav.swipeDownInList()
            if (trySelectInstanceType(def.wireName)) {
                note("回滚后选中「${def.wireName}」")
                return true
            }
        }
        return false
    }

    /**
     * 在**左侧副本类型列表**里找并点击一个副本类型。
     *
     * ## 用区域 OCR + 模糊匹配
     *
     * 列表里的字 OCR 不一定 100% 认对（实测「侵蚀隧洞」可能读成
     * 「侵蚀隧道」「侵烛隧洞」），所以：
     *
     * 1. 只 OCR **左栏那一块**（[CROP_INSTANCE_TYPE]），又快又准
     * 2. 匹配用多策略：完全相等 → 互相包含 → **字符重合度过半**
     *
     * 第 3 条是关键：「侵蚀隧洞」和「侵蚀隧道」重合 3/4 个字，
     * 应该认作同一个。靠它兜住 OCR 的小误差。
     */
    private fun trySelectInstanceType(wireName: String): Boolean {
        val wanted = wireName.replace(" ", "")
        val frame = nav.grabFrameMat() ?: return false
        return try {
            val region = cropOf(CROP_INSTANCE_TYPE, frame.cols(), frame.rows())
            val all = OcrEngine.run(frame, region)
            if (all.isEmpty()) {
                Ln.w("$TAG: 副本类型列表 OCR 为空")
                return false
            }
            Ln.i("$TAG: 副本类型列表 OCR: " + all.joinToString { "\"${it.text}\"@(${it.cx},${it.cy})" })

            fun norm(s: String) = s.replace(" ", "").replace("\u3000", "")

            // ① 完全相等 / 互相包含（最可靠）
            var hit = all.firstOrNull {
                val t = norm(it.text)
                t == wanted || t.contains(wanted) || wanted.contains(t)
            }

            // ② 字符重合度兜底：读错一个字也能认出来
            if (hit == null) {
                hit = all
                    .filter { norm(it.text).length >= 2 }
                    .maxByOrNull { o ->
                        val t = norm(o.text)
                        val common = t.count { c -> wanted.contains(c) }
                        common.toFloat() / maxOf(t.length, wanted.length)
                    }
                    ?.takeIf { o ->
                        val t = norm(o.text)
                        val common = t.count { c -> wanted.contains(c) }
                        common.toFloat() / maxOf(t.length, wanted.length) >= 0.5f
                    }
            }

            if (hit == null) {
                Ln.w("$TAG: 列表里没认出「$wanted」")
                return false
            }

            Ln.i("$TAG: 命中「${hit.text}」@(${hit.cx},${hit.cy}) 置信=${"%.2f".format(hit.score)}")
            auto.tap(hit.cx, hit.cy)
            sleepInterruptible(SETTLE_MS)
            true
        } finally {
            frame.release()
        }
    }

    // ==================== 副本列表 ====================

    /**
     * 在副本列表里找到目标副本并传送。
     *
     * ## 关键点：点「传送」而不是点副本名
     *
     * 列表项本身点了不一定有反应（有些版本是"选中预览"，有些是"直接进"）。
     * M7A 的做法是点**离副本名最近的「传送/进入/追踪」按钮**
     * （`instance.py:126-133` 的 `min_distance_text`）。
     *
     * 这里用等价的简单形式：找到副本名那一行，再在列表区里找
     * `传送/进入/追踪` 中 **y 最接近**的那个。
     *
     * ## 「追踪」= 未解锁
     *
     * 最近的按钮若是「追踪」，说明副本没解锁，**立刻停下**
     * （M7A `instance.py:137-143` 同样处理）。
     *
     * @return 进入后的副本名；失败返回 null
     */
    private fun selectAndEnterInstance(def: PowerInstanceTable.Entry): String? {
        val wanted = config.instanceName.ifBlank { def.defaultInstanceName }

        repeat(4) { round ->
            checkCancelled()
            val frame = nav.grabFrameMat() ?: run {
                note("取帧失败")
                return null
            }
            val entered = try {
                val listRegion = cropOf(CROP_INSTANCE_LIST, frame.cols(), frame.rows())
                val texts = OcrEngine.run(frame, listRegion)

                // 找副本名 —— 排除掉按钮本身的文字
                val nameHit = if (wanted.isBlank()) {
                    // 没指定副本名：不猜名字，改为"挑第一个可进入的行"。
                    // 下面用 enterHit 的 y 反推那一行的名字。
                    null
                } else {
                    texts.firstOrNull {
                        val t = it.normalized()
                        t.contains(wanted) && ENTER_TEXTS.none { e -> t == e }
                    }
                }

                if (nameHit == null && wanted.isNotBlank()) {
                    note("列表里没看到「$wanted」（第 ${round + 1} 轮），向下滚")
                    null
                } else {
                    // 找可传送的按钮。
                    //
                    // 两个分支共用这段：
                    //  - 指定了副本名 → 取与名字同一行（y 最接近）的按钮
                    //  - 没指定         → 取**最靠上**的那个可进入按钮
                    val enterCandidates = texts
                        .filter { o -> ENTER_TEXTS.any { e -> o.normalized().contains(e) } }

                    val enterHit = if (nameHit != null) {
                        // 指定了副本名 → 取与名字同一行（y 最接近）的按钮
                        enterCandidates.minByOrNull { o -> kotlin.math.abs(o.cy - nameHit.cy) }
                    } else {
                        // 没指定名字 → 取**最靠上**的可进入按钮
                        //
                        // OCR 返回的框顺序不保证按 y 排列。实测踩过：
                        // 饰品提取页点了「进入」@(1636,540)，而正确的在
                        // 第一行 y≈392，结果传送失败、卡死 25 秒。
                        enterCandidates
                            .filter { !it.normalized().contains("追踪") }
                            .minByOrNull { it.cy }
                    }

                    // 这一行的显示名（用于日志和"进入后验证"）
                    val rowName = nameHit?.text
                        ?: enterHit?.let { e ->
                            // 取同一行里最靠左的那段文字当副本名
                            texts.filter {
                                kotlin.math.abs(it.cy - e.cy) < 40 &&
                                    ENTER_TEXTS.none { t -> it.normalized() == t }
                            }.minByOrNull { it.x }?.text
                        }
                        ?: wanted

                    when {
                        enterHit == null -> {
                            note("看到「$wanted」但没找到传送按钮，改为直接点副本名")
                            nav.clickTextAndVerify(wanted, CROP_INSTANCE_LIST, pageChanged = true)
                            rowName
                        }
                        enterHit.normalized().contains("追踪") -> {
                            note("「$rowName」显示为「追踪」—— 副本未解锁")
                            return null
                        }
                        else -> {
                            note("点「${enterHit.text}」@(${enterHit.cx},${enterHit.cy}) 进入「$rowName」")
                            auto.tap(enterHit.cx, enterHit.cy)
                            sleepInterruptible(SETTLE_MS)
                            rowName
                        }
                    }
                }
            } finally {
                frame.release()
            }

            if (entered != null) {
                // 验证：只要画面明显变了就算进去了
                //
                // ## 优化：先走**快的图像判断**，再走慢的 OCR
                //
                //
                // 而"传送成功"的直接信号是**画面整体切换**（黑屏→加载→副本页），
                // 用像素差几百毫秒就能测出来，根本不用认字。
                if (waitForLeaveGuide(timeoutSec = 25)) {
                    note("已离开生存索引，判定传送成功（副本：$entered）")
                    return entered
                }
                note("点了传送但页面没变化，滚回列表顶部重试")
                repeat(3) { nav.swipeDownInList() }
                return@repeat
            }

            nav.swipeUpInList()
        }
        return null
    }

    /**
     * 等待离开「生存索引」页面。
     *
     * ## 为什么要这条判据
     *
     * 传送到副本后，画面会先黑一下再加载战斗准备页。
     * 这时候**画面上未必还显示副本名**（尤其进的是战斗准备页时），
     * 死等「回忆之蕾」这类文字会一直等到超时，把成功判成失败。
     *
     * 判据改成：页面上不再出现「生存索引」这个标题，并且出现了
     * 「开始挑战」/「挑战」/「队伍」这类副本内的特征词。
     */
    private fun waitForLeaveGuide(timeoutSec: Int = 25): Boolean {
        // ## 两层判断：先看画面是否还在「生存索引」，再看是否已进副本
        //
        // ⚠️ 之前有个致命缺陷：只在"画面刚变化的那一刻"做 OCR，
        // 然后立刻把基准帧重置成当前帧 —— 于是页面**加载完成之后**
        // 画面静止了，`frameChanged` 永远返回 false，OCR 再也不跑，
        // 直到超时。
        //
        // 实测就是这样卡在「位面饰品提取」页 25 秒：传送明明成功了，
        // 但因为判断时机错了，一直认不出来。
        //
        // 现在改成：
        // 1. **画面一变化就开始连续观察**（给页面 2 秒加载时间）
        // 2. 观察期内每轮都跑 OCR，任意一轮命中就返回
        // 3. 观察期外降频轮询（每 1 秒一次），避免长时间无谓 OCR
        val deadline = SystemClock.uptimeMillis() + timeoutSec * 1000L
        var baseline: org.opencv.core.Mat? = null
        var lastCheck = 0L
        var watchingUntil = 0L

        try {
            while (SystemClock.uptimeMillis() < deadline) {
                checkCancelled()
                val frame = nav.grabFrameMat()
                if (frame == null) {
                    sleepInterruptible(200)
                    continue
                }

                val base = baseline
                if (base == null) {
                    baseline = frame.clone()
                    frame.release()
                    sleepInterruptible(200)
                    continue
                }

                val now = SystemClock.uptimeMillis()
                if (nav.frameChanged(base, frame)) {
                    // 画面在变 → 进入 2 秒的高频观察期
                    watchingUntil = now + 2_000L
                    base.release()
                    baseline = frame.clone()
                }

                // 观察期内每次循环都判；观察期外每秒判一次
                val shouldCheck = now < watchingUntil || now - lastCheck >= 1_000L
                if (shouldCheck) {
                    lastCheck = now

                    // ① 先用**差分宇宙图标**快检（模板匹配几十毫秒）
                    //    命中就直接判定进副本，不用跑 OCR ——
                    //    这是位面饰品提取页独有的锚点，用户实测最稳。
                    if (nav.findInRegion(TEMPLATE_DIFF_UNIVERSE, 0.80, CROP_DIFF_ANCHOR) != null) {
                        Ln.i("$TAG: 命中差分宇宙图标 ⇒ 判定已进入副本")
                        frame.release()
                        return true
                    }

                    // 只 OCR **右侧面板 + 底部按钮区**：
                    // 副本页的所有特征文字都在这一带（开始挑战/难度/挑战次数…），
                    // 左侧是立绘和列表，全屏扫纯属浪费。
                    val texts = OcrEngine.run(frame, cropOf(CROP_INSTANCE_FEATURES, frame.cols(), frame.rows()))
                        .map { it.normalized() }
                    val joined = texts.joinToString("|")

                    // 还在生存索引？看左侧列表特征
                    val stillGuide = joined.contains("生存索引") && (
                        joined.contains("规则说明") || joined.contains("培养目标")
                        )
                    // 已进副本？三条路任一命中即可：
                    // ① 差分宇宙图标（位面饰品提取页，最可靠）
                    // ② 文字特征（其它副本页）
                    val hasDiffIcon = nav.findInRegion(
                        TEMPLATE_DIFF_UNIVERSE, 0.80, CROP_DIFF_ANCHOR,
                    ) != null
                    val inInstance = hasDiffIcon ||
                        joined.contains("开始挑战") ||
                        joined.contains("难度") ||
                        joined.contains("推荐队伍") ||
                        joined.contains("挑战次数") ||
                        joined.contains("自动战斗")
                    if (hasDiffIcon) Ln.i("$TAG: 命中差分宇宙图标 ⇒ 已进副本")

                    if (!stillGuide && inInstance) {
                        frame.release()
                        Ln.i("$TAG: 判定已进入副本页")
                        return true
                    }
                }

                frame.release()
                sleepInterruptible(250)
            }
            Ln.w("$TAG: 等离开生存索引超时（${timeoutSec}s）")
            return false
        } finally {
            baseline?.release()
        }
    }

    // ==================== 次数 ====================

    /**
     * 设置连续挑战次数：找到「+」按钮，点 (n-1) 次。
     *
     * M7A `instance.py:331-343`：默认 1 次，点一次 +1。
     * 找不到 + 按钮就**放弃设次数**（调用方会退化为单次挑战），
     * 而不是硬猜一个坐标点下去。
     */
    /**
     * 把「挑战次数」设成 [n]。
     *
     * ## 布局（1920×1080 实测）
     *
     * ```
     *  快捷补充开拓力              [开]
     *  ─────────────────────────────────
     *  (i) 挑战次数 1 ●────── 8     (+)
     *                  ↑ 滑块       ↑ 按钮
     * ```
     *
     * ## 为什么改成 OCR 驱动
     *
     * 原来用模板找「+」按钮（`screen/guide/plus.png`），问题：
     *
     * 1. `+` 是纯图形，和界面上其它加号（如体力栏的 `+`）长得一样；
     * 2. 用户实测反馈：**体力不够时下方会展开自动消耗说明，把整块
     *    内容往上顶**，模板的固定位置就失配了；
     * 3. 次数上限会随副本变（花萼 24 / 凝滞虚影 8 / 隧洞 6），
     *    靠模板看不出当前是多少。
     *
     * 改成 OCR 之后：先读「挑战次数 N」拿到**当前值**，再算需要点
     * 几次「+」或「−」，最后**读回验证**。位置怎么飘都不影响。
     *
     * @return 是否设置成功（已读回确认）
     */
    private fun setAttempts(n: Int, def: PowerInstanceTable.Entry): Boolean {
        // ## 不再先读当前值
        //
        // 原实现第一步是 readAttemptCount() 读当前次数，读不到就直接 false
        // —— 于是"OCR 认不出挑战次数"就等于"完全不能设次数"。
        //
        // 但次数滑块**每次进本都从 1 开始**，所以根本不需要读：
        // 目标 n 就是"从 1 点 n-1 次 +"。
        if (n <= 1) {
            note("目标次数就是 1，无需调整")
            return true
        }

        val delta = n - 1
        if (!adjustAttempts(delta, def)) {
            note("调整次数失败")
            return false
        }

        // ## 历战余响：点完就完事，**不做读回验证**
        //
        // 周本的次数上限是**动态的** —— 取决于「本周可领取奖励次数」
        // （第一张图里显示 `3/3`，用掉一次就变 `2/3`）。
        //
        // 也就是说滑块最大值每周、每次进本都不一样，要为它做 OCR 校验
        // 得先适配那个动态上限，成本高、收益低。
        //
        // 而且用户设的次数本来就被 UI 限制在 3 以内（滑块上限跟着副本走），
        // 点够次数就够了 —— 多验证一遍纯属自找麻烦。
        if (def.wireName.contains("历战余响")) {
            note("历战余响：已点 $delta 次 +（不做读回验证）")
            return true
        }

        // 点完等界面刷新，再读回验证（读不到也当成功 —— 已按固定坐标点过）
        sleepInterruptible(600)
        val after = readAttemptCount()
        if (after == null) {
            note("已点 $delta 次 +（读回失败，按已设置处理）")
            return true
        }
        if (after == n) {
            note("挑战次数已设为 $n")
            return true
        }

        note("次数未达预期（当前 $after，目标 $n），补点 ${n - after} 次")
        adjustAttempts(n - after, def)
        sleepInterruptible(600)
        val final = readAttemptCount()
        if (final == n || final == null) {
            note("挑战次数已设为 $n")
            return true
        }
        note("设置挑战次数失败（最终 $final，目标 $n）")
        return false
    }

    /**
     * 读「挑战次数」当前值。
     *
     * 同一行上 OCR 会读出 `挑战次数`、`N`、以及可能的 `−`/`+` 符号。
     * 取和「挑战次数」**同一行、且是纯数字**的那一个。
     */
    private fun readAttemptCount(): Int? = nav.withFrameOcr { mat ->
        val region = cropOf(CROP_ATTEMPTS, mat.cols(), mat.rows())
        val texts = OcrEngine.run(mat, region)
        Ln.i("$TAG: 次数区 OCR: " + texts.joinToString { "\"${it.text}\"(${it.cx},${it.cy})" })

        // ## OCR 常把「挑战次数 1」读成**一整串** `挑战次数1`
        //
        // 实测日志：`"挑战次数1"(1543,833)`
        //
        // 所以不能只找"纯数字"的框，要从「挑战次数」那段文字的**结尾**
        // 里把数字抠出来。顺序：
        //  ① 同一段文字里紧跟在「挑战次数」后面的数字
        //  ② 同一行里独立的纯数字框
        val label = texts.firstOrNull { it.normalized().contains("挑战次数") }

        // ① 从标签文字尾部抠数字
        label?.let { o ->
            val t = o.normalized()
            val idx = t.indexOf("挑战次数")
            if (idx >= 0) {
                val tail = t.substring(idx + 4)
                val digits = tail.takeWhile { it.isDigit() }
                if (digits.isNotEmpty()) return@withFrameOcr digits.toIntOrNull()
                // 数字可能被识别成右括号/空格隔开，退一步：整段里的最后一段数字
                val all = tail.filter { it.isDigit() }.takeIf { it.isNotEmpty() }
                if (all != null) return@withFrameOcr all.toIntOrNull()
            }
        }

        // ② 同一行里的纯数字框
        texts
            .filter { o ->
                val t = o.normalized()
                val same = label == null || kotlin.math.abs(o.cy - label.cy) < 60
                same && t.isNotEmpty() && t.all { it.isDigit() } && t.length <= 2
            }
            .minByOrNull { kotlin.math.abs(it.cx - (label?.cx ?: it.cx)) }
            ?.normalized()?.toIntOrNull()
    }

    /**
     * 点 [delta] 次「+」（正）或「−」（负）。
     *
     * ## 用**模板匹配**定位按钮（不再用固定坐标）
     *
     * 实测这一行在不同副本页、不同内容下 y 会漂移 10~80px：
     *
     * ```
     * 有「活动期间双倍奖励」黄条时：+ 在 y≈865
     * 没有黄条时：                  + 在 y≈793
     * ```
     *
     * 固定坐标必然失败 —— 之前把 y=878 砸在了下面的「开始挑战」
     * 按钮上，所以"怎么点次数都不变"。
     *
     * 而这两个按钮是**纯白胶囊 + 黑色符号**，在深色面板上对比度极高，
     * 模板匹配非常稳 —— 位置漂移多少都能找到。
     */
    private fun adjustAttempts(delta: Int, def: PowerInstanceTable.Entry): Boolean {
        if (delta == 0) return true

        val template = if (delta > 0) TEMPLATE_BTN_PLUS else TEMPLATE_BTN_MINUS
        val hit = nav.findInRegion(template, 0.75, CROP_SLIDER_ROW)
        if (hit == null) {
            note("没找到次数按钮 $template（次数未调整）")
            return false
        }

        val (cx, cy) = matchCenter(hit)
        val count = kotlin.math.abs(delta)
        note(
            "点「${if (delta > 0) "+" else "-"}」按钮 @($cx,$cy) 共 $count 次" +
                "（模板 ${"%.2f".format(hit.score)}，${def.wireName}）",
        )

        repeat(count) {
            checkCancelled()
            auto.tap(cx, cy, durationMs = 80L)
            sleepInterruptible(TAP_INTERVAL_MS)
        }
        return true
    }



    // ==================== 补充开拓力（对齐 March7thAssistant）====================

    /**
     * 补充开拓力 —— 完全对齐 March7thAssistant `power.py` 的 `Power.get()`。
     *
     * # 原项目怎么做的
     *
     * ```python
     * screen.change_to('map')                    # 切到地图页
     * auto.click_element("trailblaze_power.png", crop=...)   # 点右上角开拓力
     * auto.find_element("confirm.png")            # 等弹窗出现
     * auto.click_element("reserved_trailblaze_power.png")    # 勾选"后备开拓力"
     * move_button_and_confirm()                   # 滑块拖到最右 + 确认
     * ```
     *
     * 补充弹窗长这样：
     *
     * ```
     * ┌─ 补充开拓力 ─────────────┐
     * │ [ ] 后备开拓力    [图标]  │  reserved_trailblaze_power.png
     * │ [ ] 燃料          [图标]  │  fuel.png
     * │ [ ] 星琼          [图标]  │  stellar_jade.png（**不用**）
     * │  [════●──────]           │  button.png 按住拖到 plus.png
     * │        [确认]            │  zh_CN/base/confirm.png
     * └──────────────────────────┘
     *
     * # 为什么用弹窗而不是副本页那个小开关
     *
     * 副本页的「快捷补充开拓力」是**单个布尔开关**，只能表达"用/不用"，
     * 而且状态是纯图形、OCR 读不出来，点了也不知道切没切过去。
     *
     * 弹窗则能：
     * - **分别**勾选后备开拓力 / 燃料（原项目两个配置项独立）；
     * - 通过拖动滑块**指定补充到多少**；
     * - 明确保证**不点星琼**。
     *
     * # 优先级
     *
     * 原项目的 `if / elif` 是"后备开拓力优先，没有才用燃料"。
     * 这里保持一致。
     *
     * @return 是否成功补充
     */
    /**
     * 确保「快捷补充开拓力」处于**开启**状态。
     *
     * # 怎么判断开关状态
     *
     * 开关是**橙色胶囊**（开）/ 灰色胶囊（关）。用模板匹配判断：
     *
     * - 命中 `refill_switch_on.png`（橙色、圆点在右）→ 已开启
     * - 未命中 → 认为关闭，点一次再复查
     *
     * # 为什么用模板而不是 OCR
     *
     * 开关是纯图形，OCR 读不出"开还是关"。而它位置固定
     * （在「挑战次数」上方一行），模板匹配非常稳定。
     *
     * # 位置会浮动
     *
     * 用户反馈：体力不足时下方会展开消耗预览，把整块内容往上顶。
     * 所以搜索区域要给足纵向余量（0.55~0.75），不能卡死一行。
     *
     * @return 最终是否处于开启状态
     */

    private fun refillPower(): Boolean {
        note("开始补充开拓力")

        // ① 找到并点击右上角的开拓力数字（打开补充弹窗）
        //
        // 用模板定位而不是写死坐标 —— 原项目 PC 版用的是
        // (1588/1920, 35/1080, 198/1920, 56/1080)，
        // 手游位置不同，所以这里限定在右上角区域里找图标。
        val opened = nav.clickImage("share/power/trailblaze_power/trailblaze_power.png", 0.90, 3)
        if (!opened) {
            note("没找到开拓力图标，无法打开补充弹窗")
            return false
        }
        sleepInterruptible(1200)

        // ② 等弹窗出现（用确认按钮当判据，和原项目一致）
        if (!waitForTemplate("zh_CN/base/confirm.png", 0.90, timeoutSec = 8)) {
            note("补充弹窗没有出现")
            closePopup()
            return false
        }
        note("补充弹窗已打开")

        // ③ 勾选资源：后备开拓力优先，其次燃料
        val usedReserved = nav.findInRegion(
            "share/power/trailblaze_power/reserved_trailblaze_power.png", 0.90, null,
        )
        val usedFuel = nav.findInRegion(
            "share/power/trailblaze_power/fuel.png", 0.90, null,
        )

        val picked = when {
            usedReserved != null -> {
                note("勾选「后备开拓力」")
                tapMatch(usedReserved)
                true
            }
            usedFuel != null -> {
                note("没有后备开拓力，勾选「燃料」")
                tapMatch(usedFuel)
                true
            }
            else -> false
        }

        if (!picked) {
            note("后备开拓力和燃料都没有，无法补充")
            closePopup()
            return false
        }

        sleepInterruptible(800)

        // ④ 拖动滑块到最右（补到最大）
        return moveSliderAndConfirm()
    }

    /**
     * 把补充滑块拖到最右并确认 —— 对齐原项目的 `move_button_and_confirm()`。
     *
     * 原实现：
     * 1. 找 `button.png`（滑块圆点）→ **按下**
     * 2. 找 `plus.png`（最右端的 +）→ **移动过去**
     * 3. 松手
     * 4. 点 `confirm.png` 确认
     *
     * 手游上「按下-拖动-松开」用 [ShellAutomation.swipe] 实现，
     * 从滑块位置拖到 + 的位置即可（中间有足够时长让游戏识别为拖拽）。
     */
    private fun moveSliderAndConfirm(): Boolean {
        val button = nav.findInRegion("share/power/trailblaze_power/button.png", 0.90, null)
        val plus = nav.findInRegion("share/power/trailblaze_power/plus.png", 0.90, null)

        if (button != null && plus != null) {
            val from = matchCenter(button)
            val to = matchCenter(plus)
            note("拖动滑块 (${from.first},${from.second}) → (${to.first},${to.second})")
            // 用 swipe 模拟"按住拖过去"，600ms 让游戏识别为拖拽而非点击
            auto.swipe(from.first, from.second, to.first, to.second, durationMs = 600)
            sleepInterruptible(600)
        } else {
            note("没找到滑块或 +，跳过拖动（可能已默认最大）")
        }

        // ⑤ 确认
        if (!nav.clickImage("zh_CN/base/confirm.png", 0.90, 3)) {
            note("点确认失败")
            closePopup()
            return false
        }
        note("已确认补充")
        sleepInterruptible(1500)
        return true
    }

    /** 等某个模板出现（用于等弹窗）。 */
    private fun waitForTemplate(path: String, threshold: Double, timeoutSec: Int): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutSec * 1000L
        while (SystemClock.uptimeMillis() < deadline) {
            checkCancelled()
            if (nav.findInRegion(path, threshold, null) != null) return true
            sleepInterruptible(500)
        }
        return false
    }

    /** 模板匹配结果 → 像素中心点。 */
    private fun matchCenter(r: ImageUtils.MatchResult): Pair<Int, Int> {
        val sf = ImageUtils.BASE_HEIGHT.toDouble() / auto.height
        val cx = ((r.x + r.templateWidth * r.scale / 2) / sf).toInt()
        val cy = ((r.y + r.templateHeight * r.scale / 2) / sf).toInt()
        return cx to cy
    }

    /** 点一下模板匹配到的位置。 */
    private fun tapMatch(r: ImageUtils.MatchResult): Boolean {
        val (x, y) = matchCenter(r)
        return auto.tap(x, y)
    }

    /** 关闭补充弹窗（BACK 键，手游没有 ESC）。 */
    private fun closePopup() {
        runCatching { auto.pressKey(android.view.KeyEvent.KEYCODE_BACK) }
        sleepInterruptible(500)
    }

    /**
     * 按体力算出**总共**要打几次（跨多轮）。
     *
     * ## 和旧 planAttempts 的区别
     *
     * 旧版算的是"这一轮打几次"，受游戏连打上限约束（花萼 24 次）。
     * 体力多的时候（比如 240 体力 = 24 次）刚好够，但换成
     * 侵蚀隧洞（每次 40 体力、连打上限 6）就打不完了 —— 240÷40 = 6 次，
     * 刚好等于上限，再往上就必须分轮。
     *
     * 所以这里返回**不受单轮上限约束**的总次数，由主循环拆成多轮。
     *
     * @param currentPower 当前体力；**null 表示没读到**
     * @param cost 单次消耗
     * @param maxRuns 用户配置的上限；0 = 不限制
     */
    /**
     * 算出这次任务**总共要打几次**。
     *
     * ## 核心规则：看「自动补充」开没开
     *
     * 用户实测确认的游戏行为：
     *
     * | 自动补充 | 体力不足时 | 后果 |
     * |---|---|---|
     * | **开着** | 游戏自己从后备/燃料补 | 不弹窗，能一直打 |
     * | **关着** | **弹窗「需要手动补充」** | **卡住，进不去** |
     *
     * 所以：
     * - **开了补充** → 按用户设的次数打（体力不是限制，游戏会补）；
     * - **没开补充** → **必须按体力截断**，否则会撞上无法自动处理的
     *   弹窗，任务必然卡死。
     *
     * ## 读不到体力时
     *
     * 保守只打 1 次 —— 宁可少刷，也不能撞弹窗。
     *
     * @param currentPower 当前开拓力（可能的可用量，见调用方）；null = 没读到
     * @param cost 单次消耗
     * @param maxRuns 用户设的上限；0 = 不限
     * @param allowRefill 是否开启「快捷补充开拓力」
     */
    fun planTotalRuns(currentPower: Int?, cost: Int, maxRuns: Int, allowRefill: Boolean = false): Int {
        if (cost <= 0) return 1

        val byPower = currentPower?.let { if (it > 0) it / cost else 0 }

        return when {
            // ① 开了自动补充：体力不是限制，按用户设定
            allowRefill -> if (maxRuns > 0) maxRuns else (byPower ?: 1).coerceAtLeast(1)

            // ② 没开补充：**必须**按体力截断，否则会撞上手动补充弹窗
            else -> {
                val affordable = byPower ?: 0
                if (affordable <= 0) 0                       // 连一次都打不起
                else if (maxRuns > 0) minOf(affordable, maxRuns)
                else affordable
            }
        }
    }

    // ==================== 开打 ====================

    /**
     * 点「开始挑战」/「挑战」。
     *
     * ## 为什么要分区
     *
     * 「饰品提取」的按钮在右下（[CROP_START_FIGHT]），其余副本在
     * [CROP_CHALLENGE]。先在限定区里找（更准），找不到再全屏兜底。
     *
     * ## 只认这两个词
     *
     * 「模拟战斗」「快速战斗」这类相似按钮语义完全不同，绝不点。
     */
    private fun startFight(isDiffUniverse: Boolean = false): Boolean {
        // ## 两步点击（缺一不可）
        //
        // 崩铁的进本流程是**两个页面**：
        //
        // ```
        // ① 副本入口页：设好次数 → 点「挑战」
        //       ↓ 切到队伍配置页（有 1~1.5 秒的转场动画）
        // ② 队伍配置页：点「开始挑战」→ 才真正进战斗
        // ```
        //
        // 之前只点了 ②，没有点 ① —— 所以一直停在副本入口页不动。
        //（早期用 OCR 找「挑战」时，那个词把「挑战次数」也匹配上了，
        // 反而点错了地方，掩盖了"根本没点挑战按钮"这个事实。）

        // ① 点「挑战」（副本入口页右下角）
        val c1x = (auto.width * CHALLENGE_BTN_X_RATIO).toInt()
        val c1y = (auto.height * CHALLENGE_BTN_Y_RATIO).toInt()
        note("点「挑战」@($c1x,$c1y)")
        auto.tap(c1x, c1y, durationMs = 120L)

        // 等转场到队伍配置页
        sleepInterruptible(CHALLENGE_TRANSITION_MS)

        // ② 点「开始挑战」（队伍配置页右下角）
        val c2x = (auto.width * START_BTN_X_RATIO).toInt()
        val c2y = (auto.height * START_BTN_Y_RATIO).toInt()
        note("点「开始挑战」@($c2x,$c2y)")
        auto.tap(c2x, c2y, durationMs = 120L)

        // 点完等战斗加载（黑屏转场）—— 可中断，随时能停
        sleepInterruptible(2500)

        // ## 差分宇宙（位面饰品提取）特殊处理
        //
        // 它**不像其它副本直接进战斗** —— 点完「开始挑战」后会进入
        // 一个**自由移动场景**，需要玩家自己走过去触发战斗。
        //
        // 所以这里额外做一步：等场景加载好（1 秒），然后长按摇杆
        // **向前移动**。
        if (isDiffUniverse) {
            note("差分宇宙：进入自由移动场景")
            sleepInterruptible(1_000)

            // ① 长按摇杆向前走（2500ms 走到下一段）
            walkForward(durationMs = WALK_FORWARD_MS)

            // ② 点攻击键触发战斗
            //
            // 不用任何识别 —— 每个角色的攻击键外观都不同。
            // 位置是所有角色统一的，直接点。
            note("点攻击键 @($ATK_BTN_X,$ATK_BTN_Y)")
            auto.tap(ATK_BTN_X, ATK_BTN_Y, durationMs = 100L)
            sleepInterruptible(1_500)
        }
        return true
    }

    // ==================== 战斗 ====================

    /** 等待结算的结果。 */
    private enum class SettleResult {
        /** 正常打完，停在结算页 */
        DONE,

        /** 遗器背包满，打不下去了 */
        BAG_FULL,

        /** 战斗失败 */
        FAILED,

        /** 超时 */
        TIMEOUT,
    }

    /**
     * 等**整个战斗流程**结束，停在结算页。
     *
     * ## 和旧的 waitFight 有什么不同
     *
     * 旧版一看到「再来一次」就认为"这场打完了"并返回，然后由外面的
     * 循环去点「再来一次」—— 但游戏内的连续挑战**本来就会自己连打**，
     * 于是两边叠加，造成"打完反而在结算页反复点再来一次"。
     *
     * 现在只认一个终点：**结算页上同时出现「退出关卡」**。
     * 不管游戏内部打了几场，只要退出按钮出现了，就说明全部结束。
     *
     * ## 为什么用 OCR 而不是模板
     *
     * 「退出关卡」是纯文字按钮，OCR 直接读字最稳；`fight_again.png`
     * 只在结算页出现、战斗中不出现，用它反而分不清"打完了"和
     * "又开了一局"。
     *
     * ## 战斗中断
     *
     * 中途可能弹出：
     * - 「已处于无法战斗状态」→ 点确定继续；
     * - 「背包内遗器持有数量已达上限」→ 无法继续，返回 [SettleResult.BAG_FULL]；
     * - `fight_fail.png` 出现 → 战斗失败。
     */
    private fun waitUntilSettled(): SettleResult {
        note("等待战斗流程结束")
        // 给游戏一点时间进入加载画面；可中断
        sleepInterruptible(5000)
        val startAt = SystemClock.uptimeMillis()
        val deadline = startAt + FIGHT_TIMEOUT_SEC * 1000L
        var lastHeartbeat = startAt

        // 画面停滞检测用：上一次的帧 + 最后一次"画面有变化"的时间
        var lastFrame: org.opencv.core.Mat? = null
        var lastChangeAt = startAt
        val stalledLimit = FIGHT_STALL_TIMEOUT_SEC * 1000L

        while (SystemClock.uptimeMillis() < deadline) {
            checkCancelled()
            // ⓪ 画面停滞检测：长时间无变化 ⇒ 战斗没开始，直接判失败
            run {
                val cur = nav.grabFrameMat()
                if (cur != null) {
                    if (lastFrame != null && nav.frameChanged(lastFrame, cur)) {
                        lastChangeAt = SystemClock.uptimeMillis()
                    }
                    lastFrame?.release()
                    lastFrame = cur
                }
                val stalled = SystemClock.uptimeMillis() - lastChangeAt
                if (stalled > stalledLimit) {
                    note("画面已停滞 ${stalled / 1000}s —— 战斗似乎没有开始，判定失败")
                    lastFrame?.release()
                    lastFrame = null
                    return SettleResult.FAILED
                }
            }

            // ① 结算页：出现「退出关卡」就是全部打完了
            if (nav.hasText("退出关卡")) {
                note("已到结算页")
                return SettleResult.DONE
            }

            // ② 遗器背包满 —— 必须单独区分，因为这是**可恢复但需要人工处理**
            //    的情况，不能跟"战斗失败"混为一谈
            if (nav.hasText("背包内遗器持有数量已达上限") ||
                nav.hasText("背包内遗器持有") ||
                nav.hasText("持有数量已达上限")
            ) {
                note("检测到遗器背包已满，无法继续")
                nav.clickImage("zh_CN/base/confirm.png", 0.90, 3)
                return SettleResult.BAG_FULL
            }

            // ③ 战斗失败
            if (nav.findInRegion("zh_CN/fight/fight_fail.png", 0.90, null) != null) {
                note("战斗失败")
                nav.clickImage("zh_CN/fight/fight_fail.png", 0.90, 3)
                return SettleResult.FAILED
            }

            // ④ 可恢复的弹窗
            if (nav.hasText("已处于无法战斗状态")) {
                note("检测到「已处于无法战斗状态」，点确定继续")
                nav.clickImage("zh_CN/base/confirm.png", 0.90, 3)
            }

            // ⑤ 心跳日志：每 30 秒报一次，方便判断"卡在战斗"还是"卡在加载"
            val now = SystemClock.uptimeMillis()
            if (now - lastHeartbeat > 30_000L) {
                note("仍在战斗中…（已等待 ${(now - startAt) / 1000}s）")
                lastHeartbeat = now
            }

            sleepInterruptible(2000)
        }
        note("战斗超时（${FIGHT_TIMEOUT_SEC}s）")
        return SettleResult.TIMEOUT
    }

    /*
     * ⚠️ **已移除 `clickFightAgain()`** —— 不要再加回来。
     *
     * 「再来一次」和游戏内的「连续挑战 N 次」是**互相冲突的两种模式**：
     *
     * - 连续挑战：设好次数后游戏自己连打 N 场，中间不回结算页；
     * - 再来一次：每打完一场都回结算页，靠再次点击进入下一场。
     *
     * 早期实现两者都用了 —— 先设连续挑战 N 次，又在外面循环点 N-1 次
     * 「再来一次」，结果打完一轮后还在结算页反复点，要好几次才轮到退出。
     *
     * 现在统一走**连续挑战**（战斗节奏由游戏控制，更稳），
     * 只等「退出关卡」出现就收工。
     */

    /** 退出副本，尽量回到能导航的状态。 */
    /**
     * 退出副本，回到大厅。
     *
     * ## 结算页的按钮文字是「退出关卡」，不是「退出」
     *
     * 实测（1920×1080 结算帧）：底部两个按钮
     * 「**退出关卡**」（左，中心约 660,935）和「再来一次」（右）。
     *
     * ## 为什么要 OCR + 模板双保险
     *
     * 模板 `zh_CN/fight/fight_exit.png` 是**从 PC 版裁的**，
     * 实测在同一帧上能到 0.917，但 App 里跑起来**经常报"未找到"**
     * （`clickImage` 5 次全失败，每次取帧要 5 秒，白白耗掉 26 秒）。
     *
     * 与其纠结模板匹配的边缘情况，不如直接用 OCR 点文字 ——
     * 它按「退出关卡」这四个字找，位置怎么变都能命中，
     * 也和其它步骤的实现方式保持一致。
     *
     * 顺序：先 OCR 点「退出关卡」→ 失败再退回模板 → 最后逐级 BACK。
     */
    private fun exitInstance() {
        note("退出副本")

        // ① OCR 点「退出关卡」（结算页的主路径）
        val exitTexts = listOf("退出关卡", "退出挑战", "退出")
        var clicked = false
        for (t in exitTexts) {
            if (nav.clickTextAndVerify(t, null, pageChanged = false, maxRetries = 2)) {
                note("已点「$t」")
                clicked = true
                break
            }
        }

        // ② 模板兜底（万一 OCR 没读出来）
        if (!clicked && nav.clickImage("zh_CN/fight/fight_exit.png", 0.90, 3)) {
            note("已点「退出关卡」（模板）")
            clicked = true
        }

        if (clicked) {
            // ## 优化：不再死等 5 秒
            //
            // 原来点完「退出关卡」固定 sleep 5000ms 再看界面 ——
            // 实测退出转场通常 1~2 秒就结束了，白等 3 秒。
            //
            // 现在改成**轮询判断是否已回到大厅**，命中立刻返回。
            // 同时顺手处理可能出现的二次确认弹窗。
            val exitDeadline = SystemClock.uptimeMillis() + 8000
            var confirmed = false
            while (SystemClock.uptimeMillis() < exitDeadline) {
                checkCancelled()
                if (nav.isAt(ScreenNavigator.Screen.MAIN)) {
                    note("已回到大厅")
                    return
                }
                // 只在还没点过的情况下尝试二次确认，避免反复点
                if (!confirmed) {
                    for (c in listOf("确定", "确认")) {
                        if (nav.clickTextAndVerify(c, null, pageChanged = false, maxRetries = 1)) {
                            note("已确认「$c」")
                            confirmed = true
                            break
                        }
                    }
                }
                sleepInterruptible(400)
            }
        } else {
            note("没找到退出按钮，尝试逐级返回")
        }

        // ③ 逐级返回，直到回到大厅
        repeat(6) {
            checkCancelled()
            if (nav.isAt(ScreenNavigator.Screen.MAIN)) {
                note("已回到大厅")
                return
            }
            if (nav.waitFor(ScreenNavigator.Screen.MAIN, 3)) {
                note("已回到大厅")
                return
            }
            nav.closeCurrent(2)
        }
        note("未能自动回到大厅")
    }

    // ==================== OCR 辅助 ====================

    /**
     * 读当前开拓力数值。
     *
     * 数值在右上角，形如 `240/300`。OCR 可能读成 `240/300 +`、
     * `240 / 300`，所以：先取 `/` 前部分，只留数字，再做区间校验。
     *
     * 读不到返回 null —— **不猜**。猜错的次数会直接打空体力槽。
     */

    /** 等某段文字出现在画面上。 */
    private fun waitForText(text: String, timeoutSec: Int): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutSec * 1000L
        while (SystemClock.uptimeMillis() < deadline) {
            if (nav.hasText(text)) return true
            sleepInterruptible(1500)
        }
        return false
    }

    /** 归一化矩形 → 像素 Rect（并夹到帧范围内）。 */
    private fun cropOf(r: ScreenNavigator.NormRect, w: Int, h: Int): Rect {
        val (x, y, cw, ch) = r.toPixels(w, h)
        val sx = x.coerceIn(0, w - 1)
        val sy = y.coerceIn(0, h - 1)
        return Rect(
            sx, sy,
            cw.coerceIn(1, w - sx),
            ch.coerceIn(1, h - sy),
        )
    }
}
