package com.march7th.hkrpg.starrail

import com.march7th.hkrpg.data.model.CloudGameTaskConfig
import android.os.SystemClock
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

        // ---- 区域常量（全部只搜一小块，避免全屏开销）----

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
            // ---- A: 启动游戏 ----
            "StartUp" -> startUp()
            // ---- A': 启动云游戏 ----
            //
            // 云游戏客户端（com.miHoYo.cloudgames.hkrpg）的启动流程和本地
            // 客户端完全不同，所以单独一条分支，不复用 startUp()。
            "CloudGameStartUp" -> cloudGameStartUp(params)
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

    // ============ A'. 启动云游戏 ============
    /**
     * 启动**云游戏客户端**（`com.miHoYo.cloudgames.hkrpg`）并推进到可操作状态。
     *
     * # 为什么单独实现，不复用 [startUp]
     *
     * 本地客户端和云游戏的启动是**两条完全不同的链路**：
     *
     * | 环节 | 本地客户端 | 云游戏 |
     * |---|---|---|
     * | 启动 | `am start` 直接拉起游戏 | 拉起**云游戏启动器** |
     * | 中间态 | 热更 → 标题页 | **分配云端实例 → 建流 → 串流加载** |
     * | 进入 | 点「点击进入」 | 待定 |
     * | 超时 | 180 秒够 | 要 300 秒以上（排队 + 建流） |
     *
     * 所以这里不复用 `startUp()` 的任何逻辑，独立实现。
     *
     * # ⚠️ 当前状态：**框架已就绪，启动步骤待补充**
     *
     * 下面的 `TODO` 标记处是实际要填的流程。把每一步的：
     * - 界面特征（用什么文字/模板判断到了哪一步）
     * - 该点什么（坐标 or 识别）
     * - 每步大概等多久
     *
     * 告诉我，我按实测顺序填进去。
     *
     * @param params JSON：`{"packageName":"...","startupTimeoutSec":300}`
     */
    private fun cloudGameStartUp(params: String): TaskResult {
        val nav = navigator
            ?: return TaskResult("CloudGameStartUp", false, "导航器未就绪")
        val auto = this.auto
            ?: return TaskResult("CloudGameStartUp", false, "自动化通道未就绪")

        fun str(key: String, def: String): String =
            Regex("\"$key\"\\s*:\\s*\"([^\"]*)\"")
                .find(params)?.groupValues?.get(1)?.takeIf { it.isNotBlank() } ?: def

        fun int(key: String, def: Int): Int =
            Regex("\"$key\"\\s*:\\s*\"?(\\d+)\"?")
                .find(params)?.groupValues?.get(1)?.toIntOrNull() ?: def

        val pkg = str("packageName", CloudGameTaskConfig.DEFAULT_PACKAGE)
        val timeoutSec = int("startupTimeoutSec", 300)

        Ln.i("$TAG: 启动云游戏 pkg=$pkg timeout=${timeoutSec}s")

        // ① 前置检查：云游戏客户端装了吗
        if (!GameLauncher.isInstalled(pkg)) {
            return TaskResult(
                "CloudGameStartUp", false,
                "未安装云游戏客户端（$pkg）。请先安装《崩坏：星穹铁道》云游戏版",
            )
        }

        // ② 拉起云游戏到虚拟屏
        //
        // 和本地客户端一样，要先 forceStop 再 startOnDisplay ——
        // 否则可能复用主屏上的实例。
        val onDisplay = GameLauncher.currentDisplayOf(pkg)
        if (onDisplay != displayId) {
            if (onDisplay >= 0) {
                Ln.w("$TAG: 云游戏在 display=$onDisplay（不是虚拟屏），先强杀重启")
                GameLauncher.forceStop(pkg)
                Thread.sleep(2500)
            }
            if (!GameLauncher.startOnDisplay(pkg, displayId)) {
                return TaskResult("CloudGameStartUp", false, "云游戏启动指令被拒绝")
            }
            if (!GameLauncher.ensureOnDisplay(pkg, displayId)) {
                return TaskResult(
                    "CloudGameStartUp", false,
                    "云游戏未落到虚拟屏（display=${GameLauncher.currentDisplayOf(pkg)}）",
                )
            }
        }

        // ③ 等第一帧
        if (!waitForFrame(timeoutSec = 60)) {
            return TaskResult("CloudGameStartUp", false, "启动后未采到画面")
        }

        // ==================== TODO: 云游戏专属启动步骤 ====================
        //
        // 从这里开始是云游戏特有的流程，本地客户端那套「等点击进入」
        // 不适用。需要实测确认后按顺序填：
        //
        //   步骤1. 云游戏启动器界面 —— 特征？点哪？
        //   步骤2. 实例分配 / 排队 —— 特征？最长等多久？
        //   步骤3. 串流建立 —— 怎么判断建流完成？
        //   步骤4. 游戏内加载 —— 是否和本地一样有「点击进入」？
        //   步骤5. 确认进大厅 —— 用什么做锚点？
        //
        // 现阶段先做**兜底判断**：等待一段固定时间，然后看是否已经
        // 出现可操作画面（大厅锚点）。这能跑通就说明链路没问题，
        // 跑不通再按上面五步细化。
        // =================================================================
        Ln.i("$TAG: 云游戏：等待启动完成（最长 ${timeoutSec}s）")

        val deadline = SystemClock.uptimeMillis() + timeoutSec * 1000L
        var lastReport = 0L
        var entered = false

        while (SystemClock.uptimeMillis() < deadline) {
            if (isCancelled()) {
                return TaskResult("CloudGameStartUp", false, "任务已被用户停止")
            }

            // 用大厅锚点判断是否已经进到可操作状态
            if (nav.isAt(ScreenNavigator.Screen.MAIN)) {
                entered = true
                break
            }

            // 每 10 秒报一次进度，方便看日志定位卡在哪
            val now = SystemClock.uptimeMillis()
            if (now - lastReport > 10_000) {
                lastReport = now
                val waited = (now - (deadline - timeoutSec * 1000L)) / 1000
                Ln.i("$TAG: 云游戏启动中…（已等 ${waited}s）")
                // 顺便打一次全屏 OCR，出问题时能看出卡在哪个界面
                runCatching {
                    val all = nav.readAllText()
                    if (all.isNotEmpty()) {
                        Ln.i(
                            "$TAG: [云游戏诊断 ${waited}s] OCR ${all.size} 条: " +
                                all.take(12).joinToString(" | ") { it.text },
                        )
                    }
                }
            }

            Thread.sleep(2_000)
        }

        if (!entered) {
            // 超时了 —— 导出画面，方便分析卡在哪一步
            runCatching {
                val s = nav.exportFrame("cloudgame_timeout")
                Ln.e("$TAG: 云游戏启动超时，已导出画面 -> $s")
            }
            return TaskResult(
                "CloudGameStartUp", false,
                "云游戏启动超时（${timeoutSec}s）—— 启动步骤尚未实现，等待补充实测流程",
            )
        }

        Ln.i("$TAG: 云游戏：已进入大厅")
        return TaskResult(
            "CloudGameStartUp", true,
            "云游戏启动成功（${auto.width}x${auto.height}）",
        )
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
    private fun startUp(): TaskResult {
        if (displayId < 0) {
            return TaskResult("StartUp", false, "虚拟屏未就绪（displayId=$displayId）")
        }

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
        if (!GameLauncher.isInstalled(pkg)) {
            val hint = when (pkg) {
                GameLauncher.PKG_CN ->
                    "未检测到《崩坏：星穹铁道》国服（$pkg）。请先安装游戏，" +
                        "或在设置里切换到正确的客户端版本。"
                else ->
                    "未检测到《崩坏：星穹铁道》（$pkg）。请先安装游戏，" +
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
        val running = GameLauncher.isRunning(pkg)
        val onDisplay = if (running) GameLauncher.currentDisplayOf(pkg) else -1

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
            GameLauncher.forceStop(pkg)
            Thread.sleep(2500)
            // 落到下面的正常启动流程
        }

        // ---- 不在虚拟屏上：先杀掉再重新启动 ----
        if (running) {
            Ln.w("$TAG: 游戏在 display=$onDisplay（不是虚拟屏），先强杀进程再重启")
            GameLauncher.forceStop(pkg)
            // 给系统一点时间真正回收进程
            Thread.sleep(2500)
        } else {
            Ln.i("$TAG: 游戏进程不在，直接冷启动")
        }

        val accepted = GameLauncher.startOnDisplay(pkg, displayId)
        if (!accepted) {
            return TaskResult("StartUp", false, "启动指令被拒绝")
        }

        // 等待落到虚拟屏（含二段跳漂移拉回）
        if (!GameLauncher.ensureOnDisplay(pkg, displayId)) {
            return TaskResult("StartUp", false, "游戏未落到虚拟屏（display=${GameLauncher.currentDisplayOf(pkg)}）")
        }

        // 等第一帧渲染出来
        if (!waitForFrame(timeoutSec = 40)) {
            return TaskResult("StartUp", false, "启动后未采到画面")
        }

        val nav = navigator
            ?: return TaskResult("StartUp", false, "导航器未就绪")

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

        // ---- 「列车补给」公告 ----
        //
        // 点了「点击进入」之后，游戏有时会弹一个「列车补给」的开屏公告，
        // 挡住主界面 —— 此时角色头像根本不在，[waitForMain] 会一直等到超时。
        //
        // 实测这个公告：
        // - 标题文字在**顶部 25%** 区域；
        // - 点屏幕靠下的位置能翻页 / 关闭；
        // - 需要**点两次**（中间隔 3 秒）才能完全过掉。
        //
        // 所以这里先探一次：认到「列车补给」就点两下，然后继续走主界面验证。
        // 没认到就什么都不做（绝大多数正常启动没有这个公告）。
        // 处理完公告后如果已经确认在大厅，就跳过 waitForMain 的 9 秒等待
        if (handleTrainSupplyNotice(nav)) {
            return TaskResult("StartUp", true, "已进入主界面（已过列车补给公告）${auto.width}x${auto.height}")
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
     * 处理开屏的「列车补给」公告。
     *
     * ## 完整时序
     *
     * ```
     * 点「点击进入」→ 等 9 秒（见 waitForMain 的开头）
     *      ↓
     * 识别「列车补给」（顶部 25%）
     *      ├─ 没识别到 → 直接走角色头像验证
     *      └─ 识别到 → 【第 1 轮】
     *             点「点击领取今日补给」(960, 1020)
     *                  ↓ 等 4 秒（奖励动画 + 星穹领取明细弹出）
     *             再点一次同样的位置 (960, 1020)      ← 关掉明细
     *                  ↓ 等 4 秒
     *             识别角色头像
     *                  ├─ 成功 → 完成
     *                  └─ 失败 → 【第 2 轮】重复上面三件事
     *                            └─ 仍失败 → 交给 waitForMain 继续等
     * ```
     *
     * ## 为什么点两次同一个位置
     *
     * 第一次点「点击领取今日补给」是**领取**；
     * 点完会弹「星穹领取明细」窗口，**它盖在画面中央偏下**，
     * 正好挡住领取文字那一带 —— 再点同一位置就是点在明细窗口上，
     * 相当于"点击空白关闭"。
     *
     * ## ⚠️ 不要改成点屏幕中心
     *
     * 明细窗口就在屏幕中心区域，点中心可能触发窗口里的按钮。
     *
     * @return true 表示已确认在大厅
     */
    private fun handleTrainSupplyNotice(nav: ScreenNavigator): Boolean {
        val hit = nav.withFrameOcrRegion(CROP_TOP_QUARTER) { mat, region ->
            OcrEngine.findText(mat, TEXT_TRAIN_SUPPLY, region)
        }

        if (hit == null) {
            Ln.i("$TAG: 没有「$TEXT_TRAIN_SUPPLY」公告，直接走主界面验证")
            return false
        }

        Ln.w("$TAG: 检测到「${hit.text}」公告，开始处理")

        repeat(NOTICE_MAX_ROUNDS) { round ->
            // ① 点「点击领取今日补给」
            Ln.i("$TAG: 第 ${round + 1} 轮 —— 点「点击领取今日补给」@($NOTICE_CLICK_X,$NOTICE_CLICK_Y)")
            auto.tap(NOTICE_CLICK_X, NOTICE_CLICK_Y, durationMs = 100L)
            Thread.sleep(NOTICE_STEP_MS)

            // ② 再点一次同一位置（关掉星穹领取明细）
            Ln.i("$TAG: 第 ${round + 1} 轮 —— 再点一次同一位置关明细")
            auto.tap(NOTICE_CLICK_X, NOTICE_CLICK_Y, durationMs = 100L)
            Thread.sleep(NOTICE_STEP_MS)

            // ③ 识别角色头像
            if (isMainUi(nav)) {
                Ln.i("$TAG: 第 ${round + 1} 轮后已回到大厅")
                return true
            }
            Ln.w("$TAG: 第 ${round + 1} 轮后仍未回到大厅")
        }

        Ln.w("$TAG: $NOTICE_MAX_ROUNDS 轮处理完毕仍未回到大厅，交给 waitForMain 继续等")
        return false
    }

    /**
     * 确认已经进入主界面（只比**角色头像**）。
     *
     * 点完「点击进入」后先等 9 秒（加载存档），
     * 然后每 5 秒查一次，直到累计 110 秒。
     */
    private fun waitForMain(nav: ScreenNavigator): Boolean {
        val startAt = SystemClock.uptimeMillis()
        Thread.sleep(AFTER_ENTER_MS)

        while (SystemClock.uptimeMillis() - startAt < TOTAL_LIMIT_MS) {
            if (nav.isCancelled()) return false
            if (isMainUi(nav)) {
                val waited = (SystemClock.uptimeMillis() - startAt) / 1000
                Ln.i("$TAG: 已进入主界面（点击进入后 ${waited}s）")
                return true
            }
            val waited = (SystemClock.uptimeMillis() - startAt) / 1000
            Ln.i("$TAG: 还没进主界面（${waited}s），${RETRY_INTERVAL_MS / 1000}s 后重试")
            Thread.sleep(RETRY_INTERVAL_MS)
        }

        Ln.e("$TAG: 进主界面超时（${TOTAL_LIMIT_MS / 1000}s）")
        return false
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
        GameLauncher.forceStop(pkg)
        Thread.sleep(3000)

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
