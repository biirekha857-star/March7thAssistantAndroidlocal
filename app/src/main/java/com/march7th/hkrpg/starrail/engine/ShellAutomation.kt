package com.march7th.hkrpg.starrail.engine

import android.graphics.Bitmap
import android.os.SystemClock
import com.march7th.hkrpg.bridge.NativeBridgeLib
import com.march7th.hkrpg.core.InputControlUtils
import com.march7th.hkrpg.remote.internal.DiagDumper
import com.march7th.hkrpg.third.Ln
import java.io.ByteArrayOutputStream

/**
 * 屏控层：截图 + 触控。
 *
 * 运行在**特权进程**内，直连 `libbridge.so`（采集）与 `InputControlUtils`（注入），
 * 不经过 AIDL/TCP，因此没有跨进程拷贝。
 *
 * ## 采集
 * [NativeBridgeLib.setupNativeCapturer] 建 `AImageReader`（RGBA_8888, 5 缓冲）并返回
 * 一个 `Surface`；把这个 Surface 交给虚拟屏后，native 侧每帧写入帧池，
 * [NativeBridgeLib.getFrameBufferBitmap] 取出最新帧的 Bitmap（native 侧已完成
 * RGBA→BGR 转换与打包）。
 *
 * ## 注入
 * 通过 [InputControlUtils] 注入，**必须带 displayId**，否则事件落到主屏。
 *
 * ## 坐标
 * 所有对外坐标都基于**采集到的帧尺寸**。UI 层拿到的预览也是同一尺寸，
 * 因此点击坐标可以直接对应，不需要额外换算。
 */
class ShellAutomation {

    companion object {
        private const val TAG = "ShellAutomation"
        private const val DEFAULT_WIDTH = 1920
        private const val DEFAULT_HEIGHT = 1080

        /**
         * 两次"触控失败现场快照"之间的最小间隔。
         *
         * 快照是异步线程跑 `dumpsys`（几条命令合计几百毫秒~数秒），
         * 一旦进入失败重试循环，每次都 dump 只会拖慢恢复。
         * 30 秒一顿足够——同一次卡死期间采到的现场本来就一样。
         */
        private const val INPUT_DIAG_MIN_GAP_MS = 30_000L
    }

    /** 最近一次采集到的帧。 */
    @Volatile
    var screenshot: Bitmap? = null
        private set

    /** 采集分辨率（由虚拟屏配置决定）。 */
    var width: Int = DEFAULT_WIDTH
        private set
    var height: Int = DEFAULT_HEIGHT
        private set

    private var capturerReady = false

    /** 当前生效的 displayId（虚拟屏）。-1 表示未启动。 */
    @Volatile
    var displayId: Int = -1
        private set

    /**
     * 建立采集通道并创建 Surface。
     *
     * @return 交给虚拟屏的 Surface；失败返回 null
     */
    fun setupCapturer(w: Int = DEFAULT_WIDTH, h: Int = DEFAULT_HEIGHT): android.view.Surface? {
        if (capturerReady) {
            Ln.w("$TAG: capturer 已就绪，先释放")
            releaseCapturer()
        }
        return try {
            val surface = NativeBridgeLib.setupNativeCapturer(w, h)
            if (surface == null) {
                Ln.e("$TAG: setupNativeCapturer 返回 null")
                return null
            }
            width = w
            height = h
            capturerReady = true
            Ln.i("$TAG: 采集通道就绪 ${w}x$h")
            surface
        } catch (t: Throwable) {
            Ln.e("$TAG: setupCapturer 失败: ${t.message}")
            null
        }
    }

    fun releaseCapturer() {
        if (!capturerReady) return
        runCatching { NativeBridgeLib.releaseNativeCapturer() }
            .onFailure { Ln.w("$TAG: releaseNativeCapturer 失败: ${it.message}") }
        capturerReady = false
        screenshot = null
        Ln.i("$TAG: 采集通道已释放")
    }

