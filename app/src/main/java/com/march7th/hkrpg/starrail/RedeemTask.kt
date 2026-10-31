package com.march7th.hkrpg.starrail

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.SystemClock
import com.march7th.hkrpg.starrail.engine.OcrEngine
import com.march7th.hkrpg.starrail.engine.ScreenNavigator
import com.march7th.hkrpg.starrail.engine.ShellAutomation
import com.march7th.hkrpg.third.FakeContext
import com.march7th.hkrpg.third.Ln

/**
 * 兑换码一键兑换。
 *
 * # 流程（按实机截图对齐）
 *
 * ```
 * ① 角色头像确认在大厅（和其他任务同一步）
 * ② 点左上角手机图标 → 手机菜单
 * ③ 名片右上角「…」→ 展开面板 →「兑换码」
 * ④ 弹窗打开：输入框占位「请输入兑换码…」，右侧图标是「粘贴」
 * ⑤ 逐码：写剪贴板 → 点右侧图标（有旧码时那里是 ✕，先点掉清空）
 *        → 再点「粘贴」→（会话首次会弹「提示」就点「粘贴」）
 *        → 点「确认」→ 读结果
 * ⑥ 退出回大厅
 * ```
 *
 * # ⚠️ 不要先去点输入框
 *
 * 实测：这个输入框**不需要先聚焦**，点右侧的粘贴图标就会把剪贴板内容填进去。
 * 早期实现每次先点输入框（想拿焦点再清空），反而会打乱游戏自己的状态
 * —— 聚焦这一下就可能提前触发游戏的粘贴询问，等再点粘贴图标时弹窗已经在
 * 屏幕上、点击落到别处，表现就是"码没进去"。所以整条链路**不碰输入框**。
 *
 * # ⚠️ 每次粘贴"新内容"都会问一次
 *
 * 「提示：请求从剪切板粘贴」的触发条件是**剪贴板内容和上一次粘贴的不一样**，
 * 跟对话框开了几次无关。我们每个码都会重写一次剪贴板（内容必然不同），
 * 所以**每一个码**点粘贴图标后都会弹这个框，必须点掉「粘贴」才算填上。
 * 千万别按"一个对话框只问一次"去做优化 —— 那会把后面所有码都卡在弹窗上。
 *
 * # ⚠️ 兑换成功后弹窗会**自己关掉**
 *
 * 实测：成功后点那个「确认」，不是回到输入框，而是**直接退回手机菜单**
 * （名片页）。所以每兑成功一个码，下一个码都得**重新走一遍入口**
 * （「…」→ 兑换码）。失败时相反：弹窗留着、码也留在输入框里，
 * 下一个码只要点一下 ✕ 清掉、再点粘贴就行。
 *
 * 这一条如果漏了，表现就是"第一个码成功，后面全部失败" ——
 * 因为后面几次都在对着一张不再存在的弹窗发 `MOVE_END` + 退格。
 *
 * # 为什么走剪贴板而不是 `input text`
 *
 * 兑换码里会出现中文（实测有「永远不会抛弃你」这种），而 `input text`
 * 只能注入 ASCII，中文一进去就是乱码或直接失败。剪贴板是系统级共享的，
 * 特权进程用 [FakeContext] 以 shell 身份写入，游戏侧点一下粘贴就能读到，
 * 中英文都走同一条路。
 *
 * 只有在剪贴板写入失败、且码是纯 ASCII 时，才回退到 `input text`。
 *
 * # 输入框右侧那个图标是「✕ / 粘贴」二态
 *
 * 实测：兑换失败（无效、已用过）后**码不会消失，继续留在输入框里**。
 * 而输入框右侧那个按钮会随内容变：
 * - 输入框**有内容** → 图标是 **✕**，点一下清空，图标随即变回「粘贴」；
 * - 输入框**为空** → 图标是「粘贴」，点一下把剪贴板内容填进去。
 *
 * 所以两个码之间只要在**同一个位置点两下**：第一下是 ✕（清掉上一个），
 * 第二下是「粘贴」（填新的）。不需要点输入框、不需要按退格 ——
 * 用 [inputHasText] 记着输入框里有没有码，有就先点一下清空。
 *
 * # 结果判定（实测）
 *
 * - **成功**：弹出一个独立对话框「兑换提示 / 兑换成功 / 请前往邮箱领取奖励」，
 *   底下只有一个「确认」，点它关掉。
 * - **失败**：不发弹窗，只在**输入框下方**压一条红横幅（「无效的兑换码」
 *   「兑换码已被使用」），弹窗不关、可以直接改内容重试。
 *
 * 这是最关键的一处区别：失败时底部那个「确认」如果再点一次，等于把同一个
 * 码又提交一遍。所以**只有成功才去点确认**。
 *
 * 判定顺序也必须是"先成功后失败"——成功文案里没有失败词，但失败时输入框
 * 里的码、标题「兑换码」都可能被 OCR 读进来，先判失败容易误伤。
 */
