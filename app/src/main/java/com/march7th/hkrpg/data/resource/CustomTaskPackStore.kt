package com.march7th.hkrpg.data.resource

import com.march7th.hkrpg.data.model.CustomTaskPack
import com.march7th.hkrpg.data.model.InstalledTaskPack
import com.march7th.hkrpg.data.model.PackExpr
import com.march7th.hkrpg.data.model.PackStep
import com.march7th.hkrpg.utils.JsonUtils
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream

/**
 * 自定义任务包的导入校验。
 *
 * 规范见 `docs/task-file.md` 第 5 节。这里的原则是**一次把所有问题都列出来**，
 * 而不是遇到第一个错误就返回 —— 手写 JSON 的人一次改一处、再导入一次，
 * 十轮就崩了。所以做全量扫描，最多报 [MAX_ERRORS] 条。
 *
 * 校验器不碰文件系统：图片是否存在由调用方把 zip 里实际存在的条目传进来，
 * 这样纯 JSON 的单测也能覆盖全部规则。
 */
object CustomTaskPackValidator {

    /** 一次最多报几条，再多就没人看了 */
    private const val MAX_ERRORS = 12

    /**
     * 白名单。新增原语要同时改执行器和这里，否则老 App 跑到一半才发现不认识。
     *
     * 这张表同时是**安全边界**：不在表里的 op 一律拒绝，所以"能不能执行任意代码"
     * 不取决于用户写得多巧妙，而取决于这里有没有那个动作。新增时先读
     * [com.march7th.hkrpg.starrail.engine.PackSandbox] 的类注释。
     */
    val KNOWN_OPS: Set<String> = setOf(
        // 基础操作
        "log", "wait",
        "tap", "longPress", "swipe",
        // 模板识别
        "waitTemplate", "tapTemplate", "assertTemplate",
        // OCR
        "waitText", "tapText", "assertText",
        "readNumber", "readText",
        // 颜色 / 计数
        "ifCount", "assertCount", "waitColor",
        // 滑到出现
        "swipeUntil",
        // 流程控制
        "repeat", "ifTemplate", "ifText",
        "setVar", "ifExpr", "whileExpr",
        "stop",
    )

    /**
     * 单条 `repeat` 的 times 上限。
     *
     * ⚠️ **总步数不再限制**（原来 200 步）。步骤数限制挡不住恶意流程
     * （真想毁号 9 步就够），却会把正常的长流程误杀 —— 一个跑完日常、
     * 领奖、再跑周常的包很容易超过 200 步。
     *
     * 这里保留的只是单条语法的 sanity check：`times` 写成 99999 多半是笔误。
     */
    private const val MAX_REPEAT = 500

    /**
     * 嵌套层数上限。和 [com.march7th.hkrpg.starrail.engine.PackSandbox.MAX_NEST_DEPTH]
     * 保持一致 —— 导入能过的包，运行期不该因为深度被拒。
     */
    private const val MAX_NEST_DEPTH = 4
    private const val MAX_TIMEOUT_MS = 120_000L
    private const val MAX_WAIT_MS = 60_000L

    /**
     * `{key}` 占位符。
     *
     * **结尾的 `}` 必须转义**：`\{` 已经被转义成普通字符 `{`，后面留一个裸 `}`，
     * Android 的 ICU 正则引擎会把它当量词结束符，直接抛
     * `PatternSyntaxException: Syntax error in regexp pattern near index 18`。
     *
     * 这个异常发生在 `object` 的 `<clinit>` 里，症状是**一导入就整进程崩**
     * （`ExceptionInInitializerError`），而不是「校验失败」。JVM 的
     * `java.util.regex` 对裸 `}` 宽容，所以本地单测和编译都拦不住 ——
     * 改这类正则时务必在真机上导一次包。
     */
    private val PLACEHOLDER = Regex("""\{([A-Za-z0-9_]+)\}""")

