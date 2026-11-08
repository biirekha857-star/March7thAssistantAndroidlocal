package com.march7th.hkrpg.data.resource

import android.util.Base64
import com.march7th.hkrpg.constant.M7aApi
import com.march7th.hkrpg.utils.JsonUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.concurrent.TimeUnit

/**
 * 站点索引里的一个任务包条目。
 *
 * 这是「目录」，不是包本体 —— 只有元数据 + 下载地址 + 指纹。
 */
@Serializable
data class RemoteTaskPack(
    val id: String,
    val name: String,
    val author: String = "",
    val version: String = "",
    val description: String = "",
    @SerialName("stepCount") val stepCount: Int = 0,
    /** 主下载地址（Cloudflare Pages） */
    val url: String,
    /** 备用下载地址（GitHub Release），主源拉不动时用 */
    @SerialName("urlFallback") val urlFallback: String? = null,
    /** 包文件的 SHA-256（小写十六进制）。**必填**，对不上就拒绝安装 */
    val sha256: String,
    /** 字节数，用来提前挡掉超大包；0 = 不校验 */
    val size: Long = 0,
    /** 低于这个 versionCode 的 App 不显示/不允许装；0 = 不限制 */
    @SerialName("minAppVersionCode") val minAppVersionCode: Int = 0,
) {
    /** 卡片上的一行副标题 */
    val displayName: String get() = name.ifBlank { id }

    val sizeText: String
        get() = when {
            size <= 0 -> ""
            size < 1024 -> "$size B"
            size < 1024 * 1024 -> "${size / 1024} KB"
            else -> String.format("%.1f MB", size / 1024.0 / 1024.0)
        }
}

/** 索引文件的顶层结构。 */
@Serializable
private data class TaskPackIndexFile(
    val schemaVersion: Int = 1,
    val updatedAt: String = "",
    val packs: List<RemoteTaskPack> = emptyList(),
)

/** 拉索引的结果。`Failure` 的文案是给人看的，直接进对话框。 */
sealed interface RemoteIndexResult {
    data class Success(val packs: List<RemoteTaskPack>, val fromFallback: Boolean) : RemoteIndexResult
    data class Failure(val reason: String) : RemoteIndexResult
}

/** 下载一个包的结果。 */
sealed interface RemoteDownloadResult {
    data class Success(val bytes: ByteArray) : RemoteDownloadResult
    data class Failure(val reason: String) : RemoteDownloadResult
}

/**
 * 从站点拉「任务包目录」和任务包本体。
 *
 * # 为什么索引要验签，而兑换码不用
 *
 * `codes.md` 是文本，被篡改最坏结果是多兑几个无效码。
 * 任务包是**会被执行的指令** —— 里面写着「点这里、滑那里、等 3 秒」。
 * 如果索引能随便改，攻击者（或者你误传的一份）就能让所有用户的手机
 * 去点任意位置。所以：
 *
 * ```
 * index.json (原始字节) ──┐
 *                        ├─→ SHA256withECDSA 验签 ─→ 通过才解析
 * index.json.sig ────────┘
 * ```
 *
 * 公钥内置在 APK（[M7aApi.TASKPACK_INDEX_PUBKEY]），私钥只在发版机器上。
 * 这样即使 Pages 项目被入侵、DNS 被劫持、或者仓库被人提了 PR，
 * 客户端也会拒绝。**没有「跳过校验」的开关**，那是自欺欺人。
 *
 * # 单独的 sha256 不够
 *
 * 每个包还带 `sha256`，但那个 hash 写在索引里 —— 索引被改了 hash 也会跟着改。
 * 所以 sha256 只解决「下载过程损坏 / 主备源被换」，真正防篡改的是索引签名。
 * 两者都要。
 */