    fun setDisplayId(id: Int) {
        displayId = id
        Ln.i("$TAG: displayId = $id")
    }

    /**
     * 复用**已存在**的采集通道。
     *
     * `VirtualDisplayManager` 创建虚拟屏时已经调过
     * `NativeBridgeLib.setupNativeCapturer`，帧池已经在写。
     * 这里只需把 displayId 接上并标记就绪，**不要**重复 setup
     * （重复 setup 会先 release 再重建，白白丢帧）。
     */
    fun attachExistingCapturer(id: Int, w: Int, h: Int) {
        displayId = id
        width = w
        height = h
        capturerReady = true
        Ln.i("$TAG: 复用已有采集通道 display=$id ${w}x$h")
    }

    /**
     * 抓一帧。
     *
     * @param retries 取不到帧时的重试次数（虚拟屏刚建立时可能还没有内容）
     */
    fun takeScreenshot(retries: Int = 3): Boolean {
        if (!capturerReady) {
            Ln.w("$TAG: 采集通道未就绪")
            return false
        }
        for (i in 0 until retries) {
            try {
                val bmp = NativeBridgeLib.getFrameBufferBitmap()
                if (bmp != null && bmp.width > 0 && bmp.height > 0) {
                    // ## 帧尺寸和虚拟屏尺寸不一致 → **主动缩放到目标尺寸**
                    //
                    // 实测踩过：虚拟屏是 1920×1080，采到的帧却是 1920×872
                    // （长屏 ROM 按物理屏 2.2 比例渲染），画面横向压扁，
                    // OCR 认不出字、坐标全错位 —— 启动一直超时。
                    //
                    // 之前只打警告不修正，任务照样跑不通。现在改成：
                    // **把帧缩放到声明的分辨率**，让后续 OCR / 坐标计算
                    // 全部基于 1920×1080，不再受畸形帧影响。
                    //
                    // 注意：缩放只是让坐标体系自洽，画质会略糊；
                    // 真正修好还得靠 `VirtualDisplayManager` 那边
                    // setForcedDisplaySize 把虚拟屏尺寸纠正过来。
                    val fixed = if (bmp.width != width || bmp.height != height) {
                        if (!sizeMismatchWarned) {
                            sizeMismatchWarned = true
                            Ln.e(
                                "$TAG: ⚠️ 帧尺寸异常！帧 ${bmp.width}x${bmp.height} " +
                                    "vs 虚拟屏 ${width}x$height —— 已缩放到目标尺寸继续",
                            )
                        }
                        val scaled = Bitmap.createScaledBitmap(bmp, width, height, true)
                        if (scaled !== bmp) bmp.recycle()
                        scaled
                    } else {
                        bmp
                    }
                    screenshot = fixed
                    return true
                }
            } catch (t: Throwable) {
                Ln.w("$TAG: getFrameBufferBitmap 异常: ${t.message}")
            }
            if (i < retries - 1) SystemClock.sleep(200)
        }
        Ln.w("$TAG: 取帧失败（$retries 次）")
        return false
    }

    /** 帧尺寸警告是否已经打过（只打一次，避免刷屏）。 */
    @Volatile
    private var sizeMismatchWarned = false

    /** 当前帧编码为 PNG。 */
    fun framePng(): ByteArray? {
        val bmp = screenshot ?: return null
        return runCatching {
            ByteArrayOutputStream().use { bos ->
                bmp.compress(Bitmap.CompressFormat.PNG, 100, bos)
                bos.toByteArray()
            }
        }.onFailure { Ln.e("$TAG: PNG 编码失败: ${it.message}") }.getOrNull()
    }