    /**
     * @param appVersionCode 当前 App 的 versionCode，用来卡 `minAppVersionCode`
     * @param availableAssets zip 里实际存在的 `assets/...` 条目（纯 JSON 传空集）
     */
    fun validate(
        pack: CustomTaskPack,
        appVersionCode: Int,
        availableAssets: Set<String>,
    ): List<String> {
        val errors = mutableListOf<String>()
        fun err(msg: String) {
            if (errors.size < MAX_ERRORS) errors += msg
        }

        // ---- 顶层 ----
        if (pack.schemaVersion != 1) {
            err("schemaVersion 必须是 1（当前 ${pack.schemaVersion}）")
        }
        if (pack.id.isBlank()) err("缺少 id")
        else if (!ID_PATTERN.matches(pack.id)) {
            err("id 只能用字母、数字、点、下划线、连字符：${pack.id}")
        }
        if (pack.name.isBlank()) err("缺少 name")
        else if (pack.name.length > 24) err("name 超过 24 字")
        if (pack.author.isBlank()) err("缺少 author（署名不能空）")
        else if (pack.author.length > 32) err("author 超过 32 字")
        if (pack.version.isBlank()) err("缺少 version")
        if (pack.description.length > 120) err("description 超过 120 字")

        if (pack.minAppVersionCode > appVersionCode) {
            err(
                "这个任务包要求 App versionCode >= ${pack.minAppVersionCode}，" +
                    "当前只有 $appVersionCode，请先更新 App"
            )
        }

        // ---- 参数 ----
        val paramKeys = mutableSetOf<String>()
        pack.params.forEachIndexed { i, p ->
            if (p.key.isBlank()) err("params[$i] 缺少 key")
            else if (!KEY_PATTERN.matches(p.key)) err("params[$i].key 只能含字母数字下划线：${p.key}")
            else if (!paramKeys.add(p.key)) err("params[$i].key 重复：${p.key}")

            if (p.type !in setOf("int", "bool", "string")) {
                err("params[$i].type 只能是 int / bool / string（当前 ${p.type}）")
            }
            if (p.type == "int") {
                val lo = p.min
                val hi = p.max
                if (lo != null && hi != null && lo > hi) err("params[$i] 的 min 大于 max")
            }
        }

        // ---- 步骤 ----
        if (pack.steps.isEmpty()) err("steps 不能为空")

        validateSteps(pack.steps, "steps", 1, paramKeys, availableAssets) { msg -> err(msg) }

        return errors
    }

