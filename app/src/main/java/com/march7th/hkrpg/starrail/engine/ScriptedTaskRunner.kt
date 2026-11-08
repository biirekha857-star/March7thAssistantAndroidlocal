package com.march7th.hkrpg.starrail.engine

import com.march7th.hkrpg.data.model.CustomTaskPack
import com.march7th.hkrpg.data.model.PackExpr
import com.march7th.hkrpg.data.model.PackStep
import com.march7th.hkrpg.remote.CoreDataDir
import com.march7th.hkrpg.utils.JsonUtils
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * 自定义任务包执行器。
 *
 * 规范见 `docs/task-file.md`。这里只做**解释**，不执行任何外部代码：
 * 每个 `op` 都是硬编码的一段逻辑，映射到 [ShellAutomation] / [ScreenNavigator]
 * 已有的原语上。
 *
 * ## 沙箱
 *
 * 这个类持有的东西只有三样：[ShellAutomation]（点/滑/截图）、[ScreenNavigator]
 * （匹配/OCR/等）、一个日志回调。**没有** App 启动入口、没有 shell、没有网络客户端、
 * 没有文件写入。所以哪怕任务文件写满恶意步骤，它能做的上限也就是"在屏幕上乱点"。
 * 边界与理由见 [PackSandbox] 的类注释。
 *
 * 运行期还有两道硬拦：
 * - 模板路径必须落在本包的 `assets/` 内（[PackSandbox.assetRel]）；
 * - 步数与总时长预算（[PackSandbox.Budget]），死循环会被强制中止。
 *
 * ## 为什么用异常做控制流
 *
 * 流程是树状的（`repeat` / `ifTemplate` 里还有步骤），失败要能立刻跳出整棵树。
 * 用返回值层层传递成功标志，代码会变成一堆 `if (!ok) return false`，
 * 且 `repeat` 里到底继续还是中断会很难读。所以：
 *
 * - [StepFailure] —— 某一步失败，默认穿透到顶层；
 * - [StopFlow] —— 任务主动 `stop`，属于正常收工。
 *
 * `optional: true` 的步骤只吞 [StepFailure]，不会吞 [StopFlow]。
 */