    /**
     * 当前帧编码为 **JPEG**（调试落盘专用）。
     *
     * ## 为什么调试帧不能用 PNG
     *
     * 截图是**照片类内容**（渐变背景、立绘、半透明叠层），PNG 的无损
     * 压缩在这种画面上几乎失效：1080p 一帧稳定 1.5~2.3 MB。
     * 实测一次任务导出 274 帧 → **363 MB** 的日志包。
     *
     * JPEG q85 在同一帧上只有 200~350 KB，**小 6~8 倍**，而调试看的是
     * "界面对不对、文字在哪"，画质损失完全不影响判断。
     *
     * 需要无损的地方（模板采集、[framePng] 走 IPC 给 App 显示）保持原样，
     * 只有写磁盘的调试帧改走这条路。
     */
    fun frameJpeg(quality: Int = 85): ByteArray? {
        val bmp = screenshot ?: return null
        return runCatching {
            ByteArrayOutputStream().use { bos ->
                bmp.compress(Bitmap.CompressFormat.JPEG, quality, bos)
                bos.toByteArray()
            }
        }.onFailure { Ln.e("$TAG: JPEG 编码失败: ${it.message}") }.getOrNull()
    }

    // ---- 触控 ----

    /**
     * 最近一次注入失败的原因。
     *
     * 以前失败只写一句 `down(53,100) 失败`，看不出是"权限被拒"、
     * "通道异常"还是"残留手势导致 touchstream 脏了"。这三个的处理方式
     * 完全不同，所以必须把原因一路记下来。
     */
    @Volatile
    var lastInputFailure: String = ""
        private set

    /** 连续注入失败次数（供上层判断"通道是不是瘸了"）。 */
    @Volatile
    var consecutiveInputFailures: Int = 0
        private set

    /** 上一次输入现场快照的时间（限流，避免在重试循环里反复 dump）。 */
    @Volatile
    private var lastInputDiagAt = 0L

    /**
     * 单击（带按下时长，模拟真实触摸）。
     *
     * ## 失败自愈：先清槽再重试一次
     *
     * `InputControlUtils` 用一份 `slots` 记录"系统侧认为还在屏幕上的手指"。
     * 只要有一次 UP/CANCEL 没送达（异步模式不保证送达），系统侧就会留下
     * 一根幽灵手指 —— 之后所有 DOWN 都会被 InputDispatcher 判成
     * "指针状态不一致"而**直接丢弃**，表现就是：画面正常、坐标正确、
     * 就是点不动，而且不会自己恢复。
     *
     * 唯一的解法是注入一次 `ACTION_CANCEL` 把系统侧的手指全部抬掉。
     * 所以这里失败后固定走一遍：记原因 → CANCEL 清槽 → 重试一次。
     * 重试成功说明确实是这个原因，日志里也能看出"自愈"痕迹。
     */
    fun tap(x: Int, y: Int, contact: Int = InputControlUtils.SINGLE_CONTACT, durationMs: Long = 60L): Boolean {
        if (displayId < 0) {
            Ln.w("$TAG: tap 时 displayId 未设置")
            return false
        }
        if (performTap(x, y, contact, durationMs)) {
            consecutiveInputFailures = 0
            return true
        }

        val reason = lastInputFailure
        val pending = runCatching { InputControlUtils.pendingContacts() }.getOrDefault(-1)
        Ln.w("$TAG: tap($x,$y) 失败 —— $reason（残留触点=$pending），清槽后重试")

        // 把系统侧的幽灵手指全部 CANCEL 掉
        runCatching { InputControlUtils.cancel(displayId) }
        SystemClock.sleep(120)

        if (performTap(x, y, contact, durationMs)) {
            consecutiveInputFailures = 0
            Ln.i("$TAG: tap($x,$y) 重试成功 —— 前一次是残留手势，已自愈")
            return true
        }

        // ② 最后一招：DOWN 降级成"只等入队，不等消费"
        //
        // WAIT_FOR_FINISH 会一直等到目标窗口处理完才返回；游戏主线程在
        // 转场/加载/GC 时会超时 —— 事件其实送到了，只是没等上。
        // 这种失败换模式就能过，而"权限被拒/指针状态错乱"换什么都没用，
        // 正好可以借它区分两类原因。
        if (performTapQueued(x, y, contact, durationMs)) {
            consecutiveInputFailures = 0
            Ln.i("$TAG: tap($x,$y) 降级注入成功 —— 前两次是「等待界面消费」超时，不是权限问题")
            return true
        }

        consecutiveInputFailures++
        Ln.e("$TAG: tap($x,$y) 三种方式均失败 —— $reason（累计 $consecutiveInputFailures 次）")
        dumpInputDiag(x, y, reason)
        return false
    }