class RedeemTask(
    private val auto: ShellAutomation,
    private val nav: ScreenNavigator,
    /** 本次要兑换的码（已按站点清单过滤过期） */
    private val codes: List<String>,
    /** 本地记录里已经兑换成功的码，直接跳过 */
    private val alreadyUsed: Set<String> = emptySet(),
    /**
     * 已兑换记录文件（一行一个码）。
     *
     * 放在内核的数据目录（`/data/local/tmp/maameow/redeem_used.txt`）：
     * 内核跑在特权进程，读不到 App 的 DataStore；而这块目录主进程读不了，
     * 所以这份记录只有内核自己在用 —— 够用，它的唯一职责就是"别再输第二遍"。
     *
     * 传 `null` 表示不留记录（调试用）。
     */
    private val usedFile: java.io.File? = null,
) {

    companion object {
        private const val TAG = "RedeemTask"

        /** 点「确认」后等结果出现（成功是弹窗、失败是红条，都不会太慢）。 */
        private const val RESULT_WAIT_MS = 1_500L

        /** 点粘贴图标 → 输入框被填上（游戏自己弹确认框，等它出现）。 */
        private const val AFTER_PASTE_MS = 600L

        /** 等游戏「请求从剪切板粘贴」提示出现的最长时间。 */
        private const val PASTE_PROMPT_TIMEOUT_MS = 2_400L

        /** 轮询这个提示的间隔 —— 弹窗有淡入动画，越早读到越省时间。 */
        private const val PASTE_PROMPT_POLL_MS = 250L

        /** 关掉成功弹窗后等界面回到输入状态。 */
        private const val AFTER_CLOSE_MS = 600L

        /** 清空输入框最多按几次退格。中文码按字符删，32 位留足余量。 */
        private const val MAX_BACKSPACE = 40

        /** 点「✕」清空后等输入框变空、图标变回「粘贴」。 */
        private const val CLEAR_SETTLE_MS = 400L

        /**
         * 输入框右侧那个按钮的位置（占屏幕比例）。
         *
         * 它是**二态**的：输入框有内容时是「✕」（点一下清空），为空时是
         * 「粘贴」（点一下填入剪贴板）。两种状态在同一个位置，所以一套坐标
         * 就能同时完成"清空"和"粘贴"。
         *
         * 实测帧 1080×608 上图标在 (857, 296)，换成比例即这两个值。
         * 虚拟屏固定 16:9，用比例比写死坐标稳。
         */
        private const val PASTE_ICON_X = 0.7935f
        private const val PASTE_ICON_Y = 0.4868f

        /**
         * 「提示」弹窗右下角「粘贴」按钮的位置（占屏幕比例）—— OCR 失败时兜底。
         *
         * 实测 1080×608 帧上按钮中心在 (690, 397)；**不能**用来点左边那个
         * 「不粘贴」（x≈0.361）。
         */
        private const val PASTE_CONFIRM_X = 0.639f
        private const val PASTE_CONFIRM_Y = 0.653f

        /** 输入框为空时的占位文字 —— 判断"粘贴到底有没有生效"。 */
        private val PLACEHOLDER_WORDS = listOf("请输入兑换码", "输入兑换码")

        /**
         * 兑换成功的提示词。
         *
         * 崩铁实测是「兑换成功」+「请前往邮箱领取奖励」；渠道服/国际服可能
         * 写成「已发放至邮箱」。都收进来，宁可多认几种也别把成功当失败。
         */
        private val SUCCESS_WORDS = listOf(
            "兑换成功", "领取成功", "已发放", "已发送", "奖励已发",
        )

        /**
         * 兑换失败的提示词（输入框下方的红条）。
         *
         * 「已被使用」和「无效」要分开记：前者说明码是真码但用过了，
         * 后者说明码写错了/过期了 —— 运维看到日志能直接判断是哪一类。
         */
        private val FAIL_WORDS = listOf(
            "已被使用", "已使用", "已兑换", "重复兑换", "无效", "不存在",
            "已过期", "错误", "失败", "不正确",
        )

        /** 成功弹窗上的关闭按钮。 */
        private val CLOSE_WORDS = listOf("确认", "确定", "我知道了", "关闭")

        /** 输入框的提示文字（点它取得焦点）。 */
        private val INPUT_HINTS = listOf("请输入兑换码", "输入兑换码")
    }

    /** 一个码的兑换结果。 */
    private enum class Outcome { SUCCESS, USED, INVALID, UNKNOWN }

    data class Result(
        val ok: Boolean,
        val detail: String,
        /** 本次真正兑换成功的码（调用方用来写本地记录） */
        val succeeded: List<String> = emptyList(),
    )

    private val notes = mutableListOf<String>()

    /**
     * 输入框里现在是否有码。
     *
     * 有码时右侧图标是「✕」，点一下是清空而不是粘贴 —— 如果状态判断错了，
     * 下一个码就会被"清空"掉，提交的是空内容。所以每次粘贴后置 `true`，
     * 清空/成功关弹窗/重开对话框时置 `false`。
     */
    private var inputHasText = false

    private fun note(msg: String) {
        Ln.i("$TAG: $msg")
        notes += msg
    }

    fun run(): Result {
        val todo = codes.filter { it.isNotBlank() }.distinct()
        if (todo.isEmpty()) {
            return Result(false, "没有可兑换的码")
        }
        val usedBefore = loadUsed() + alreadyUsed.map { it.uppercase() }
        val pending = todo.filterNot { usedBefore.contains(it.uppercase()) }
        if (pending.isEmpty()) {
            return Result(true, "全部 ${todo.size} 个码此前已兑换过，跳过")
        }
        note("待兑换 ${pending.size}/${todo.size} 个码（其余已兑换过）")

        if (!nav.gotoRedeem()) {
            return Result(false, "打不开兑换码界面${dumpHint()}")
        }

        val succeeded = mutableListOf<String>()
        val used = mutableListOf<String>()
        val failed = mutableListOf<String>()

        /**
         * 当前是否还停在兑换码弹窗上。
         *
         * 成功后弹窗会自己关掉（退回手机菜单），失败则留着 ——
         * 靠这个标记决定下一个码要不要重开入口。
         */
        var dialogOpen = true

        for ((index, code) in pending.withIndex()) {
            // 上一个码成功过 → 弹窗已关，先重新进一遍入口
            if (!dialogOpen) {
                note("弹窗已关闭，重新进入兑换入口")
                if (!nav.gotoRedeem()) {
                    failed += pending.drop(index)
                    note("重开兑换入口失败，剩余 ${pending.size - index} 个码本轮放弃")
                    break
                }
                dialogOpen = true
                // 新弹窗 = 新会话：输入框是空的
                inputHasText = false
            }

            note("—— 第 ${index + 1}/${pending.size} 个：${mask(code)} ——")
            val outcome = redeemOne(code)
            when (outcome) {
                Outcome.SUCCESS -> {
                    succeeded += code.uppercase()
                    appendUsed(code)
                    note("兑换成功：${mask(code)}")
                    // 实测点「确认」后会退回手机菜单，但要按实际画面确认，
                    // 不能假设 —— 万一某个版本留着弹窗，重开入口反而更慢
                    dialogOpen = nav.isRedeemDialogOpen()
                    if (dialogOpen) note("成功提示关掉后仍停在兑换弹窗，下一个码直接用")
                }
                Outcome.USED -> {
                    // 用过的码也算"不用再试"，但不算成功（没有奖励到手）
                    used += code.uppercase()
                    appendUsed(code)
                    note("该码已被使用，后续不再尝试：${mask(code)}")
                }
                Outcome.INVALID -> {
                    failed += code
                    note("该码无效或已过期：${mask(code)}")
                }
                Outcome.UNKNOWN -> {
                    failed += code
                    note("结果未知（界面文字见上），不计成功：${mask(code)}")
                    // 结果没认出来时弹窗状态也不确定，下一个码前统一核查一次
                    dialogOpen = nav.isRedeemDialogOpen()
                }
            }
        }

        // 收尾：关掉兑换码弹窗、退出设置，回大厅
        nav.closeCurrent()

        val ok = succeeded.isNotEmpty() || (failed.isEmpty() && used.isNotEmpty())
        val detail = buildString {
            append("兑换码：")
            if (succeeded.isNotEmpty()) append("成功 ${succeeded.size} 个")
            if (used.isNotEmpty()) {
                if (isNotEmpty()) append("，")
                append("已用过 ${used.size} 个")
            }
            if (failed.isNotEmpty()) {
                if (isNotEmpty()) append("，")
                append("失败 ${failed.size} 个")
            }
        }
        // 成功兑换的 + 已用过的都要写进本地记录，下次不必再试
        return Result(ok, detail, succeeded + used)
    }

    // ==================== 单个码 ====================

    private fun redeemOne(code: String): Outcome {
        // ① 写剪贴板 → 点粘贴图标 →（本会话第一次还会弹「提示」，点「粘贴」）
        if (!fillCode(code)) {
            note("输入失败（剪贴板没生效，回退注入也失败）")
            return Outcome.UNKNOWN
        }
        SystemClock.sleep(300)

        // ② 点「确认」
        if (!clickSubmit()) {
            note("没找到「确认」按钮")
            return Outcome.UNKNOWN
        }

        // ③ 读结果
        SystemClock.sleep(RESULT_WAIT_MS)
        val outcome = readResult()

        // ④ 只有成功才有"关弹窗"这一步
        //
        // 失败是输入框下方的一条红横幅，弹窗原样留着、**码也留在输入框里**
        // （右侧图标变成 ✕）—— 下一个码进来时先点 ✕ 清掉再粘贴。
        if (outcome == Outcome.SUCCESS) {
            // 成功会把弹窗关掉，输入框跟着没了
            inputHasText = false
            closeResultDialog()
            SystemClock.sleep(AFTER_CLOSE_MS)
        }
        return outcome
    }

    // ==================== 输入（剪贴板） ====================

    /**
     * 把码填进输入框。
     *
     * 优先走剪贴板：中文码（实测有「永远不会抛弃你」）用 `input text`
     * 根本打不进去，而剪贴板是系统级共享的 —— 特权进程用 [FakeContext]
     * 以 shell 身份写入，游戏侧点「粘贴」就能读到。
     */
    private fun fillCode(code: String): Boolean {
        if (writeClipboard(code) && pasteFromClipboard()) return true

        // 剪贴板这条路没走通（老系统 / 服务被限）：纯 ASCII 还能退回注入
        if (code.any { it.code > 0x7F }) {
            note("剪贴板不可用，中文码无法用注入方式输入")
            return false
        }
        Ln.w("$TAG: 剪贴板路径失败，回退 input text")
        focusInputBox()
        clearInput()
        return auto.inputText(code)
    }

    /** 写系统剪贴板（shell 身份，游戏侧读得到）。 */
    private fun writeClipboard(code: String): Boolean = runCatching {
        val cm = FakeContext.get()
            .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("redeem", code))
        Ln.d("$TAG: 已写入剪贴板（${mask(code)}）")
        true
    }.getOrElse {
        Ln.w("$TAG: 写剪贴板失败", it)
        false
    }

    /**
     * 把新码放进输入框：先清掉上一个，再粘贴。
     *
     * ## 为什么要"点两下"
     *
     * 输入框右侧那个按钮是**二态**的（见类注释）：
     * - 框里有码 → 是「✕」，点一下清空，图标变回「粘贴」；
     * - 框里没码 → 是「粘贴」，点一下填入剪贴板。
     *
     * 失败（无效/已用过）后码不会消失，所以第二个码进来时那个位置是「✕」。
     * 用 [inputHasText] 判断，有就先点一下清空，再点一下粘贴。
     *
     * ## 兜底
     *
     * 万一状态判断偏了（第一次点其实是清空、没粘上），点完会仍然看到占位
     * 文字「请输入兑换码…」；这时再点一次即可。不点输入框、不按退格。
     */
    private fun pasteFromClipboard(): Boolean {
        if (inputHasText) clearByIcon()

        var pasted = tapPasteIcon()
        if (!pasted) {
            // 仍然看到占位文字 → 刚才那一下落在 ✕ 上（只清空没粘贴），再点一次
            Ln.w("$TAG: 粘贴没生效（仍是占位文字），再点一次图标")
            pasted = tapPasteIcon()
        }
        if (!pasted) {
            Ln.w("$TAG: 两次都没填上，继续提交，看游戏判定结果")
        }
        inputHasText = true
        return true
    }

    /**
     * 点一下「✕」把输入框里上一个码清掉。
     *
     * 同一套坐标：框里有内容时那里就是 ✕。清完图标自动变回「粘贴」，
     * 所以不用管状态同步，点完直接按"空了"处理。
     */
    private fun clearByIcon() {
        val x = (auto.width * PASTE_ICON_X).toInt()
        val y = (auto.height * PASTE_ICON_Y).toInt()
        if (!auto.tap(x, y)) {
            Ln.w("$TAG: 点 ✕ 清空失败 ($x,$y)")
            return
        }
        Ln.i("$TAG: 输入框里有上一个码，点 ✕ 清空 ($x,$y)")
        SystemClock.sleep(CLEAR_SETTLE_MS)
        inputHasText = false
    }

    /**
     * 点粘贴图标，然后点掉游戏的「提示」框。
     *
     * 返回 `true` 表示"明确填上了"（提示框点过，或者占位文字已经消失）。
     *
     * 每个码写进剪贴板的都是**新内容**，所以每次都会弹「请求从剪切板粘贴」，
     * 这里必须每次都等、每次都点 —— 不能因为"上次点过"就跳过。
     */
    private fun tapPasteIcon(): Boolean {
        val x = (auto.width * PASTE_ICON_X).toInt()
        val y = (auto.height * PASTE_ICON_Y).toInt()
        if (!auto.tap(x, y)) {
            Ln.w("$TAG: 点粘贴图标失败 ($x,$y)")
            return false
        }
        Ln.i("$TAG: 已点粘贴图标 ($x,$y)")

        val confirmed = confirmPastePrompt()
        SystemClock.sleep(AFTER_PASTE_MS)
        if (confirmed) return true
        // 没等到提示框有两种可能：这版直接粘上了，或者压根没粘成功。
        Ln.w("$TAG: 没等到「请求从剪切板粘贴」提示框，可能已直接粘贴")
        return !hasPlaceholder()
    }

    /**
     * 点掉游戏自带的粘贴询问框（**不是**系统授权框）。
     *
     * 弹窗长这样：
     * ```
     *              提示
     *   《崩坏：星穹铁道》请求从剪切板粘贴，可选择粘贴打开内容
     *        ✕ 不粘贴          ✓ 粘贴
     * ```
     *
     * ## 为什么不能只判"以「粘贴」结尾"
     *
     * 正文那句「请求从剪切板粘贴」同样以「粘贴」结尾、也不含「不」，
     * 按这个条件找，第一个命中的会是正文，点上去什么都不会发生 ——
     * 询问框没关、码也没进去。
     *
     * 所以优先级是：**精确等于「粘贴」** > 短的、不含「不/请求/剪切」的
     * 以「粘贴」结尾的文字。按钮最多两个字，正文二十多个字，用长度分开。
     *
     * 弹窗有淡入动画，所以轮询找而不是睡死一个固定时长；认不出按钮位置
     * 但**确实看到了这个弹窗**时，用实测比例坐标兜底点右侧「粘贴」。
     */
    private fun confirmPastePrompt(): Boolean {
        val deadline = SystemClock.uptimeMillis() + PASTE_PROMPT_TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            val texts = nav.readPastePromptText()
            if (texts.isNotEmpty()) {
                val button = texts.firstOrNull { it.normalized() == "粘贴" }
                    ?: texts.firstOrNull { t ->
                        val s = t.normalized()
                        s.endsWith("粘贴") &&
                            s.length <= 4 &&
                            !s.contains("不") &&
                            !s.contains("请求") &&
                            !s.contains("剪切")
                    }
                if (button != null) {
                    Ln.i("$TAG: 游戏询问是否粘贴，点「粘贴」@(${button.cx},${button.cy})")
                    return auto.tap(button.cx, button.cy)
                }
                // 认得出这个弹窗（有「不粘贴」或正文）却认不出按钮 → 固定坐标兜底
                val promptVisible = texts.any { t ->
                    val s = t.normalized()
                    s.contains("不粘贴") || s.contains("请求从剪切板粘贴") || s == "提示"
                }
                if (promptVisible) {
                    val x = (auto.width * PASTE_CONFIRM_X).toInt()
                    val y = (auto.height * PASTE_CONFIRM_Y).toInt()
                    Ln.w("$TAG: 认出粘贴提示框但没定位到按钮，兜底点 ($x,$y)")
                    return auto.tap(x, y)
                }
            }
            SystemClock.sleep(PASTE_PROMPT_POLL_MS)
        }
        return false
    }

    /** 输入框还显示「请输入兑换码…」吗（仅用于日志判断）。 */
    private fun hasPlaceholder(): Boolean =
        PLACEHOLDER_WORDS.any { nav.hasRedeemDialogText(it) }

    /** 点输入框：优先认提示文字，认不到退到输入框那条水平线上。 */
    private fun focusInputBox(): Boolean {
        for (hint in INPUT_HINTS) {
            if (nav.clickRedeemDialogText(hint)) {
                Ln.i("$TAG: 已点输入框（提示文字「$hint」）")
                return true
            }
        }
        // 兜底：输入框在弹窗垂直中线附近（实测 y≈0.487H），点一下也能聚焦
        Ln.w("$TAG: 没认出输入框提示，点弹窗中央兜底")
        return auto.tap(auto.width / 2, (auto.height * PASTE_ICON_Y).toInt())
    }

    /**
     * 清空输入框。
     *
     * 先按 `MOVE_END` 把光标推到末尾，再连按退格 —— 顺序反了的话
     * 光标停在哪里就只删哪里，前面残留的字符会跟新码拼在一起。
     */
    private fun clearInput() {
        auto.pressKey(android.view.KeyEvent.KEYCODE_MOVE_END)
        var pressed = 0
        repeat(MAX_BACKSPACE) {
            if (!auto.pressKey(android.view.KeyEvent.KEYCODE_DEL, holdMs = 20L)) return@repeat
            pressed++
            SystemClock.sleep(25)
        }
        if (pressed > 0) Ln.d("$TAG: 清空输入框，按了 $pressed 次退格")

        // 全部删完再回一次开头，防止输入光标停在中间
        auto.pressKey(android.view.KeyEvent.KEYCODE_MOVE_HOME)
    }

    /**
     * 点右下角的「确认」。
     *
     * 顺序很重要：先认「确认」，别先认「兑换」—— `findText` 的兜底是
     * "包含"，拿「兑换」去找会命中标题「兑换码」，点在标题上什么都不会发生。
     */
    private fun clickSubmit(): Boolean {
        for (word in listOf("确认", "确认兑换", "兑换")) {
            if (nav.clickText(word, maxRetries = 1, logDump = false)) {
                Ln.i("$TAG: 已点「$word」")
                return true
            }
        }
        return false
    }

    /**
     * 读兑换结果。
     *
     * 只扫**弹窗那一带**（[ScreenNavigator.readRedeemResultText]），
     * 因为两种结果固定出现在那里：
     * - 失败：输入框下方的红横幅；
     * - 成功：居中的「兑换提示 / 兑换成功 / 请前往邮箱领取奖励」。
     *
     * 全屏 OCR 会把底下的手机菜单文字也读进来 ——「商店」「好友」这类
     * 词本身无害，但背景画面里如果出现「失败」「成功」，判定就翻了。
     */
    private fun readResult(): Outcome {
        val texts = nav.readRedeemResultText()
        if (texts.isEmpty()) {
            Ln.w("$TAG: 结果界面没有识别到任何文字")
            return Outcome.UNKNOWN
        }

        val joined = texts.joinToString("|") { it.normalized() }

        SUCCESS_WORDS.firstOrNull { joined.contains(it) }?.let {
            Ln.i("$TAG: 结果 = 成功（命中「$it」）")
            return Outcome.SUCCESS
        }
        FAIL_WORDS.firstOrNull { joined.contains(it) }?.let {
            val usedWord = listOf("已被使用", "已使用", "已兑换", "重复兑换").firstOrNull { w ->
                joined.contains(w)
            }
            Ln.w("$TAG: 结果 = 失败（命中「$it」）识别：$joined")
            return if (usedWord != null) Outcome.USED else Outcome.INVALID
        }

        // 不认识的结果 —— 把整屏文字留下来，便于对版本
        Ln.w("$TAG: 结果未知，全屏识别：$joined")
        return Outcome.UNKNOWN
    }

    /**
     * 关掉结果弹窗（点确定/确认/我知道了）。
     *
     * 只用 `clickText` 一次到位，不先 `hasText` 再点 ——
     * 那样每个词要 OCR 两遍，四个词就是八遍（约四秒）。
     */
    private fun closeResultDialog() {
        for (word in CLOSE_WORDS) {
            if (nav.clickText(word, maxRetries = 1)) {
                Ln.i("$TAG: 已关结果弹窗（「$word」）")
                return
            }
        }
        Ln.d("$TAG: 没有结果弹窗需要关闭")
    }

    private fun dumpHint(): String =
        if (notes.isEmpty()) "" else "（${notes.joinToString("；")}）"

    /** 读已兑换记录（一行一个码）。 */
    private fun loadUsed(): Set<String> {
        val f = usedFile ?: return emptySet()
        return runCatching {
            if (!f.isFile) {
                emptySet()
            } else {
                f.readLines()
                    .map { it.trim().uppercase() }
                    .filter { it.isNotEmpty() }
                    .toSet()
            }
        }.getOrElse {
            Ln.w("$TAG: 读已兑换记录失败: ${it.message}")
            emptySet()
        }
    }

    /** 追加一条已兑换记录。写失败不影响本次任务，只是下次会再试一遍。 */
    private fun appendUsed(code: String) {
        val f = usedFile ?: return
        runCatching {
            f.parentFile?.mkdirs()
            f.appendText(code.uppercase() + "\n")
        }.onFailure { Ln.w("$TAG: 写已兑换记录失败: ${it.message}") }
    }

    /** 日志里不露出完整兑换码。 */
    private fun mask(text: String): String =
        if (text.length <= 4) "****"
        else text.take(2) + "*".repeat(text.length - 4) + text.takeLast(2)
}
