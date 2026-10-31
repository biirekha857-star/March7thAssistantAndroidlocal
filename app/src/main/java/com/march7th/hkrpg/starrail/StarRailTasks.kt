package com.march7th.hkrpg.starrail

import android.os.SystemClock
import com.march7th.hkrpg.data.model.StartUpTaskConfig
import com.march7th.hkrpg.starrail.engine.GameLauncher
import com.march7th.hkrpg.starrail.engine.ImageUtils
import com.march7th.hkrpg.starrail.engine.OcrEngine
import com.march7th.hkrpg.starrail.engine.ScreenNavigator
import com.march7th.hkrpg.starrail.engine.ShellAutomation
import com.march7th.hkrpg.third.Ln

/**
 * 崩铁任务实现。
 *
 * 按用户确认的优先级：**A（启动/关闭）→ B（邮件）→ D（每日实训）→ C（成就）**。
 *
 * 每个任务都是"前置导航 → 操作 → 收尾"三段式，与 M7A 的结构对应。
 */
class StarRailTasks(
    private val auto: ShellAutomation,
    private val pkg: String = GameLauncher.PKG_CN,
    /** 界面导航（懒建，避免未接入采集时构造失败）。 */
    private val navigator: ScreenNavigator? = null,
    /** 导出画面（调试任务）的落盘根目录。 */
    private val dumpRoot: String = com.march7th.hkrpg.remote.CoreDataDir.ROOT,
    /**
     * 用户取消信号（「停止任务」）。
     *
     * 引擎的 `running` 标志只在**任务之间**检查，一个任务内部
     * （清体力要跑几分钟）中途取消是拦不住的。所以把它透传给
     * 各任务实现，让它们在等待循环里自查。
     */
    private val isCancelled: () -> Boolean = { false },
    /**
     * 非致命事件上报 —— 让外壳能「提个醒」而不是只写日志。
     *
     * 引擎跑在特权进程，主进程看不到它的日志，所以要走
     * `M7aCoreCallback`（[com.march7th.hkrpg.starrail.StarRailLocalEngine] 里接的）
     * 把事件送回主进程。参数是 `(what, 附加字段的 JSON 片段)`，
     * `what` 会被 [com.march7th.hkrpg.core.callback.SubTaskHandler] 路由。
     */
    private val onNotice: ((what: String, extraJson: String) -> Unit)? = null,
) {

    companion object {
        private const val TAG = "StarRailTasks"

        // ---- 启动检测的时间参数（实测自最低配置手机）----
        //
        // 用户实测（麒麟 820 这类低配机）：
        //   点「进入」→ 出现「点击进入」：约 52 秒
        //   → 完全进大厅：约 91 秒（1 分 31 秒）
        //
        // 所以 35 秒开始找按钮、点完再等 9 秒确认主界面，
        // 一切失败都每 5 秒重试，总上限 110 秒。
        //
        // 为什么 110 秒是硬上限：再等只有两种可能 —— 游戏出错，
        // 或者设备太老跑不动。继续等没有意义，直接中止任务链。

        /** 首次检测「点击进入」前先等这么久。 */
        private const val FIRST_CHECK_MS = 25_000L

        /** 点完「点击进入」后等这么久再确认主界面。 */
        private const val AFTER_ENTER_MS = 9_000L

        /** 「列车补给」公告标题的识别区（顶部 25%）。 */
        private val CROP_TOP_QUARTER = ScreenNavigator.NormRect(
            0.00f, 0.00f, 1.00f, 0.25f,
        )

        /** 开屏公告的标题文字。 */
        private const val TEXT_TRAIN_SUPPLY = "列车补给"

        /** 公告最多处理几轮（每轮 = 点领取 + 等 + 再点 + 等 + 验证）。 */
        private const val NOTICE_MAX_ROUNDS = 2

        /** 每一步之间的等待（领取 → 明细弹出 → 关明细 → 回大厅）。 */
        private const val NOTICE_STEP_MS = 4_000L

        /**
         * 公告的点击位置 —— 「点击领取今日补给」那一行。
         *
         * 实测：素材图里这行文字在归一化 y≈0.949（1080 下 ≈1025）。
         * 第一次点是「领取」，第二次点同一位置是「点击明细窗口关闭它」。
         */
        private const val NOTICE_CLICK_X = 960
        private const val NOTICE_CLICK_Y = 1020

        /**
         * 月卡（列车补给凭证）领取按钮可能出现的文案。
         *
         * ## 为什么要认文字，而不是只靠坐标
         *
         * 之前只按 (960,1020) 硬点。但那个坐标是从素材图的
         * "点击领取今日补给" 一行量出来的，手机版排版一变就点空 ——
         * 点空不会报错，只会静默地什么都没领到。
         *
         * OCR 认到这行字就点它自己的中心，位置随界面走，换设备也不用改。
         * 认不到才退回坐标。
         * ⚠️ 这几个词**必须都带「补给」**。
         *
         * 它们同时充当"这里是不是月卡界面"的判据，要是写成裸的「领取」，
         * 邮件页的「全部领取」（x≈540,y≈984）正好落在底部搜索区里，
         * 会被误判成月卡弹窗 —— 那个弹窗一点都不像月卡。
         */
        private val MONTH_CARD_CLAIM_TEXTS = listOf(
            "点击领取今日补给",
            "领取今日补给",
            "今日补给",
        )

        /** 领取按钮的搜索区（屏幕下沿那一带）。 */
        private val CROP_BOTTOM_AREA = ScreenNavigator.NormRect(
            0.00f, 0.78f, 1.00f, 0.22f,
        )

        /**
         * 月卡领取按钮最多点几次。
         *
         * ## 完整时序（真实链路，别再猜）
         *
         * ```
         * 加载界面
         *   → 月卡领取界面   「列车补给凭证」+「点击领取今日补给」
         *   → 月卡奖励确认界面   点完领取后弹出，星穹到账提示
         *   → 大厅           角色头像在这一步才可识别
         * ```
         *
         * 也就是说：**头像出现 = 前面三步全走完了**。
         *
         * ## ⚠️ 原来为什么领不到
         *
         * 旧代码点完领取后发现「点击领取今日补给」这行字消失，
         * 就直接判定"领取界面已消失 → 成功"并返回。
         * 但那时画面正停在**奖励确认界面**，离大厅还差一步。
         * 于是 `waitForMain` 立刻放行、后续任务开始跑，
         * 而屏幕上还盖着一层奖励确认框、头像根本不可见 ——
         * 后面的任务全部错乱，玩家看到的就是"月卡没领成"。
         *
         * 所以现在的判据只有一个：**角色头像可见**。
         * 点多少次领取不重要（正常一次就够），次数是"点空了"的保险。
         */
        private const val MONTH_CARD_MAX_ROUNDS = 6

        /**
         * 奖励确认界面最多关几次。
         *
         * 这一层是「点完领取」和「回到大厅」之间的必经界面。
         * 不关掉它，`isMainUi()` 永远是 false，
         * `waitForMain` 会一直空转到 180 秒超时。
         */
        private const val MONTH_CARD_CONFIRM_TRIES = 5

        /** 关奖励确认时每步之间的等待（收奖励动画 + 界面淡出）。 */
        private const val MONTH_CARD_CONFIRM_STEP_MS = 1_500L

        /**
         * 奖励确认界面上的确认类文字。
         *
         * 崩铁这类全屏奖励提示，通常有「确认 / 我知道了」按钮，
         * 或者写着「点击空白处继续」。
         */
        private val MONTH_CARD_CONFIRM_TEXTS = listOf(
            "点击空白处继续",
            "点击任意处继续",
            "我知道了",
            "确认",
            "确定",
        )

        /** 奖励确认界面的识别区（画面中央，避开顶部标题与底部按钮）。 */
        private val CROP_REWARD_CONFIRM = ScreenNavigator.NormRect(
            0.10f, 0.25f, 0.80f, 0.50f,
        )
        private const val MONTH_CARD_STEP_MS = 2_500L

        /**
         * 兜底点击用的「安全空白区」—— 画面水平居中、纵向 24%。
         *
         * ## ⚠️ 这个坐标是经过排查的，别随手改
         *
         * 崩铁各界面在这个位置都**没有绑定任何功能**：
         *
         * | 界面 | 这个位置是什么 | 点了会怎样 |
         * |---|---|---|
         * | 加载界面 | 背景 | 无反应 |
         * | 月卡/公告页 | 标题下方的插图空白 | 无反应（公告页不吃空白点击） |
         * | 奖励确认页 | 奖励标题上方的空白 | 多数情况会"点空白继续" |
         * | 大厅 | 天空/远景 | 无反应 |
         *
         * ## 屏幕上这些地方**绝对不能**当兜底点
         *
         * - **左上角**：绝大多数子界面的返回/✕ 就在这，一点就退出去
         * - **右上角**：大厅是角色头像和菜单，点开菜单会挡住一切
         * - **右侧一列**：大厅的跃迁/角色/背包/编队按钮
         * - **底部中央**：大厅的任务追踪、聊天，还有各页面的主操作按钮
         *
         * 之前想过用底部 (960,1020) 去"盲点领取按钮"，这是错的 ——
         * 那个位置只在月卡页是领取按钮，在大厅可能是别的 UI。
         * 认不出按钮就该认，不能拿别的界面去赌。
         */
        private const val SAFE_BLANK_X = 960
        private const val SAFE_BLANK_Y = 260

        /**
         * 看到角色头像之后，还要再探几次月卡才敢放行。
         *
         * ## 正常时序下用不到，这是异常兜底
         *
         * 正常链路是「加载 → 领取 → 奖励确认 → 大厅」，
         * 头像出现时月卡早就该处理完了，守门纯属保险。
         *
         * 但如果哪天公告比大厅晚一拍（弱网、广告位异步加载、
         * 或者游戏版本改成了"进大厅后再弹"），头像会先出现。
         * 一旦放行，后面就没人再管这个弹窗了 ——
         * 当天的补给直接丢掉，而且**不会有任何报错**。
         *
         * 所以宁可多等 2 × 1s，也不赌。确认过没有弹窗就立刻放行。
         */
        private const val MONTH_CARD_GUARD_PROBES = 2

        /** 守门窗口内的探测间隔。比主循环的 5 秒密得多。 */
        private const val MONTH_CARD_GUARD_INTERVAL_MS = 1_200L

        /**
         * 点了「点击进入」多久还没进大厅、又认不到月卡界面，就认为卡住了。
         *
         * 实测正常进入大厅只要 14~21 秒。给到 45 秒已经很宽了。
         *
         * 到这个点还卡着，只有两种可能：
         * ① 月卡界面在，但 OCR 认不到那两行字（区域/字体/分辨率问题）；
         * ② 停在某个我们没处理过的弹窗上。
         *
         * 两种情况都先打一份全屏 OCR 到日志（下次不用再猜），
         * 再按坐标盲点一次领取兜底 —— 因为月卡**一天只有一次机会**，
         * 宁可盲点一下（最坏是点空），也不能整天不领。
         */
        private const val MONTH_CARD_BLIND_AFTER_SEC = 45L

        /** 自救时最多点几次 X。 */
        private const val MAX_SELF_HEAL_CLICKS = 14

        /**
         * 启动任务里"跳过前确认"的自救次数。
         *
         * 点 X 后识别角色头像，失败再点，共 2 次 —— 还不行就杀进程重启。
         */
        private const val MAX_SKIP_HEAL_TRIES = 2

        /** 每次点完 X 后等多久再复查。 */
        private const val SELF_HEAL_INTERVAL_MS = 800L

        /** 失败后的重试间隔（等「点击进入」/等主界面的轮询间隔）。 */
        private const val RETRY_INTERVAL_MS = 5_000L

        /** 总时长硬上限（实测 90 秒不够，放宽到 110 秒）：超过就判定启动失败并中止任务链。 */
        private const val TOTAL_LIMIT_MS = 180_000L

        /**
         * 等「点击进入」多久还没出现，就强制游戏重新布局一次（「主屏往返」）。
         *
         * 正常启动 40~50 秒内必然出现该按钮（实测 42s 左右）。留到 50 秒
         * 说明不是"加载慢"，而是画面本身有问题 —— 典型是荣耀类 ROM 的速启
         * 让游戏带着物理屏的图形环境进来，UI 被放大裁切到画框外。
         */
        private const val RELAYOUT_KICK_AFTER_SEC = 50L

        // ---- 区域常量（全部只搜一小块，避免全屏开销）----

        /**
         * 云游戏排队的最长等待时间。
         *
         * 排队时长完全不可控（用户实测可能超过 30 分钟），所以排队期间
         * **暂停启动超时计时**，只用这个常量做最终兜底 ——
         * 60 分钟足够覆盖任何高峰排队，又不至于真卡死时永远挂起。
         */
        private const val QUEUE_MAX_WAIT_MS = 60L * 60 * 1000

        /**
         * 云游戏「开始游戏」的点击冷却。
         *
         * 按钮点完不会立刻消失（要等界面切换），所以必须留出冷却，
         * 否则下一次轮询会对着同一屏再点一遍。8 秒既够界面完成跳转，
         * 又能在真点偏时快速重试。
         */
        private const val START_TAP_COOLDOWN_MS = 8_000L

        /** 「点击进入」按钮的搜索区（标题页中下部）。 */
        private val CROP_CLICK_ENTER = ScreenNavigator.NormRect(
            0.15f, 0.60f, 0.70f, 0.40f,
        )

        /** 角色头像的搜索区（右上角）。 */
        private val CROP_MAIN_ANCHOR = ScreenNavigator.NormRect(
            0.90f, 0.00f, 0.10f, 0.12f,
        )

        /** 右上角 X 关闭按钮的搜索区。 */
        private val CROP_CLOSE_BUTTON = ScreenNavigator.NormRect(
            0.80f, 0.00f, 0.20f, 0.15f,
        )
    }

    data class TaskResult(val type: String, val ok: Boolean, val detail: String)

    /** 任务执行上下文（目前只需要 displayId）。 */
    private val displayId: Int get() = auto.displayId

    /**
     * 执行单个任务。
     *
     * @param params 任务的参数字符串（`AppendTask` 传下来的 JSON）。
     *   崩铁的"一键领取"类任务不需要参数，[PowerTask] 需要。
     */
    fun run(type: String, params: String = "{}"): TaskResult {
        Ln.i("$TAG: 执行任务 $type (display=$displayId)")
        return when (type) {
            // ---- A: 启动游戏（客户端在 params 里选）----
            "StartUp" -> startUp(params)
            // ---- B: 领邮件 ----
            "Mail" -> mail()
            // ---- 清体力 ----
            "Power" -> power(params)
            // ---- 每日实训 ----
            "Daily" -> daily(params)
            // ---- A: 关闭游戏 ----
            "CloseDown" -> closeDown()
            // ---- 调试：导出当前帧（采模板用）----
            "Dump" -> dump()
            else -> TaskResult(type, false, "尚未实现")
        }
    }

    // ============ A. 启动游戏 ============
    /**
     * 启动游戏并**推进到主界面**。
     *
     * ## 为什么"启动进程"不等于"能开始干活"
     *
     * 崩铁启动后会停在标题页，必须点一次「点击进入」才真正加载存档进大厅。
     * M7A 的做法是 `wait_until(check_and_click_enter, start_game_timeout*60)`，
     * 其中 `check_and_click_enter` 每一轮都会：
     *   1. 找 `click_enter.png`（0.9）—— 命中就点，返回成功
     *   2. 否则依次尝试「热更确认」「网络重启」「开始游戏」「切换账号」「同意协议」
     *
     * 手游版同样是这个流程，只是**没有登录器/多账号/B服**这些 PC 分支，
     * 所以这里只保留「点击进入」+ 两个通用兜底（确认 / 重启）。
     */
    private fun startUp(params: String = "{}"): TaskResult {
        if (displayId < 0) {
            return TaskResult("StartUp", false, "虚拟屏未就绪（displayId=$displayId）")
        }

        // ## 解析客户端选择
        //
        // 「启动游戏」带一个客户端选择器：
        //   · local → com.miHoYo.hkrpg           （本地客户端）
        //   · cloud → com.miHoYo.cloudgames.hkrpg（云游戏）
        //
        // 手机同一时刻只能跑一个游戏实例，所以做成**一个任务 + 二选一**，
        // 而不是两个独立任务。
        //
        // 默认 LOCAL —— 老配置（没有这个字段）行为完全不变。
        val client = StartUpTaskConfig.GameClient.fromWire(
            Regex("\"client\"\\s*:\\s*\"([^\"]*)\"").find(params)?.groupValues?.get(1),
        )
        val effectivePkg = Regex("\"packageName\"\\s*:\\s*\"([^\"]*)\"")
            .find(params)?.groupValues?.get(1)
            ?.takeIf { it.isNotBlank() }
            ?: client.packageName
        val timeoutOverride = Regex("\"timeoutSec\"\\s*:\\s*(\\d+)")
            .find(params)?.groupValues?.get(1)?.toIntOrNull()

        Ln.i(
            "$TAG: 启动游戏 客户端=${client.wireName} 包名=$effectivePkg " +
                "超时=${timeoutOverride ?: client.defaultTimeoutSec}s",
        )


        // ---- ① 先确认游戏装了没 ----
        //
        // 实测踩过：在**没装崩铁**的设备上跑任务，日志里全是
        // ```
        // NameNotFoundException: com.miHoYo.hkrpg
        // GameLauncher: 找不到 com.miHoYo.hkrpg 的启动 Intent
        // ShellAutomation: 取帧失败（3 次）  ← 刷了几十次
        // ```
        // 用户看到的是「任务出错:启动游戏」，完全猜不到是没装游戏。
        //
        // 所以这里先查包是否存在，不存在就直接给出**明确可行动**的提示。
        if (!GameLauncher.isInstalled(effectivePkg)) {
            val hint = when (effectivePkg) {
                GameLauncher.PKG_CN ->
                    "未检测到《崩坏：星穹铁道》国服（$effectivePkg）。请先安装游戏，" +
                        "或在设置里切换到正确的客户端版本。"
                else ->
                    "未检测到《崩坏：星穹铁道》（$effectivePkg）。请先安装游戏，" +
                        "或在设置里切换到正确的客户端版本。"
            }
            Ln.e("$TAG: $hint")
            return TaskResult("StartUp", false, hint)
        }
        // ---- ⓪ 判断游戏是不是**已经在虚拟屏上正常运行** ----
        //
        // ## 跳过条件（两个都要满足）
        //
        // 1. 游戏进程活着；
        // 2. 游戏就落在**我们这个虚拟屏**上（displayId 一致）。
        //
        // 满足就说明游戏状态完全正常，直接跳过整个启动流程 ——
        // 不用重新拉起，也不用等「点击进入」，省掉 1~2 分钟。
        //
        // ## 不满足时的处理
        //
        // - **进程不在** → 直接冷启动；
        // - **进程在，但在手机主屏**（用户自己打开过）→
        //   **必须先杀掉进程再重新启动**。
        //
        //   把已经在主屏跑着的游戏直接"拉"到虚拟屏会出现各种奇怪问题
        //   （画面不同步、输入注入错位、采集帧异常），实测确认过。
        //   唯一可靠的做法是 force-stop 之后在虚拟屏上重新拉起。
        val running = GameLauncher.isRunning(effectivePkg)
        val onDisplay = if (running) GameLauncher.currentDisplayOf(effectivePkg) else -1

        // ## ⚠️ 必须**同时**满足两个条件才算"已在虚拟屏"
        //
        // `currentDisplayOf` 是解析 `dumpsys activity activities` 里
        // 的 `* Task{...com.miHoYo.hkrpg...}` 行来判断的。
        //
        // **问题**：即使游戏进程已经死了，系统里仍可能残留 task 记录
        // （最近任务列表、launcher 引用等），这行照样存在 ——
        // 于是 `currentDisplayOf` 会返回一个 display 号，
        // 如果恰好等于虚拟屏 id，就会误判成"游戏已经在虚拟屏上"，
        // 直接跳过启动，后续任务全部对着黑屏跑。
        //
        // 所以这里**先用 pidof 确认进程真活着**（isRunning），
        // 再谈在哪个 display 上。两者都满足才跳过。
        val onOurDisplay = running && onDisplay == displayId

        Ln.i(
            "$TAG: 启动前探测 —— 进程在=$running 当前display=$onDisplay " +
                "期望display=$displayId ⇒ ${if (onOurDisplay) "已在虚拟屏，跳过启动" else "需要（重新）启动"}",
        )

        if (onOurDisplay) {
            // ## 跳过前必须先确认"真的在大厅"
            //
            // "游戏在虚拟屏上"只是必要条件 —— 它可能停在登录页、
            // 活动弹窗、角色详情页等任何地方。直接跳过启动，
            // 后面的任务会对着错误的界面操作。
            //
            // 所以这里做一次状态确认：
            //
            // ```
            // 识别角色头像
            //    ├─ 成功 → 真在大厅 → 跳过启动
            //    └─ 失败 → 识别一次 X 号
            //         ├─ 识别到 → 自救（点 X 直到回大厅）
            //         │    ├─ 成功 → 跳过启动
            //         │    └─ 连续 2 次不行 → 杀进程重启
            //         └─ 没识别到 → 杀进程重启
            // ```
            val navEarly = navigator
            if (navEarly != null && auto.takeScreenshot(retries = 1) && isMainUi(navEarly)) {
                Ln.i("$TAG: 已在虚拟屏且确认在大厅 ⇒ 跳过启动流程")
                return TaskResult(
                    "StartUp",
                    true,
                    "复用虚拟屏上的游戏（已在大厅）${auto.width}x${auto.height}",
                )
            }

            Ln.w("$TAG: 游戏在虚拟屏但不在大厅，尝试识别 X 号关闭弹窗")
            var healed = false
            if (navEarly != null) {
                val closeBtn = findCloseButton(navEarly)
                if (closeBtn == null) {
                    Ln.w("$TAG: 没有识别到 X 号 ⇒ 无法自救，直接杀进程重启")
                } else {
                    Ln.i("$TAG: 识别到 X 号 @(${closeBtn.first},${closeBtn.second})，开始自救")
                    healed = healToMainMenu(navEarly)
                }
            }

            if (healed) {
                Ln.i("$TAG: 自救成功，已回到大厅 ⇒ 跳过启动流程")
                return TaskResult(
                    "StartUp",
                    true,
                    "复用虚拟屏上的游戏（自救后回到大厅）${auto.width}x${auto.height}",
                )
            }

            Ln.w("$TAG: 自救失败 ⇒ 杀进程后重新启动")
            GameLauncher.forceStop(effectivePkg)
            Thread.sleep(2500)
            // 落到下面的正常启动流程
        }

        // ---- 不在虚拟屏上：先杀掉再重新启动 ----
        if (running) {
            Ln.w("$TAG: 游戏在 display=$onDisplay（不是虚拟屏），先强杀进程再重启")
            // 用确认版：发完 force-stop 不代表进程死了，ROM 速启会把它拉回来，
            // 拉回来的热进程带着物理屏的图形环境，直接复用就会画面放大错位
            GameLauncher.forceStopAndConfirm(effectivePkg)
            Thread.sleep(800)
        } else {
            Ln.i("$TAG: 游戏进程不在，直接冷启动")
        }

        val accepted = GameLauncher.startOnDisplay(effectivePkg, displayId)
        if (!accepted) {
            return TaskResult("StartUp", false, "启动指令被拒绝")
        }

        // 等待落到虚拟屏（含二段跳漂移拉回）
        if (!GameLauncher.ensureOnDisplay(effectivePkg, displayId)) {
            return TaskResult("StartUp", false, "游戏未落到虚拟屏（display=${GameLauncher.currentDisplayOf(effectivePkg)}）")
        }

        // 等第一帧渲染出来
        if (!waitForFrame(timeoutSec = 40)) {
            return TaskResult("StartUp", false, "启动后未采到画面")
        }

        val nav = navigator
            ?: return TaskResult("StartUp", false, "导航器未就绪")

        // ## 云游戏走独立流程
        //
        // 云游戏客户端不是「标题页 → 点击进入」，而是：
        //
        // ```
        // ① 云游戏启动页（左上「云·星穹铁道」，右下橙色「开始游戏」）
        //       ↓ 点「开始游戏」
        // ② 排队中（可能瞬间过，也可能要等几分钟）
        //       ↓ 不操作，等它自己过
        // ③ 串流建立 → 进游戏
        // ```
        //
        // 所以这里分流，不走 waitAndClickEnter 那套。
        if (client == StartUpTaskConfig.GameClient.CLOUD) {
            return cloudGameStartUpFlow(nav, client)
        }

        // ---- 标题页 -> 大厅 ----
        //
        // 按实测节奏推进：35 秒后找「点击进入」，点了再等 9 秒确认主界面。
        // 任何一步失败都每 5 秒重试，**总上限 110 秒** —— 超过就判定
        // 启动失败，由引擎中止整个任务链（而不是傻等下去）。
        if (!waitAndClickEnter(nav)) {
            return TaskResult(
                "StartUp",
                false,
                "启动超时：${TOTAL_LIMIT_MS / 1000} 秒内没等到「点击进入」。" +
                    "可能是游戏异常，或设备性能不足。任务链已中止。",
            )
        }

        // ---- 月卡（列车补给凭证） ----
        //
        // 点了「点击进入」之后，如果今天还没领月卡，游戏会在**加载完成后**
        // 依次弹出「月卡领取界面」→「月卡奖励确认界面」，然后才进大厅。
        // 这几层都会挡住角色头像，[waitForMain] 认不到头像就会一直等到超时。
        //
        // 每天只弹一次，领完当天不再出现，所以**没有第二次机会**。
        //
        // 这里先探一次（此刻通常还在加载界面，探不到就立刻返回）；
        // 真正可靠的探测在 [waitForMain] 的轮询里 —— 那边会一直等到它出现。
        // 如果这里就领完并确认在大厅，可以直接跳过 waitForMain 开头的 9 秒等待。
        if (handleTrainSupplyNotice(nav)) {
            return TaskResult("StartUp", true, "已进入主界面（已领取月卡补给）${auto.width}x${auto.height}")
        }

        if (waitForMain(nav)) {
            return TaskResult("StartUp", true, "已进入主界面 ${auto.width}x${auto.height}")
        }

        return TaskResult(
            "StartUp",
            false,
            "启动超时：点了「点击进入」但 ${TOTAL_LIMIT_MS / 1000} 秒内没进到主界面。" +
                "游戏可能卡在加载或公告页，任务链已中止。",
        )
    }

    /**
     * **云游戏**的启动流程。
     *
     * # 实测界面
     *
     * | 界面 | 特征 | 操作 |
     * |---|---|---|
     * | 云游戏启动页 | 左上「云·星穹铁道」，右下橙色**「开始游戏」** | 点「开始游戏」 |
     * | 排队中 | 「排队中……」「云服务器资源紧张」「退出排队」 | **只等待，绝不点击** |
     *
     * # ⚠️ 排队不能按固定时长算超时
     *
     * 云游戏显示的「预计等待时间」是**动态估算**，随时会变 ——
     * 用户实测排队可能超过 30 分钟。所以：
     *
     * - **不能用固定 deadline**（会误判失败）
     * - **不能用预测时间算**（估算不准）
     *
     * 正确做法：**排队期间暂停超时计时**，只看界面状态。
     *
     * 判断排队是否结束，用「**退出排队**」这个按钮 ——
     * 它是排队页独有的，比识别会变的数字可靠得多：
     *
     * ```
     * 「退出排队」还在  →  仍在排队，继续等
     * 「退出排队」消失  →  排队结束，恢复计时
     * ```
     *
     * # 安全兜底
     *
     * 排队超过 [QUEUE_MAX_WAIT_MS]（60 分钟）才主动放弃 ——
     * 足够覆盖任何高峰，又不至于真卡死时永远挂起。
     *
     * # 为什么绝不点击排队页
     *
     * 排队页底部有「**退出排队**」按钮，位置可能和「开始游戏」重合。
     * 盲点一下就把排队退了，前功尽弃。所以只有**明确认出
     * 「开始游戏」** 才点，认出「排队」就完全不操作。
     */
    private fun cloudGameStartUpFlow(
        nav: ScreenNavigator,
        client: StartUpTaskConfig.GameClient,
    ): TaskResult {
        val startupBudgetMs = client.defaultTimeoutSec * 1000L
        var startupElapsedMs = 0L          // 非排队累计，**真实墙钟**而非轮数×步长
        var lastTickAt = 0L                // 上一轮结算时刻，用于计算真实耗时
        var lastStartTapAt = 0L            // 上次点「开始游戏」的时刻（防连点）
        var lastDiagLog = 0L
        var lastQueueLog = 0L
        var queueStartAt = 0L              // 首次进入排队的时刻
        var inQueue = false
        val pollMs = 2_000L

        while (true) {
            if (isCancelled()) {
                return TaskResult("StartUp", false, "任务已被用户停止")
            }

            // ---- 已经进游戏？----
            if (nav.isAt(ScreenNavigator.Screen.MAIN)) {
                Ln.i("$TAG: 云游戏已进入大厅")
                return TaskResult("StartUp", true, "云游戏启动成功 ${auto.width}x${auto.height}")
            }

            val now = SystemClock.uptimeMillis()

            // ---- 结算上一轮的真实耗时 ----
            //
            // ## 修：原来写的是 `startupElapsedMs += pollMs`（固定 2000）
            //
            // 但每轮实际要做一次**全屏 OCR**（实测 1.5~3 秒）+ 可能的点击等待
            // + 末尾 sleep(2000)，真实一轮 4~6 秒。按 2000 累加的话，
            // 配 300 秒预算实际要跑 10 分钟以上才超时 —— 用户感知就是"卡住了"。
            //
            // 现在按墙钟结算：`now - lastTickAt` 就是上一轮的真实长度。
            // 排队期间不计入（`inQueue` 用上一轮末尾的状态判定，
            // 偏差最多一轮，远小于原来的 2~3 倍失真）。
            if (lastTickAt != 0L && !inQueue) {
                startupElapsedMs += now - lastTickAt
            }
            lastTickAt = now

            // ---- 超时判断 ----
            //
            // ## 修：原来这段放在循环末尾、且被 `continue` 绕过
            //
            // 「点击进入」「开始游戏」两个分支点完就 `continue`，
            // 那一轮的超时检查根本不执行。启动页只要一直认得出按钮，
            // 就可以无限点下去而永不超时。
            //
            // 现在挪到**所有分支之前**：无论后面走哪条路，每轮都先判一次。
            // 顺带把它放到 OCR 之前 —— 超时时不必再多跑一次识别。
            if (!inQueue && startupElapsedMs > startupBudgetMs) {
                runCatching {
                    val s = nav.exportFrame("cloudgame_timeout")
                    Ln.e("$TAG: 云游戏启动超时，已导出画面 -> $s")
                }
                return TaskResult(
                    "StartUp", false,
                    "云游戏启动超时（非排队累计 ${startupElapsedMs / 1000}s）。" +
                        "可能启动页特征未识别。",
                )
            }

            val texts = nav.readAllText()
            val joined = texts.joinToString("") { it.text }
            val queueing = joined.contains("排队") || joined.contains("资源紧张")

            if (queueing) {
                // ==================== 排队模式 ====================
                //
                // 超时计时器**暂停** —— 排队多久不由我们决定。
                if (!inQueue) {
                    inQueue = true
                    queueStartAt = now
                    Ln.i("$TAG: 云游戏进入排队，暂停启动超时计时")
                }
                // 每 60 秒报一次进度
                if (now - lastQueueLog > 60_000) {
                    lastQueueLog = now
                    val waitedMin = (now - queueStartAt) / 60_000
                    Ln.i("$TAG: 云游戏排队中…已等 ${waitedMin} 分钟")
                }
                // 兜底：排太久就放弃
                if (now - queueStartAt > QUEUE_MAX_WAIT_MS) {
                    runCatching {
                        val s = nav.exportFrame("cloudgame_queue_timeout")
                        Ln.e("$TAG: 排队超 ${QUEUE_MAX_WAIT_MS / 60_000} 分钟，已导出画面 -> $s")
                    }
                    return TaskResult(
                        "StartUp", false,
                        "云游戏排队超过 ${QUEUE_MAX_WAIT_MS / 60_000} 分钟仍未进入，已放弃",
                    )
                }
            } else {
                // ==================== 非排队：正常计时 ====================
                //
                // 计时已在循环开头按墙钟结算，这里只负责退出排队状态。
                if (inQueue) {
                    inQueue = false
                    val qMin = (now - queueStartAt) / 60_000
                    Ln.i("$TAG: 云游戏排队结束（等了 ${qMin} 分钟），恢复启动计时")
                }

                // ---- ① 标题页「点击进入」----
                //
                // 云游戏串流建立后和本地客户端一样，会停在崩铁标题页，
                // 必须点一次「点击进入」才加载存档进大厅。
                //
                // 之前漏了这一步 —— 只等「大厅」出现，可「点击进入」
                // 不点永远到不了大厅，于是白等到超时。
                val enterBtn = texts.firstOrNull {
                    val t = it.text.replace(" ", "")
                    t.contains("点击进入") || t.contains("点击屏幕")
                }
                if (enterBtn != null) {
                    Ln.i("$TAG: 云游戏标题页，点「${enterBtn.text}」@(${enterBtn.cx},${enterBtn.cy})")
                    auto.tap(enterBtn.cx, enterBtn.cy)
                    Thread.sleep(3_000)
                    continue
                }

                // ---- ② 启动页「开始游戏」----
                //
                // **不设「只点一次」**。两种真实情况都需要再点一次：
                // - 上一次点击偏了（按钮位置随分辨率浮动）；
                // - 排队结束后又退回启动页，需要重新发起。
                //
                // 用冷却时间防连点：按钮点完不会立刻消失，
                // 不加冷却会在 2 秒后把自己刚点出来的界面再点一遍。
                val startBtn = texts.firstOrNull {
                    val t = it.text.replace(" ", "")
                    t.contains("开始游戏") || t.contains("立即进入")
                }
                if (startBtn != null && now - lastStartTapAt > START_TAP_COOLDOWN_MS) {
                    lastStartTapAt = now
                    Ln.i("$TAG: 云游戏启动页，点「${startBtn.text}」@(${startBtn.cx},${startBtn.cy})")
                    auto.tap(startBtn.cx, startBtn.cy)
                    Thread.sleep(2_000)
                    continue
                }

                // ---- ③ 都没认出来：每 10 秒打一次诊断 ----
                if (now - lastDiagLog > 10_000) {
                    lastDiagLog = now
                    Ln.i(
                        "$TAG: [云游戏诊断] 全屏 OCR ${texts.size} 条: " +
                            texts.take(12).joinToString(" | ") { it.text },
                    )
                }

                // 超时判断已上移到循环开头 —— 原来放在这里，会被
                // 上面两个 `continue` 绕过，导致"一直认得出按钮"时永不超时。
            }

            Thread.sleep(pollMs)
        }
    }

    /** 轮询直到能采到一帧。 */
    private fun waitForFrame(timeoutSec: Int): Boolean {
        repeat(timeoutSec) { i ->
            if (auto.takeScreenshot(retries = 1)) {
                Ln.i("$TAG: 第 ${i + 1}s 采到画面 ${auto.width}x${auto.height}")
                return true
            }
            SystemClock.sleep(1000)
        }
        return false
    }

    /**
     * 等「点击进入」出现并点它（**区域 OCR**）。
     *
     * 等 35 秒后在屏幕中下部跑 OCR 找「点击进入」，
     * 找不到就每 5 秒重试，总上限 110 秒。
     */
    private fun waitAndClickEnter(nav: ScreenNavigator): Boolean {
        val startAt = SystemClock.uptimeMillis()
        var retryCount = 0
        var kickedRelayout = false
        Thread.sleep(FIRST_CHECK_MS)

        while (SystemClock.uptimeMillis() - startAt < TOTAL_LIMIT_MS) {
            if (nav.isCancelled()) return false

            if (isMainUi(nav)) {
                Ln.i("$TAG: 已在主界面，跳过「点击进入」")
                return true
            }

            val hit = nav.withFrameOcrRegion(CROP_CLICK_ENTER) { mat, region ->
                OcrEngine.findText(mat, "点击进入", region)
            }
            if (hit != null) {
                Ln.i("$TAG: OCR 命中「${hit.text}」(${hit.cx},${hit.cy}) -> 点击")
                auto.tap(hit.cx, hit.cy)
                return true
            }

            // ## 删掉了两个无效的模板点击
            //
            // 原来这里会点 `zh_CN/base/confirm.png` 和 `restart.png` ——
            // 那是**从 PC 版抄来的**，手游 assets 里根本没有这两个文件，
            // 于是每轮都在日志里刷「未找到 xxx.png」，纯属噪音，
            // 还浪费两次模板匹配的时间。
            //
            // 手游启动时若真有弹窗，下面的全屏 OCR 诊断会把它暴露出来。

            val waited = (SystemClock.uptimeMillis() - startAt) / 1000

            // ## 卡住了就踢一次「主屏往返」
            //
            // ## 这是什么问题（已由诊断日志确认，2026-10-30）
            //
            // 不是"游戏没重新布局"那么简单，是**同一个缩放被叠加了两次**：
            //
            // 崩铁不支持自由改窗口尺寸，被放到 1920×1080/240dpi 的虚拟屏上时，
            // 系统让它进 size-compat 模式 —— 本该给它 1631×917 的逻辑画布，
            // 画完再放大 1.1774 倍（=1920/1631）铺满。但部分 ROM 在这一步
            // **只算了一半**：`mCompatFrame` 已经是 1631×917，layout 下发的
            // 却是全尺寸 1920×1080。
            //
            // 结果应用按 1920×1080 画满 buffer（实测 `geomBufferSize=[0 0 1920 1080]`），
            // SurfaceFlinger 又按 1.1774 倍贴上去：
            //
            // ```
            // 1080 × 1.1774 = 1272  → 右侧空出
            // 1920 × 1.1774 = 2261  → 屏幕只有 1080，只剩顶部可见
            // ```
            //
            // 于是画面以左上角为锚点放大、右下被丢弃。「点击进入」正常在
            // y=997，放大后变 1174 —— **正好出框**，所以 OCR 一条都读不到。
            // 触控不受影响：它走 window frame + scaleFactor=0.8493(=1/1.1774)
            // 的坐标映射，压根不经过合成。
            //
            // 对策：把 task 移到主屏停一下再移回来，触发一次真正的 display
            // change，逼 WindowManager 完整重算 —— 实测 `Requested` 会从
            // 1920×1080 变回 1631×917，与 1.1774 重新对齐，2.2 秒内就恢复。
            //
            // **只踢一次**：真失败时踢再多次也没用，不如早点判超时。
            if (!kickedRelayout && waited >= RELAYOUT_KICK_AFTER_SEC) {
                kickedRelayout = true
                Ln.w("$TAG: 已等 ${waited}s 仍未出现「点击进入」，疑似画面被放大裁切，强制重新布局一次")
                onNotice?.invoke("ScreenAnomalyDetected", """{"waited":$waited}""")
                val ok = GameLauncher.kickRelayout(pkg, displayId)
                Ln.w("$TAG: 强制重新布局${if (ok) "完成" else "失败"}，继续等「点击进入」")
                onNotice?.invoke(
                    if (ok) "ScreenAnomalyRepaired" else "ScreenAnomalyRepairFailed",
                    """{"waited":$waited}""",
                )
                Thread.sleep(2_000)
                continue
            }

            // ## 每 3 次重试打一次**全屏 OCR**
            //
            // 只报"还没出现"根本没法排查 —— 不知道画面到底是黑屏、
            // 加载中、还是卡在某个弹窗上。
            //
            // 每 15 秒（3 次 × 5 秒）把整屏认到的文字打出来，
            // 一看就知道卡在哪。
            if (retryCount % 3 == 0) {
                val all = nav.readAllText()
                Ln.i(
                    "$TAG: [诊断 ${waited}s] 全屏 OCR ${all.size} 条: " +
                        all.joinToString(" | ") { "${it.text}@${it.cx},${it.cy}" },
                )
            }

            Ln.i("$TAG: 还没出现「点击进入」（已等 ${waited}s），${RETRY_INTERVAL_MS / 1000}s 后重试")
            Thread.sleep(RETRY_INTERVAL_MS)
            retryCount++
        }

        Ln.e("$TAG: 启动超时（${TOTAL_LIMIT_MS / 1000}s）未出现「点击进入」")

        // ## 超时时把当前画面导出，供事后排查
        //
        // 只报"超时"什么也说明不了 —— 是黑屏？还停在 logo？
        // 卡在某个弹窗？画面被拉伸？
        //
        // 导出帧后能从日志包里直接看到那张图，一眼定位。
        // （和第 415 行那次全屏 OCR 互补：OCR 只有文字，图片有全部信息）
        runCatching {
            val s = nav.exportFrame()
            if (s != null) {
                Ln.e("$TAG: 已导出超时画面 -> $s")
            } else {
                Ln.w("$TAG: 导出超时画面失败（取帧为空）")
            }
        }.onFailure { Ln.w("$TAG: 导出超时画面异常: ${it.message}") }

        return false
    }

    /**
     * 处理开屏的「列车补给」月卡。
     *
     * 薄封装：真正的逻辑都在 [claimTrainSupplyIfAny]。
     *
     * ## ⚠️ 这里曾经有两个致命的时序错误
     *
     * **错误一：只探一次就放弃。**
     *
     * 旧实现在 `waitAndClickEnter` 返回后**立刻**探一次公告，探不到就永久放弃：
     *
     * ```
     * 08:47:51.275  点「点击进入」
     * 08:47:51.590  没有「列车补给」公告，直接走主界面验证   ← 只隔了 315ms
     * 08:48:10.875  已进入主界面（点击进入后 19s）
     * ```
     *
     * 点完「点击进入」到大厅加载出来要 **19 秒**，300 毫秒后去探
     * 画面还停在加载，当然什么都认不到。
     *
     * **错误二：把「按钮消失」当成「领完了」。**
     *
     * 真实链路是「加载 → 领取 → 奖励确认 → 大厅」。
     * 点完领取后「点击领取今日补给」就消失了，但那一刻停在奖励确认界面，
     * 离大厅还差一层。旧代码在这里直接返回成功，于是后续任务
     * 全被这层界面挡住 —— 玩家看到的就是"月卡没领成"。
     *
     * 现在改成 [waitForMain] 每轮都调用，并且只有**角色头像可见**才算完成。
     */
    private fun handleTrainSupplyNotice(nav: ScreenNavigator): Boolean = claimTrainSupplyIfAny(nav)

    /**
     * 认到「列车补给凭证」的领取界面就领掉，否则什么都不做。
     *
     * ## 判据（两个都要留）
     *
     * | 判据 | 位置 | 作用 |
     * |---|---|---|
     * | 「列车补给」标题 | 顶部 25% | 开屏公告 |
     * | 「…补给」领取文字 | 底部 22% | 按钮还在、标题已经滚走的中间态 |
     *
     * 两个都是**含「补给」**的词，所以不会和邮件页的「全部领取」撞车。
     *
     * ## 点击策略
     *
     * 每一轮都**重新 OCR 找位置**再点，而不是复用上一轮的坐标 ——
     * 每点一下界面都会变（弹星穹明细、页面下移），旧坐标必然点偏。
     * 认不到文字才退回 [NOTICE_CLICK_X] / [NOTICE_CLICK_Y]。
     *
     * @return true = 已经确认回到大厅（角色头像可见）
     */
    private fun claimTrainSupplyIfAny(nav: ScreenNavigator): Boolean {
        val titleHit = nav.withFrameOcrRegion(CROP_TOP_QUARTER) { mat, region ->
            OcrEngine.findText(mat, TEXT_TRAIN_SUPPLY, region)
        }
        val claimHit = findClaimText(nav)
        if (titleHit == null && claimHit == null) return false

        Ln.w(
            "$TAG: 检测到月卡领取界面（标题=${titleHit?.text ?: "未识别"}" +
                "，领取=${claimHit?.text ?: "未识别"}），开始领取",
        )

        // ---------- 阶段 1：点「点击领取今日补给」 ----------
        // 第一轮无论如何要点一次（认不到文字就用兜底坐标）；
        // 之后文字一消失就说明已经进入奖励确认界面，跳出。
        for (round in 0 until MONTH_CARD_MAX_ROUNDS) {
            val hit = findClaimText(nav)
            if (hit == null && round > 0) break

            val x = hit?.cx ?: NOTICE_CLICK_X
            val y = hit?.cy ?: NOTICE_CLICK_Y
            Ln.i(
                "$TAG: 月卡第 ${round + 1}/$MONTH_CARD_MAX_ROUNDS 次点击领取 @($x,$y)" +
                    if (hit != null) "（OCR 命中「${hit.text}」）" else "（坐标兜底）",
            )
            if (!auto.tap(x, y, durationMs = 100L)) {
                Ln.w("$TAG: 月卡点击注入失败 —— ${auto.lastInputFailure}")
            }
            Thread.sleep(MONTH_CARD_STEP_MS)

            if (isMainUi(nav)) {
                Ln.i("$TAG: 月卡领取完成，已回到大厅（共点 ${round + 1} 次）")
                return true
            }
        }

        // ⚠️ 点了这么多次，领取按钮居然还在 —— 说明还停在领取界面，
        // 点击全部落空了。这种状态下任何"关闭"动作都可能把当天的月卡弄丢，
        // 所以直接交回上层，让主循环下一轮重新识别、重新点。
        if (findClaimText(nav) != null) {
            Ln.w("$TAG: 点了 $MONTH_CARD_MAX_ROUNDS 次，领取按钮仍在 —— 点击可能全部落空，交回上层重试")
            return false
        }

        // ---------- 阶段 2：关掉奖励确认界面 ----------
        return dismissMonthCardReward(nav)
    }

    /**
     * 关掉月卡的**奖励确认界面**，直到回到大厅。
     *
     * ## 这一层为什么必须单独处理
     *
     * 真实时序是：
     *
     * ```
     * 加载界面 → 月卡领取界面 → 月卡奖励确认界面 → 大厅
     * ```
     *
     * 点完领取后「点击领取今日补给」就消失了，但画面并没有到大厅，
     * 而是停在奖励确认界面。旧代码把这行字消失当成"领完了"直接返回，
     * 于是 `waitForMain` 放行、后续任务开跑，可屏幕上还盖着这一层 ——
     * 角色头像根本不可见，后面的每个任务都会错乱。
     *
     * ## 关闭顺序（全部避开敏感区）
     *
     * 1. 认「确认 / 我知道了 / 点击空白处继续」→ 点**文字自己的坐标**（最准）
     * 2. 认不到 → 点 [SAFE_BLANK_X] / [SAFE_BLANK_Y] 的安全空白
     *
     * ## ⚠️ 这里**不点**右上角 ✕
     *
     * 奖励页的 ✕ 确实在右上角，但大厅的角色头像/菜单也在右上角。
     * 一旦判态出错（比如奖励页已经自己关了、画面回到大厅），
     * 这一点会直接打开菜单，把后面所有任务全带乱 ——
     * 收益远小于风险，所以干脆不碰。
     *
     * 同理也不点左上角：崩铁几乎所有子界面的返回键都在那儿。
     *
     * @return true = 已回到大厅
     */
    private fun dismissMonthCardReward(nav: ScreenNavigator): Boolean {
        repeat(MONTH_CARD_CONFIRM_TRIES) { i ->
            if (isMainUi(nav)) {
                Ln.i("$TAG: 奖励确认界面已关闭，回到大厅")
                return true
            }

            val confirmHit = nav.withFrameOcrRegion(CROP_REWARD_CONFIRM) { mat, region ->
                MONTH_CARD_CONFIRM_TEXTS.firstNotNullOfOrNull { t ->
                    OcrEngine.findText(mat, t, region)
                }
            }

            when {
                confirmHit != null -> {
                    Ln.i(
                        "$TAG: 第 ${i + 1}/$MONTH_CARD_CONFIRM_TRIES 次关奖励界面 —— " +
                            "点「${confirmHit.text}」@(${confirmHit.cx},${confirmHit.cy})",
                    )
                    auto.tap(confirmHit.cx, confirmHit.cy, durationMs = 100L)
                }

                else -> {
                    Ln.i(
                        "$TAG: 第 ${i + 1}/$MONTH_CARD_CONFIRM_TRIES 次关奖励界面 —— " +
                            "认不到确认词，点安全空白 @($SAFE_BLANK_X,$SAFE_BLANK_Y)",
                    )
                    auto.tap(SAFE_BLANK_X, SAFE_BLANK_Y, durationMs = 100L)
                }
            }
            Thread.sleep(MONTH_CARD_CONFIRM_STEP_MS)
        }

        val ok = isMainUi(nav)
        if (!ok) {
            Ln.w("$TAG: 奖励确认界面关了 $MONTH_CARD_CONFIRM_TRIES 次仍没回到大厅，交给上层继续等")
        }
        return ok
    }

    /** 在屏幕下沿找「…补给」领取文字。 */
    private fun findClaimText(nav: ScreenNavigator): OcrEngine.OcrResult? =
        nav.withFrameOcrRegion(CROP_BOTTOM_AREA) { mat, region ->
            MONTH_CARD_CLAIM_TEXTS.firstNotNullOfOrNull { t ->
                OcrEngine.findText(mat, t, region)
            }
        }

    /**
     * 等到**真正**进入主界面，期间顺手把月卡领掉。
     *
     * ## 真实时序
     *
     * ```
     * 点「点击进入」
     *   → 加载界面            约 19 秒
     *   → 月卡领取界面        「列车补给凭证」+「点击领取今日补给」
     *   → 月卡奖励确认界面    点完领取后弹出，星穹到账
     *   → 大厅                角色头像在这步才可识别
     * ```
     *
     * ## 为什么每轮都先探月卡、再判大厅
     *
     * 月卡这三层界面全程盖住角色头像，正常情况下 `isMainUi()`
     * 不可能提前返回。但万一某次弹窗比大厅晚一拍（弱网、
     * 广告位异步加载），头像先出现就会被放行 —— 之后再没人管它，
     * 当天的补给直接丢，而且**不会有任何报错**。
     *
     * 先探一次月卡只多一次区域 OCR，换的是不会漏。
     *
     * ## 头像出现后还要守一小段
     *
     * 见 [MONTH_CARD_GUARD_PROBES]。确认确实没有待领的弹窗就立刻放行，
     * 正常情况只多花 1~2 秒。
     */
    private fun waitForMain(nav: ScreenNavigator): Boolean {
        val startAt = SystemClock.uptimeMillis()
        var blindTried = false
        Thread.sleep(AFTER_ENTER_MS)

        while (SystemClock.uptimeMillis() - startAt < TOTAL_LIMIT_MS) {
            if (nav.isCancelled()) return false
            val waited = (SystemClock.uptimeMillis() - startAt) / 1000

            // ① 先探月卡 —— 它出现在大厅之前，且全程盖着头像
            if (claimTrainSupplyIfAny(nav)) {
                Ln.i("$TAG: 月卡已领取并回到大厅（点击进入后 ${waited}s）")
                return true
            }

            // ② 再判大厅；命中后先守一小段，防止弹窗晚到
            if (isMainUi(nav)) {
                if (confirmNoPendingMonthCard(nav)) {
                    Ln.i("$TAG: 已进入主界面（点击进入后 ${waited}s）")
                    return true
                }
                // 守门时发现画面又变了，让主循环重来一轮
                continue
            }

            // ③ 卡住兜底：太久没进大厅、也没认到月卡 → 留证据 + 安全试探
            if (!blindTried && waited >= MONTH_CARD_BLIND_AFTER_SEC) {
                blindTried = true
                if (blindMonthCardAttempt(nav)) {
                    Ln.i("$TAG: 兜底成功，已回到大厅（点击进入后 ${waited}s）")
                    return true
                }
                continue
            }

            Ln.i("$TAG: 还没进主界面（${waited}s），${RETRY_INTERVAL_MS / 1000}s 后重试")
            Thread.sleep(RETRY_INTERVAL_MS)
        }

        Ln.e("$TAG: 进主界面超时（${TOTAL_LIMIT_MS / 1000}s）")
        return false
    }

    /**
     * 卡住时的兜底。**只在有依据的地方点，绝不满屏乱点。**
     *
     * ## 分两种情形
     *
     * **情形一：全屏 OCR 能找到"补给/领取"字样。**
     * 说明文字其实认得到，只是固定区域没覆盖到。那就点**文字自己的中心**，
     * 属于有依据的点击，和正常领取流程一样安全。
     *
     * **情形二：一个字都找不到。**
     * 此时我们根本不知道按钮在哪，任何坐标都是猜。所以只点一次
     * [SAFE_BLANK_X] / [SAFE_BLANK_Y] —— 那是各界面都没有绑定功能的空白区，
     * 唯一作用是"如果有个遮罩，试着把它点掉"。
     * **不指望这一下能领到月卡**，领不到就承认领不到，等下次日志再修。
     *
     * ## ⚠️ 为什么不用底部 (960,1020) 去"盲点领取按钮"
     *
     * 那个坐标只在月卡页是领取按钮。可一旦判错界面，
     * 它在别的页面可能是主操作按钮、任务追踪、聊天入口 ——
     * 月卡一天只有一次机会，但把界点点乱是一整天都恢复不了。
     * 这笔账不划算。
     *
     * @return true = 已回到大厅
     */
    private fun blindMonthCardAttempt(nav: ScreenNavigator): Boolean {
        Ln.w("$TAG: ${MONTH_CARD_BLIND_AFTER_SEC}s 未进大厅且固定区域没认到月卡 —— 启动安全兜底")

        // ---- 情形一：全屏找"补给"文字，找到就点文字中心 ----
        val allText = runCatching { nav.readAllText() }.getOrDefault(emptyList())
        dumpScreenText(allText)

        val hint = allText.firstOrNull {
            val t = it.normalized()
            (t.contains("补给") || t.contains("列车")) && it.cy > auto.height * 0.5
        }

        if (hint != null) {
            Ln.i("$TAG: 全屏 OCR 找到「${hint.text}」@(${hint.cx},${hint.cy})，按文字位置点击")
            if (!auto.tap(hint.cx, hint.cy, durationMs = 100L)) {
                Ln.w("$TAG: 兜底点击注入失败 —— ${auto.lastInputFailure}")
                return false
            }
            Thread.sleep(MONTH_CARD_STEP_MS)
            if (isMainUi(nav)) return true
            return dismissMonthCardReward(nav)
        }

        // ---- 情形二：认不到任何线索，只点一次安全空白 ----
        Ln.w(
            "$TAG: 全屏也认不到「补给/列车」字样 —— " +
                "只点一次安全空白 ($SAFE_BLANK_X,$SAFE_BLANK_Y) 试探遮罩，不猜按钮位置",
        )
        auto.tap(SAFE_BLANK_X, SAFE_BLANK_Y, durationMs = 100L)
        Thread.sleep(MONTH_CARD_CONFIRM_STEP_MS)
        if (isMainUi(nav)) return true

        // 点空白之后如果画面变了（遮罩被点掉、露出奖励页），再把奖励页关掉
        return dismissMonthCardReward(nav)
    }

    /**
     * 把当前画面上的全部文字打进日志。
     *
     * 只在"卡住"时调用一次。之前排查月卡问题时，
     * 日志里只有一句"没有「列车补给」公告"，完全看不出画面上到底是什么，
     * 只能靠猜。有了这份 dump，下次直接对着文字调区域和关键词。
     */
    private fun dumpScreenText(texts: List<OcrEngine.OcrResult>) {
        if (texts.isEmpty()) {
            Ln.w("$TAG: 卡住现场 —— 全屏 OCR 一条文字都没认出来（画面应该是纯加载动画）")
            return
        }
        val detail = texts.joinToString(" | ") { "${it.normalized()}@(${it.cx},${it.cy})" }
        Ln.w("$TAG: 卡住现场 —— 全屏 OCR 共 ${texts.size} 条：$detail")
    }

    /**
     * 头像已经可见，再确认一下有没有晚到的月卡弹窗。
     *
     * 正常时序下这里什么都探不到，直接放行；
     * 真探到了就当场领掉，领完它自己会回大厅。
     *
     * @return true = 确认没有待领的月卡，可以放行
     */
    private fun confirmNoPendingMonthCard(nav: ScreenNavigator): Boolean {
        repeat(MONTH_CARD_GUARD_PROBES) { i ->
            // 弹出来了 → 就地领掉，领完就算完成
            if (claimTrainSupplyIfAny(nav)) return true
            // 画面变了（比如又退回加载）→ 交给主循环
            if (!isMainUi(nav)) return false
            if (i < MONTH_CARD_GUARD_PROBES - 1) {
                Thread.sleep(MONTH_CARD_GUARD_INTERVAL_MS)
            }
        }
        Ln.i("$TAG: 守门 $MONTH_CARD_GUARD_PROBES 次没有月卡弹窗，放行")
        return true
    }

    /**
     * 是不是在主界面 —— 只看右上角有没有**角色头像**。
     */
    private fun isMainUi(nav: ScreenNavigator): Boolean {
        val hit = nav.findInRegion("screen/main_avatar_icon.png", 0.85, CROP_MAIN_ANCHOR)
        if (hit != null) {
            Ln.i("$TAG: 主界面锚点命中（角色头像 ${"%.3f".format(hit.score)}）")
            return true
        }
        return false
    }

    // ==================== 通用自救 ====================

    /**
     * **确保在大厅** —— 所有任务的统一入口检查。
     *
     * ## 自救流程（**绝不使用返回键**）
     *
     * ```
     * 识别角色头像
     *   ├─ 成功 → 继续
     *   └─ 连续失败 2 次 → 点右上角 X 关弹窗 → 再识别
     *        └─ 最多 14 次
     *        └─ 还不行 → 冷启动游戏 → 重跑完整启动流程
     *             └─ 冷启动也失败 → 报错，中止整个任务链
     * ```
     *
     * ## ⚠️ 为什么不能用返回键
     *
     * 崩铁里的 `BACK` 事件会触发「**是否退出游戏**」对话框 ——
     * 那个弹窗一旦出来，后续所有识别都会失效，是最难处理的状态。
     *
     * 所以只点右上角的 X（各界面通用的关闭按钮）。
     */
    private fun ensureMainMenu(nav: ScreenNavigator): Boolean {
        repeat(2) { i ->
            if (isMainUi(nav)) return true
            if (i == 0) Thread.sleep(400)
        }

        // ## 不在大厅时，先看看是不是月卡弹窗挡着
        //
        // 下面的自救逻辑是"点右上角 X"。月卡领取界面的 X 一旦被点到，
        // 弹窗就关掉了 —— 当天那份补给也就没了，而且不会有任何提示。
        // 所以先认一次月卡界面，能领就领，领完它自己会回大厅。
        if (claimTrainSupplyIfAny(nav)) {
            Ln.i("$TAG: 不在大厅是因为月卡弹窗，已领完并回到大厅")
            return true
        }

        Ln.w("$TAG: 连续 2 次没识别到角色头像，开始自救（点 X 关弹窗）")
        repeat(MAX_SELF_HEAL_CLICKS) { i ->
            val x = findCloseButton(nav)
            if (x == null) {
                Ln.w("$TAG: 第 ${i + 1} 次自救 —— 没找到 X 按钮")
            } else {
                Ln.i("$TAG: 第 ${i + 1} 次自救 —— 点 X @(${x.first},${x.second})")
                auto.tap(x.first, x.second)
            }
            Thread.sleep(SELF_HEAL_INTERVAL_MS)
            if (isMainUi(nav)) {
                Ln.i("$TAG: 自救成功（点了 ${i + 1} 次 X），继续当前任务")
                return true
            }
        }

        Ln.e("$TAG: 自救失败（${MAX_SELF_HEAL_CLICKS} 次 X 都没恢复），尝试冷启动游戏")
        return coldRestart(nav)
    }

    /** 找右上角的 X 关闭按钮。 */
    private fun findCloseButton(nav: ScreenNavigator): Pair<Int, Int>? {
        val hit = nav.findInRegion("screen/close_x.png", 0.80, CROP_CLOSE_BUTTON) ?: return null
        val sf = ImageUtils.BASE_HEIGHT.toDouble() / auto.height
        val cx = ((hit.x + hit.templateWidth * hit.scale / 2) / sf).toInt()
        val cy = ((hit.y + hit.templateHeight * hit.scale / 2) / sf).toInt()
        return cx to cy
    }

    /**
     * **精简自救** —— 点 X 直到回到大厅，最多 2 次。
     *
     * 与 [ensureMainMenu] 的区别：那个是"任务开始前"的通用自救，
     * 失败会走冷启动。这个是"启动任务里跳过前的确认"，
     * 失败由调用方决定（杀进程重启）。
     *
     * @return 是否成功回到大厅
     */
    private fun healToMainMenu(nav: ScreenNavigator): Boolean {
        repeat(MAX_SKIP_HEAL_TRIES) { i ->
            val cb = findCloseButton(nav)
            if (cb == null) {
                Ln.w("$TAG: 第 ${i + 1} 次自救 —— 找不到 X 号")
            } else {
                Ln.i("$TAG: 第 ${i + 1} 次自救 —— 点 X @(${cb.first},${cb.second})")
                auto.tap(cb.first, cb.second, durationMs = 100L)
            }
            Thread.sleep(SELF_HEAL_INTERVAL_MS)

            if (auto.takeScreenshot(retries = 1) && isMainUi(nav)) {
                Ln.i("$TAG: 自救成功（点了 ${i + 1} 次）")
                return true
            }
        }
        Ln.w("$TAG: 自救 $MAX_SKIP_HEAL_TRIES 次仍未回到大厅")
        return false
    }

    /** 冷启动游戏 —— 自救的最后手段。 */
    private fun coldRestart(nav: ScreenNavigator): Boolean {
        Ln.w("$TAG: —— 冷启动游戏 ——")
        // 确认版：必须确保进程真的退了，否则荣耀类 ROM 会用热进程顶上来
        GameLauncher.forceStopAndConfirm(pkg)
        Thread.sleep(800)

        if (!GameLauncher.launch(pkg, displayId)) {
            Ln.e("$TAG: 冷启动失败：无法拉起游戏")
            return false
        }
        if (!GameLauncher.ensureOnDisplay(pkg, displayId, timeoutMs = 30_000L)) {
            Ln.e("$TAG: 冷启动失败：游戏没落到虚拟屏")
            return false
        }

        if (!waitAndClickEnter(nav)) {
            Ln.e("$TAG: 冷启动后仍没等到「点击进入」")
            return false
        }
        if (!waitForMain(nav)) {
            Ln.e("$TAG: 冷启动后仍没进到主界面")
            return false
        }
        Ln.i("$TAG: 冷启动成功，已回到大厅")
        return true
    }

    // ============ B. 领邮件 ============

    /**
     * 领邮件。
     *
     * 对应 M7A 的 `tasks/reward/mail.py`。
     *
     * 前置：手游版没有 ESC，改走
     * 「点手机图标 → 点信封图标」。
     */
    private fun mail(): TaskResult {
        val nav = navigator
            ?: return TaskResult("Mail", false, "导航器未就绪")

        // 起点统一：先确保在大厅（识别角色头像，失败则自救）
        if (!ensureMainMenu(nav)) {
            return TaskResult("Mail", false, "无法回到大厅（自救 + 冷启动均失败），任务链中止")
        }

        // 1. 导航到邮件界面
        //    路径：大厅 → 点手机图标 → 点信封 → 邮件页
        if (!nav.gotoMail()) {
            return TaskResult("Mail", false, "导航到邮件界面失败")
        }

        // 2. 点「全部领取」
        //    ⚠️ 用坐标而不是模板：该按钮是半透明次级样式，
        //    背景随邮件内容变化，模板匹配天然不稳
        //    （M7A 的 receive_all.png 在手游上只有 0.47）。
        //    详见 ScreenNavigator.Hotspot.MAIL_RECEIVE_ALL。
        //
        //    没有可领邮件时点它不会有任何反应，属正常情况。
        Ln.i("$TAG: 点「全部领取」")
        nav.clickCrop(ScreenNavigator.Hotspot.MAIL_RECEIVE_ALL, "全部领取")

        // 3. 等奖励结算动画
        SystemClock.sleep(2500)

        // 4. 关闭邮件界面回大厅（BACK 键为主，模板兜底）
        val closed = nav.closeCurrent()
        return if (closed) {
            TaskResult("Mail", true, "已点「全部领取」，并回到大厅")
        } else {
            TaskResult("Mail", true, "已点「全部领取」（未能确认回到大厅）")
        }
    }

    // ============ 清体力 ============
    /**
     * 清体力（开拓力）。
     *
     * 全流程走 [PowerTask]：选副本类型 → 选副本并传送 → 设连打次数
     * → 开打 → 等结算 → 连续挑战 → 退出。
     *
     * 其中"按名字选东西"全部交给 OCR，"位置固定"的按钮继续用模板匹配。
     * 详见 [PowerTask] 的类注释（尤其是安全阀那一节）。
     */
    // ============ 每日实训 ============

    /**
     * 每日实训：识别任务状态 + 领取活跃度奖励（+ 可选刷副本补任务）。
     *
     * 流程：
     *
     * ```
     * ① 进每日实训页
     * ② 把金色「领取」按钮全点一遍
     * ③ 如果开启了「自动消耗体力」→ 去刷副本补「累计消耗120点开拓力」
     * ④ 回每日实训页再领一遍
     * ⑤ 输出活跃度
     * ```
     *
     * 第 ③ 步是这次新增的：用户明确要求"消耗 120 体力就算完成，
     * 周本排除"。参数里的 instance 决定刷哪个本，次数自动算。
     */
    private fun daily(params: String): TaskResult {
        val nav = navigator
            ?: return TaskResult("Daily", false, "导航器未就绪")

        // 起点统一：先确保在大厅
        if (!ensureMainMenu(nav)) {
            return TaskResult("Daily", false, "无法回到大厅（自救 + 冷启动均失败），任务链中止")
        }

        val parsed = parseDailyParams(params)
        Ln.i(
            "$TAG: 每日实训参数 instance=${parsed.instance} " +
                "委托=${parsed.doDispatch} 拍照=${parsed.doPhoto} 合成=${parsed.doSynthesize} 秘技=${parsed.doTechnique} 体力=${parsed.doPower} 领奖=${parsed.doClaim}",
        )

        val task = DailyTask(auto, nav, parsed)
        return try {
            // 第一步：领奖 + （可选）刷副本
            val r = task.run()
            TaskResult("Daily", r.ok, r.detail)
        } catch (e: ScreenNavigator.StopRequested) {
            TaskResult("Daily", false, "任务已被用户停止")
        } catch (e: Exception) {
            Ln.e("$TAG: 每日实训异常: ${e.message}")
            TaskResult("Daily", false, "异常: ${e.message}")
        }
    }

    /**
     * 解析每日实训的参数 JSON。
     *
     * 字段少且固定，手工解析即可（和 [parsePowerParams] 同样的理由）。
     */
    private fun parseDailyParams(json: String): DailyTaskParams {
        fun str(key: String, def: String): String =
            Regex("\"$key\"\\s*:\\s*\"([^\"]*)\"")
                .find(json)?.groupValues?.get(1)
                ?.takeIf { it.isNotBlank() } ?: def

        fun bool(key: String, def: Boolean): Boolean =
            Regex("\"$key\"\\s*:\\s*(true|false)")
                .find(json)?.groupValues?.get(1)?.toBoolean() ?: def

        fun int(key: String, def: Int): Int =
            Regex("\"$key\"\\s*:\\s*(\\d+)")
                .find(json)?.groupValues?.get(1)?.toIntOrNull() ?: def

        return DailyTaskParams(
            doDispatch = bool("doDispatch", true),
            doPhoto = bool("doPhoto", true),
            doSynthesize = bool("doSynthesize", true),
            doTechnique = bool("doTechnique", true),
            doPower = bool("doPower", false),
            doClaim = bool("doClaim", true),
            instance = str("instance", com.march7th.hkrpg.data.model.PowerInstance.CALYX_CRIMSON.wireName),
            targetPower = int("targetPower", 120),
        )
    }


    private fun power(params: String): TaskResult {
        val nav = navigator
            ?: return TaskResult("Power", false, "导航器未就绪")

        // 起点统一：先确保在大厅（识别角色头像，失败则自救）
        if (!ensureMainMenu(nav)) {
            return TaskResult("Power", false, "无法回到大厅（自救 + 冷启动均失败），任务链中止")
        }

        val parsed = parsePowerParams(params)
        Ln.i(
            "$TAG: 清体力参数 instance=${parsed.instance} " +
                "instanceName=${parsed.instanceName} maxRuns=${parsed.maxRuns} " +
                "allowRefill=${parsed.allowRefill}",
        )

        val task = PowerTask(auto, nav, parsed, isCancelled)
        return try {
            val r = task.run()
            TaskResult("Power", r.ok, r.detail)
        } catch (e: PowerTask.Stopped) {
            // 用户主动停止：不当作失败，如实说明"已停止"
            Ln.i("$TAG: 清体力被用户停止")
            TaskResult("Power", false, "已被用户停止${e.partial}")
        }
    }

    /**
     * 解析 `PowerTaskConfig` 序列化出来的 JSON。
     *
     * 手工解析而不用 kotlinx.serialization：这个 JSON 是外壳拼的，
     * 字段极少且固定；引一个反序列化器进来反而多一层依赖。
     * 解析失败时回落到默认值（金花 / 用完体力 / 不用燃料）。
     */
    private fun parsePowerParams(json: String): PowerTask.PowerParams {
        val instance = Regex("\"instance\"\\s*:\\s*\"([^\"]*)\"")
            .find(json)?.groupValues?.get(1)
            ?.takeIf { it.isNotBlank() }
            ?: com.march7th.hkrpg.data.model.PowerInstance.DEFAULT.wireName
        val instanceName = Regex("\"instanceName\"\\s*:\\s*\"([^\"]*)\"")
            .find(json)?.groupValues?.get(1)
            ?.takeIf { it.isNotBlank() }
            ?: ""
        val maxRuns = Regex("\"maxRuns\"\\s*:\\s*(-?\\d+)")
            .find(json)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        // allowRefill 是当前字段名；旧配置里可能还是 useFuel，两个都读
        val allowRefill = Regex("\"allowRefill\"\\s*:\\s*(true|false)")
            .find(json)?.groupValues?.get(1)?.toBooleanStrictOrNull()
            ?: Regex("\"useFuel\"\\s*:\\s*(true|false)")
                .find(json)?.groupValues?.get(1)?.toBooleanStrictOrNull()
            ?: false
        return PowerTask.PowerParams(
            instance = instance,
            maxRuns = maxRuns,
            allowRefill = allowRefill,
            instanceName = instanceName,
        )
    }

    // ============ A. 关闭游戏 ============
    private fun closeDown(): TaskResult {
        GameLauncher.forceStop(pkg)
        SystemClock.sleep(1500)
        val stillRunning = GameLauncher.isRunning(pkg)
        return if (stillRunning) {
            TaskResult("CloseDown", false, "进程仍在")
        } else {
            TaskResult("CloseDown", true, "已关闭")
        }
    }

    // ============ 调试：导出画面 ============
    /**
     * 把虚拟屏当前帧导出到 `{coreRoot}/debug/screenshots/dump_<ts>.png`。
     *
     * 移植期采模板用：虚拟屏的画面抓不到（`screencap -d` 不支持 VD），
     * 而模板必须按**采集分辨率**裁，所以只能从引擎内部导出。
     */
    private fun dump(): TaskResult {
        val nav = navigator ?: return TaskResult("Dump", false, "导航器未就绪")
        val root = java.io.File(dumpRoot)
        val label = "dump_" + java.text.SimpleDateFormat("HHmmss", java.util.Locale.US)
            .format(java.util.Date())
        val path = nav.dumpFrame(root, label)
            ?: return TaskResult("Dump", false, "导出失败")
        return TaskResult("Dump", true, path)
    }
}