    /**
     * 真正执行一次按下-抬起。异常不再被 `runCatching{}.getOrDefault(false)` 吞掉，
     * 而是记进 [lastInputFailure]。
     */
    private fun performTap(x: Int, y: Int, contact: Int, durationMs: Long): Boolean {
        val downOk = try {
            InputControlUtils.down(x, y, contact, displayId)
        } catch (t: Throwable) {
            lastInputFailure = "DOWN 抛 ${t.javaClass.simpleName}: ${t.message}"
            return false
        }
        if (!downOk) {
            lastInputFailure = "DOWN 返回 false（事件未被 InputDispatcher 接受）"
            return false
        }

        SystemClock.sleep(durationMs)

        val upOk = try {
            InputControlUtils.up(x, y, contact, displayId)
        } catch (t: Throwable) {
            lastInputFailure = "UP 抛 ${t.javaClass.simpleName}: ${t.message}"
            // 手指已经按下去了，抬不起来必须 CANCEL，否则这根手指永远留在系统侧
            runCatching { InputControlUtils.cancel(displayId) }
            return false
        }
        if (!upOk) {
            lastInputFailure = "UP 返回 false（手指可能卡在屏幕上了）"
            runCatching { InputControlUtils.cancel(displayId) }
            return false
        }
        lastInputFailure = ""
        return true
    }

    /**
     * 降级版按下-抬起：DOWN 只等入队（不等界面消费完）。
     *
     * 与 [performTap] 的唯一差别是 DOWN 的注入模式。命中说明"游戏当时很忙、
     * 但通道本身是通的"。
     */
    private fun performTapQueued(x: Int, y: Int, contact: Int, durationMs: Long): Boolean {
        val downOk = try {
            InputControlUtils.downQueued(x, y, contact, displayId)
        } catch (t: Throwable) {
            lastInputFailure = "DOWN(queued) 抛 ${t.javaClass.simpleName}: ${t.message}"
            return false
        }
        if (!downOk) {
            lastInputFailure = "DOWN(queued) 也被拒绝 —— 通道级问题（权限/displayId/指针状态）"
            return false
        }

        SystemClock.sleep(durationMs)

        val upOk = try {
            InputControlUtils.up(x, y, contact, displayId)
        } catch (t: Throwable) {
            lastInputFailure = "UP(queued) 抛 ${t.javaClass.simpleName}: ${t.message}"
            runCatching { InputControlUtils.cancel(displayId) }
            return false
        }
        if (!upOk) {
            lastInputFailure = "UP(queued) 返回 false"
            runCatching { InputControlUtils.cancel(displayId) }
            return false
        }
        lastInputFailure = ""
        return true
    }

