package com.march7th.hkrpg.starrail

import com.march7th.hkrpg.data.model.PowerInstance
import com.march7th.hkrpg.starrail.engine.OcrEngine
import com.march7th.hkrpg.starrail.engine.ScreenNavigator
import com.march7th.hkrpg.starrail.engine.ShellAutomation
import com.march7th.hkrpg.third.Ln
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 每日实训（Daily）。
 *
 * # 页面结构（1920×1080 实测）
 *
 * ```
 * ┌─ 每日实训 ──────────────────────────────── ✕ ─┐
 * │  [🔥100] ───●────────○────────○────────○───○  │ ← 活跃度进度条
 * │           0   100     200      300      400 500│
 * │ ┌─────┐ ┌─────┐ ┌─────┐ ┌─────┐ ┌─────┐       │
 * │ │进度 │ │进度 │ │进度 │ │进度 │ │进度 │       │ ← 5 张卡片横排
 * │ │1/1  │ │0/1  │ │0/120│ │0/20 │ │...  │       │
 * │ │任务名│ │任务名│ │任务名│ │任务名│ │...  │       │
 * │ │+100 │ │+100 │ │+200 │ │+100 │ │...  │       │
 * │ │[领取]│ │[前往]│ │[前往]│ │[进行中]│ ...   │       │
 * │ └─────┘ └─────┘ └─────┘ └─────┘ └─────┘       │
 * └────────────────────────────────────────────────┘
 * ```
 *
 * ## 按钮三态
 *
 * | 按钮 | 含义 |
 * |---|---|
 * | **金色「领取」** | 任务已完成但**没领** → 要点它 |
 * | 灰色「前往」 | 任务未完成 → 跳过 |
 * | 灰色「进行中」 | 累积类任务（如累计消耗体力），无法主动完成 → 跳过 |
 *
 * ## 进度标签
 *
 * 每张卡片左上角有「进度 X/Y」：
 * - `1/1`、`120/120` → 已完成
 * - `0/1`、`0/120` → 未完成
 *
 * 但**判"已完成"不能只看进度** —— 进度满了但没领的时候按钮才是金色。
 * 所以真正的判据是**按钮颜色**（金色 = 可领）。
 *
 * # 本阶段做什么
 *
 * 1. 进每日实训页
 * 2. 逐张卡片找**金色「领取」按钮**，点它
 * 3. 每次领完会出现「获得物品」弹窗 → 点空白处关闭
 * 4. 循环直到没有可领的
 * 5. 输出「活跃度 X/500」
 *
 * **不操作游戏内容**（不打副本、不合成都、不拍照），零风险。
 */
/**
 * 每日实训的执行参数（由外壳下发的 JSON 解析而来）。
 *
 * 注意：必须放在**类外面** —— 主构造函数的参数类型不能用
 * 同一个类内部声明的嵌套类（Kotlin 不允许这种循环引用）。
 *
 * @param doDispatch   做「派遣委托或收取1次委托奖励」
 * @param doPhoto      做「拍照1次」
 * @param doSynthesize 做「使用1次『万能合成机』」
 * @param doTechnique  做「累计施放2次秘技」
 * @param doPower      做「累计消耗120点开拓力」（复用清体力）
 * @param doClaim      做完后领取活跃度奖励
 * @param instance     刷哪个副本（仅 [doPower] 用）
 * @param targetPower  消耗体力目标（固定 120）
 */
internal data class DailyTaskParams(
    val doDispatch: Boolean = true,
    val doPhoto: Boolean = true,
    val doSynthesize: Boolean = true,
    val doTechnique: Boolean = true,
    val doPower: Boolean = false,
    val doClaim: Boolean = true,
    val instance: String,
    val targetPower: Int,
)

