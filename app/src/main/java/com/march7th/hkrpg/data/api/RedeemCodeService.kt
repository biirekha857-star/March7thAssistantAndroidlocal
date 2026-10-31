package com.march7th.hkrpg.data.api

import android.content.Context
import com.march7th.hkrpg.constant.M7aApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/**
 * 一个兑换码。
 *
 * @param code 码本身（纯字母数字）
 * @param group 分组名（站点上这一节的标题，如「3.8 前瞻直播」）；空串 = 未命名
 * @param expiresAt 截止日期；`null` = 长期有效或没写日期
 * @param custom 是否是用户自己加的码（不来自站点）
 */
data class RedeemCode(
    val code: String,
    val group: String = "",
    val expiresAt: LocalDate? = null,
    val custom: Boolean = false,
) {
    /**
     * 分组标识。
     *
     * 同名分组可能出现在不同批次（两次直播都叫「前瞻直播」），
     * 所以用「名字 + 截止日」一起做 key，避免 UI 把两批码并到一组里。
     */
    val sectionKey: String get() = "$group|${expiresAt ?: "-"}"

    /** 到今天为止是否已经过期。 */
    fun isExpired(today: LocalDate = LocalDate.now()): Boolean =
        expiresAt != null && today.isAfter(expiresAt)

    /** 距离截止还有几天；`null` = 长期。今天到期返回 0。 */
    fun daysLeft(today: LocalDate = LocalDate.now()): Long? =
        expiresAt?.let { java.time.temporal.ChronoUnit.DAYS.between(today, it) }
}

/**
 * 兑换码清单的拉取与解析。
 *
 * # 数据流（和公告同构）
 *
 * ```
 * https://m7a.pages.dev/codes.md
 *      ↓ 成功
 * 写入 files/redeem/codes.md → 半小时内直接用缓存
 *      ↓ 失败（断网 / 站点没部署）
 * 读缓存（哪怕过期） → 再不行返回空列表
 * ```
 *
 * **不做 APK 内置兜底**：公告里有关键使用说明，空白不可接受；
 * 兑换码是运营侧随时变的内容，内置一份只会让人兑到一堆失效码。
 *
 * # 为什么解析器要用「宽容」而不是「严格」
 *
 * 这个文件是手工维护的，格式错一点点（多一个缩进、用了中文逗号、
 * 日期写成 `2026.11.15`）就不该整份作废 —— 那样用户会以为"没有新码"。
 * 所以：
 * - 认不出日期的分节按「长期」处理；
 * - 列表符号 `-` `*` 可有可无，裸写一行也认；
 * - 不像码的短行直接跳过，不影响其它行。
 */
