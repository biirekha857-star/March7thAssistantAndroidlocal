package com.march7th.hkrpg.starrail

import android.os.SystemClock
import com.march7th.hkrpg.data.model.ForgottenHallConfig
import com.march7th.hkrpg.starrail.engine.ImageUtils
import com.march7th.hkrpg.starrail.engine.OcrEngine
import com.march7th.hkrpg.starrail.engine.ScreenNavigator
import com.march7th.hkrpg.starrail.engine.ShellAutomation
import com.march7th.hkrpg.third.Ln

/**
 * 忘却之庭 · **混沌回忆**（移植自 March7thAssistant 的
 * `tasks/challenge/memoryofchaos.py`）。
 *
 * # 完整链路
 *
 * ```
 * 大厅
 *   → 手机 → 指南 → 逐光捡金            (ScreenNavigator.gotoGuide4)
 *   → 点「忘却之庭」→ 右侧详情出「混沌回忆」(ScreenNavigator.openChaosDetail)
 *   → 读进度 x/36，满了就直接收工        (ScreenNavigator.readChaosStars)
 *   → 点「传送」→ 混沌回忆页             (ScreenNavigator.teleportToChaos)
 *   → 关卡栏滑到最左，逐层：
 *        找「07」→ 数星星 → 满 3 星跳过
 *        → 点层号 → 配队页点「开始挑战」
 *        → 走向敌人 / 开自动战斗 / 等「返回忘却之庭」
 *        → 点返回，回列表
 *   → 点红点 → 循环点「领取」→ 关弹窗
 *   → 退回大厅
 * ```
 *
 * # 和上游的差异（都是**刻意**的）
 *
 * | 上游做法 | 这里 | 原因 |
 * |---|---|---|
 * | `select_characters()` 按名字点头像、滚 19 行 × 4 次 | **不做** | 混沌回忆的队伍是**按层持久保存**的，配过一次就一直有效；手游选人还要处理命途弹窗，第一版不冒这个险 |
 * | `press_key('w')` 前进 4 秒 | 长按虚拟摇杆 | 手机没有键盘 |
 * | `press_key('1'..'4')` 切人放秘技 | **不做** | 见下 |
 * | `press_key(hotkey_auto_battle)` 开自动 | 点左下角「自动」图标 | 手机没键盘；图标位置由模板定位 |
 * | `mouse_scroll` 横向滚关卡栏 | `swipe` 横滑 | 手机没有滚轮 |
 *
 * ## 为什么不做「切人放秘技」
 *
 * 崩铁战斗里手动切人放秘技的收益只在**开场**（进战斗前叠 buff）。
 * 自动战斗打开之后，游戏自己会切人放技能。为了"开场多两次秘技"去
 * 实现一套角色头像识别 + 数字键映射，风险和收益完全不成比例。
 * 所以这里只保证"进战斗 + 开自动"，剩下的交给游戏。
 *
 * # 安全阀
 *
 * 1. **找不到的层不猜**：关卡栏滚到头都没找到就停，绝不"随便点一个层"。
 * 2. **星数读不到也打**：读不到星数（OCR 失手）时按"没满星"处理 ——
 *    宁可白打一层，也不要因为识别问题跳过该打的层（后者会漏奖励）。
 * 3. **单层有时间上限**：`battleTimeoutMin`（默认 30 分钟）到点就放弃该层，
 *    不会挂到天荒地老。
 * 4. **每步都写日志**：失败时能直接看出卡在哪一句。
 */