class RemoteTaskPackService(
    private val httpClient: OkHttpClient,
) {
    companion object {
        private const val TAG = "RemoteTaskPack"

        /** 索引很小，超过这个数基本不是索引（多半是 Pages 返回了首页 HTML） */
        private const val MAX_INDEX_BYTES = 512L * 1024

        /** 和 [CustomTaskPackStore] 的单包上限对齐 */
        /** 单个任务包上限。带录屏或大量模板的包会超过 20MB。 */
        private const val MAX_PACK_BYTES = 500L * 1024 * 1024L
    }

    private val json = Json(JsonUtils.common) { ignoreUnknownKeys = true }

    private val client: OkHttpClient by lazy {
        httpClient.newBuilder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    /**
     * 拉取并校验索引。
     *
     * 主源失败（或验签不过）会自动试备用源。**两边都验签**，
     * 备用源不等于可信源 —— GitHub 仓库同样可能被改。
     */
    suspend fun fetchIndex(): RemoteIndexResult = withContext(Dispatchers.IO) {
        val sources = listOf(
            false to (M7aApi.TASKPACK_INDEX to M7aApi.TASKPACK_INDEX_SIG),
            true to (M7aApi.TASKPACK_INDEX_FALLBACK to M7aApi.TASKPACK_INDEX_SIG_FALLBACK),
        )

        var lastReason = "未知原因"

        for ((isFallback, pair) in sources) {
            val (indexUrl, sigUrl) = pair
            val indexBytes = download(indexUrl, MAX_INDEX_BYTES, "索引")
            if (indexBytes is RemoteDownloadResult.Failure) {
                lastReason = indexBytes.reason
                Timber.tag(TAG).w("索引主备源均失败：%s", lastReason)
                continue
            }
            val sigText = download(sigUrl, 4L * 1024, "索引签名")
            if (sigText is RemoteDownloadResult.Failure) {
                lastReason = sigText.reason
                continue
            }

            val body = (indexBytes as RemoteDownloadResult.Success).bytes
            val sig = (sigText as RemoteDownloadResult.Success).bytes.toString(Charsets.UTF_8)

            if (!verify(body, sig)) {
                lastReason = "索引签名校验失败 —— 文件可能被篡改，或签名与当前 App 版本不匹配"
                Timber.tag(TAG).w("索引验签失败：%s", indexUrl)
                continue
            }

            val parsed = runCatching {
                json.decodeFromString(TaskPackIndexFile.serializer(), body.toString(Charsets.UTF_8))
            }.getOrElse { e ->
                lastReason = "索引格式不对：${e.message?.lineSequence()?.firstOrNull().orEmpty()}"
                continue
            }

            if (parsed.schemaVersion != 1) {
                lastReason = "索引 schemaVersion 是 ${parsed.schemaVersion}，当前 App 只认 1"
                continue
            }

            Timber.tag(TAG).i("拉到 %d 个在线任务包（备用源=%s）", parsed.packs.size, isFallback)
            return@withContext RemoteIndexResult.Success(parsed.packs, isFallback)
        }

        RemoteIndexResult.Failure(lastReason)
    }

    /**
     * 下载一个任务包，并校验大小与 sha256。
     *
     * @param appVersionCode 用来挡 `minAppVersionCode` 太高的包
     */
    suspend fun downloadPack(
        pack: RemoteTaskPack,
        appVersionCode: Int,
    ): RemoteDownloadResult = withContext(Dispatchers.IO) {
        if (pack.minAppVersionCode > appVersionCode) {
            return@withContext RemoteDownloadResult.Failure(
                "这个任务包要求 App versionCode >= ${pack.minAppVersionCode}，当前是 $appVersionCode"
            )
        }
        if (pack.sha256.isBlank()) {
            return@withContext RemoteDownloadResult.Failure("索引里没有这个包的 sha256，拒绝安装")
        }

        val urls = listOfNotNull(pack.url, pack.urlFallback)
            .filter { it.isNotBlank() }
        if (urls.isEmpty()) return@withContext RemoteDownloadResult.Failure("索引里没有下载地址")

        var lastReason = "未知原因"
        for (url in urls) {
            when (val res = download(url, MAX_PACK_BYTES, "任务包")) {
                is RemoteDownloadResult.Failure -> {
                    lastReason = res.reason
                    Timber.tag(TAG).w("下载失败 %s：%s", url, lastReason)
                }

                is RemoteDownloadResult.Success -> {
                    if (pack.size > 0 && res.bytes.size.toLong() != pack.size) {
                        lastReason = "大小不符（索引 ${pack.size} 字节，实际 ${res.bytes.size} 字节）"
                        continue
                    }
                    val actual = sha256Hex(res.bytes)
                    if (!actual.equals(pack.sha256.trim(), ignoreCase = true)) {
                        lastReason = "sha256 不符（索引 ${pack.sha256.take(12)}…，实际 ${actual.take(12)}…）"
                        Timber.tag(TAG).w("任务包指纹不符：%s", url)
                        continue
                    }
                    return@withContext RemoteDownloadResult.Success(res.bytes)
                }
            }
        }
        RemoteDownloadResult.Failure(lastReason)
    }

    // ==================== 内部 ====================

    /**
     * 下载到内存，带体积上限。
     *
     * 上限必须在这里卡：OkHttp 的 `contentLength` 可能是 `-1`（分块传输），
     * 光看长度头挡不住。边读边数，超了直接中断。
     */
    private fun download(url: String, maxBytes: Long, what: String): RemoteDownloadResult {
        return runCatching {
            val req = Request.Builder().url(url).get().build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return RemoteDownloadResult.Failure("$what 下载失败：HTTP ${resp.code}")
                }
                val declared = resp.body?.contentLength() ?: -1L
                if (declared > maxBytes) {
                    return RemoteDownloadResult.Failure(
                        "$what 太大（${declared / 1024} KB，上限 ${maxBytes / 1024} KB）"
                    )
                }
                val stream = resp.body?.byteStream()
                    ?: return RemoteDownloadResult.Failure("$what 响应是空的")

                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(32 * 1024)
                var total = 0L
                while (true) {
                    val n = stream.read(buf)
                    if (n <= 0) break
                    total += n
                    if (total > maxBytes) {
                        return RemoteDownloadResult.Failure(
                            "$what 超过上限（${maxBytes / 1024} KB），已中断"
                        )
                    }
                    out.write(buf, 0, n)
                }
                val bytes = out.toByteArray()
                if (bytes.isEmpty()) return RemoteDownloadResult.Failure("$what 内容是空的")

                // Cloudflare Pages 对不存在的路径不返回 404，而是把首页 HTML 一起 200 返回。
                // 不拦下来，"站点没部署"会变成"索引格式不对"，排查时完全看不出。
                if (looksLikeHtml(bytes)) {
                    return RemoteDownloadResult.Failure(
                        "$what 拿到的是网页而不是文件 —— 站点上多半没有这个路径"
                    )
                }
                RemoteDownloadResult.Success(bytes)
            }
        }.onFailure {
            Timber.tag(TAG).w(it, "下载 %s 失败", url)
        }.getOrElse { e ->
            RemoteDownloadResult.Failure(e.message ?: "网络错误")
        }
    }

    private fun looksLikeHtml(bytes: ByteArray): Boolean {
        val head = bytes.take(256).toByteArray().toString(Charsets.UTF_8).trimStart()
        return head.startsWith("<!DOCTYPE", ignoreCase = true) ||
            head.startsWith("<html", ignoreCase = true) ||
            head.startsWith("<?xml", ignoreCase = true)
    }

    /**
     * SHA256withECDSA 验签。任何异常都当失败 —— 不抛，只返回 false。
     *
     * 逐把公钥试：私钥丢过一次，换新钥匙之后，用旧钥匙签的存量索引
     * 还得能验过，否则升级到新版本的人反而装不了现有的包。
     */
    private fun verify(content: ByteArray, signatureBase64: String): Boolean {
        val sigBytes = runCatching {
            Base64.decode(signatureBase64.filterNot { it.isWhitespace() }, Base64.NO_WRAP)
        }.getOrNull() ?: return false

        return M7aApi.TASKPACK_INDEX_PUBKEYS.any { b64 ->
            runCatching {
                val keyBytes = Base64.decode(b64, Base64.NO_WRAP)
                val pub = KeyFactory.getInstance("EC")
                    .generatePublic(X509EncodedKeySpec(keyBytes))
                Signature.getInstance("SHA256withECDSA").run {
                    initVerify(pub)
                    update(content)
                    verify(sigBytes)
                }
            }.onFailure {
                Timber.tag(TAG).w(it, "验签异常")
            }.getOrDefault(false)
        }
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val sb = StringBuilder(digest.size * 2)
        digest.forEach { b -> sb.append("%02x".format(b.toInt() and 0xFF)) }
        return sb.toString()
    }
}