    private fun validateSteps(
        steps: List<PackStep>,
        path: String,
        depth: Int,
        paramKeys: Set<String>,
        assets: Set<String>,
        err: (String) -> Unit,
    ) {
        if (depth > MAX_NEST_DEPTH) {
            err("$path 嵌套超过 $MAX_NEST_DEPTH 层")
            return
        }

        steps.forEachIndexed { i, s ->
            val at = "$path[$i]"
            if (s.op !in KNOWN_OPS) {
                err(
                    if (s.op.isBlank()) "$at 缺少 op" else "$at 未知动作「${s.op}」"
                )
                return@forEachIndexed
            }
            val label = "$at ${s.op}"

            // 所有字符串字段里的 {key} 引用都要有对应参数
            listOfNotNull(s.text, s.image).forEach { raw ->
                PLACEHOLDER.findAll(raw).forEach { m ->
                    val key = m.groupValues[1]
                    if (key !in paramKeys) err("$label 引用了未声明的参数 {$key}")
                }
            }

            when (s.op) {
                "log" -> if (s.text.isNullOrBlank()) err("$label 缺少 text")
                "wait" -> {
                    val ms = s.ms
                    if (ms == null) {
                        err("$label 缺少 ms")
                    } else {
                        if (ms <= 0) err("$label 的 ms 必须大于 0")
                        if (ms > MAX_WAIT_MS) err("$label 的 ms 超过 ${MAX_WAIT_MS}ms")
                    }
                }

                "tap", "longPress" -> {
                    checkNorm(label, "x", s.x, err)
                    checkNorm(label, "y", s.y, err)
                }

                "swipe" -> {
                    checkNorm(label, "fromX", s.fromX, err)
                    checkNorm(label, "fromY", s.fromY, err)
                    checkNorm(label, "toX", s.toX, err)
                    checkNorm(label, "toY", s.toY, err)
                }

                "waitTemplate", "tapTemplate", "assertTemplate", "ifTemplate" -> {
                    checkImage(label, s.image, assets, err)
                    checkThreshold(label, s.threshold, err)
                    checkRegion(label, s.region, err)
                }

                "waitText", "tapText", "assertText", "ifText" -> {
                    if (s.text.isNullOrBlank()) err("$label 缺少 text")
                    checkRegion(label, s.region, err)
                }

                // ---- OCR 读值 ----
                "readNumber", "readText" -> {
                    if (s.region == null) err("$label 需要 region（指定读哪一块）")
                    else checkRegion(label, s.region, err)
                    checkVarName(label, "to", s.to, err)
                }

                // ---- 颜色 / 计数 ----
                "ifCount", "assertCount" -> {
                    if (s.region == null) err("$label 需要 region（指定数哪一块）")
                    else checkRegion(label, s.region, err)
                    checkColor(label, s.color, s.tolerance, err)
                    checkIntRange(label, "minArea", s.minArea, 1, 200_000, err)
                    checkIntRange(label, "maxArea", s.maxArea, 1, 200_000, err)
                    checkCompare(label, s.compare, err)
                    if (s.value == null) err("$label 缺少 value（数到几个算命中）")
                    if (s.op == "ifCount") {
                        validateBranch(s, at, "$label", depth, paramKeys, assets, err)
                    }
                }

                "waitColor" -> {
                    checkColor(label, s.color, s.tolerance, err)
                    checkRegion(label, s.region, err)
                    val r = s.ratio
                    if (r != null && (r <= 0.0 || r > 1.0)) {
                        err("$label 的 ratio 必须在 0~1 之间：$r")
                    }
                }

                // ---- 滑到出现 ----
                "swipeUntil" -> {
                    if (s.image.isNullOrBlank() && s.text.isNullOrBlank()) {
                        err("$label 至少要给 image 或 text（要滑到出现什么）")
                    }
                    if (!s.image.isNullOrBlank()) checkImage(label, s.image, assets, err)
                    checkNorm(label, "fromX", s.fromX, err)
                    checkNorm(label, "fromY", s.fromY, err)
                    checkNorm(label, "toX", s.toX, err)
                    checkNorm(label, "toY", s.toY, err)
                    checkIntRange(label, "maxTries", s.maxTries, 1, 30, err)
                    checkIntRange(
                        label, "intervalMs",
                        s.intervalMs?.toInt(), 50, 5_000, err,
                    )
                }

                // ---- 变量 / 表达式 ----
                "setVar" -> {
                    checkVarName(label, "to", s.to, err)
                    checkExpr(label, s.expr, err)
                }

                "ifExpr" -> {
                    checkExpr(label, s.expr, err)
                    validateBranch(s, at, label, depth, paramKeys, assets, err)
                }

                "whileExpr" -> {
                    checkExpr(label, s.expr, err)
                    val sub = s.steps
                    if (sub.isNullOrEmpty()) err("$label 缺少 steps") else {
                        validateSteps(sub, "$at.steps", depth + 1, paramKeys, assets, err)
                    }
                }

                "repeat" -> {
                    val times = s.times
                    if (times == null) {
                        err("$label 缺少 times")
                    } else {
                        val literal = (times as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
                        val asText = (times as? JsonPrimitive)?.content?.trim().orEmpty()
                        if (literal != null) {
                            if (literal < 1) err("$label 的 times 必须 >= 1")
                            if (literal > MAX_REPEAT) err("$label 的 times 超过 $MAX_REPEAT")
                        } else if (asText.startsWith("{") && asText.endsWith("}")) {
                            val key = asText.removeSurrounding("{", "}")
                            if (key !in paramKeys) err("$label 引用了未声明的参数 {$key}")
                        } else {
                            err("$label 的 times 只能是整数或 {参数}")
                        }
                    }
                    val sub = s.steps
                    if (sub.isNullOrEmpty()) err("$label 缺少 steps") else {
                        validateSteps(sub, "$at.steps", depth + 1, paramKeys, assets, err)
                    }
                }

                "ifTemplate", "ifText" -> {
                    val then = s.then
                    if (then.isNullOrEmpty()) err("$label 缺少 then") else {
                        validateSteps(then, "$at.then", depth + 1, paramKeys, assets, err)
                    }
                    s.elseSteps?.takeIf { it.isNotEmpty() }?.let {
                        validateSteps(it, "$at.else", depth + 1, paramKeys, assets, err)
                    }
                }

                "stop" -> Unit
            }

            val timeout = s.timeoutMs
            if (timeout != null && timeout > MAX_TIMEOUT_MS) {
                err("$label 的 timeoutMs 超过 ${MAX_TIMEOUT_MS}ms")
            }
            val stable = s.stable
            if (stable != null && (stable < 1 || stable > 5)) err("$label 的 stable 只能是 1~5")
        }
    }

    private fun checkNorm(label: String, field: String, v: Double?, err: (String) -> Unit) {
        if (v == null) {
            err("$label 缺少 $field")
            return
        }
        if (v < 0.0 || v > 1.0) err("$label 的 $field 必须在 0~1 之间（归一化坐标）：$v")
    }

    private fun checkThreshold(label: String, v: Double?, err: (String) -> Unit) {
        if (v == null) return
        if (v < 0.3 || v > 1.0) err("$label 的 threshold 应在 0.3~1.0：$v")
    }

    private fun checkRegion(label: String, region: List<Double>?, err: (String) -> Unit) {
        if (region == null) return
        if (region.size != 4) {
            err("$label 的 region 必须是 [x, y, w, h] 四个数")
            return
        }
        region.forEachIndexed { i, v ->
            if (v < 0.0 || v > 1.0) err("$label 的 region[$i] 必须在 0~1 之间：$v")
        }
        if (region[2] <= 0.0 || region[3] <= 0.0) err("$label 的 region 宽高必须大于 0")
        if (region[0] + region[2] > 1.001 || region[1] + region[3] > 1.001) {
            err("$label 的 region 超出画面")
        }
    }

    private fun checkImage(label: String, image: String?, assets: Set<String>, err: (String) -> Unit) {
        if (image.isNullOrBlank()) {
            err("$label 缺少 image")
            return
        }
        if (!image.startsWith("assets/")) {
            err("$label 的 image 必须以 assets/ 开头：$image")
            return
        }
        if (image.contains("..")) {
            err("$label 的 image 路径不能含 ..：$image")
            return
        }
        if (image in assets) return
        // 纯 JSON 包里没有 assets，报的错要能说明原因
        if (assets.isEmpty()) {
            err("$label 引用了 $image，但这不是任务包（没有 assets 目录）")
        } else {
            err("$label 引用的图片不存在：$image")
        }
    }

    /**
     * 分支类动作的 then / else 校验。
     *
     * `ifCount` / `ifExpr` 和 `ifTemplate` / `ifText` 的规则完全一样，
     * 抽出来是为了新增分支动作时不会漏掉嵌套校验（漏了就等于绕过了步数预算）。
     */
    private fun validateBranch(
        s: PackStep,
        at: String,
        label: String,
        depth: Int,
        paramKeys: Set<String>,
        assets: Set<String>,
        err: (String) -> Unit,
    ) {
        val then = s.then
        if (then.isNullOrEmpty()) err("$label 缺少 then") else {
            validateSteps(then, "$at.then", depth + 1, paramKeys, assets, err)
        }
        s.elseSteps?.takeIf { it.isNotEmpty() }?.let {
            validateSteps(it, "$at.else", depth + 1, paramKeys, assets, err)
        }
    }

    private fun checkVarName(label: String, field: String, v: String?, err: (String) -> Unit) {
        if (v.isNullOrBlank()) {
            err("$label 缺少 $field（结果写进哪个变量）")
            return
        }
        if (!VAR_PATTERN.matches(v)) err("$label 的 $field 只能含字母数字下划线：$v")
    }

    private fun checkColor(label: String, color: String?, tolerance: Int?, err: (String) -> Unit) {
        if (color.isNullOrBlank()) {
            err("$label 缺少 color")
            return
        }
        if (!isValidColor(color)) {
            err("$label 的 color 要写成 #RRGGBB、RRGGBB 或 R,G,B（0~255）：$color")
        }
        if (tolerance != null && (tolerance < 0 || tolerance > 128)) {
            err("$label 的 tolerance 只能是 0~128")
        }
    }

    private fun isValidColor(raw: String): Boolean {
        val s = raw.trim()
        if (s.contains(',')) {
            val parts = s.split(',').map { it.trim() }
            if (parts.size != 3) return false
            return parts.all { p -> (p.toIntOrNull() ?: return false) in 0..255 }
        }
        val hex = s.removePrefix("#")
        return hex.length == 6 && hex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
    }

    private fun checkCompare(label: String, v: String?, err: (String) -> Unit) {
        if (v != null && v !in COMPARE_OPS) {
            err("$label 的 compare 只能是 ${COMPARE_OPS.joinToString(" ")}：$v")
        }
    }

    private fun checkExpr(label: String, expr: String?, err: (String) -> Unit) {
        if (expr.isNullOrBlank()) {
            err("$label 缺少 expr")
            return
        }
        PackExpr.checkSyntax(expr)?.let { err("$label 的表达式有问题：$it") }
    }

    private fun checkIntRange(
        label: String,
        field: String,
        v: Int?,
        lo: Int,
        hi: Int,
        err: (String) -> Unit,
    ) {
        if (v == null) return
        if (v < lo || v > hi) err("$label 的 $field 应在 $lo~$hi：$v")
    }

    private val ID_PATTERN = Regex("^[A-Za-z0-9._-]{3,64}$")
    private val KEY_PATTERN = Regex("^[A-Za-z0-9_]{1,32}$")
    private val VAR_PATTERN = Regex("^[A-Za-z_][A-Za-z0-9_]{0,31}$")
    private val COMPARE_OPS = setOf(">=", ">", "<=", "<", "==", "!=")
}

/** 导入结果。 */
sealed interface TaskPackInstallResult {
    data class Success(val pack: InstalledTaskPack, val upgraded: Boolean) : TaskPackInstallResult
    data class Invalid(val errors: List<String>) : TaskPackInstallResult
}

/**
 * 任务包的安装 / 列举 / 删除。
 *
 * ## 落在哪个目录
 *
 * 落在 MAA 根目录下的 `taskpacks/<id>/`，和其它用户文件一样由
 * [com.march7th.hkrpg.domain.service.CoreDataPusher] 在任务下发前整体投递给
 * 提权进程 —— 执行器跑在 core 侧，读不到 App 的 filesDir。
 *
 * 目录名统一用 [com.march7th.hkrpg.remote.CoreDataDir.TASKPACKS_DIR]：
 * 崩铁内核的根目录恒为 `/data/local/tmp/maameow`，两种 coreDataLocation
 * 模式下都要投递，所以**不能**只靠 USER_DATA_DIRS。
 */
class CustomTaskPackStore(
    private val rootDirProvider: () -> File,
) {
    companion object {
        /** 子目录名，与 [com.march7th.hkrpg.remote.CoreDataDir.TASKPACKS_DIR] 同源 */
        const val DIR_NAME = com.march7th.hkrpg.remote.CoreDataDir.TASKPACKS_DIR

        /** 单包上限（解压后总大小），与远端下载上限一致。 */
        private const val MAX_TOTAL_BYTES = 500L * 1024 * 1024

        /** 单个文件上限。 */
        private const val MAX_ENTRY_BYTES = 64L * 1024 * 1024

        /** 安装时的临时目录前缀。`.tmp-xxx`，装完改名成正式目录 */
        private const val STAGING_PREFIX = ".tmp-"
        private const val MAX_ENTRIES = 200

        private val ZIP_MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04)

        /**
         * App 侧 MAA 根目录。
         *
         * 和 [com.march7th.hkrpg.data.config.CorePathConfig.rootDir] 同一个位置；
         * 这里单独给一份是为了让 UI 层（任务卡片的选包面板）不必为读一个列表
         * 去依赖 CorePathConfig → AppSettingsManager 那条链。
         */
        fun appRootDir(context: android.content.Context): File =
            File(context.getExternalFilesDir(null), com.march7th.hkrpg.constant.M7aFiles.MAA)
    }