internal class ForgottenHallTask(
    private val auto: ShellAutomation,
    private val nav: ScreenNavigator,
    private val config: Params,
    /** 用户取消信号；每个等待循环都会检查。 */
    private val isCancelled: () -> Boolean = { false },
) {

    companion object {
        private const val TAG = "ForgottenHall"

        // ==================== 模板 ====================
        //
        // 全部来自 M7A `assets/images/forgottenhall/`，按 PC 分辨率裁，
        // 虚拟屏 1920×1080 与之逐像素一致，可以直接用。

        /** 「剩余轮」—— 战斗界面顶部独有的文字，用它判断"在不在战斗里"。 */
        private const val TEMPLATE_IN_BATTLE = "forgottenhall/prepare_fight.png"

        /** 「返回忘却之庭」—— 战斗/结算结束时右下角的返回按钮。 */
        private const val TEMPLATE_BACK = "forgottenhall/back.png"

        /** 「回忆」—— 挑战失败页上才出现的页签。 */
        private const val TEMPLATE_AGAIN = "forgottenhall/again.png"

        /** 角色无法战斗的暂停图标。 */
        private const val TEMPLATE_PAUSE = "forgottenhall/pause.png"

        /** 「开始挑战」按钮（配队页右下角）。 */
        private const val TEMPLATE_START = "forgottenhall/start.png"

        /** 「领取」按钮（奖励弹窗每一行右侧）。 */
        private const val TEMPLATE_RECEIVE = "forgottenhall/receive.png"

        // 注：`forgottenhall/star.png`（17×17）**故意不用** —— 实机星星是 40×40，
        // 尺度对不上，模板分数接近 0。星数改用数金色连通块，见 [judgeStars]。

        /** 红点（有未领奖励）。 */
        private const val TEMPLATE_RED_DOT = "share/base/RedExclamationMark.png"

        /** 自动战斗**未开启**时的图标。 */
        private const val TEMPLATE_NOT_AUTO = "share/base/not_auto.png"

        /** 通用确认按钮。 */
        private const val TEMPLATE_CONFIRM = "zh_CN/base/confirm.png"

        /** 通用关闭按钮。 */
        private const val TEMPLATE_CLOSE = "zh_CN/base/click_close.png"

        // ==================== 区域 ====================

        /**
         * 战斗界面顶部（「剩余轮」所在的横带）。
         *
         * 取左上 45%×20%：足够宽（「剩余轮」在左上角偏右一点），
         * 又不会把右侧的行动条读进来。
         */
        private val CROP_BATTLE_TOP = ScreenNavigator.NormRect(0.00f, 0.00f, 0.45f, 0.20f)

        /**
         * 战斗界面左下角的「自动战斗」按钮区。
         *
         * M7A：`crop=(0, 903/1080, 144/1920, 120/1080)`。
         */
        private val CROP_AUTO_BUTTON = ScreenNavigator.NormRect(
            0f / 1920f, 903f / 1080f, 144f / 1920f, 120f / 1080f,
        )

        /**
         * 星星带相对**层号文字框**的偏移（1920×1080 基准像素）。
         *
         * 实测：左边界 = 层号左边界 - 16；上边界 = 层号下边界 + 2；
         * 带宽 156（3 颗 40px 星 + 余量）；带高 64。
         * 四层（09/10/11/12）逐一验证过，见 [judgeStars] 的表。
         */
        private const val STAR_BAND_DX = 16f
        private const val STAR_BAND_DY = 2f
        private const val STAR_BAND_W = 156f
        private const val STAR_BAND_H = 64f

        /**
         * 一颗星的金色像素面积区间（1920×1080 实测量级）。
         *
         * 实测单颗约 300~330 像素，三颗合计 930~970 且互不相连。
         * 下限 80 容得下抗锯齿把边缘吃掉一半；上限 4000 挡住整片背景。
         */
        private const val STAR_MIN_AREA = 80
        private const val STAR_MAX_AREA = 4_000

        /**
         * 星星的颜色（**BGR** 顺序）。
         *
         * M7A 写的是 `pixel_bgr=[112,200,255]`，即 RGB(255,200,112) —— 金色。
         * 实机采样到的星点像素是 `FFCA6E / FBC968 / E8B969 / FACB73`，
         * 落在同一个色域里；背景是深紫 `2C185F` 一类，差得足够远。
         */
        private val STAR_COLOR_BGR = doubleArrayOf(112.0, 200.0, 255.0)

        /** 颜色容差（各通道差平方和）。比上游的 800 宽松，抗压缩噪点。 */
        private const val STAR_COLOR_TOLERANCE = 3_000.0

        // ==================== 自由移动（复刻 PowerTask）====================
        //
        // 数值与 [PowerTask] 里那组**实测过**的完全相同（同一套 1920×1080
        // 虚拟屏 UI）：摇杆中心 (372,839)，四个方向各偏 39~40px；
        // 普攻键 (1594,820)。差分宇宙上线后用户已验证这套坐标能用。

        private const val JOY_UP_X = 374
        private const val JOY_UP_Y = 800
        private const val ATK_BTN_X = 1594
        private const val ATK_BTN_Y = 820

        /** 一次「向前走」按住摇杆多久。M7A 是 `press_key('w', 4)` = 4 秒。 */
        private const val WALK_FORWARD_MS = 4_000L

        /** 走完之后连点几次普攻（每次间隔见 [ATTACK_GAP_MS]）。 */
        private const val ATTACK_TAPS = 3

        /** 普攻之间的间隔 —— 要等角色收招 + 判定命中。 */
        private const val ATTACK_GAP_MS = 1_200L

        /** 不在战斗时，隔多久重新"走过去开打"一次（防原地空转刷屏）。 */
        private const val FIELD_RETRY_MS = 12_000L

        /** 战斗轮询间隔（M7A 也是 2 秒）。 */
        private const val FIGHT_POLL_MS = 2_000L

        /** 开自动战斗失败后，隔多久再试（游戏有切换动画，不能连点）。 */
        private const val AUTO_RETRY_MS = 5_000L

        /** 关卡区最多横滑几次去找一个层号。M7A 是左右各 15 次 × 6 秒。 */
        private const val MAX_LEVEL_SWIPES = 14

        /** 开局"滑到最小层号那头"最多滑几次（看到第 1~2 层就早停）。 */
        private const val MAX_START_SWIPES = 10

        /** 层号文字的形状：1~2 位纯数字（`07` / `12` / `7`）。 */
        private val LEVEL_NUM_RE = Regex("""\d{1,2}""")

        /** 领奖最多点几次「领取」（防死循环）。 */
        private const val MAX_CLAIMS = 8

        /** 点完「开始挑战」后，等战斗真正开始的上限。 */
        private const val START_BATTLE_TIMEOUT_MS = 25_000L
    }

    /** 任务参数（由 `StarRailTasks.parseForgottenHallParams` 从 JSON 解析）。 */
    data class Params(
        val fromLevel: Int = 7,
        val toLevel: Int = 12,
        val skipFullStar: Boolean = true,
        val autoBattle: Boolean = true,
        val battleTimeoutMin: Int = 30,
        /**
         * 本期已满星时是否仍传送进去。
         *
         * 默认 false（满星就收工）。打开它可以把"传送 → 找层 → 数星"
         * 这后半条链路在满星期完整跑一遍，用于实机验证。
         */
        val enterEvenIfFull: Boolean = false,
    )

    /** 结果。 */
    data class Result(val ok: Boolean, val detail: String)

    /** 用户取消（与 PowerTask.Stopped 同构，让上层能统一 catch）。 */
    class Stopped(val partial: String = "") : ScreenNavigator.StopRequested()

    // ==================== 运行时状态 ====================

    private val notes = mutableListOf<String>()

    /** 上次尝试开自动战斗的时刻。 */
    private var lastAutoAt = 0L

    /** 上次"走向敌人"的时刻。 */
    private var lastFieldAt = 0L

    private fun sleepInterruptible(totalMs: Long, stepMs: Long = 200L) {
        val end = SystemClock.uptimeMillis() + totalMs
        while (SystemClock.uptimeMillis() < end) {
            checkCancelled()
            val left = end - SystemClock.uptimeMillis()
            SystemClock.sleep(minOf(stepMs, left).coerceAtLeast(1))
        }
    }

    private fun checkCancelled() {
        if (isCancelled()) throw Stopped(tail())
    }

    private fun note(s: String) {
        notes += s
        Ln.i("$TAG: $s")
    }

    private fun tail(): String =
        if (notes.isEmpty()) "" else notes.joinToString("；", prefix = "。过程：")

    // ==================== 主流程 ====================

    fun run(): Result {
        Ln.i(
            "$TAG: 开始忘却之庭 层=${config.fromLevel}~${config.toLevel} " +
                "跳过满星=${config.skipFullStar} 自动战斗=${config.autoBattle} " +
                "单层上限=${config.battleTimeoutMin}min",
        )

        if (!OcrEngine.isReady) {
            return Result(
                false,
                "OCR 引擎未就绪 —— 混沌回忆要靠 OCR 找层号和「开始挑战」，" +
                    "没有 OCR 无法保证点到正确的层（本次未做任何操作）",
            )
        }

        // ---- ① 起点：大厅 ----
        if (!nav.isAt(ScreenNavigator.Screen.MAIN)) {
            note("不在大厅，先尝试返回")
            nav.closeCurrent()
        }

        // ---- ② 导航到逐光捡金 ----
        if (!nav.gotoGuide4()) {
            return Result(false, "导航到「星际和平指南 · 逐光捡金」失败${tail()}")
        }

        // ---- ③ 打开忘却之庭详情 ----
        if (!nav.openChaosDetail()) {
            return Result(false, "没能打开「忘却之庭」详情面板${tail()}")
        }

        // ---- ④ 本期是否已满星 ----
        //
        // 满星就直接收工 —— 再进去也是白打（0 奖励，纯浪费电）。
        // 例外：用户打开了「满星也进入」（[Params.enterEvenIfFull]），
        // 那是为了把后半条链路实机验证一遍，此时照常往下走。
        val stars = nav.readChaosStars(ForgottenHallConfig.TOTAL_STARS)
        if (stars != null) {
            note("混沌回忆进度 ${stars.first}/${stars.second}")
            if (stars.first >= stars.second) {
                if (!config.enterEvenIfFull) {
                    nav.closeCurrent()
                    return Result(true, "本期混沌回忆已满星（${stars.first}/${stars.second}），无需挑战")
                }
                note("已满星，但用户勾选了「满星也进入」—— 继续传送（每层都会被判定为满星跳过）")
            }
        } else {
            note("没能读到星数进度，按「未满星」继续")
        }

        // ---- ⑤ 传送进混沌回忆页 ----
        if (!nav.teleportToChaos()) {
            return Result(false, "点「传送」后没进到混沌回忆页${tail()}")
        }

        // ---- ⑥ 逐层挑战 ----
        val outcome = challengeLevels()

        // ---- ⑦ 领奖 + 回大厅 ----
        val claimed = collectRewards()
        backToLobby()

        val summary = buildString {
            append("混沌回忆：")
            append("已打完 ${outcome.cleared} 层")
            if (outcome.skipped > 0) append("，跳过满星 ${outcome.skipped} 层")
            if (outcome.failedLevel != null) append("，第 ${outcome.failedLevel} 层未通过")
            if (claimed > 0) append("，领奖 $claimed 次")
            if (outcome.cleared == 0 && outcome.skipped == 0) append("（本次没有可挑战的层）")
        }
        // 只要没有"卡在某一层"就算成功；具体过程写在 detail 里给用户看
        return Result(outcome.failedLevel == null, summary + tail())
    }

    /** 逐层结果。 */
    private data class LevelsOutcome(
        val cleared: Int,
        val skipped: Int,
        val failedLevel: Int?,
    )

    /**
     * 逐层挑战。
     *
     * ## 顺序与跳过
     *
     * 从 [Params.fromLevel] 到 [Params.toLevel] 逐层往上，**已满 3 星的层跳过**
     * （重复打满星层没有任何奖励）。
     *
     * ## 为什么失败就 break
     *
     * 崩铁里打不过某一层，说明队伍强度不够 —— 继续往上打只会更打不过，
     * 而且会白耗时间。所以第一层失败就停，把原因如实报出来。
     */
    private fun challengeLevels(): LevelsOutcome {
        var cleared = 0
        var skipped = 0
        var failed: Int? = null

        // 关卡栏先滑到最左（第 1 层那边），保证"往右找"一定覆盖到目标层
        scrollLevelBarToStart()

        val levels = if (config.fromLevel <= config.toLevel) {
            config.fromLevel..config.toLevel
        } else {
            config.toLevel..config.fromLevel
        }.map { it.coerceIn(ForgottenHallConfig.MIN_LEVEL, ForgottenHallConfig.MAX_LEVEL) }
            .distinct()
            .sorted()

        for (level in levels) {
            checkCancelled()

            val hit = findLevel(level)
            if (hit == null) {
                note("第 $level 层没找到 —— 关卡栏滑到头也没有「${"%02d".format(level)}」")
                failed = level
                break
            }

            val st = judgeStars(hit)
            note("第 $level 层星数 = ${st ?: "未识别"}")

            if (config.skipFullStar && st == ForgottenHallConfig.STARS_PER_LEVEL) {
                skipped++
                note("第 $level 层已满星，跳过")
                continue
            }

            if (!enterLevel(level, hit)) {
                note("第 $level 层：没能开始挑战")
                failed = level
                break
            }

            when (clearLevel(level)) {
                LevelResult.CLEARED -> {
                    cleared++
                    note("第 $level 层完成")
                    if (!waitBackToList()) {
                        note("第 $level 层打完后没回到关卡列表")
                        break
                    }
                }

                LevelResult.FAILED -> {
                    note("第 $level 层挑战失败")
                    failed = level
                    waitBackToList()
                    break
                }

                LevelResult.TIMEOUT -> {
                    note("第 $level 层超过 ${config.battleTimeoutMin} 分钟没打完，放弃")
                    failed = level
                    break
                }
            }
        }

        return LevelsOutcome(cleared, skipped, failed)
    }

    // ==================== 关卡栏 ====================

    /**
     * 把关卡区滑到**最小层号那头**。
     *
     * ## 为什么要先滑到头
     *
     * 实机上这一页一次只显示 **4 层**（比如 09/10/11/12），是斜向散开的
     * 四颗晶石，不是 PC 那条能一眼看全的横排。默认视口停在"最近几层"，
     * 想打第 7 层就得往回滑。
     *
     * 先滑到最小层号那头，之后按升序找层就只需要**单向滑动**，
     * 不会出现"滑过头又得滑回来"的来回拉扯。
     *
     * ## 早停判据
     *
     * 看到第 1 或第 2 层就停 —— 再滑也不会更小了。滑到头还看不到
     * （可能是本期开放的层数不从这里起）就按当前位置继续，不报错。
     */
    private fun scrollLevelBarToStart() {
        var texts = scanLevels()
        note("关卡区当前可见层：${describeLevels(texts)}")
        if (levelsOf(texts).let { it.isNotEmpty() && it.min() <= 2 }) return

        repeat(MAX_START_SWIPES) { i ->
            checkCancelled()
            nav.swipeChaosLevelBar(toRight = true)
            texts = scanLevels()
            if (levelsOf(texts).let { it.isNotEmpty() && it.min() <= 2 }) {
                note("已滑到最小层号（滑 ${i + 1} 次）：${describeLevels(texts)}")
                return
            }
        }
        note("滑了 $MAX_START_SWIPES 次仍没见到第 1~2 层（${describeLevels(texts)}），按当前位置继续")
    }

    /**
     * 在关卡区里找某一层，找不到就**往层号变大的方向滑**继续找。
     *
     * 为什么要往一个方向找：层是按升序逐个处理的，`scrollLevelBarToStart`
     * 之后第 7 层一定在当前视口的右边，往后滑就行，不用双向试探。
     */
    private fun findLevel(level: Int): OcrEngine.OcrResult? {
        var texts = scanLevels()
        pickLevel(texts, level)?.let { return it }

        repeat(MAX_LEVEL_SWIPES) { i ->
            checkCancelled()
            nav.swipeChaosLevelBar(toRight = false)
            texts = scanLevels()
            pickLevel(texts, level)?.let { hit ->
                note("第 $level 层在滑 ${i + 1} 次后找到 @(${hit.cx},${hit.cy})")
                return hit
            }
        }
        note("滑到头了，当前可见层：${describeLevels(texts)}")
        return null
    }

    /** 一次 OCR 读完整条关卡区（后续所有判断都复用这一份结果）。 */
    private fun scanLevels(): List<OcrEngine.OcrResult> =
        nav.readAllText(ScreenNavigator.Hotspot.CHAOS_LEVEL_BAR)

    /** 从一次 OCR 结果里解析出可见的层号（1~12）。 */
    private fun levelsOf(texts: List<OcrEngine.OcrResult>): List<Int> {
        val out = sortedSetOf<Int>()
        for (t in texts) {
            val s = t.normalized()
            if (!LEVEL_NUM_RE.matches(s)) continue
            val v = s.toIntOrNull() ?: continue
            if (v in ForgottenHallConfig.MIN_LEVEL..ForgottenHallConfig.MAX_LEVEL) out.add(v)
        }
        return out.toList()
    }

    private fun describeLevels(texts: List<OcrEngine.OcrResult>): String {
        val lv = levelsOf(texts)
        return if (lv.isEmpty()) "未识别" else lv.joinToString("/")
    }

    /**
     * 从一次 OCR 结果里挑出目标层的那个文字框。
     *
     * 三级匹配，从严到松：
     * 1. 完全等于 `07`（最理想，OCR 直接读对）；
     * 2. 包含 `07`（OCR 把卡片上的「第07层」整块框进来）；
     * 3. 完全等于 `7`（OCR 吃掉了前导 0 —— **只认整框相等**，
     *    否则「7」会命中「17」「7/12」之类的无关文字）。
     */
    private fun pickLevel(
        texts: List<OcrEngine.OcrResult>,
        level: Int,
    ): OcrEngine.OcrResult? {
        if (texts.isEmpty()) return null
        val padded = "%02d".format(level)
        val plain = level.toString()
        return texts.firstOrNull { it.normalized() == padded }
            ?: texts.firstOrNull { it.normalized().contains(padded) }
            ?: texts.firstOrNull { it.normalized() == plain }
    }

    // ==================== 星数 ====================

    /**
     * 数某一层的星星（0~3）。
     *
     * ## 区域怎么来的（实机量出来的，不是照抄上游）
     *
     * 上游 `judge_stars` 是"层号文字框左上角起取 120×120"。实机上星星
     * **在层号正下方、左边对齐**，四层实测：
     *
     * | 层 | 层号框 | 星星带 |
     * |---|---|---|
     * | 09 | x400 y564 h56 | x400..520 y632..672 |
     * | 10 | x744 y640 h60 | x744..864 y712..744 |
     * | 11 | x1132 y540 h52 | x1128..1248 y608..648 |
     * | 12 | x1528 y468 h56 | x1520..1640 y536..576 |
     *
     * 归纳出：**左边界 = 层号左边界 - 16，上边界 = 层号下边界 + 2，
     * 尺寸 156×64**。这个式子对四层全部成立（星星比层号往左飘 0~8px，
     * 靠 -16 的余量吃掉）。
     *
     * ## 为什么不用模板匹配
     *
     * `forgottenhall/star.png` 是 **17×17**（PC 抠的），实机那颗星约
     * **40×40**。CCOEFF 对尺度敏感，17px 模板塞进 40px 实心星里几乎是
     * 一片纯白，归一化后分数接近 0 —— 三颗满星会被读成 0 星，
     * 于是任务会去白打一层已经满星的关卡。改用**数金色连通块**
     * （[ImageUtils.countColorBlobs]），完全不吃尺度。
     *
     * @return 0~3；读不出来返回 null（调用方按"没满星"处理）
     */
    private fun judgeStars(hit: OcrEngine.OcrResult): Int? {
        val sx = auto.width / 1920f
        val sy = auto.height / 1080f
        val left = hit.x - (STAR_BAND_DX * sx).toInt()
        val top = hit.y + hit.h + (STAR_BAND_DY * sy).toInt()
        val rect = org.opencv.core.Rect(
            left.coerceAtLeast(0),
            top.coerceAtLeast(0),
            (STAR_BAND_W * sx).toInt().coerceAtLeast(1),
            (STAR_BAND_H * sy).toInt().coerceAtLeast(1),
        )
        val count = nav.withFrameOcr { mat ->
            ImageUtils.countColorBlobs(
                src = mat,
                region = rect,
                bgr = STAR_COLOR_BGR,
                toleranceSq = STAR_COLOR_TOLERANCE,
                minArea = STAR_MIN_AREA,
                maxArea = STAR_MAX_AREA,
            )
        } ?: return null

        if (count > ForgottenHallConfig.STARS_PER_LEVEL) {
            // 数多了说明图形被切碎或框里混进了别的东西 —— **不能夹到 3**，
            // 夹到 3 会被当成"已满星"从而漏打。按未知处理（= 去打）。
            Ln.w("$TAG: 星数数出 $count 团（>3），按未知处理")
            return null
        }
        if (count == 0) {
            // 0 星可能是"真的 0 星"，也可能是"星星不在这个框里"。
            // 两种情况的处置一样（去打），但导出帧供下次修区域用。
            runCatching { nav.exportFrame("stars_0") }
        }
        return count
    }

    // ==================== 进关 ====================

    /**
     * 点层号 → 配队页点「开始挑战」→ 等战斗开始。
     *
     * ## 为什么点完要"验证真的离开了列表"
     *
     * 「开始挑战」在队伍没配好时是**禁用**的：点下去毫无反应，
     * 画面纹丝不动。如果这里不验证，后面的"走向敌人 + 等 30 分钟"
     * 会全部对着关卡列表空转。
     */
    private fun enterLevel(level: Int, hit: OcrEngine.OcrResult): Boolean {
        repeat(2) { attempt ->
            checkCancelled()
            note("点第 $level 层 @(${hit.cx},${hit.cy})（第 ${attempt + 1} 次）")
            auto.tap(hit.cx, hit.cy, durationMs = 120L)
            sleepInterruptible(2_000)

            // 配队页：点「开始挑战」
            clickStartButton()
            sleepInterruptible(1_500)
            nav.dismissBlankTapPrompt()

            // 等战斗/加载开始：只要不再停在关卡列表，就算进去了
            if (waitLeaveList(START_BATTLE_TIMEOUT_MS)) {
                note("已进入第 $level 层")
                return true
            }
            note("点了「开始挑战」但还停在关卡列表 —— 可能这一层没配队伍")
        }
        return false
    }

    /**
     * 点配队页右下角的「开始挑战」。
     *
     * 四层兜底，从"最准"到"最糙"：
     * 1. 区域内 OCR 认「开始挑战」三个字（位置随文案浮动也不怕）；
     * 2. 全屏 OCR 兜底（按钮被挪出预定区域）；
     * 3. 模板 `forgottenhall/start.png`；
     * 4. 直接点区域的中心（区域本身就是按钮所在的那一块）。
     */
    private fun clickStartButton() {
        val crop = ScreenNavigator.Hotspot.CHAOS_START_BUTTON
        if (nav.clickText("开始挑战", crop, maxRetries = 2)) {
            note("已点「开始挑战」（区域 OCR）")
            return
        }
        if (nav.clickText("开始挑战", null, maxRetries = 1)) {
            note("已点「开始挑战」（全屏 OCR）")
            return
        }
        if (nav.clickImage(TEMPLATE_START, 0.80, 2)) {
            note("已点「开始挑战」（模板）")
            return
        }
        note("认不到「开始挑战」，按区域中心点击")
        nav.clickCrop(crop, "开始挑战")
    }

    /**
     * 等画面**不再是关卡列表**（= 已经开始加载战斗场景）。
     *
     * 用"标题锚点消失"当判据：进了战斗/加载页之后，左上角那个
     * 「忘却之庭 / 混沌回忆」标题就不在了。
     */
    private fun waitLeaveList(timeoutMs: Long): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            checkCancelled()
            if (!nav.isMemoryOfChaosPage()) return true
            sleepInterruptible(800)
        }
        return false
    }

    // ==================== 战斗 ====================

    private enum class LevelResult { CLEARED, FAILED, TIMEOUT }

    /**
     * 打完一层。
     *
     * ## 状态机
     *
     * ```
     * 循环（每 2 秒）：
     *   ├─ 见到「返回忘却之庭」 → 结算了 → 判断成功/失败 → 点返回 → 结束
     *   ├─ 见到「剩余轮」       → 在战斗里 → 确保自动战斗开着 → 继续等
     *   └─ 都没有              → 在场地里 → 走向敌人 + 普攻（最多 12 秒一次）
     * ```
     *
     * 用「剩余轮」当"在不在战斗里"的判据，而不是"画面在不在动"——
     * 崩铁战斗有自动战斗的过场、技能特写的静止帧，看画面变化会误判。
     */
    private fun clearLevel(level: Int): LevelResult {
        val startAt = SystemClock.uptimeMillis()
        val deadline = startAt + config.battleTimeoutMin * 60_000L
        var lastHeartbeat = startAt

        note("第 $level 层：开始战斗流程（上限 ${config.battleTimeoutMin} 分钟）")
        approachAndAttack()

        while (SystemClock.uptimeMillis() < deadline) {
            checkCancelled()

            // ① 结算标志：右下角「返回忘却之庭」
            if (nav.findInRegion(TEMPLATE_BACK, 0.85, null) != null) {
                return settleLevel(level)
            }

            // ② 在战斗里
            if (inBattle()) {
                if (config.autoBattle) ensureAutoBattle()
                val now = SystemClock.uptimeMillis()
                if (now - lastHeartbeat > 30_000L) {
                    lastHeartbeat = now
                    note("第 $level 层战斗中…（已等 ${(now - startAt) / 1000}s）")
                }
                sleepInterruptible(FIGHT_POLL_MS)
                continue
            }

            // ③ 不在战斗：多半是两间之间回到场地了，走过去继续打
            val now = SystemClock.uptimeMillis()
            if (now - lastFieldAt > FIELD_RETRY_MS) {
                lastFieldAt = now
                note("第 $level 层：不在战斗中，走向敌人")
                approachAndAttack()
            }
            sleepInterruptible(FIGHT_POLL_MS)
        }

        return LevelResult.TIMEOUT
    }

    /** 当前是不是在战斗界面（顶部有「剩余轮」）。 */
    private fun inBattle(): Boolean =
        nav.findInRegion(TEMPLATE_IN_BATTLE, 0.80, CROP_BATTLE_TOP) != null

    /**
     * 结算界面：判断这一层是过了还是没过，然后点「返回忘却之庭」。
     *
     * 判据照抄 M7A：
     * - 出现「回忆」页签 → **挑战失败**（失败页才会有这个页签）
     * - 出现暂停图标 → **角色无法战斗**（要再点一次确定）
     * - 都没有 → 正常打完
     */
    private fun settleLevel(level: Int): LevelResult {
        note("第 $level 层：出现「返回忘却之庭」")
        sleepInterruptible(2_000)

        if (nav.findInRegion(TEMPLATE_AGAIN, 0.85, null) != null) {
            note("第 $level 层：出现「回忆」页签 ⇒ 挑战失败")
            nav.clickImage(TEMPLATE_BACK, 0.85, 2)
            return LevelResult.FAILED
        }

        if (nav.findInRegion(TEMPLATE_PAUSE, 0.85, null) != null) {
            note("第 $level 层：出现暂停图标 ⇒ 角色无法战斗")
            nav.clickImage(TEMPLATE_BACK, 0.85, 2)
            sleepInterruptible(1_500)
            nav.clickImage(TEMPLATE_CONFIRM, 0.85, 3)
            return LevelResult.FAILED
        }

        nav.clickImage(TEMPLATE_BACK, 0.85, 3)
        return LevelResult.CLEARED
    }

    /**
     * 开自动战斗。
     *
     * 崩铁战斗界面左下角有个「自动」开关；没开的时候图标是
     * **带斜杠**的样子（`share/base/not_auto.png`）。找到它就点掉。
     *
     * 有 5 秒冷却：游戏切换这个开关有动画，连点等于反复开关。
     */
    private fun ensureAutoBattle() {
        val now = SystemClock.uptimeMillis()
        if (now - lastAutoAt < AUTO_RETRY_MS) return
        val hit = nav.findInRegion(TEMPLATE_NOT_AUTO, 0.85, CROP_AUTO_BUTTON) ?: return
        lastAutoAt = now
        val (x, y) = matchCenter(hit)
        note("开启自动战斗 @($x,$y)")
        auto.tap(x, y, durationMs = 100L)
    }

    /**
     * 走向敌人并开打。
     *
     * ## 为什么是"长按摇杆 + 连点普攻"
     *
     * 手机上没有 WASD。崩铁的虚拟摇杆是**按住即走**（不需要拖动），
     * 所以长按上方向 4 秒 ≈ 上游的 `press_key('w', 4)`。
     * 走到位之后连点普攻键，角色会朝最近的敌人挥一刀，命中即进战斗。
     */
    private fun approachAndAttack() {
        lastFieldAt = SystemClock.uptimeMillis()
        note("向前走 ${WALK_FORWARD_MS}ms @($JOY_UP_X,$JOY_UP_Y)")
        auto.longPress(JOY_UP_X, JOY_UP_Y, WALK_FORWARD_MS)
        sleepInterruptible(600)

        repeat(ATTACK_TAPS) { i ->
            if (inBattle()) return
            note("点攻击键 @($ATK_BTN_X,$ATK_BTN_Y)（第 ${i + 1}/$ATTACK_TAPS 次）")
            auto.tap(ATK_BTN_X, ATK_BTN_Y, durationMs = 120L)
            sleepInterruptible(ATTACK_GAP_MS)
        }
    }

    // ==================== 领奖 ====================

    /**
     * 领取混沌回忆奖励。
     *
     * ## 链路
     *
     * ```
     * 关卡列表右下角有红点 → 点它进奖励弹窗
     *   → 弹窗里每一行右侧有「领取」
     *   → 点一次会弹明细，关掉它，再点下一个「领取」
     *   → 没有「领取」了 → 关弹窗
     * ```
     *
     * @return 点成功的「领取」次数（0 = 没有可领的）
     */
    private fun collectRewards(): Int {
        val redDot = nav.findInRegion(TEMPLATE_RED_DOT, 0.85, ScreenNavigator.Hotspot.CHAOS_RED_DOT)
        if (redDot == null) {
            note("关卡列表没有红点，跳过领奖")
            return 0
        }
        val (dx, dy) = matchCenter(redDot)
        note("点奖励红点 @($dx,$dy)")
        auto.tap(dx, dy, durationMs = 120L)
        sleepInterruptible(1_500)

        var claimed = 0
        while (claimed < MAX_CLAIMS) {
            checkCancelled()
            val hit = nav.findInRegion(
                TEMPLATE_RECEIVE, 0.85, ScreenNavigator.Hotspot.CHAOS_REWARD_LIST,
            ) ?: break
            val (x, y) = matchCenter(hit)
            note("点「领取」@($x,$y)")
            auto.tap(x, y, durationMs = 120L)
            sleepInterruptible(1_500)
            claimed++

            // 领取后会弹一个明细框，关掉它才能点下一个
            if (!nav.clickImage(TEMPLATE_CLOSE, 0.85, 2)) {
                nav.dismissBlankTapPrompt()
            }
            sleepInterruptible(800)
        }

        if (claimed > 0) {
            // 收尾：把奖励页关掉，回到关卡列表
            nav.closeCurrent()
        }
        note("领奖 $claimed 次")
        return claimed
    }

    /** 打完之后从关卡页退回大厅，尽量回到"能继续导航"的状态。 */
    private fun backToLobby() {
        runCatching {
            repeat(3) {
                if (nav.isAt(ScreenNavigator.Screen.MAIN)) return
                if (!nav.closeCurrent()) return
                sleepInterruptible(1_000)
            }
        }.onFailure { Ln.w("$TAG: 退回大厅失败（不影响结果）：${it.message}") }
    }

    // ==================== 杂项 ====================

    /** 等回到关卡列表（打完一层之后）。 */
    private fun waitBackToList(timeoutMs: Long = 45_000L): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            checkCancelled()
            if (nav.isMemoryOfChaosPage()) return true
            sleepInterruptible(1_000)
        }
        Ln.w("$TAG: ${timeoutMs / 1000}s 内没回到关卡列表")
        return false
    }

    /**
     * 把模板命中点换算成**屏幕像素坐标**。
     *
     * `findInRegion` 返回的是归一化帧（高 1080）里的坐标，
     * 虚拟屏实际高度可能不是 1080（虽然目前恒为 1080），所以要除回缩放比。
     */
    private fun matchCenter(r: ImageUtils.MatchResult): Pair<Int, Int> {
        val sf = ImageUtils.BASE_HEIGHT.toDouble() / auto.height
        val cx = ((r.x + r.templateWidth * r.scale / 2) / sf).toInt()
        val cy = ((r.y + r.templateHeight * r.scale / 2) / sf).toInt()
        return cx to cy
    }
}
