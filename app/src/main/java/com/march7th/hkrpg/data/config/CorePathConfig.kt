package com.march7th.hkrpg.data.config

import android.content.Context
import com.march7th.hkrpg.constant.M7aFiles.APP_VERSION_FILE
import com.march7th.hkrpg.constant.M7aFiles.ASSET_VERSION_FILE
import com.march7th.hkrpg.constant.M7aFiles.CACHE
import com.march7th.hkrpg.constant.M7aFiles.DEBUG
import com.march7th.hkrpg.constant.M7aFiles.MAA
import com.march7th.hkrpg.constant.M7aFiles.OVERRIDES
import com.march7th.hkrpg.constant.M7aFiles.RESOURCE
import com.march7th.hkrpg.constant.M7aFiles.SCREENSHOTS
import com.march7th.hkrpg.constant.M7aFiles.VERSION_FILE
import com.march7th.hkrpg.data.preferences.AppSettingsManager
import com.march7th.hkrpg.domain.models.CoreDataLocation
import com.march7th.hkrpg.remote.CoreDataDir
import com.march7th.hkrpg.remote.ResourceFiles
import timber.log.Timber
import java.io.File

class CorePathConfig(
    private val context: Context,
    private val appSettings: AppSettingsManager,
) {

    companion object {
        fun toCorePath(path: String, appRoot: String, coreRoot: String): String = when {
            path == appRoot -> coreRoot
            path.startsWith(appRoot + File.separator) -> coreRoot + path.substring(appRoot.length)
            else -> path
        }
    }

    /** 进程内固定；改了设置要重启（MaaCore 状态本就不可回滚） */
    val coreLocation: CoreDataLocation by lazy { appSettings.coreDataLocation.value }

    /** core 在 /data/local/tmp，App 读不到 */
    val isCoreSeparated: Boolean
        get() = coreLocation == CoreDataLocation.LOCAL_TMP

    val coreRootDir: String
        get() = if (isCoreSeparated) CoreDataDir.ROOT else rootDir

    /** core 侧 debug/，asst.log / logcat / 截图落这里 */
    val coreDebugDir: String
        get() = File(coreRootDir, DEBUG).absolutePath

    val coreDebugScreenshotsDir: String
        get() = File(coreDebugDir, SCREENSHOTS).absolutePath

    fun toCorePath(path: String): String =
        if (isCoreSeparated) toCorePath(path, rootDir, CoreDataDir.ROOT) else path

    /** Maa 根目录 */
    val rootDir: String by lazy {
        val ext = context.getExternalFilesDir(null)
        Timber.i("context.getExternalFilesDir $ext")
        File(ext, MAA).absolutePath
    }

    /** 资源目录 */
    val resourceDir: String by lazy {
        File(rootDir, RESOURCE).absolutePath
    }

    /** 缓存目录（热更新资源） */
    val cacheDir: String by lazy {
        File(rootDir, CACHE).absolutePath
    }

    /** 缓存资源目录（cache/resource/） */
    val cacheResourceDir: String by lazy {
        File(cacheDir, RESOURCE).absolutePath
    }

    /**
     * 全球服资源目录（resource/global/{clientType}/resource/）
     * 对标 WPF: Path.Combine(mainRes, "global", clientType, "resource")
     */
    fun globalResourceDir(clientType: String): File {
        return File(resourceDir, "global/$clientType/$RESOURCE")
    }

    /**
     * 全球服缓存资源目录（cache/resource/global/{clientType}/resource/）
     * 对标 WPF: Path.Combine(mainCacheRes, "global", clientType, "resource")
     */
    fun globalCacheResourceDir(clientType: String): File {
        return File(cacheResourceDir, "global/$clientType/$RESOURCE")
    }

    /** 调试日志目录 */
    val debugDir: String by lazy {
        File(rootDir, DEBUG).absolutePath
    }

    /** 调试截图目录（debug/screenshots/） */
    val debugScreenshotsDir: String by lazy {
        File(debugDir, SCREENSHOTS).absolutePath
    }

    /**
     * Android 特化覆盖目录（传给 AsstLoadResource 的 parentDir）
     * 对应磁盘路径：{rootDir}/overrides/
     * 加载链末位，优先级最高
     */
    val overridesDir: String by lazy {
        File(rootDir, OVERRIDES).absolutePath
    }

    /**
     * 覆盖用 tasks.json 文件路径
     * {rootDir}/overrides/resource/tasks/tasks.json
     */
    val overrideTasksFile: File
        get() = File(rootDir, "$OVERRIDES/$RESOURCE/tasks/tasks.json")

    /** version.json 路径 */
    private val versionFile: File
        get() = File(resourceDir, VERSION_FILE)

    private val bundledResourceVersion: String? by lazy {
        try {
            context.assets.open(ASSET_VERSION_FILE).bufferedReader().use { ResourceFiles.readLastUpdated(it.readText()) }
        } catch (e: Exception) {
            Timber.w(e, "读取内置资源版本失败")
            null
        }
    }

    /**
     * 资源是否已就绪。
     *
     * ## 换芯为崩铁后的语义
     *
     * 原实现要求 MaaCore 的 `version.json` + App 版本戳都到位。
     * 但崩铁内核的识别资源（模板图 + OCR 模型）**随 APK 打包在 assets 里**，
     * 不需要从外部目录加载，所以这里恒为 true。
     *
     * 保留 [isAppVersionCurrent] 等原逻辑供将来并行支持方舟时启用。
     */
    val isResourceReady: Boolean
        get() = true

    /** 原 MaaCore 的资源就绪判定（崩铁内核不用）。 */
    val isMaaCoreResourceReady: Boolean
        get() = versionFile.exists() && isAppVersionCurrent()

    private fun isBundledResourceNewer(): Boolean {
        val bundled = bundledResourceVersion ?: return false
        val disk = readDiskResourceVersion() ?: return false
        return ResourceVersionHelper.compareVersions(bundled, disk) > 0
    }

    /** 磁盘资源版本戳（version.json 的 last_updated），缺失或解析失败为 null */
    fun readDiskResourceVersion(): String? = ResourceFiles.readLastUpdated(versionFile)

    private fun isAppVersionCurrent(): Boolean {
        val file = File(rootDir, APP_VERSION_FILE)
        if (!file.exists()) return false
        return try {
            file.readText().trim().toLong() == appVersionCode
        } catch (_: Exception) {
            false
        }
    }

    val appVersionCode: Long
        get() = context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode

    fun markAppVersion() {
        File(rootDir, APP_VERSION_FILE).writeText(appVersionCode.toString())
    }

    fun ensureDirectories(): Boolean {
        return runCatching {
            File(rootDir).mkdirs()
            File(cacheDir).mkdirs()
            File(rootDir, ".nomedia").createNewFile()
        }.getOrDefault(false)
    }
}