class RedeemCodeService(
    private val context: Context,
    private val httpClient: OkHttpClient,
) {
    companion object {
        private const val TAG = "RedeemCodeService"
        private const val CACHE_DIR = "redeem"
        private const val CACHE_FILE = "codes.md"

        /** 缓存有效期：半小时。运营加码是当天的事，没必要每次启动都拉。 */
        private const val CACHE_TTL_MS = 30 * 60 * 1000L

        private const val TIMEOUT_SEC = 10L

        /**
         * 兑换码允许的字符。
         *
         * 一直以为只有字母数字，直到实测遇到「永远不会抛弃你」这种中文码 ——
         * 只认 ASCII 会把整条码静默丢掉，用户看到的只是"少兑了一个"。
         * 所以放宽到字母、数字、汉字（顺带日文假名/韩文，国际服可能用），
         * 但不允许空格和标点：那些是说明文字的特征。
         *
         * 长度下限取 2：短码确实存在，而 4 位会把它们误杀。
         */
        private val CODE_PATTERN =
            Regex("^[A-Za-z0-9\\u4E00-\\u9FFF\\u3040-\\u30FF\\uAC00-\\uD7AF]{2,32}$")

        /** 纯 ASCII 的码允许裸写一行；含中文的必须带列表符号，避免把说明文字当码。 */
        private val ASCII_ONLY_PATTERN = Regex("^[A-Za-z0-9]+$")

        /** 分节标题里的日期，只认 `YYYY-MM-DD`。 */
        private val DATE_PATTERN = Regex("""\d{4}-\d{1,2}-\d{1,2}""")

        /**
         * 标题里用来分隔「分组名」和「日期 / 有效期」的符号。
         *
         * `## 3.8 前瞻直播 · 2026-11-15`、`## 社群福利 | 长期` 两种写法都常见，
         * 所以把能想到的分隔符都算上，去掉日期后再把两头的符号削干净。
         */
        private const val GROUP_SEPARATORS = "·・|｜/／:：-—–,，、"

        /** 标题里表示「永不过期」的词。 */
        private val ETERNAL_WORDS = listOf("长期", "永久", "常驻", "不限时", "长期有效")

        private val COMMENT_PATTERN = Regex("""<!--.*?-->""", RegexOption.DOT_MATCHES_ALL)

        /**
         * 把分节标题拆成「分组名 + 截止日」。
         *
         * ```
         * ## 2026-11-15              → (""          , 2026-11-15)
         * ## 长期                     → (""          , null)
         * ## 3.8 前瞻直播 · 2026-11-15 → ("3.8 前瞻直播", 2026-11-15)
         * ## 社群福利                  → ("社群福利"     , null)
         * ```
         *
         * 认不出的写法一律当「长期」，只是分组名会保留原文 —— 宁可多兑一个
         * 可能过期的码，也别把整节码当成过期静默丢掉。
         */
        private fun parseHeading(title: String): Pair<String, LocalDate?> {
            val dateText = DATE_PATTERN.find(title)?.value
            val date = dateText?.split('-')?.let { parts ->
                runCatching {
                    LocalDate.of(parts[0].toInt(), parts[1].toInt(), parts[2].toInt())
                }.getOrNull()
            }

            var group = title
            if (dateText != null) group = group.replace(dateText, " ")
            ETERNAL_WORDS.forEach { group = group.replace(it, " ") }
            group = group.trim { it.isWhitespace() || it in GROUP_SEPARATORS }

            return group to date
        }

        /**
         * 解析站点上的 `codes.md`。
         *
         * @param markdown 文件内容
         * @param today 用来判断过期（测试时可注入固定日期）
         * @return 未过期的码，按文件里的顺序去重
         */
        fun parse(markdown: String, today: LocalDate = LocalDate.now()): List<RedeemCode> {
            // 先把 HTML 注释整段删掉 —— 注释里可能举了 `- ABC123` 这种例子，
            // 不删就会被人当成真码兑一遍。
            val body = COMMENT_PATTERN.replace(markdown, "")

            var sectionGroup = ""
            var sectionExpiry: LocalDate? = null
            val out = LinkedHashMap<String, RedeemCode>()

            for (raw in body.lineSequence()) {
                val line = raw.trim()
                if (line.isEmpty()) continue

                // 分节标题：`## 3.8 前瞻直播 · 2026-11-15` / `## 长期`
                //
                // 只认 `##` 及以上：文档大标题 `# 兑换码` 不是分组，
                // 否则第一组码会被塞进一个叫「兑换码」的组里。
                if (line.startsWith("##")) {
                    val (group, expiry) = parseHeading(line.trimStart('#').trim())
                    sectionGroup = group
                    sectionExpiry = expiry
                    continue
                }
                // 单个 `#` 是文档标题，忽略
                if (line.startsWith("#")) continue

                // 列表项去掉符号；裸行原样当候选
                val isList = line.startsWith("-") || line.startsWith("*")
                val candidate = line
                    .removePrefix("- ")
                    .removePrefix("* ")
                    .removePrefix("-")
                    .trim()

                if (!CODE_PATTERN.matches(candidate)) {
                    // 说明性文字（"有效期至…"、分隔线）走到这里，跳过即可
                    continue
                }
                // 含中文/假名/韩文的码必须带列表符号：
                // 不然「有效期至年底」这种纯中文说明行会被当成兑换码。
                if (!isList && !ASCII_ONLY_PATTERN.matches(candidate)) continue

                val upper = candidate.uppercase()
                if (out.containsKey(upper)) continue

                val item = RedeemCode(upper, sectionGroup, sectionExpiry)
                if (item.isExpired(today)) {
                    Timber.tag(TAG).d("跳过已过期兑换码 %s（%s）", upper, sectionExpiry)
                    continue
                }
                out[upper] = item
            }
            return out.values.toList()
        }
    }

    private val cacheDir: File by lazy {
        File(context.filesDir, CACHE_DIR).apply { mkdirs() }
    }

    /**
     * 上一次 [load] 是否没能从站点拿到内容。
     *
     * 给配置面板显示「获取失败，正在用本地缓存」用。`load` 自己不会抛，
     * 失败时只是返回缓存或空列表 —— 调用方没法从返回值区分「站点上没有码」
     * 和「站点挂了」。
     */
    @Volatile
    var lastLoadFailed: Boolean = false
        private set

    /**
     * 读取兑换码。
     *
     * @param forceRefresh 跳过缓存直接拉远程
     * @param today 判断过期用的"今天"（测试注入）
     */
    suspend fun load(
        forceRefresh: Boolean = false,
        today: LocalDate = LocalDate.now(),
    ): List<RedeemCode> = withContext(Dispatchers.IO) {
        val cacheFile = File(cacheDir, CACHE_FILE)
        lastLoadFailed = false

        if (!forceRefresh && cacheFile.isFile &&
            System.currentTimeMillis() - cacheFile.lastModified() < CACHE_TTL_MS
        ) {
            val cached = runCatching { parse(cacheFile.readText(), today) }.getOrDefault(emptyList())
            if (cached.isNotEmpty()) {
                Timber.tag(TAG).d("用缓存 %d 个兑换码", cached.size)
                return@withContext cached
            }
        }

        val remote = fetchRemote()
        if (remote == null) lastLoadFailed = true
        if (remote != null) {
            runCatching { cacheFile.writeText(remote) }
            val list = parse(remote, today)
            if (list.isEmpty()) {
                Timber.tag(TAG).w(
                    "站点清单解析出 0 个未过期码（原文 %d 字）—— 检查 codes.md 是否只有标题、或所有分节日期都已过期",
                    remote.length,
                )
            } else {
                Timber.tag(TAG).i("从站点拉到 %d 个未过期兑换码", list.size)
            }
            return@withContext list
        }

        // 远程失败 → 用旧缓存（哪怕超过 TTL）
        if (cacheFile.isFile) {
            val cached = runCatching { parse(cacheFile.readText(), today) }.getOrDefault(emptyList())
            Timber.tag(TAG).w("拉取失败，回退旧缓存 %d 个", cached.size)
            return@withContext cached
        }

        Timber.tag(TAG).w("拉取失败且没有缓存，返回空列表")
        emptyList()
    }

    private fun fetchRemote(): String? = runCatching {
        val client = httpClient.newBuilder()
            .connectTimeout(TIMEOUT_SEC, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SEC, TimeUnit.SECONDS)
            .build()
        val req = Request.Builder().url(M7aApi.REDEEM_CODES).get().build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                Timber.tag(TAG).w("兑换码清单 HTTP %d", resp.code)
                return null
            }
            val contentType = resp.header("Content-Type").orEmpty()
            val body = resp.body?.string()
            if (body.isNullOrBlank()) {
                Timber.tag(TAG).w("兑换码清单内容为空")
                return null
            }
            // Cloudflare Pages 对不存在的路径**不返回 404**，而是把 index.html
            // 一起 200 发回来。所以「HTTP 200」不代表文件存在：不拦下来的话，
            // 整页 HTML 会被当成 markdown 解析成 0 个码，界面上只会显示
            // 「没有可兑换的码」，完全看不出是站点没部署。
            if (isHtml(contentType, body)) {
                Timber.tag(TAG).w(
                    "兑换码清单返回的是 HTML 而不是 markdown（Content-Type=%s，%d 字）" +
                        " —— 站点上多半没有 codes.md",
                    contentType.ifEmpty { "?" },
                    body.length,
                )
                return null
            }
            body
        }
    }.onFailure {
        Timber.tag(TAG).w(it, "拉取兑换码清单失败")
    }.getOrNull()

    /** 判断响应是不是被替换成了首页 HTML。 */
    private fun isHtml(contentType: String, body: String): Boolean {
        if (contentType.contains("html", ignoreCase = true)) return true
        val head = body.take(256).trimStart()
        return head.startsWith("<!DOCTYPE", ignoreCase = true) ||
            head.startsWith("<html", ignoreCase = true) ||
            head.startsWith("<?xml", ignoreCase = true)
    }
}
