package com.march7th.hkrpg.data.api

import com.march7th.hkrpg.BuildConfig
import com.march7th.hkrpg.constant.M7aApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * 更新清单（站点上的 `update.md`）。
 *
 * ```markdown
 * ---
 * versionCode: 8
 * versionName: 0.5.7beta
 * apkUrl: https://.../March7thAssistant-0.5.7beta.apk
 * forceUpdate: false
 * minSupportedVersionCode: 1
 * publishedAt: 2026-10-31
 * ---
 *
 * 更新要点，Markdown 正文
 * ```
 *
 * 解析见 [UpdateManifestParser]；旧的 `update.json` 也仍然兼容。
 */
@Serializable
data class UpdateManifest(
    /** 新版本的 versionCode，和本机比对 */
    @SerialName("versionCode") val versionCode: Int,
    /** 展示用的版本名，如 `0.6.0beta` */
    @SerialName("versionName") val versionName: String,
    /** APK 下载地址（可以是 GitHub Release 链接） */
    @SerialName("apkUrl") val apkUrl: String,
    /** 更新说明（Markdown；md 清单里就是 front matter 下面的正文） */
    @SerialName("changelog") val changelog: String = "",
    /** 是否强制更新（低于 minSupportedVersionCode 时也会强制） */
    @SerialName("forceUpdate") val forceUpdate: Boolean = false,
    /** 低于这个 versionCode 的版本**必须**更新 */
    @SerialName("minSupportedVersionCode") val minSupportedVersionCode: Int = 0,
    @SerialName("publishedAt") val publishedAt: String = "",
)

/**
 * 检查更新。
 *
 * # 数据源
 *
 * 和公告一个套路 —— 都是静态站上的一个文件：
 *
 * ```
 * https://m7a.pages.dev/update.md     ← 现在的主源，人写的
 * https://m7a.pages.dev/update.json   ← 回退，给 0.5.7beta 以前的旧客户端
 * ```
 *
 * `update.md` 拿不到（还没部署 / 手滑删了 / 格式写坏）就自动退回 JSON。
 * 两条都失败就当没有更新，绝不打扰用户 —— 检查更新是锦上添花。
 *
 * # 判定规则
 *
 * ```
 * 有新版本  = manifest.versionCode > BuildConfig.VERSION_CODE
 * 必须更新  = manifest.forceUpdate
 *          || BuildConfig.VERSION_CODE < manifest.minSupportedVersionCode
 * ```
 *
 * # 失败策略
 *
 * **静默失败。** 断网或站点没部署时不应该弹任何错误打扰用户 —— 只打一条日志。
 * 强制更新的兜底不靠这里，靠 [com.march7th.hkrpg.data.preferences.AppSettingsManager]
 * 里的本地锁（`forcedUpdateFloor`）。
 */
class UpdateChecker(
    private val httpClient: OkHttpClient,
) {
    companion object {
        private const val TAG = "UpdateChecker"
        private const val TIMEOUT_SEC = 10L

        /** 清单随时可能被改，别让任何中间层（含 Cloudflare）缓存它。 */
        private const val NO_CACHE = "no-cache"
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /** 检查结果。 */
    data class Result(
        val hasUpdate: Boolean,
        val forceUpdate: Boolean,
        val manifest: UpdateManifest?,
    ) {
        companion object {
            val NONE = Result(false, false, null)
        }
    }

    /**
     * 检查是否有新版本。
     *
     * @return 检查结果；网络/解析失败时返回 [Result.NONE]
     */
    suspend fun check(): Result = withContext(Dispatchers.IO) {
        runCatching {
            val manifest = fetchMarkdown() ?: fetchJson()
                ?: run {
                    Timber.d("$TAG: 两份清单都拿不到，跳过更新检查")
                    return@withContext Result.NONE
                }

            val current = BuildConfig.VERSION_CODE
            val hasUpdate = manifest.versionCode > current
            val mustUpdate = manifest.forceUpdate || current < manifest.minSupportedVersionCode

            if (hasUpdate) {
                Timber.i(
                    "$TAG: 发现新版本 ${manifest.versionName}" +
                        "（本机 $current -> ${manifest.versionCode}）" +
                        if (mustUpdate) " [强制]" else "",
                )
            } else {
                Timber.d("$TAG: 已是最新版本 ($current)")
            }

            Result(hasUpdate, mustUpdate, manifest.takeIf { hasUpdate })
        }.getOrElse { e ->
            Timber.w(e, "$TAG: 检查更新失败（静默）")
            Result.NONE
        }
    }

    // ==================== 主源：update.md ====================

    /**
     * 拉 `update.md` 并解析。
     *
     * 解析失败时除了打日志还会**继续回退 JSON**：md 写坏了还有人兜着，
     * 总比所有用户都收不到更新强。
     */
    private fun fetchMarkdown(): UpdateManifest? {
        val body = fetch(M7aApi.UPDATE_MANIFEST_MD, "text/markdown, text/plain, */*")
            ?: return null
        val manifest = UpdateManifestParser.parseMarkdown(body)
        if (manifest == null) {
            Timber.w("$TAG: update.md 解析失败（front matter 缺失或字段写错），回退 update.json")
            return null
        }
        Timber.d("$TAG: 清单来源 update.md")
        return manifest.withDownloadFallback()
    }

    // ==================== 回退：旧版 update.json ====================

    /**
     * 拉 `update.json`。
     *
     * 纯粹为了还在用 0.5.7beta 之前版本的用户 —— 他们的客户端只会读 JSON。
     * 这个文件由 `scripts/update_manifest.py` 从 `update.md` 生成，不用手改。
     */
    private fun fetchJson(): UpdateManifest? {
        val body = fetch(M7aApi.UPDATE_MANIFEST, "application/json")
            ?: return null
        val manifest = runCatching { json.decodeFromString<UpdateManifest>(body) }
            .onFailure { Timber.w(it, "$TAG: update.json 解析失败") }
            .getOrNull() ?: return null
        Timber.d("$TAG: 清单来源 update.json（回退）")
        return manifest.withDownloadFallback()
    }

    /**
     * 下载地址兜底。
     *
     * 清单里 `apkUrl` 写空（或者写错成空串）时，直接把用户送到 Release 页 ——
     * 弹窗上那个「立即更新」按钮按下去必须有反应，不能是个死按钮。
     */
    private fun UpdateManifest.withDownloadFallback(): UpdateManifest =
        if (apkUrl.isBlank()) copy(apkUrl = M7aApi.APP_GITHUB_RELEASES_LATEST) else this

    /** 发一个 GET，返回正文；非 2xx 或空正文返回 `null`。 */
    private fun fetch(url: String, accept: String): String? = runCatching {
        val client = httpClient.newBuilder()
            .connectTimeout(TIMEOUT_SEC, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SEC, TimeUnit.SECONDS)
            .build()

        val request = Request.Builder()
            .url(url)
            .header("Cache-Control", NO_CACHE)
            .header("Pragma", NO_CACHE)
            .header("Accept", accept)
            .build()

        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                Timber.d("$TAG: HTTP ${resp.code} $url")
                return null
            }
            resp.body?.string()?.takeIf { it.isNotBlank() }
        }
    }.onFailure { Timber.w(it, "$TAG: 请求失败 $url") }.getOrNull()
}