    /**
     * 注入失败时采一份现场快照。
     *
     * 单靠 engine.log 里的"down 失败"没法区分：
     * - 权限/通道问题（`injectInputEvent` 直接拒绝）
     * - 目标窗口不接受输入（虚拟屏的 focused window 换了）
     * - 输入映射错位（`Viewport` 和窗口 frame 对不上）
     *
     * 这三类只有 `dumpsys input` / `dumpsys window` 能看出来，所以借
     * 现成的 [DiagDumper] 打一份全量快照进日志包。
     */
    private fun dumpInputDiag(x: Int, y: Int, reason: String) {
        val now = SystemClock.uptimeMillis()
        if (now - lastInputDiagAt < INPUT_DIAG_MIN_GAP_MS) return
        lastInputDiagAt = now

        Ln.w("$TAG: 采集触控链路现场 —— display=$displayId 帧=${width}x$height 采集就绪=$capturerReady")

        // 焦点窗口：能一句话区分"事件被拒"和"窗口不接受输入"
        //
        // 后者在画面上极其迷惑 —— 游戏画面仍然可见、模板照样命中，
        // 只是所有触摸都被丢弃，过一阵子又会自己好。
        val focus = runCatching { DiagDumper.currentFocus() }.getOrDefault("")
        if (focus.isNotEmpty()) {
            Ln.w("$TAG: 各屏焦点窗口 ——\n$focus")
        }

        runCatching {
            DiagDumper.snapshot(
                "touch-fail",
                "tap($x,$y) display=$displayId size=${width}x$height reason=$reason",
            )
        }.onFailure { Ln.w("$TAG: 现场快照失败: ${it.message}") }
    }

    /** 长按。 */
    fun longPress(x: Int, y: Int, durationMs: Long = 800L, contact: Int = InputControlUtils.SINGLE_CONTACT): Boolean =
        tap(x, y, contact, durationMs)

    /** 滑动（线性插值，模拟真实手势轨迹）。 */
    fun swipe(
        fromX: Int, fromY: Int, toX: Int, toY: Int,
        durationMs: Long = 300L, steps: Int = 12,
        contact: Int = InputControlUtils.SINGLE_CONTACT,
    ): Boolean {
        if (displayId < 0) {
            Ln.w("$TAG: swipe 时 displayId 未设置")
            return false
        }
        if (!runCatching { InputControlUtils.down(fromX, fromY, contact, displayId) }.getOrDefault(false)) {
            Ln.w("$TAG: swipe 起点 down($fromX,$fromY) 失败，清槽后重试")
            runCatching { InputControlUtils.cancel(displayId) }
            SystemClock.sleep(120)
            if (!runCatching { InputControlUtils.down(fromX, fromY, contact, displayId) }.getOrDefault(false)) {
                Ln.e("$TAG: swipe 起点连续两次注入失败")
                return false
            }
        }
        val stepDelay = (durationMs / steps).coerceAtLeast(1L)
        for (i in 1..steps) {
            val t = i.toFloat() / steps
            val x = (fromX + (toX - fromX) * t).toInt()
            val y = (fromY + (toY - fromY) * t).toInt()
            runCatching { InputControlUtils.move(x, y, contact, displayId) }
            SystemClock.sleep(stepDelay)
        }
        val ok = runCatching { InputControlUtils.up(toX, toY, contact, displayId) }.getOrDefault(false)
        if (!ok) {
            Ln.w("$TAG: swipe 终点 up($toX,$toY) 失败，取消手势")
            runCatching { InputControlUtils.cancel(displayId) }
        }
        return ok
    }

    /** 按键。 */
    fun pressKey(keyCode: Int, holdMs: Long = 50L): Boolean {
        if (displayId < 0) return false
        val d = runCatching { InputControlUtils.keyDown(keyCode, displayId) }.getOrDefault(false)
        SystemClock.sleep(holdMs)
        val u = runCatching { InputControlUtils.keyUp(keyCode, displayId) }.getOrDefault(false)
        if (!d || !u) {
            Ln.w("$TAG: pressKey($keyCode) 注入失败（down=$d up=$u）")
            // 按键 DOWN 成功 UP 失败时，系统侧会认为这个键一直按着
            runCatching { InputControlUtils.cancel(displayId) }
        }
        return d && u
    }

    /** 取消当前手势（异常时自愈）。 */
    fun cancelGesture() {
        if (displayId < 0) return
        runCatching { InputControlUtils.cancel(displayId) }
    }