internal class DailyTask(
    private val auto: ShellAutomation,
    private val nav: ScreenNavigator,
    private val params: DailyTaskParams,
) {

    private val notes = mutableListOf<String>()

    private fun note(msg: String) {
        Ln.i("$TAG: $msg")
        notes += msg
    }

    // ==================== 坐标常量 ====================

    companion object {
        private const val TAG = "DailyTask"

        /** 页面标题「每日实训」的搜索区（左上角）。 */
        private val CROP_TITLE = ScreenNavigator.NormRect(0.02f, 0.02f, 0.25f, 0.10f)

        /** 任务卡片区（5 张卡横排，含卡片下方的按钮）。 */
        private val CROP_CARDS = ScreenNavigator.NormRect(0.08f, 0.32f, 0.86f, 0.48f)

        /** 卡片按钮那一行的中心 y（实测金色「领取」在 y 838~894，中心 866）。 */
        private const val BTN_ROW_Y = 866

        /**
         * 5 张卡片的按钮中心 x。
         *
         * 实测卡片 1 的按钮中心在 x=348，卡片间距约 335。
         */
        private val BTN_XS = listOf(348, 683, 1018, 1353, 1688)

        /**
         * 金色「领取」按钮的模板。
         *
         * 金色 ≠ 灰色「前往/进行中」，颜色区分非常明显，
         * 用模板匹配比 OCR 靠谱（OCR 读「领取」也会匹配到别处的字）。
         */
        private const val TEMPLATE_RECEIVE = "daily/btn_receive.png"

        /** 「获得物品」弹窗的搜索区（标题在正中上方）。 */
        private val CROP_REWARD_TITLE = ScreenNavigator.NormRect(0.35f, 0.10f, 0.30f, 0.12f)

        /** 奖励弹窗的关闭点击位置（「点击空白处关闭」，点屏幕下方空白）。 */
        private const val REWARD_DISMISS_X = 960
        private const val REWARD_DISMISS_Y = 950

        /** 单次领奖循环最多跑几轮（防止死循环）。 */
        private const val MAX_CLAIM_ROUNDS = 8

        // ==================== 任务卡片扫描 ====================
        //
        // 卡片是等宽横排的。实测（1920×1080）：
        //   第 1 张按钮中心 x=348，卡片间距 335
        //   所以卡片区从 x≈180 开始，每张宽 335
        //
        // 扫的时候按 x 把 OCR 碎框归到对应卡片。

        /** 第 1 张卡片的**中心** x（实测按钮中心 348，卡片宽 335 → 左边界约 180）。 */
        private const val CARD_FIRST_X = 180

        /** 单张卡片宽度（含间距）。 */
        private const val CARD_WIDTH = 335

        /**
         * 卡片**文字区**（任务名 + 进度 + 分数）。
         *
         * 只扫这一块，不扫按钮行 —— 按钮状态由 [findReceiveButton] 单独判断。
         */
        private val CROP_CARD_TEXT = ScreenNavigator.NormRect(
            0.08f, 0.33f, 0.86f, 0.36f,
        )

        /** 卡片行右滑（露出后面的卡片）。 */
        private const val CARD_SWIPE_FROM_X = 0.85f
        private const val CARD_SWIPE_TO_X = 0.25f
        private const val CARD_SWIPE_Y = 0.55f

        // ==================== 委托（派遣）====================
        //
        // 对应实训任务「派遣委托或收取1次委托奖励」(+100)。
        //
        // 原项目 `tasks/reward/dispatch.py:19-32` 的逻辑非常简单：
        // 在委托页底部找「领取奖励」文字，找到就点，然后关弹窗。
        // **不需要任何模板素材**，纯 OCR 就能做。

        /** 委托页底部「领取奖励」按钮的搜索区（原项目 crop 同款）。 */
        private val CROP_DISPATCH_CLAIM = ScreenNavigator.NormRect(
            1194f / 1920f, 866f / 1080f, 610f / 1920f, 156f / 1080f,
        )

        /** 委托页顶部「专属材料」标题（用于确认进对了页面）。 */
        private val CROP_DISPATCH_TITLE = ScreenNavigator.NormRect(
            163f / 1920f, 99f / 1080f, 1115f / 1920f, 118f / 1080f,
        )

        /** 委托页里表示"没有可领"的提示。 */
        private const val TEXT_DISPATCH_IDLE = "委托派遣中"

        // ==================== 拍照 ====================

        /**
         * 相机取景区**快门按钮**的中心（白色圆形）。
         *
         * 实测（1920×1080）：白底圆 x 1650~1769、y 480~598，中心 (1709, 539)。
         */
        private const val CAM_BTN_X = 1709
        private const val CAM_BTN_Y = 539

        /**
         * 手机菜单里**相机图标**的位置。
         *
         * 相机在右侧竖栏（邮件/设置/相机/电源键 那一列），
         * 从截图看约在 (1660, 645)。这是纯图标无文字，只能按坐标点。
         */
        private const val CAM_MENU_X = 1660
        private const val CAM_MENU_Y = 645

        /** 拍照预览页「保存」按钮的搜索区（底部偏右）。 */
        private val CROP_PHOTO_PREVIEW = ScreenNavigator.NormRect(
            0.60f, 0.84f, 0.30f, 0.12f,
        )

        // ==================== 万能合成机 ====================
        //
        // 对应实训「使用1次「万能合成机」」(+100)。
        //
        // ## 实测流程（用户提供的 5 张截图）
        //
        // ```
        // ① 手机菜单 → 「合成」图标 (1360, 495)
        //       ↓
        // ② 消耗品合成页
        //      左侧材料网格（默认选中第一个，如「健康炒米」）
        //      右侧详情 + 底部「合成」按钮 (1297, 995)
        //       ↓ 点「合成」
        // ③ 「确认合成」弹窗
        //      「取消」(约 700,735) / 「确认」(1200,735)
        //       ↓ 点「确认」
        // ④ **等 1~2 秒动画**（这里必须等，否则弹窗还没出来）
        //       ↓
        // ⑤ 「获得物品」弹窗 → 点空白处关闭
        // ⑥ 回到合成页（材料数量 -1）
        // ```
        //
        // 注意：默认选中的第一个材料就是**消耗品**（食物），
        // 用它合成一次最省事，不需要筛选。

        /** 手机菜单里「合成」图标的位置。 */
        private const val SYNTH_MENU_X = 1360
        private const val SYNTH_MENU_Y = 495

        /** 合成页底部「合成」按钮中心。 */
        private const val SYNTH_BTN_X = 1297
        private const val SYNTH_BTN_Y = 995

        /** 「确认合成」弹窗里「确认」按钮中心。 */
        private const val SYNTH_CONFIRM_X = 1200
        private const val SYNTH_CONFIRM_Y = 735

        /** 点完确认后等动画的时间（用户实测 1~2 秒）。 */
        private const val SYNTH_ANIM_MS = 2_000L

        /** 「确认合成」弹窗的搜索区（屏幕中部偏下，含两个按钮）。 */
        private val CROP_SYNTH_DIALOG = ScreenNavigator.NormRect(
            0.10f, 0.55f, 0.80f, 0.25f,
        )

        // ==================== 秘技 ====================
        //
        // 对应实训「累计施放2次秘技」(+200)。
        //
        // ## 按钮位置
        //
        // 右下角的**召唤球按钮**（像素怪兽图标 + 旁边数字/紫色菱形能量）。
        // 用户明确要求：**只能用坐标，不能做图像识别** ——
        // 因为这个按钮在不同角色/场景下外观会变。
        //
        // 实测（差分宇宙场景，1920×1080）：
        // 圆心 (1413, 913)，半径约 90。

        /** 秘技（召唤球）按钮中心。 */
        private const val SKILL_BTN_X = 1413
        private const val SKILL_BTN_Y = 913

        /** 施放秘技后等冷却/动画。 */
        private const val SKILL_WAIT_MS = 2_500L

        /** 施放几次（实训要求累计 2 次，多放一次保险）。 */
        private const val SKILL_TIMES = 3
    }

    /** 执行结果。 */
    data class Result(val ok: Boolean, val detail: String)

    /**
     * 扫出来的一条任务。
     *
     * @param name  标准任务名（见 [DailyTaskCatalog]）
     * @param done  是否**已完成**（进度满 或 已领取）
     */
    data class ScannedTask(val name: String, val done: Boolean)

    /**
     * 执行每日实训。
     *
     * ## 完整流程（对齐原项目 `daily.py:220-316`）
     *
     * ```
     * ① 打开每日实训页
     * ② **扫描任务列表** —— 滑动前扫一次，滑动后扫一次，合并去重
     * ③ 把**已完成**的任务分数加起来 → 得到"白拿"的分数
     * ④ 按优先级遍历任务表：
     *      分数 ≥ 500 → 停
     *      该任务不在今天的列表里 → 跳过
     *      该任务已完成          → 跳过
     *      能主动做              → 去做，成功就加分
     *      只检测不能做          → 跳过
     * ⑤ 领奖 + 领里程碑
     * ```
     *
     * ## 为什么必须扫描而不是全做一遍
     *
     * 崩铁**每个号每天的实训任务是不同的**（从大池子里随机抽 5 个）。
     *
     * 不扫描的话：
     * - 今天没有「拍照」任务，却去拍了一张 —— 白操作
     * - 今天没有副本任务，却去刷了副本 —— 白花体力
     * - 已经 500 分了还在继续做 —— 纯浪费
     */
    /**
     * 执行每日实训。
     *
     * # 流程（用户勾选式，不做任务识别）
     *
     * ```
     * ① 进每日实训页，确认页面可用
     * ② 按固定顺序执行勾选的子项：
     *      委托 → 拍照 → 万能合成机 → 秘技 → [刷副本]
     *    **每项做完都回大厅确认**，避免上一个动作把界面带歪后
     *    下一个动作直接失败
     * ③ 最后回实训页：领取活跃度奖励 + 领里程碑
     * ```
     *
     * # 为什么不自动识别任务列表
     *
     * 早期版本想 OCR 扫描卡片、算分补齐到 500，但手游下极不可靠
     * （OCR 拆字跨卡片、「宇宙」认成「字画」、布局随文字长度浮动）。
     * 现在把「今天做哪几项」交给用户勾选，程序只负责**可靠地执行**。
     *
     * # 为什么每项之间要回大厅
     *
     * 用户明确要求：**每做完一项回大厅确认再继续**，不然很容易驾崩。
     *
     * 原因很实在 —— 委托页、合成页、相机页、副本页都是不同的弹层，
     * 从一个弹层直接跳到另一个弹层，一旦某个关闭动作没生效，
     * 后续所有点击都会落在错误的位置上。
     */
    fun run(): Result {
        // ① 进每日实训页（既能确认导航通，也顺便看看当前活跃度）
        if (!openDailyPage()) {
            return Result(false, "打不开每日实训页面")
        }
        val startScore = readActivityScore()
        note("当前活跃度 = ${startScore ?: "未读到"}/500")

        // 如果有未领取的奖励，先记一下（但**不在这里领**，
        // 因为领了之后分数不会涨，做完任务还得再领一次）
        val doneActions = mutableListOf<String>()
        val failedActions = mutableListOf<String>()

        /** 执行一项，做完回大厅确认。 */
        fun runStep(label: String, action: () -> Boolean) {
            note("—— 执行：$label ——")
            val ok = try {
                action()
            } catch (e: Exception) {
                note("$label 异常：${e.message}")
                false
            }
            if (ok) {
                doneActions += label
                note("$label 完成")
            } else {
                failedActions += label
                note("$label 失败，继续下一项")
            }
            // **无论成败都回大厅**，给下一项一个干净起点
            returnToMain()
        }

        // ② 按固定顺序执行勾选的子项
        if (params.doDispatch) {
            runStep("委托派遣") { claimDispatch() }
        }
        if (params.doPhoto) {
            runStep("拍照") { takePhoto() }
        }
        if (params.doSynthesize) {
            runStep("万能合成机") { useSynthesizer() }
        }
        if (params.doTechnique) {
            runStep("施放秘技") { useTechnique() }
        }

        // 刷副本单独处理（它自己会进出大厅多次）
        var powerInfo = ""
        if (params.doPower) {
            val inst = PowerInstance.fromWireName(params.instance)
                ?: PowerInstance.CALYX_CRIMSON
            val runs = (params.targetPower / inst.powerPerRun).coerceAtLeast(1)
            note("—— 执行：消耗体力（${inst.wireName} × $runs 次）——")
            if (runInstanceForPower(inst, runs)) {
                doneActions += "消耗体力"
                powerInfo = "，已刷 ${inst.wireName} $runs 次"
            } else {
                failedActions += "消耗体力"
                powerInfo = "，刷副本失败"
            }
            returnToMain()
        }

        // ③ 最后领奖
        var claimed = 0
        if (params.doClaim) {
            note("—— 领取活跃度奖励 ——")
            if (openDailyPage()) {
                claimed = claimAll()
                // 里程碑也一起收
                val milestone = claimMilestones()
                if (milestone > 0) note("领取 $milestone 个活跃度里程碑奖励")
            } else {
                note("回不到每日实训页，领奖跳过")
            }
        }

        val finalScore = readActivityScore()
        val actionText = buildString {
            if (doneActions.isEmpty()) {
                append("没有执行任何项")
            } else {
                append("完成 ${doneActions.size} 项：").append(doneActions.joinToString("、"))
            }
            if (failedActions.isNotEmpty()) {
                append("；失败 ").append(failedActions.size).append(" 项：")
                append(failedActions.joinToString("、"))
            }
        }
        val claimText = if (!params.doClaim) {
            "未勾选领奖"
        } else if (claimed == 0) {
            "没有可领取的奖励"
        } else {
            "领取 $claimed 个奖励"
        }

        return Result(
            // 只有一项都没成功才算失败；部分成功仍算通过（用户能看到哪项失败）
            ok = failedActions.size < doneActions.size || failedActions.isEmpty(),
            detail = "每日实训：活跃度 ${finalScore ?: startScore ?: "?"}/500，" +
                "$actionText，$claimText$powerInfo",
        )
    }

    /**
     * 回大厅并确认。
     *
     * 用户明确要求每项之间要回大厅 —— 不做这一步，连续在不同弹层间
     * 跳转很容易因为某个关闭动作没生效而全部错位。
     *
     * @return 是否确实回到了大厅
     */
    private fun returnToMain(): Boolean {
        if (nav.isAt(ScreenNavigator.Screen.MAIN)) {
            return true
        }
        repeat(3) { i ->
            nav.closeCurrent()
            if (nav.isAt(ScreenNavigator.Screen.MAIN)) {
                Ln.i("$TAG: 已回大厅（第 ${i + 1} 次尝试）")
                return true
            }
            Thread.sleep(600)
        }
        Ln.w("$TAG: 没能回到大厅，后续步骤可能受影响")
        return false
    }


    /**
     * 执行单个任务（按标准任务名分派）。
     *
     * @return 是否确实完成
     */
    private fun runOneTask(name: String): Boolean = try {
        when {
            name == "登录游戏" -> true          // 都跑起来了，这条必然已完成
            name.contains("派遣委托") -> claimDispatch()
            name.contains("万能合成机") -> useSynthesizer()
            name.contains("拍照") -> takePhoto()
            name.contains("秘技") -> useTechnique()
            else -> false
        }
    } catch (e: Exception) {
        note("「$name」异常：${e.message}")
        false
    }

    // ==================== 任务扫描 ====================

    /**
     * 扫出今天有哪些实训任务、各自完成没完成。
     *
     * ## 为什么要扫两次（对齐原项目 `tasks.py:26-29`）
     *
     * ```python
     * def start(self):
     *     if self.detect():     # 先扫当前可见的
     *         self.scroll()      # 右滑一次
     *         self.detect()      # 再扫露出来的
     * ```
     *
     * 手机屏只能同时看到 4 张卡片，第 5 张要滑动才露出来。
     * 扫两次后按任务名**去重合并**。
     */
    private fun scanTasks(): List<ScannedTask> {
        val result = LinkedHashMap<String, ScannedTask>()

        fun scanOnce(tag: String) {
            val texts = nav.withFrameOcrRegion(CROP_CARD_TEXT) { mat, region ->
                OcrEngine.run(mat, region)
            }.orEmpty()
            if (texts.isEmpty()) {
                note("扫描[$tag]：卡片区没认到文字（OCR 空）")
                return
            }

            // ## 诊断：把 OCR 原文和坐标全打出来
            //
            // 扫描出错时，光看"识别到 N 个任务"没法定位 ——
            // 是没认到文字？认到了但拼错？还是坐标算错了？
            // 这行日志能直接看出问题在哪。
            Ln.i("$TAG: 扫描[$tag] OCR ${texts.size} 条: " +
                texts.joinToString(" | ") { "\"${it.text}\"@${it.cx}" })

            // ## 分卡片：用「进度 X/Y」当**卡片分隔锚点**
            //
            // 之前按固定宽度分桶（x=180 起、每 335 一张），
            // 实测会被 OCR 拆字搞乱。日志证据：
            //
            // ```
            // "累计消耗120点开拓"@727 | "力"@608     ← 半截跑到了左边那张
            // ```
            // 结果「累计消耗120点开拓力」被切进两个桶，谁都匹配不上。
            //
            // ## 新办法
            //
            // 每张卡片**左上角必定有「进度 X/Y」**，它是整张卡片的起点。
            // 所以：
            //   1. 把所有「进度 X/Y」框按 x 排好，它们就是各卡片的锚点
            //   2. 每个非锚点框，归到**离它最近且在其左侧**的锚点
            //      （没有更左的锚点就归第一个）
            //
            // 这样「力」@608 会归到「进度120/120」@686 那张卡片，
            // 而不是按死板边界飞到别的桶里。
            val progressRegex = Regex("""进度\s*(\d{1,3})\s*[/／]\s*(\d{1,3})""")

            data class Card(val anchorX: Int, val cur: Int, val total: Int) {
                val parts = mutableListOf<String>()
            }

            // ① 找出所有锚点
            val anchors = texts.mapNotNull { o ->
                val m = progressRegex.find(o.normalized()) ?: return@mapNotNull null
                Card(
                    anchorX = o.cx,
                    cur = m.groupValues[1].toIntOrNull() ?: 0,
                    total = m.groupValues[2].toIntOrNull() ?: 0,
                )
            }.sortedBy { it.anchorX }

            if (anchors.isEmpty()) {
                Ln.w("$TAG: 扫描[$tag] 没找到任何「进度 X/Y」锚点，无法分卡片")
                return
            }

            // ② 每个框归到最近的左侧锚点
            for (o in texts) {
                // 锚点本身也要进自己那张卡（如「进度1/1」）
                val idx = anchors.indexOfLast { it.anchorX <= o.cx + 40 }
                    .let { if (it < 0) 0 else it }
                anchors[idx].parts.add(o.normalized())
            }

            // ③ 拼起来匹配任务
            for ((i, card) in anchors.withIndex()) {
                val joined = card.parts.joinToString("")
                val taskName = DailyTaskCatalog.matchTask(joined)
                if (taskName == null) {
                    Ln.i("$TAG: 卡片$i 没匹配上任何任务: $joined")
                    continue
                }
                val done = (card.total > 0 && card.cur >= card.total) || joined.contains("已领取")
                Ln.i(
                    "$TAG: 卡片$i → 「$taskName」${if (done) "已完成" else "未完成"}" +
                        "（进度 ${card.cur}/${card.total}，原文：$joined）",
                )
                // 同一条任务两次扫描可能都出现，**以"未完成"为准**
                val prev = result[taskName]
                result[taskName] = if (prev != null && !prev.done) prev else ScannedTask(taskName, done)
            }
        }

        // ① 滑动前扫一次（左边这几张）
        scanOnce("滑动前")

        // ② 滑到底 / 滑到最右，再扫一次（露出后面的卡片）
        scrollCardRowToEnd()

        // ③ 滑动后再扫一次
        scanOnce("滑动后")

        return result.values.toList()
    }

    /**
     * 把任务卡片行**滑到最右**（露出最后几张）。
     *
     * ## 为什么不是"滑一次"
     *
     * 卡片是横向排列的，手机屏只能同时看到 4 张。
     * 滑一次可能只露出半张，所以**连续滑几次滑到底**，
     * 确保最后一张也能被扫到。
     *
     * 对齐原项目 `tasks.py:54-57` 的 `scroll()`：它是直接
     * `mouse_scroll(40, -1)` 一路滚到底，而不是滚一点点。
     */
    private fun scrollCardRowToEnd() {
        val sy = (auto.height * CARD_SWIPE_Y).toInt()
        note("把任务卡片滑到底")
        repeat(3) {
            auto.swipe(
                (auto.width * CARD_SWIPE_FROM_X).toInt(), sy,
                (auto.width * CARD_SWIPE_TO_X).toInt(), sy,
                durationMs = 280,
            )
            Thread.sleep(400)
        }
        // 等界面停稳再扫
        Thread.sleep(600)
    }

    /**
     * 判断一条卡片文字里的进度是否已满。
     *
     * 形如 `进度1/1`、`0/120`、`120/120`。
     */
    private fun isProgressComplete(text: String): Boolean {
        val m = Regex("""(\d{1,3})\s*[/／]\s*(\d{1,3})""").find(text) ?: return false
        val cur = m.groupValues[1].toIntOrNull() ?: return false
        val total = m.groupValues[2].toIntOrNull() ?: return false
        return total > 0 && cur >= total
    }

    /**
     * 把任务卡片行**向左滑**，露出右边的卡片。
     *
     * 手机屏一次只能看到 4 张卡，第 5 张（以及可能更多的）要滑动才出现。
     * 对应原项目 `tasks.py:54-56` 的 `scroll()`。
     */
    private fun swipeLeftInCardRow() {
        val sy = (auto.height * CARD_SWIPE_Y).toInt()
        note("右滑卡片行，查看后面的任务")
        auto.swipe(
            (auto.width * CARD_SWIPE_FROM_X).toInt(), sy,
            (auto.width * CARD_SWIPE_TO_X).toInt(), sy,
            durationMs = 300,
        )
        Thread.sleep(500)
    }

    /** 把金色「领取」按钮全部点一遍，返回领取个数。 */
    private fun claimAll(): Int {
        var claimed = 0
        repeat(MAX_CLAIM_ROUNDS) { round ->
            val hit = findReceiveButton() ?: return@repeat
            note("第 ${round + 1} 轮：点「领取」@(${hit.first},${hit.second})")
            auto.tap(hit.first, hit.second, durationMs = 100L)
            Thread.sleep(1_200)
            dismissRewardPopup()
            claimed++
        }
        return claimed
    }

    // ==================== 刷副本 ====================

    /**
     * 去刷指定副本 [runs] 次，用来完成「累计消耗开拓力」。
     *
     * ## 为什么直接复用 [PowerTask]
     *
     * 进副本、设次数、点挑战、等结算、退出 —— 这套流程清体力已经调稳了，
     * 没必要重新实现一遍。这里只包一层：
     *
     * ```kotlin
     * val parsed = PowerTask.PowerParams(
     *     instance = inst.wireName,
     *     maxRuns = runs,       // 固定次数，不是 0（0 表示全部打完）
     *     allowRefill = false,
     * )
     * PowerTask(auto, nav, parsed, isCancelled).run()
     * ```
     */
    private fun runInstanceForPower(inst: PowerInstance, runs: Int): Boolean {
        // 先回大厅（每日实训页要退出去）
        if (!nav.isAt(ScreenNavigator.Screen.MAIN)) {
            nav.closeCurrent()
        }
        return try {
            val parsed = PowerTask.PowerParams(
                instance = inst.wireName,
                maxRuns = runs,
                allowRefill = false,
            )
            val r = PowerTask(auto, nav, parsed) { false }.run()
            note("刷副本结果：${if (r.ok) "成功" else "失败"} —— ${r.detail}")
            r.ok
        } catch (e: Exception) {
            note("刷副本异常：${e.message}")
            false
        }
    }

    /** 关掉「获得物品」弹窗（可能连续弹多个）。 */
    private fun dismissRewardPopup() {
        repeat(3) {
            if (!isRewardPopupOpen()) return@repeat
            note("关闭「获得物品」弹窗")
            auto.tap(REWARD_DISMISS_X, REWARD_DISMISS_Y, durationMs = 80L)
            Thread.sleep(800)
        }
    }

    // ==================== 委托（派遣）====================

    /**
     * 收取委托奖励（对应实训「派遣委托或收取1次委托奖励」+100）。
     *
     * ## 逻辑来自原项目 `dispatch.py`
     *
     * ```
     * ① 打开委托页
     * ② 在底部区域找「领取奖励」→ 找到就点 → 关弹窗 → 成功
     * ③ 如果读到「委托派遣中」→ 说明还没有可领的 → 跳过
     * ```
     *
     * 纯 OCR，不需要模板素材。
     */
    private fun claimDispatch(): Boolean {
        note("检查委托派遣")
        if (!openDispatchPage()) {
            note("打不开委托页")
            return false
        }

        // 在底部区域找「领取奖励」
        val hit = nav.withFrameOcrRegion(CROP_DISPATCH_CLAIM) { mat, region ->
            OcrEngine.findText(mat, "领取奖励", region)
        }

        if (hit != null) {
            note("点「领取奖励」@(${hit.cx},${hit.cy})")
            auto.tap(hit.cx, hit.cy, durationMs = 100L)
            Thread.sleep(2_000)
            dismissRewardPopup()
            return true
        }

        // 没找到「领取奖励」→ 看是不是"暂时没有可领"
        val idle = nav.withFrameOcrRegion(CROP_DISPATCH_CLAIM) { mat, region ->
            OcrEngine.findText(mat, TEXT_DISPATCH_IDLE, region)
        }
        if (idle != null) {
            note("委托派遣中，暂无可领取奖励")
        } else {
            note("未检测到可领取的委托奖励")
        }
        return false
    }

    /**
     * 打不开委托页。
     *
     * 委托入口在**手机菜单右侧竖栏**（和邮件、指南一样）。
     * 用 OCR 找「委托」二字。
     */
    private fun openDispatchPage(): Boolean {
        if (!nav.openPhoneMenu()) return false
        if (nav.clickTextAndVerify("委托", null, pageChanged = true)) {
            Thread.sleep(1_000)
            return true
        }
        note("手机菜单里没找到「委托」")
        return false
    }

    // ==================== 拍照 ====================

    /**
     * 完成「拍照1次」实训任务（+100）。
     *
     * ## 实测流程（用户提供的 4 张截图）
     *
     * ```
     * ① 手机菜单 → 右侧竖栏的相机图标 📷
     *       ↓
     * ② 相机取景界面
     *      · 右侧大圆环 = 转向控制
     *      · 正中白底相机按钮 (1709, 539) = 拍照
     *      · 底部滑块 = 视野缩放
     *       ↓ 点白色相机按钮
     * ③ 拍照完成（界面回到取景）
     *       ↓
     * ④ 拍照预览页（编辑 / 保存 / 右上角 ✕）
     *       ↓ 点 ✕ 退出
     * ⑤ 回到取景 → 再点 ✕ 回大厅
     * ```
     *
     * 原项目 `tasks/daily/photo.py` 是按键盘 `f` 拍照的，
     * 手游没有键盘，所以改成点那个白色相机按钮。
     */
    private fun takePhoto(): Boolean {
        note("拍照：打开相机")
        if (!openCamera()) {
            note("打不开相机")
            return false
        }

        // 点白色相机按钮
        val sx = auto.width / 1920f
        val sy = auto.height / 1080f
        val cx = (CAM_BTN_X * sx).toInt()
        val cy = (CAM_BTN_Y * sy).toInt()
        note("拍照：点快门 @($cx,$cy)")
        auto.tap(cx, cy, durationMs = 100L)
        Thread.sleep(2_500)

        // 关掉预览页（如果有）
        repeat(2) {
            if (!isPhotoPreviewOpen()) return@repeat
            note("关闭拍照预览")
            auto.tap((auto.width * 0.96f).toInt(), (auto.height * 0.06f).toInt(), durationMs = 80L)
            Thread.sleep(1_000)
        }

        // 退出相机（右上角 ✕）
        auto.tap((auto.width * 0.96f).toInt(), (auto.height * 0.06f).toInt(), durationMs = 80L)
        Thread.sleep(1_200)
        return true
    }

    /**
     * 打开相机。
     *
     * 入口在**手机菜单右侧竖栏**的相机图标。那是纯图标没有文字，
     * 所以按坐标点（实测在 (1660, 645) 附近）。
     */
    private fun openCamera(): Boolean {
        if (!nav.openPhoneMenu()) return false

        // 先试坐标（相机图标无字可读）
        val sx = auto.width / 1920f
        val sy = auto.height / 1080f
        auto.tap((CAM_MENU_X * sx).toInt(), (CAM_MENU_Y * sy).toInt(), durationMs = 100L)
        Thread.sleep(2_500)

        // 判断是否进了相机：取景区有底部滑块 + 三个圆按钮，用 OCR 兜底
        val texts = nav.readAllText().map { it.normalized() }.joinToString("|")
        // 取景区几乎没文字，但手机菜单有「商店/好友/委托」等
        if (texts.contains("商店") || texts.contains("好友") || texts.contains("无名勋礼")) {
            note("点相机后还在手机菜单，可能点偏了")
            return false
        }
        note("相机已打开")
        return true
    }

    /** 是否在拍照预览页（有「保存」按钮）。 */
    private fun isPhotoPreviewOpen(): Boolean {
        val hit = nav.withFrameOcrRegion(CROP_PHOTO_PREVIEW) { mat, region ->
            OcrEngine.findText(mat, "保存", region)
        }
        return hit != null
    }

    // ==================== 万能合成机 ====================

    /**
     * 使用一次万能合成机（实训「使用1次「万能合成机」」+100）。
     *
     * ## 最简路径
     *
     * 进合成 → 默认选中的第一个物品 → 合成 → 确认 → 等动画 → 关弹窗。
     *
     * 不需要筛选、不需要找特定材料 —— 随便合一个消耗品就满足条件，
     * 而默认选中的正好是消耗品（食物）。
     *
     * ## ⚠️ 确认后必须等 1~2 秒
     *
     * 用户实测：点了「确认」不会立刻弹「获得物品」，中间有合成动画。
     * 不等的话后面找弹窗会落空。
     */
    private fun useSynthesizer(): Boolean {
        note("合成：打开合成机")
        if (!openSynthesizer()) {
            note("打不开合成机")
            return false
        }

        val sx = auto.width / 1920f
        val sy = auto.height / 1080f

        // ① 点底部「合成」
        note("合成：点「合成」@($SYNTH_BTN_X,$SYNTH_BTN_Y)")
        auto.tap((SYNTH_BTN_X * sx).toInt(), (SYNTH_BTN_Y * sy).toInt(), durationMs = 100L)
        Thread.sleep(1_500)

        // ② 确认弹窗 → 点「确认」
        val confirm = nav.withFrameOcrRegion(CROP_SYNTH_DIALOG) { mat, region ->
            OcrEngine.findText(mat, "确认", region)
        }
        if (confirm != null) {
            note("合成：点「确认」@(${confirm.cx},${confirm.cy})")
            auto.tap(confirm.cx, confirm.cy, durationMs = 100L)
        } else {
            note("合成：没看到确认弹窗，按坐标点")
            auto.tap((SYNTH_CONFIRM_X * sx).toInt(), (SYNTH_CONFIRM_Y * sy).toInt(), durationMs = 100L)
        }

        // ③ **等合成动画**（用户实测 1~2 秒）
        note("合成：等动画 ${SYNTH_ANIM_MS}ms")
        Thread.sleep(SYNTH_ANIM_MS)

        // ④ 关「获得物品」弹窗
        dismissRewardPopup()

        // ⑤ 退出合成页（右上角 ✕）
        auto.tap((auto.width * 0.96f).toInt(), (auto.height * 0.06f).toInt(), durationMs = 80L)
        Thread.sleep(1_000)
        return true
    }

    /**
     * 打开万能合成机。
     *
     * 入口在手机菜单的**「合成」图标**（中间网格第 2 行第 2 列）。
     * 那是纯图标但有文字标签，可以试 OCR，失败再按坐标点。
     */
    private fun openSynthesizer(): Boolean {
        if (!nav.openPhoneMenu()) return false

        if (nav.clickTextAndVerify("合成", null, pageChanged = true)) {
            Thread.sleep(2_000)
            note("合成机已打开（OCR）")
            return true
        }

        val sx = auto.width / 1920f
        val sy = auto.height / 1080f
        auto.tap((SYNTH_MENU_X * sx).toInt(), (SYNTH_MENU_Y * sy).toInt(), durationMs = 100L)
        Thread.sleep(2_000)
        note("合成机已打开（坐标）")
        return true
    }

    // ==================== 秘技 ====================

    /**
     * 施放秘技（实训「累计施放2次秘技」+200）。
     *
     * ## 只能点坐标
     *
     * 用户明确要求**不做图像识别** —— 秘技按钮（召唤球）的外观
     * 在不同角色/场景下会变，模板匹配和 OCR 都不可靠。
     * 而它的位置是所有角色统一的，写死坐标最稳。
     *
     * 实测（1920×1080）：圆心 **(1413, 913)**。
     *
     * ## 施放 3 次
     *
     * 实训要求累计 2 次，多放 1 次保险（可能有一次被动画吞掉）。
     */
    private fun useTechnique(): Boolean {
        // 必须先在大厅（秘技按钮在大厅/副本里都有，但大厅最稳）
        if (!nav.isAt(ScreenNavigator.Screen.MAIN)) {
            note("施放秘技前先回大厅")
            if (!nav.closeCurrent()) return false
        }

        val sx = auto.width / 1920f
        val sy = auto.height / 1080f
        val cx = (SKILL_BTN_X * sx).toInt()
        val cy = (SKILL_BTN_Y * sy).toInt()

        repeat(SKILL_TIMES) { i ->
            note("施放秘技 ${i + 1}/$SKILL_TIMES @($cx,$cy)")
            auto.tap(cx, cy, durationMs = 100L)
            Thread.sleep(SKILL_WAIT_MS)
        }
        return true
    }

    // ==================== 打开页面 ====================

    /**
     * 导航到每日实训页。
     *
     * ## 路径和清体力一样，只是不用切子标签
     *
     * ```
     * 大厅 → 点手机 → 点「指南」→ 每日实训
     *                        ↑
     *                  指南默认就是这个页
     * ```
     *
     * 清体力还要再点「生存索引」，每日实训不用。
     */
    private fun openDailyPage(): Boolean {
        note("导航：手机 → 指南 → 每日实训")
        if (nav.gotoGuideDaily()) {
            note("已到每日实训")
            Thread.sleep(600)
            return true
        }
        note("无法到达每日实训页")
        return false
    }

    // ==================== 读活跃度 ====================

    /**
     * 读顶部当前活跃度（左侧大圆圈里的数字）。
     *
     * OCR 可能把它和里程牌的数字混在一起，所以只取最左边那个。
     */
    private fun readActivityScore(): Int? {
        val region = ScreenNavigator.NormRect(0.10f, 0.18f, 0.10f, 0.10f)
        val texts = nav.withFrameOcrRegion(region) { mat, r ->
            OcrEngine.run(mat, r)
        } ?: return null
        return texts
            .filter { o -> o.normalized().all { it.isDigit() } && o.normalized().isNotEmpty() }
            .minByOrNull { it.cx }
            ?.normalized()
            ?.toIntOrNull()
    }

    // ==================== 找「领取」按钮 ====================

    /**
     * 找到任意一个**金色「领取」按钮**并返回其中心。
     *
     * 5 张卡片是横排的，逐个 x 找 —— 找到第一个就返回。
     */
    private fun findReceiveButton(): Pair<Int, Int>? {
        for (x in BTN_XS) {
            val region = ScreenNavigator.NormRect(
                (x - 120) / 1920f, (BTN_ROW_Y - 45) / 1080f,
                240 / 1920f, 90 / 1080f,
            )
            val hit = nav.findInRegion(TEMPLATE_RECEIVE, 0.75, region)
            if (hit != null) {
                val sf = 1080.0 / auto.height
                val cx = ((hit.x + hit.templateWidth * hit.scale / 2) * sf).toInt()
                val cy = ((hit.y + hit.templateHeight * hit.scale / 2) * sf).toInt()
                return cx to cy
            }
        }
        return null
    }

    // ==================== 奖励弹窗 ====================

    /** 屏幕中央是否出现了「获得物品」弹窗。 */
    private fun isRewardPopupOpen(): Boolean {
        val hit = nav.withFrameOcrRegion(CROP_REWARD_TITLE) { mat, r ->
            OcrEngine.findText(mat, "获得物品", r)
        }
        return hit != null
    }

    // ==================== 里程碑奖励 ====================

    /**
     * 领活跃度里程碑奖励（100/200/300/400/500 那几个礼包）。
     *
     * 里程碑在顶部进度条上，**有未领的会显示红色感叹号**，
     * 领完变成灰色礼包图标。
     *
     * 这里用简单策略：依次点 5 个里程碑位置，
     * 有奖励就会弹「获得物品」，没奖励就什么都不发生。
     */
    private fun claimMilestones(): Int {
        // 实测顶部进度条：0 在 x≈320，100 在 x≈550，200 在 x≈782，
        // 300 在 x≈1014，400 在 x≈1247，500 在 x≈1479，y 都在 268 附近
        val xs = listOf(550, 782, 1014, 1247, 1479)
        val y = 268
        var count = 0
        for (x in xs) {
            auto.tap(x, y, durationMs = 80L)
            Thread.sleep(900)
            if (isRewardPopupOpen()) {
                count++
                note("里程碑 @($x,$y) 领到奖励")
                repeat(3) {
                    if (!isRewardPopupOpen()) return@repeat
                    auto.tap(REWARD_DISMISS_X, REWARD_DISMISS_Y, durationMs = 80L)
                    Thread.sleep(700)
                }
            }
        }
        return count
    }

    // ==================== 工具 ====================

    private fun tail(): String =
        if (notes.isEmpty()) "" else notes.joinToString("；", prefix = "。过程：")

    @Suppress("unused")
    private fun absDiff(a: Int, b: Int): Int = abs(a - b)
}
