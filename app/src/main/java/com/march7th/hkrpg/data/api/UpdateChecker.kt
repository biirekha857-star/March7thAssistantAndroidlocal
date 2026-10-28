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
 * 更新清单（站点上的 `update.json`）。
 *
 * ```json
 * {
 *   "versionCode": 6,
 *   "versionName": "0.6.0beta",
 *   "apkUrl": "https://.../app-release.apk",
 *   "changelog": "修复xxx",
 *   "forceUpdate": false,
 *   "minSupportedVersionCode": 1,
 *   "publishedAt": "2026-10-28"
 * }
 * ```
 */
@Serializable
data class UpdateManifest(
    /** 新版本的 versionCode，和本机比对 */
    @SerialName("versionCode") val versionCode: Int,
    /** 展示用的版本名，如 `0.6.0beta` */
    @SerialName("versionName") val versionName: String,
    /** APK 下载地址（可以是 GitHub Release 链接） */
    @SerialName("apkUrl") val apkUrl: String,
    /** 更新说明 */
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
 * **静默失败。** 检查更新是锦上添花，断网或站点没部署时
 * 不应该弹任何错误打扰用户 —— 只打一条日志。
 */
class UpdateChecker(
    private val httpClient: OkHttpClient,
) {
    companion object {
        private const val TAG = "UpdateChecker"
        private const val TIMEOUT_SEC = 10L
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
            val client = httpClient.newBuilder()
                .connectTimeout(TIMEOUT_SEC, TimeUnit.SECONDS)
                .readTimeout(TIMEOUT_SEC, TimeUnit.SECONDS)
                .build()

            val request = Request.Builder()
                .url(M7aApi.UPDATE_MANIFEST)
                // 清单必须拿最新的，不让中间层缓存
                .header("Cache-Control", "no-cache")
                .build()

            val body = client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Timber.d("$TAG: HTTP ${resp.code}，跳过更新检查")
                    return@withContext Result.NONE
                }
                resp.body?.string()
            } ?: return@withContext Result.NONE

            val manifest = json.decodeFromString<UpdateManifest>(body)
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
}