    private val json = Json(JsonUtils.common) { ignoreUnknownKeys = true }

    val packsDir: File
        get() = File(rootDirProvider(), DIR_NAME)

    /** 包目录（core 侧相对 MAA 根）。执行器用这个拼模板路径。 */
    fun coreRelativeDir(id: String): String = "$DIR_NAME/$id"

    fun list(): List<InstalledTaskPack> {
        val dir = packsDir
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles().orEmpty()
            .filter { it.isDirectory }
            .mapNotNull { d -> runCatching { readPack(d) }.getOrNull() }
            .sortedBy { it.name.lowercase() }
    }

    fun readPack(dir: File): InstalledTaskPack? {
        val manifest = File(dir, "task.json")
        if (!manifest.isFile) return null
        val pack = runCatching {
            json.decodeFromString(CustomTaskPack.serializer(), manifest.readText(Charsets.UTF_8))
        }.getOrNull() ?: return null
        return InstalledTaskPack(
            id = pack.id.ifBlank { dir.name },
            name = pack.name,
            author = pack.author,
            version = pack.version,
            description = pack.description,
            stepCount = pack.steps.size,
            params = pack.params,
            dir = dir,
        )
    }

    fun delete(id: String): Boolean {
        val dir = File(packsDir, id)
        if (!dir.isDirectory) return false
        return dir.deleteRecursively()
    }