class ScriptedTaskRunner(
    private val auto: ShellAutomation,
    private val nav: ScreenNavigator,
    private val isCancelled: () -> Boolean,
    private val log: (String) -> Unit,
) {
    companion object {
        private const val DEFAULT_TIMEOUT_MS = 10_000L
        private const val DEFAULT_THRESHOLD = 0.80
        private const val POLL_MS = 400L
        private const val DEFAULT_TOLERANCE = 32
        private const val MAX_TOLERANCE = 128
        private const val DEFAULT_MIN_AREA = 80
        private const val DEFAULT_MAX_AREA = 4_000
        private const val DEFAULT_MAX_TRIES = 8
        private const val MAX_MAX_TRIES = 30

        /**
         * 包目录（core 侧）。根目录恒为 [CoreDataDir.ROOT]，
         * App 侧 `CoreDataPusher.pushUserData()` 在每次下发前推过来
         */
        private val PACK_DIR = CoreDataDir.TASKPACKS_DIR
    }

    data class Result(val ok: Boolean, val detail: String)

    private val json = Json(JsonUtils.common) { ignoreUnknownKeys = true }

    /** 参数（来自 `params` 默认值 / 用户覆盖），值是文本 */
    private var params: Map<String, String> = emptyMap()

    /** 运行期变量表：`setVar` / `readNumber` / `readText` 写，表达式读 */
    private val vars = LinkedHashMap<String, PackExpr.Value>()

    /** 本次执行的包目录与 id，模板路径校验要用 */
    private var packRoot: File? = null
    private var packId: String = ""

    private var budget: PackSandbox.Budget = PackSandbox.Budget(isCancelled)

    fun run(packId: String, overrides: Map<String, String> = emptyMap()): Result {
        val root = File(CoreDataDir.ROOT)
        val packDir = File(root, "$PACK_DIR/$packId")
        val manifest = File(packDir, "task.json")
        if (!manifest.isFile) {
            return Result(false, "找不到任务包 $packId（先检查是否已投递到 ${packDir.path}）")
        }

        val pack = runCatching {
            json.decodeFromString(CustomTaskPack.serializer(), manifest.readText(Charsets.UTF_8))
        }.getOrElse { e ->
            return Result(false, "任务包 task.json 解析失败：${e.message}")
        }

        // 模板路径用 `taskpacks/<id>/assets/x.png` 这种相对路径，
        // ImageUtils 的查找顺序是 磁盘 → APK → assets，把磁盘根指到 core 目录后
        // 自定义模板能命中，内置模板自动退回 APK，互不影响。
        ImageUtils.setDiskRoot(root)

        this.packId = packId
        this.packRoot = packDir
        this.params = clampIntParams(pack, buildParams(pack, overrides) + overrides)
        this.vars.clear()
        this.budget = PackSandbox.Budget(isCancelled)

        log("开始执行自定义任务「${pack.name}」（${pack.steps.size} 步）")
        return try {
            execSteps(pack.steps, 1)
            Result(true, "流程执行完毕")
        } catch (e: StopFlow) {
            Result(e.ok, if (e.ok) "按流程要求提前结束" else "流程主动判定失败")
        } catch (e: StepFailure) {
            Result(false, e.message ?: "未知错误")
        } catch (e: PackSandbox.Exceeded) {
            Result(false, e.message ?: "执行预算耗尽")
        }
    }

    // ==================== 参数与变量 ====================

    private fun buildParams(pack: CustomTaskPack, overrides: Map<String, String>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        pack.params.forEach { p ->
            if (p.key.isBlank()) return@forEach
            val fromUser = overrides[p.key]
            if (fromUser != null) {
                out[p.key] = fromUser
                return@forEach
            }
            val raw = p.default?.let { (it as? JsonPrimitive)?.content }.orEmpty()
            out[p.key] = raw.ifBlank { if (p.type == "int") "1" else "" }
        }
        return out
    }

    /**
     * 把 int 参数夹回作者声明的 `min` ~ `max`。
     *
     * ## 为什么在执行期夹，而不是在输入框里拦
     *
     * 输入框拦会让打字过程很难受 —— 想输 15，刚按下 1 就被弹成 10。
     * 作者声明的范围是**流程能接受的边界**，不是输入格式，所以放在执行前
     * 统一收口：用户想填什么填什么，跑的时候按边界值走。
     *
     * ⚠️ 夹了必须写日志。否则用户填了 999，流程实际只跑 10 次，
     * 他会以为包写错了 —— 日志是唯一能解释清楚的地方。
     *
     * 非整数（比如空着或写了字母）回落到包里的 `default`，同样夹一次。
     */
    private fun clampIntParams(
        pack: CustomTaskPack,
        values: Map<String, String>,
    ): Map<String, String> {
        val out = LinkedHashMap(values)
        pack.params.forEach { p ->
            if (!p.type.equals("int", ignoreCase = true)) return@forEach
            val lo = p.min
            val hi = p.max
            if (lo == null && hi == null) return@forEach
            val key = p.key
            if (key.isBlank() || key !in out) return@forEach

            val raw = out.getValue(key).trim()
            val parsed = raw.toIntOrNull()
            val fallback = runCatching { p.default?.jsonPrimitive?.intOrNull }.getOrNull()
            val base = parsed ?: fallback ?: return@forEach
            val fixed = base.coerceIn(lo ?: Int.MIN_VALUE, hi ?: Int.MAX_VALUE)

            when {
                parsed == null -> log("参数 $key=「$raw」不是整数，按 $fixed 执行")
                fixed != parsed -> log(
                    "参数 $key=$parsed 超出 ${lo ?: "-∞"}~${hi ?: "+∞"}，按 $fixed 执行"
                )
            }
            out[key] = fixed.toString()
        }
        return out
    }

    /**
     * 把 `{key}` 换成参数值或变量值。
     *
     * 先查参数再查变量：参数是作者声明的，变量是运行期算出来的，
     * 同名时参数优先（否则 `readNumber` 写进一个参数同名变量会静默改掉作者意图）。
     * 都没命中就保持原样 —— 导入校验已经拦过未声明的参数。
     */
    private fun subst(raw: String?): String? {
        val src = raw ?: return null
        if (!src.contains('{')) return src
        var out: String = src
        params.forEach { (k, v) -> out = out.replace("{$k}", v) }
        vars.forEach { (k, v) -> out = out.replace("{$k}", v.asText()) }
        return out
    }

    private fun setVar(name: String?, value: PackExpr.Value) {
        val key = name?.trim().orEmpty()
        if (key.isBlank()) return
        vars[key] = when (value) {
            is PackExpr.Value.Str -> PackExpr.Value.Str(PackSandbox.clampVarText(value.v))
            else -> value
        }
        val shown = when (value) {
            is PackExpr.Value.Str -> "\"${PackSandbox.clampVarText(value.v)}\""
            else -> value.asText()
        }
        log("变量 $key = $shown")
    }

    /**
     * 求值表达式。语法错 / 变量无值都当步骤失败，不崩。
     *
     * 作用域是「参数 + 运行期变量」。只给 vars 的话，`account == 'A'` 这种
     * 直接写参数名的表达式会报「用了还没有值的变量」—— 参数存在 params 里，
     * 进不了 vars。同名时运行期变量优先，setVar 写进去的值更新。
     */
    private fun evalExpr(step: PackStep, label: String): PackExpr.Value {
        val src = subst(step.expr)
        if (src.isNullOrBlank()) throw StepFailure("缺少 expr")
        val scope = HashMap<String, PackExpr.Value>(params.size + vars.size)
        params.forEach { (k, v) -> scope[k] = paramValue(v) }
        scope.putAll(vars)
        return PackExpr.eval(src, scope)
            ?: throw StepFailure("表达式算不出来：$src（语法错，或用了还没有值的变量）")
    }

    /** 参数值是文本：能转数字就当数字，否则当字符串。 */
    private fun paramValue(text: String): PackExpr.Value =
        text.trim().toLongOrNull()?.let { PackExpr.Value.Num(it) } ?: PackExpr.Value.Str(text)

    // ==================== 步骤树 ====================

    private fun execSteps(steps: List<PackStep>, depth: Int) {
        if (depth > PackSandbox.MAX_NEST_DEPTH) {
            throw StepFailure("流程嵌套超过 ${PackSandbox.MAX_NEST_DEPTH} 层")
        }
        steps.forEachIndexed { index, step ->
            budget.tick()
            execOne(step, index, depth)
        }
    }

    private fun execOne(step: PackStep, index: Int, depth: Int) {
        val label = "#$index ${step.op}" + if (step.note.isNotBlank()) "（${step.note}）" else ""
        try {
            doExec(step, label, depth)
        } catch (e: StopFlow) {
            throw e
        } catch (e: PackSandbox.Exceeded) {
            throw e
        } catch (e: StepFailure) {
            if (step.optional) {
                log("⚠️ $label 跳过：${e.message}")
            } else {
                throw StepFailure("$label 失败：${e.message}")
            }
        }
    }

    private fun doExec(step: PackStep, label: String, depth: Int) {
        when (step.op) {
            "log" -> log("[自定义] ${subst(step.text).orEmpty()}")

            "wait" -> sleep((step.ms ?: DEFAULT_TIMEOUT_MS).coerceAtMost(60_000))

            "tap" -> {
                val (x, y) = pixel(step.x, step.y)
                if (!auto.tap(x, y, durationMs = step.durationMs ?: 60L)) {
                    throw StepFailure("点击 ($x, $y) 注入失败")
                }
            }

            "longPress" -> {
                val (x, y) = pixel(step.x, step.y)
                if (!auto.longPress(x, y, step.durationMs ?: 800L)) {
                    throw StepFailure("长按 ($x, $y) 注入失败")
                }
            }

            "swipe" -> doSwipe(step)

            "waitTemplate" -> {
                val r = find(step, wait = true)
                    ?: throw StepFailure("等不到模板 ${step.image}")
                log("$label 命中 @(${r.x},${r.y}) scale=${"%.2f".format(r.scale)}")
            }

            "tapTemplate" -> {
                val r = find(step, wait = true)
                    ?: throw StepFailure("等不到模板 ${step.image}")
                val (cx, cy) = centerOf(r, step.offsetX, step.offsetY)
                if (!auto.tap(cx, cy)) throw StepFailure("点击模板中心 ($cx,$cy) 注入失败")
            }

            "assertTemplate" -> {
                if (find(step, wait = false) == null) {
                    throw StepFailure("画面里没有 ${step.image}（流程起点不对？）")
                }
            }

            "waitText" -> {
                if (!waitText(step)) {
                    dumpOnMiss("waitText", subst(step.text))
                    throw StepFailure("等不到文字「${subst(step.text)}」")
                }
            }

            "tapText" -> {
                val text = subst(step.text).orEmpty()
                if (!waitText(step)) {
                    dumpOnMiss("tapText", text)
                    throw StepFailure("等不到文字「$text」")
                }
                if (!nav.clickText(text, cropOf(step))) {
                    throw StepFailure("点击文字「$text」失败")
                }
            }

            "assertText" -> {
                val text = subst(step.text).orEmpty()
                if (!nav.hasText(text, cropOf(step))) {
                    dumpOnMiss("assertText", text)
                    throw StepFailure("画面里没有「$text」")
                }
            }

            // ---- 颜色 / 计数 ----
            "ifCount", "assertCount" -> {
                val n = countBlobs(step)
                val hit = compareWith(n, step)
                val expect = "${step.compare ?: ">="}${step.value}"
                log("$label 数到 $n 个（判断 $expect → ${if (hit) "成立" else "不成立"}）")
                if (step.op == "assertCount") {
                    if (!hit) throw StepFailure("数量不符合预期：数到 $n 个，要求 $expect")
                } else if (hit) {
                    execSteps(step.then.orEmpty(), depth + 1)
                } else {
                    execSteps(step.elseSteps.orEmpty(), depth + 1)
                }
            }

            "waitColor" -> {
                if (!waitColor(step)) {
                    val target = step.ratio ?: 0.02
                    throw StepFailure("等不到颜色 ${step.color}（占比始终低于 $target）")
                }
            }

            // ---- OCR 读值 ----
            "readNumber" -> {
                val n = readNumber(step)
                    ?: throw StepFailure("在区域里读不到数字（OCR 没识别到纯数字文本）")
                setVar(step.to, PackExpr.Value.Num(n))
            }

            "readText" -> {
                val t = readText(step)
                if (t.isBlank()) throw StepFailure("在区域里读不到任何文字")
                setVar(step.to, PackExpr.Value.Str(t))
            }

            // ---- 滑到出现为止 ----
            "swipeUntil" -> {
                if (!swipeUntil(step)) {
                    throw StepFailure("滑了 ${maxTries(step)} 次仍然没看到目标")
                }
            }

            // ---- 变量 / 表达式 ----
            "setVar" -> {
                val name = step.to?.trim().orEmpty()
                if (name.isBlank()) throw StepFailure("缺少 to（要写到哪个变量）")
                setVar(name, evalExpr(step, label))
            }

            "ifExpr" -> {
                val hit = evalExpr(step, label).asBool()
                log("$label ${subst(step.expr)} → ${if (hit) "真" else "假"}")
                if (hit) execSteps(step.then.orEmpty(), depth + 1)
                else execSteps(step.elseSteps.orEmpty(), depth + 1)
            }

            "whileExpr" -> {
                var iter = 0
                while (evalExpr(step, label).asBool()) {
                    if (++iter > PackSandbox.MAX_WHILE_ITER) {
                        throw StepFailure("whileExpr 转了超过 ${PackSandbox.MAX_WHILE_ITER} 轮，判定死循环")
                    }
                    log("$label 第 $iter 轮")
                    execSteps(step.steps.orEmpty(), depth + 1)
                }
                log("$label 共执行 $iter 轮")
            }

            "repeat" -> {
                val times = repeatTimes(step)
                log("$label 重复 $times 次")
                repeat(times) { i ->
                    log("$label 第 ${i + 1}/$times 轮")
                    execSteps(step.steps.orEmpty(), depth + 1)
                }
            }

            "ifTemplate" -> {
                val hit = find(step, wait = false)
                if (hit != null) execSteps(step.then.orEmpty(), depth + 1)
                else execSteps(step.elseSteps.orEmpty(), depth + 1)
            }

            "ifText" -> {
                val text = subst(step.text).orEmpty()
                val hit = nav.hasText(text, cropOf(step))
                if (hit) execSteps(step.then.orEmpty(), depth + 1)
                else execSteps(step.elseSteps.orEmpty(), depth + 1)
            }

            "stop" -> throw StopFlow(step.ok ?: true)

            else -> throw StepFailure("未知动作 ${step.op}")
        }
    }

    // ==================== 原语 ====================

    private fun sleep(ms: Long) {
        if (ms <= 0) return
        nav.sleepInterruptible(ms)
    }

    private fun doSwipe(step: PackStep) {
        val (fx, fy) = pixel(step.fromX, step.fromY)
        val (tx, ty) = pixel(step.toX, step.toY)
        if (!auto.swipe(fx, fy, tx, ty, step.durationMs ?: 320L)) {
            throw StepFailure("滑动 ($fx,$fy)→($tx,$ty) 注入失败")
        }
    }

    /** 归一化坐标 → 实际帧像素 */
    private fun pixel(nx: Double?, ny: Double?): Pair<Int, Int> {
        val w = auto.width
        val h = auto.height
        val x = ((nx ?: 0.0) * w).toInt().coerceIn(0, w - 1)
        val y = ((ny ?: 0.0) * h).toInt().coerceIn(0, h - 1)
        return x to y
    }

    private fun cropOf(step: PackStep): ScreenNavigator.NormRect? =
        step.region?.takeIf { it.size == 4 }?.let {
            ScreenNavigator.NormRect(it[0].toFloat(), it[1].toFloat(), it[2].toFloat(), it[3].toFloat())
        }

    /**
     * 模板查找：`wait=false` 只查一次，`wait=true` 轮询到超时。
     *
     * 路径先过 [PackSandbox.assetRel]：越界或文件缺失直接判失败，
     * 不让它退化成「磁盘找不到 → 去 APK 内置里碰运气」。
     */
    private fun find(step: PackStep, wait: Boolean): ImageUtils.MatchResult? {
        val raw = subst(step.image) ?: return null
        val root = packRoot ?: return null
        val image = PackSandbox.assetRel(root, packId, raw)
            ?: throw StepFailure("模板路径非法或文件不存在：$raw（只允许 assets/ 下的文件）")

        val threshold = step.threshold ?: DEFAULT_THRESHOLD
        val region = cropOf(step)
        val timeout = (step.timeoutMs ?: DEFAULT_TIMEOUT_MS).coerceAtMost(budget.remainingMs())
        val needStable = (step.stable ?: 1).coerceAtLeast(1)

        if (!wait) {
            // 需要连续命中时，用极短的轮询窗口凑帧
            if (needStable <= 1) return nav.findInRegion(image, threshold, region)
            val deadline = System.currentTimeMillis() + 2_000
            var hit = 0
            while (System.currentTimeMillis() < deadline) {
                if (isCancelled()) return null
                if (nav.findInRegion(image, threshold, region) != null) {
                    if (++hit >= needStable) return nav.findInRegion(image, threshold, region)
                } else {
                    hit = 0
                }
                sleep(POLL_MS)
            }
            return null
        }

        val deadline = System.currentTimeMillis() + timeout
        var hit = 0
        while (System.currentTimeMillis() < deadline) {
            if (isCancelled()) return null
            val r = nav.findInRegion(image, threshold, region)
            if (r != null) {
                if (++hit >= needStable) return r
            } else {
                hit = 0
            }
            sleep(POLL_MS)
        }
        return null
    }

    /**
     * 匹配框中心换算成实际帧坐标。
     *
     * `MatchResult` 的 x/y 是在**归一化帧**（高度缩到 1080）上的坐标，
     * 必须先除以缩放系数才能拿去点击 —— 忘了这步会点偏到画面上方。
     */
    private fun centerOf(
        r: ImageUtils.MatchResult,
        offsetX: Double?,
        offsetY: Double?,
    ): Pair<Int, Int> {
        val sf = ImageUtils.BASE_HEIGHT.toDouble() / auto.height
        val cx = ((r.x + r.templateWidth * r.scale / 2) / sf).toInt() +
            ((offsetX ?: 0.0) * auto.width).toInt()
        val cy = ((r.y + r.templateHeight * r.scale / 2) / sf).toInt() +
            ((offsetY ?: 0.0) * auto.height).toInt()
        return cx to cy
    }

    /**
     * 等不到文字时把**当时画面上所有认出来的文字**打进日志。
     *
     * 没有这一步，失败之后只能在「OCR 漏检」和「界面根本不对」之间反复猜 ——
     * 实测就踩过：`等不到文字「188」`，看不出是列表没展开、目标在屏幕外，
     * 还是那行字被认成了别的。dump 一次全屏结果，日志里直接就有答案。
     *
     * 只在失败路径调用，不影响正常流程的耗时。
     */
    private fun dumpOnMiss(op: String, text: String?) {
        nav.dumpCurrentScreenText("$op 等不到「${text ?: ""}」")
    }

    private fun waitText(step: PackStep): Boolean {
        val text = subst(step.text) ?: return false
        val crop = cropOf(step)
        val timeout = (step.timeoutMs ?: DEFAULT_TIMEOUT_MS).coerceAtMost(budget.remainingMs())
        val deadline = System.currentTimeMillis() + timeout
        while (System.currentTimeMillis() < deadline) {
            if (isCancelled()) return false
            if (nav.hasText(text, crop)) return true
            sleep(POLL_MS)
        }
        return false
    }

    private fun repeatTimes(step: PackStep): Int {
        val raw = step.times as? JsonPrimitive ?: return 1
        raw.intOrNull?.let { return it.coerceIn(1, 20) }
        val text = subst(raw.content).orEmpty()
        return text.toIntOrNull()?.coerceIn(1, 20) ?: 1
    }

    private fun maxTries(step: PackStep): Int =
        (step.maxTries ?: DEFAULT_MAX_TRIES).coerceIn(1, MAX_MAX_TRIES)

    // ==================== 识别原语 ====================

    /**
     * 数区域内的颜色连通块。
     *
     * 这是「数星星」那类判断的通用化：给定区域 + 目标色 + 面积区间，
     * 数出有几块。尺度无关，所以比模板匹配更适合数图标。
     */
    private fun countBlobs(step: PackStep): Int {
        val bgr = parseColorBgr(step.color)
            ?: throw StepFailure("color 写错了：${step.color}（要 #RRGGBB 或 R,G,B）")
        val tolerance = (step.tolerance ?: DEFAULT_TOLERANCE).coerceIn(0, MAX_TOLERANCE)
        val toleranceSq = 3.0 * tolerance * tolerance
        val minArea = step.minArea ?: DEFAULT_MIN_AREA
        val maxArea = step.maxArea ?: DEFAULT_MAX_AREA
        val crop = cropOf(step)

        val n = nav.withFrameOcr { mat ->
            val rect = crop?.let {
                val (x, y, w, h) = it.toPixels(mat.cols(), mat.rows())
                org.opencv.core.Rect(x, y, w, h)
            }
            ImageUtils.countColorBlobs(
                src = mat,
                region = rect,
                bgr = bgr,
                toleranceSq = toleranceSq,
                minArea = minArea,
                maxArea = maxArea,
            )
        }
        return n ?: throw StepFailure("取不到画面（截图失败）")
    }

    /** 等区域内的颜色占比达到阈值。默认 0.02，也就是「有那一抹颜色」。 */
    private fun waitColor(step: PackStep): Boolean {
        val bgr = parseColorBgr(step.color)
            ?: throw StepFailure("color 写错了：${step.color}（要 #RRGGBB 或 R,G,B）")
        val tolerance = (step.tolerance ?: DEFAULT_TOLERANCE).coerceIn(0, MAX_TOLERANCE)
        val toleranceSq = 3.0 * tolerance * tolerance
        val target = step.ratio ?: 0.02
        val crop = cropOf(step)
        val timeout = (step.timeoutMs ?: DEFAULT_TIMEOUT_MS).coerceAtMost(budget.remainingMs())
        val deadline = System.currentTimeMillis() + timeout

        while (System.currentTimeMillis() < deadline) {
            if (isCancelled()) return false
            val ratio = nav.withFrameOcr { mat ->
                val rect = crop?.let {
                    val (x, y, w, h) = it.toPixels(mat.cols(), mat.rows())
                    org.opencv.core.Rect(x, y, w, h)
                }
                ImageUtils.colorRatio(mat, rect, bgr, toleranceSq)
            } ?: 0.0
            if (ratio >= target) return true
            sleep(POLL_MS)
        }
        return false
    }

    /** 读区域里的第一个整数，用来做「当前体力 42」这类判断。 */
    private fun readNumber(step: PackStep): Long? {
        val crop = cropOf(step) ?: throw StepFailure("readNumber 必须给 region")
        val timeout = (step.timeoutMs ?: DEFAULT_TIMEOUT_MS).coerceAtMost(budget.remainingMs())
        val deadline = System.currentTimeMillis() + timeout
        while (System.currentTimeMillis() < deadline) {
            if (isCancelled()) return null
            val hit = nav.readAllText(crop).firstNotNullOfOrNull { r ->
                NUMBER.find(r.normalized())?.value?.toLongOrNull()
            }
            if (hit != null) return hit
            sleep(POLL_MS)
        }
        return null
    }

    /** 读区域里的全部文字，拼成一行。 */
    private fun readText(step: PackStep): String {
        val crop = cropOf(step) ?: throw StepFailure("readText 必须给 region")
        val texts = nav.readAllText(crop).map { it.normalized() }.filter { it.isNotBlank() }
        return texts.joinToString(" ")
    }

    /**
     * 反复滑动直到目标出现。
     *
     * 这是「横滑找第 09 层」那类流程的通用化：给方向 + 上限次数，
     * 每次滑完等 [PackStep.intervalMs]，然后查模板或文字。
     * 找不到就返回 false（次数有上限，不会无限滑）。
     */
    private fun swipeUntil(step: PackStep): Boolean {
        val tries = maxTries(step)
        val wantImage = !step.image.isNullOrBlank()
        val wantText = !step.text.isNullOrBlank()
        if (!wantImage && !wantText) throw StepFailure("swipeUntil 至少要给 image 或 text")

        // 先看一眼当前画面，已经在了就不用滑
        if (probe(step, wantImage, wantText)) return true

        repeat(tries) { i ->
            if (isCancelled()) return false
            doSwipe(step)
            sleep((step.intervalMs ?: 400L).coerceAtLeast(50L))
            if (probe(step, wantImage, wantText)) {
                log("swipeUntil 第 ${i + 1} 次滑动后找到目标")
                return true
            }
        }
        return false
    }

    private fun probe(step: PackStep, wantImage: Boolean, wantText: Boolean): Boolean {
        if (wantImage && find(step, wait = false) != null) return true
        if (wantText) {
            val text = subst(step.text).orEmpty()
            if (text.isNotBlank() && nav.hasText(text, cropOf(step))) return true
        }
        return false
    }

    // ==================== 工具 ====================

    /**
     * 解析颜色：`#RRGGBB`、`RRGGBB`、`43,230,255`（**RGB 顺序**，人写的时候
     * 想的是 RGB；OpenCV 要 BGR，所以这里倒序）。
     */
    private fun parseColorBgr(raw: String?): DoubleArray? {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return null

        if (s.contains(',')) {
            val parts = s.split(',').map { it.trim() }
            if (parts.size != 3) return null
            val r = parts[0].toIntOrNull() ?: return null
            val g = parts[1].toIntOrNull() ?: return null
            val b = parts[2].toIntOrNull() ?: return null
            if (r !in 0..255 || g !in 0..255 || b !in 0..255) return null
            return doubleArrayOf(b.toDouble(), g.toDouble(), r.toDouble())
        }

        val hex = s.removePrefix("#")
        if (hex.length != 6) return null
        val r = hex.substring(0, 2).toIntOrNull(16) ?: return null
        val g = hex.substring(2, 4).toIntOrNull(16) ?: return null
        val b = hex.substring(4, 6).toIntOrNull(16) ?: return null
        return doubleArrayOf(b.toDouble(), g.toDouble(), r.toDouble())
    }

    /** 按 `compare` 比较数量。没给 `value` 时按「至少有一个」。 */
    private fun compareWith(count: Int, step: PackStep): Boolean {
        val target = step.value ?: return count > 0
        return when (step.compare ?: ">=") {
            ">=" -> count >= target
            ">" -> count > target
            "<=" -> count <= target
            "<" -> count < target
            "==" -> count == target
            "!=" -> count != target
            else -> count >= target
        }
    }

    private val NUMBER = Regex("""\d{1,6}""")

    /** 步骤主动结束流程（正常收工 / 主动判失败）。 */
    private class StopFlow(val ok: Boolean) : Exception()

    private class StepFailure(message: String) : Exception(message)
}