    /**
     * 往当前焦点输入框里输入文本（兑换码、账号密码这类短字符串）。
     *
     * ## 两条路，按可靠性排序
     *
     * 1. **`input text`**（首选）
     *    `input` 是系统自带的 shell 工具，走 IME 提交路径 —— 大写字母、
     *    数字、符号都是"直接插入"，不受键盘布局和 shift 状态影响。
     *    内核进程有 shell 身份（[GameLauncher] 里 `pidof` / `am` 都在用），
     *    所以能直接 exec 它。
     *
     * 2. **逐字符注入 KeyEvent**（兜底）
     *    `input text` 在部分 ROM 上会因为没有 IME 连接而静默失败
     *    （虚拟屏尤其常见）。这时改用 [pressKey] 逐个按键：
     *    `KEYCODE_A..KEYCODE_Z` / `KEYCODE_0..KEYCODE_9` 在 EditText 上
     *    就是字符插入。
     *
     *    ⚠️ 这条路**只能输入大写字母和数字** —— KeyEvent 不带 shift 状态，
     *    小写字母会变成大写。兑换码对大小写不敏感，正好够用；
     *    别的场景（中文、符号）不要用它。
     *
     * 两条路都只负责"把字发出去"，**不判断有没有输进去** ——
     * 调用方应该点一次提交按钮，再根据结果提示判断成功与否。
     *
     * @param text 要输入的文本（建议先自己校验成 `[A-Za-z0-9]`）
     * @return 是否至少有一条路径返回成功
     */
    fun inputText(text: String): Boolean {
        if (displayId < 0) {
            Ln.w("$TAG: inputText —— 虚拟屏未就绪")
            return false
        }
        if (text.isBlank()) return false

        if (inputTextByShell(text)) return true

        Ln.w("$TAG: input text 失败，降级为逐字符 KeyEvent 注入")
        return inputTextByKeyEvents(text)
    }

    /**
     * 用系统 `input text` 命令输入。
     *
     * 注意这里**不加 `-d <display>`**：`input` 的 `-d` 参数在老 ROM 上
     * 普遍不支持（会直接报错退出），而事件本来就是投给"当前获得焦点的窗口"，
     * 游戏在虚拟屏上前台运行时，焦点就在它那儿。
     */
    private fun inputTextByShell(text: String): Boolean = runCatching {
        val p = Runtime.getRuntime().exec(arrayOf("input", "text", text))
        // input 是同步命令，退出码 0 = 已提交给 IME
        val code = p.waitFor()
        p.inputStream.close()
        p.errorStream.close()
        if (code == 0) {
            Ln.i("$TAG: input text 已提交「${mask(text)}」")
        } else {
            Ln.w("$TAG: input text 退出码 $code")
        }
        code == 0
    }.getOrElse {
        Ln.w("$TAG: input text 执行异常: ${it.message}")
        false
    }

    /** 逐字符 KeyEvent 注入（仅大写字母与数字）。 */
    private fun inputTextByKeyEvents(text: String): Boolean {
        var allOk = true
        var typed = 0
        for (ch in text.uppercase()) {
            val code = when (ch) {
                in 'A'..'Z' -> android.view.KeyEvent.KEYCODE_A + (ch - 'A')
                in '0'..'9' -> android.view.KeyEvent.KEYCODE_0 + (ch - '0')
                else -> null
            }
            if (code == null) {
                Ln.w("$TAG: 跳过不能按键输入的字符「$ch」")
                continue
            }
            if (!pressKey(code, holdMs = 30L)) allOk = false
            typed++
            // 字符之间留一点间隔，太快会被输入框吞掉（尤其低端机）
            SystemClock.sleep(60)
        }
        Ln.i("$TAG: KeyEvent 注入 ${typed}/${text.length} 个字符，结果=$allOk")
        return allOk && typed > 0
    }

    /** 日志里不打印完整兑换码（别人的截图/日志也不该带出一个可用的码）。 */
    private fun mask(text: String): String =
        if (text.length <= 4) "****"
        else text.take(2) + "*".repeat(text.length - 4) + text.takeLast(2)
}