    /**
     * 导入一份任务包（zip 或纯 json）。
     *
     * 全程在内存里先解完、校验通过后才落盘：校验失败的包不会留下半个目录。
     */
    fun install(sourceName: String, bytes: ByteArray, appVersionCode: Int): TaskPackInstallResult {
        if (bytes.isEmpty()) {
            return TaskPackInstallResult.Invalid(listOf("文件是空的（0 字节）"))
        }

        // 解压直接写临时目录，不整包读进内存。全部读入时峰值约是包体积的两倍，
        // 单包上限放宽到 500MB 后会 OOM。校验仍在改名前做，失败只留临时目录。
        val staging = File(packsDir, "$STAGING_PREFIX${System.nanoTime()}")
        try {
            if (!staging.mkdirs()) {
                return TaskPackInstallResult.Invalid(
                    listOf("无法创建临时目录 ${staging.absolutePath}（检查存储权限与剩余空间）")
                )
            }

            val isZip = bytes.size >= 4 && bytes.copyOfRange(0, 4).contentEquals(ZIP_MAGIC)
            if (isZip) {
                runCatching { unzipTo(bytes, staging) }.getOrElse { e ->
                    return TaskPackInstallResult.Invalid(
                        listOf("解压失败：${e.message ?: "不是有效的 zip"}")
                    )
                }
            } else {
                File(staging, "task.json").writeBytes(bytes)
            }

            val jsonFile = File(staging, "task.json")
            if (!jsonFile.isFile) {
                return TaskPackInstallResult.Invalid(
                    listOf("压缩包里没有 task.json（必须放在根目录）")
                )
            }
            val jsonBytes = jsonFile.readBytes()

            val pack = runCatching {
                json.decodeFromString(
                    CustomTaskPack.serializer(),
                    jsonBytes.toString(Charsets.UTF_8),
                )
            }.getOrElse { e ->
                return TaskPackInstallResult.Invalid(
                    listOf("task.json 解析失败：${e.message?.lineSequence()?.firstOrNull() ?: "JSON 格式不对"}")
                )
            }

            // 校验只需要"有哪些素材文件"，不需要内容
            val assets = staging.walkTopDown()
                .filter { it.isFile }
                .map { it.relativeTo(staging).path.replace('\\', '/') }
                .filter { it != "task.json" }
                .toSet()

            val errors = CustomTaskPackValidator.validate(pack, appVersionCode, assets)
            if (errors.isNotEmpty()) return TaskPackInstallResult.Invalid(errors)

            // ---- 落盘：临时目录直接改名 ----
            val target = File(packsDir, pack.id)
            val upgraded = target.isDirectory
            runCatching {
                if (upgraded) target.deleteRecursively()
                if (!staging.renameTo(target)) error("无法写入 ${target.absolutePath}")
            }.onFailure { e ->
                return TaskPackInstallResult.Invalid(
                    listOf("写入失败：${e.message}（检查存储权限与剩余空间）")
                )
            }

            val installed = InstalledTaskPack(
                id = pack.id,
                name = pack.name,
                author = pack.author,
                version = pack.version,
                description = pack.description,
                stepCount = pack.steps.size,
                params = pack.params,
                dir = target,
            )
            return TaskPackInstallResult.Success(installed, upgraded)
        } finally {
            // 成功时已经改名走了，这里删的是不存在的路径；失败时负责收尸
            runCatching { staging.deleteRecursively() }
        }
    }

