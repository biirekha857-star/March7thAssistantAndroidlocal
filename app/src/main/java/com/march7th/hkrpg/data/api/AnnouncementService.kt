package com.march7th.hkrpg.data.api

import android.content.Context
import com.march7th.hkrpg.constant.M7aApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 公告拉取。
 *
 * # 数据流
 *
 * ```
 * 远程站点（Cloudflare Pages）
 *   https://m7a.pages.dev/announcement_zh.md
 *   https://m7a.pages.dev/announcement_en.md
 *        ↓ 成功
 *   写入本地缓存 → 下次启动先读缓存（秒开）
 *        ↓ 失败（断网/站点没部署）
 *   读取 APK 内置的 assets/announcement 下的 md（保底）
 * ```
 *
 * # 为什么要有本地兜底
 *
 * 站点还没部署、或者用户断网时，公告不能是一片空白 ——
 * 里面有关键的使用说明和风险提示。
 */
class AnnouncementService(
    private val context: Context,
    private val httpClient: OkHttpClient,
) {
    companion object {
        private const val TAG = "AnnouncementService"
        private const val CACHE_DIR = "announcement"
        private const val ASSET_DIR = "announcement"
        private const val TIMEOUT_SEC = 10L
    }

    private val cacheDir: File by lazy {
        File(context.filesDir, CACHE_DIR).apply { mkdirs() }
    }

    /**
     * 读取公告。
     *
     * 顺序：内存/磁盘缓存 → 远程 → APK 内置资源。
     *
     * @param english 是否读英文版
     * @param forceRefresh 是否跳过缓存直接拉远程
     */
    suspend fun load(english: Boolean = false, forceRefresh: Boolean = false): String =
        withContext(Dispatchers.IO) {
            val lang = if (english) "en" else "zh"
            val cacheFile = File(cacheDir, "announcement_$lang.md")

            if (!forceRefresh && cacheFile.exists()) {
                runCatching { cacheFile.readText() }
                    .getOrNull()?.takeIf { it.isNotBlank() }
                    ?.let {
                        Timber.d("$TAG: 命中本地缓存 $lang")
                        return@withContext it
                    }
            }

            // 远程
            fetchRemote(english)?.let { text ->
                runCatching { cacheFile.writeText(text) }
                    .onFailure { Timber.w(it, "$TAG: 写缓存失败") }
                Timber.i("$TAG: 远程公告拉取成功 $lang (${text.length} 字)")
                return@withContext text
            }

            // 已经有缓存但强制刷新失败 → 退回旧缓存
            if (cacheFile.exists()) {
                runCatching { cacheFile.readText() }.getOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?.let {
                        Timber.w("$TAG: 远程失败，用旧缓存 $lang")
                        return@withContext it
                    }
            }

            // APK 内置
            Timber.w("$TAG: 远程失败，用内置资源 $lang")
            readAsset(english)
        }

    private fun fetchRemote(english: Boolean): String? = runCatching {
        val url = if (english) M7aApi.ANNOUNCEMENT_EN else M7aApi.ANNOUNCEMENT_ZH
        val client = httpClient.newBuilder()
            .connectTimeout(TIMEOUT_SEC, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SEC, TimeUnit.SECONDS)
            .build()
        client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) {
                Timber.w("$TAG: HTTP ${resp.code} $url")
                return@runCatching null
            }
            resp.body?.string()?.takeIf { it.isNotBlank() }
        }
    }.onFailure { Timber.w(it, "$TAG: 拉取远程公告失败") }.getOrNull()

    private fun readAsset(english: Boolean): String = runCatching {
        val name = if (english) "announcement_en.md" else "announcement_zh.md"
        context.assets.open("$ASSET_DIR/$name").bufferedReader().use { it.readText() }
    }.getOrElse { e ->
        Timber.e(e, "$TAG: 读内置公告失败")
        "（公告加载失败：${e.message}）"
    }
}