    /**
     * 把 zip 流式解到 [dest]。
     *
     * 单个 entry 边读边写，超限立刻抛 —— 不把整包攒在内存里。
     * 路径穿越（绝对路径、`..`）在这里挡掉，别等写出去再检查。
     */
    private fun unzipTo(bytes: ByteArray, dest: File) {
        var total = 0L
        var entries = 0
        val buf = ByteArray(64 * 1024)
        ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                if (++entries > MAX_ENTRIES) error("压缩包内文件超过 $MAX_ENTRIES 个")

                val name = entry.name.replace('\\', '/').removePrefix("./")
                if (name.startsWith("/") || name.split('/').any { it == ".." } || name.isBlank()) {
                    error("压缩包内含非法路径：$name")
                }

                val out = File(dest, name)
                out.parentFile?.mkdirs()
                out.outputStream().use { os ->
                    var written = 0L
                    while (true) {
                        val n = zip.read(buf)
                        if (n <= 0) break
                        written += n
                        total += n
                        if (written > MAX_ENTRY_BYTES) {
                            error("$name 超过 ${MAX_ENTRY_BYTES / 1024 / 1024}MB")
                        }
                        if (total > MAX_TOTAL_BYTES) {
                            error("压缩包解压后超过 ${MAX_TOTAL_BYTES / 1024 / 1024}MB")
                        }
                        os.write(buf, 0, n)
                    }
                }
            }
        }
    }

}
