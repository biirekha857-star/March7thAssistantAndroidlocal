package com.march7th.hkrpg.domain.service

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.graphics.Point
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.view.WindowManager
import androidx.core.content.FileProvider
import com.march7th.hkrpg.BuildConfig
import com.march7th.hkrpg.constant.M7aFiles
import com.march7th.hkrpg.constant.Packages
import com.march7th.hkrpg.data.config.CorePathConfig
import com.march7th.hkrpg.data.preferences.AppSettingsManager
import com.march7th.hkrpg.data.preferences.AppSettingsSnapshot
import com.march7th.hkrpg.data.preferences.TaskChainState
import com.march7th.hkrpg.data.resource.CoreVersion
import com.march7th.hkrpg.manager.RemoteServiceManager
import com.march7th.hkrpg.manager.ShizukuManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import timber.log.Timber
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class LogExportService(
    private val context: Context,
    private val pathConfig: CorePathConfig,
    private val appSettingsManager: AppSettingsManager,
    private val taskChainState: TaskChainState,
) {
    companion object {
        private val DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")

        /**
         * 导出日志压缩包的文件名前缀。
         *
         * ⚠️ 改这里要**同时**改 [cleanupOldExports] 的匹配规则，
         * 否则旧前缀的文件会永远留在用户的文件管理器里清不掉。
         */
        private const val EXPORT_PREFIX = "m7a_logs_"
        private val INFO_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS (Z)")

        /**
         * logcat 最多保留最后多少行。
         *
         * 8000 行大约 1~2 MB，zip 后几百 KB —— 既能覆盖一次完整任务
         * 的执行过程，又不至于让日志包大到用户传不动。
         */
        private const val LOGCAT_MAX_LINES = 8000
    }

    /** 导出日志 ZIP；失败返回 null。无日志时仍生成仅含 properties/device_info 的包。不删除 debug 源日志。 */
    suspend fun exportZip(): File? = withContext(Dispatchers.IO) {
        try {
            val dir = File(pathConfig.debugDir)
            val exportDir = File(dir, LogExportCollector.EXPORT_DIR_NAME)
            exportDir.mkdirs()
            cleanupOldExports(exportDir)

            val zipFileName = "${EXPORT_PREFIX}${ZonedDateTime.now().format(DATE_FORMAT)}.zip"
            val zipFile = File(exportDir, zipFileName)

            val logFiles = LogExportCollector.collect(dir)
            if (logFiles.isEmpty()) {
                Timber.w("No log files found, exporting device info only")
            }

            createZipFile(zipFile, logFiles, dir, buildSettingsSnapshot())

            Timber.i("Exported ${logFiles.size} log files to ${zipFile.absolutePath}")

            zipFile
        } catch (e: Exception) {
            Timber.e(e, "Failed to export logs")
            null
        }
    }

    suspend fun exportAllLogs(): Intent? = exportZip()?.let { createShareIntent(it) }

    /** 写入 [targetUri]；成功返回显示名，失败返回 null。 */
    suspend fun exportToUri(targetUri: android.net.Uri): String? = withContext(Dispatchers.IO) {
        val zip = exportZip() ?: return@withContext null
        try {
            context.contentResolver.openOutputStream(targetUri)?.use { out ->
                zip.inputStream().use { it.copyTo(out) }
            } ?: return@withContext null
            queryDisplayName(targetUri) ?: zip.name
        } catch (e: Exception) {
            Timber.e(e, "Failed to export to uri: $targetUri")
            null
        }
    }

    private fun queryDisplayName(uri: android.net.Uri): String? {
        return try {
            context.contentResolver.query(
                uri,
                arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to query DISPLAY_NAME for $uri")
            null
        }
    }

    private suspend fun buildSettingsSnapshot(): String? =
        runCatching { AppSettingsSnapshot.format(appSettingsManager.settings.first()) }
            .onFailure { Timber.w(it, "Failed to snapshot app settings") }
            .getOrNull()

    private fun createZipFile(
        zipFile: File,
        logFiles: List<File>,
        baseDir: File,
        settingsSnapshot: String?,
    ) {
        ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile))).use { zos ->
            try {
                val process = Runtime.getRuntime().exec("getprop")
                zos.putNextEntry(ZipEntry("properties.txt"))
                process.inputStream.use { input ->
                    input.copyTo(zos, bufferSize = 8192)
                }
                zos.closeEntry()
                process.waitFor()
            } catch (e: Exception) {
                Timber.w(e, "Failed to collect device properties")
            }

            try {
                zos.putNextEntry(ZipEntry("device_info.txt"))
                zos.write(buildDeviceInfo().toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            } catch (e: Exception) {
                Timber.w(e, "Failed to collect device info")
            }

            if (settingsSnapshot != null) {
                try {
                    zos.putNextEntry(ZipEntry(AppSettingsSnapshot.ENTRY_NAME))
                    zos.write(settingsSnapshot.toByteArray(Charsets.UTF_8))
                    zos.closeEntry()
                } catch (e: Exception) {
                    Timber.w(e, "Failed to write app settings snapshot")
                }
            }

            // ## logcat 一起打进去
            //
            // 引擎的 `Ln.i()` 只写 logcat，**不进日志文件**。
            // 排查问题时最关键的几行恰恰只在那里，例如：
            //
            // ```
            // VirtualDisplayManager: VD created: id=11, configured=1920x1080,
            //                        actual=1920x872, rotation=0
            // ShellAutomation: ⚠️ 帧尺寸异常！帧 1920x872 vs 虚拟屏 1920x1080
            // ```
            //
            // 之前用户导出的 zip 里没有 logcat，导致这类问题只能反复猜。
            appendLogcat(zos)

            appendRemoteDebugFiles(zos)

            // 提权进程写的文件可能对 App 不可读，逐个跳过，不拖垮整包
            val skipped = mutableListOf<String>()
            for (file in logFiles) {
                val name = file.relativeTo(baseDir).path
                try {
                    FileInputStream(file).use { zos.addEntry(name, it, file.lastModified()) }
                } catch (e: IOException) {
                    Timber.w(e, "Skip unreadable log file: %s", name)
                    skipped += "$name: ${e.message}"
                }
            }
            if (skipped.isNotEmpty()) {
                zos.addEntry("export_skipped.txt", skipped.joinToString("\n").byteInputStream())
            }
        }
    }

    private fun ZipOutputStream.addEntry(name: String, input: InputStream, time: Long = 0L) {
        putNextEntry(ZipEntry(name).also { if (time > 0) it.time = time })
        input.copyTo(this, bufferSize = 64 * 1024)
        closeEntry()
    }

    /**
     * 把 logcat 写进压缩包（`logcat/full.txt`）。
     *
     * ## 为什么要抓 logcat
     *
     * 引擎日志走 `Ln` 门面，它**只输出到 logcat**，不落盘。
     * 而排查画面拉伸、服务崩溃这类问题，最有价值的几行恰好在那儿：
     *
     * ```
     * VirtualDisplayManager: VD created: ... configured=1920x1080, actual=1920x872
     * ShellAutomation: ⚠️ 帧尺寸异常！帧 1920x872 vs 虚拟屏 1920x1080
     * RemoteService: 收割孤儿提权进程 pid=1234 ppid=1
     * ```
     *
     * ## 过滤策略
     *
     * 全量 logcat 动辄几十 MB（系统其它应用的日志也在里面），
     * 直接打包会让 zip 大到没法传。所以：
     *
     * 1. 主抓 **本应用相关** 的 tag（`MaaMeow` 是引擎的统一 tag）
     * 2. 附带抓本进程 pid 的全部日志
     * 3. 只取**最近 [LOGCAT_MAX_LINES] 行**，避免超大
     * 4. 抓取失败不抛异常 —— 宁可少了 logcat，也不能让整个导出失败
     *
     * 需要 root/shell 权限才能看别的进程日志；普通 App 身份下
     * `logcat -d` 只能看到自己的，够用。
     */
    private fun appendLogcat(zos: ZipOutputStream) {
        val text = runCatching { dumpLogcat() }
            .onFailure { Timber.w(it, "dumpLogcat failed") }
            .getOrNull()
            .orEmpty()

        val body = if (text.isBlank()) {
            """
            （logcat 抓取为空）

            可能原因：
            1. Android 12+ 限制普通应用读取其它进程日志 —— 若本机未授予
               「读取日志」权限，这里只能看到本应用自己的输出
            2. 日志缓冲区刚被清空
            3. 设备禁用了 logd

            建议：在本应用内复现问题后**立刻**导出，间隔越短抓到越多。
            """.trimIndent()
        } else {
            text
        }

        runCatching {
            zos.addEntry("logcat/full.txt", body.byteInputStream())
            // 另外单独留一份引擎日志，方便直接看
            val engineOnly = body.lineSequence()
                .filter { it.contains("MaaMeow") }
                .joinToString("\n")
            if (engineOnly.isNotBlank()) {
                zos.addEntry("logcat/engine.txt", engineOnly.byteInputStream())
            }
        }.onFailure { Timber.w(it, "Failed to write logcat entry") }
    }

    /**
     * 抓当前 logcat 缓冲区，截取最近若干行。
     *
     * ## ⚠️ 不能按 pid 过滤
     *
     * 之前用 `logcat --pid=<主进程pid>`，结果**一条引擎日志都抓不到** ——
     * 因为引擎跑在 `:shizuku_service` 这个**独立进程**里，pid 完全不同。
     *
     * ```
     * com.march7th.hkrpg            ← 主进程（UI），pid=2190
     * com.march7th.hkrpg:shizuku_service  ← 特权服务，pid 是另一个
     * ```
     *
     * 而 `VirtualDisplayManager` / `ShellAutomation` / 各任务的日志
     * 全部来自后者。按主进程 pid 过滤，恰好把最需要的全滤掉了。
     *
     * ## 现在按 tag 过滤
     *
     * 引擎统一用 `MaaMeow` 作 tag（见 `Ln` 门面），所以：
     * 1. `-s MaaMeow:*` 精确抓引擎日志（优先）
     * 2. 抓不到再退回全量缓冲区
     */
    private fun dumpLogcat(): String {
        val attempts = listOf(
            // 引擎日志：按 tag 精确抓，跨进程都有效
            arrayOf("logcat", "-d", "-v", "time", "-s", "MaaMeow:V"),
            // 兜底：全量缓冲区（包含系统崩溃、ANR 等信息）
            arrayOf("logcat", "-d", "-v", "time"),
        )
        var fullBuffer = ""
        for (cmd in attempts) {
            val out = runCatching {
                val proc = ProcessBuilder(*cmd)
                    .redirectErrorStream(true)
                    .start()
                val text = proc.inputStream.bufferedReader().use { it.readText() }
                proc.waitFor()
                text
            }.getOrNull()

            if (cmd.contains("MaaMeow:V")) {
                // tag 过滤成功且确实有内容 → 直接用
                if (!out.isNullOrBlank() && out.contains("MaaMeow")) {
                    return tailLines(out, LOGCAT_MAX_LINES)
                }
            } else {
                fullBuffer = out.orEmpty()
            }
        }

        // 退回全量，但要**优先保留 hkrpg 相关行**
        // 全量几万行里混着系统其它应用，真正有用的其实很少
        if (fullBuffer.isNotBlank()) {
            val interesting = fullBuffer.lineSequence()
                .filter { line ->
                    line.contains("hkrpg") || line.contains("MaaMeow") ||
                        line.contains("DEBUG") || line.contains("AndroidRuntime")
                }
                .joinToString("\n")
            return if (interesting.isNotBlank()) {
                buildString {
                    append("（全量缓冲区中与本应用相关的行）\n\n")
                    append(tailLines(interesting, LOGCAT_MAX_LINES))
                    append("\n\n（全量缓冲区共 ")
                    append(fullBuffer.lineSequence().count())
                    append(" 行，引擎 tag 未抓到 —— 可能 logd 缓冲区已滚动）")
                }
            } else {
                tailLines(fullBuffer, LOGCAT_MAX_LINES)
            }
        }
        return ""
    }

    /** 只保留最后 [maxLines] 行。 */
    private fun tailLines(text: String, maxLines: Int): String {
        val lines = text.lineSequence().toList()
        if (lines.size <= maxLines) return text
        return buildString {
            append("（以下为最近 ").append(maxLines).append(" 行，共 ")
            append(lines.size).append(" 行）\n")
            append(lines.takeLast(maxLines).joinToString("\n"))
        }
    }

    private fun appendRemoteDebugFiles(zos: ZipOutputStream) {
        // core 用 App 目录时它的日志就在 App 的 debug/ 里，已被 collect 收进去
        if (!pathConfig.isCoreSeparated) return
        val srv = RemoteServiceManager.getInstanceOrNull()
        if (srv == null) {
            Timber.w("Remote service not connected, core debug files skipped")
            return
        }
        val files = runCatching { srv.listCoreDebugFiles() }
            .onFailure { Timber.w(it, "listCoreDebugFiles failed") }
            .getOrNull() ?: return
        for (rel in files) {
            try {
                val pfd = srv.openCoreDebugFile(rel) ?: continue
                ParcelFileDescriptor.AutoCloseInputStream(pfd).use { zos.addEntry("${M7aFiles.EXPORT_REMOTE_DIR}/$rel", it) }
            } catch (e: Exception) {
                Timber.w(e, "Failed to pull core debug file: %s", rel)
            }
        }
    }

    /** 导出时采集的设备与运行环境快照，用于 issue 排障 */
    private fun buildDeviceInfo(): String = buildString {
        val line = "=".repeat(60)
        append(line).append("\n")
        append("=== MaaMeow Device & App Info ===\n")
        append("Export Time : ${ZonedDateTime.now().format(INFO_TIME_FORMAT)}\n")
        append("App         : ${BuildConfig.APPLICATION_ID}\n")
        append("Version     : ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n")
        append("Build Type  : ${BuildConfig.BUILD_TYPE}\n")
        append("Device      : ${Build.MANUFACTURER} ${Build.MODEL}\n")
        append("Android     : ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
        append("Security    : ${Build.VERSION.SECURITY_PATCH}\n")
        append("ABI         : ${Build.SUPPORTED_ABIS.joinToString()}\n")
        append("--- MAA ---\n")
        append("Core        : ${CoreVersion.current.ifBlank { "unknown" }}\n")
        append("Resource    : ${pathConfig.readDiskResourceVersion() ?: "none"}\n")
        append("Client      : ${taskChainState.clientType}\n")
        append("Core Dir    : ${pathConfig.coreLocation} (${pathConfig.coreRootDir})\n")
        append("Game        : $gameVersionInfo\n")
        append(
            "Run Mode    : ${appSettingsManager.runMode.value} / " +
                    "${appSettingsManager.backgroundResolution.value} / " +
                    "forceFullscreen=${appSettingsManager.forceFullscreenOnVirtualDisplay.value}\n"
        )
        append("--- Backend ---\n")
        append("Startup     : ${appSettingsManager.startupBackend.value.display}\n")
        append("Shizuku     : $shizukuStatus\n")
        append("--- Device ---\n")
        append("Screen      : $screenInfo (density ${context.resources.displayMetrics.densityDpi}dpi)\n")
        append("RAM         : ${memoryInfo}\n")
        append("Storage     : $storageInfo\n")
        append("Battery Opt : $batteryOptimized\n")
        append("SELinux     : $selinuxMode\n")
        append(virtualDisplayInfo)
        append(line).append("\n")
    }

    /**
     * 虚拟屏诊断 —— **排查画面拉伸的关键**。
     *
     * ## 为什么要写进 device_info
     *
     * 「画面被拉伸」的根因是**虚拟屏声明的尺寸**和**系统实际渲染的尺寸**
     * 不一致。实测两台长屏设备（物理屏 2.2 比例）都是：
     *
     * ```
     * 配置 1920x1080 (1.78)  ->  实际取到 1920x872 (2.20)
     *                                      ^ 正好等于物理屏比例
     * ```
     *
     * 这个数值以前只出现在 logcat 里，而用户导出的 zip 没有 logcat。
     * 现在一并写进 device_info.txt，一次导出拿全现场。
     */
    private val virtualDisplayInfo: String
        get() = buildString {
            append("--- Virtual Display ---\n")

            runCatching {
                append("Configured  : ").append(appSettingsManager.backgroundResolution.value).append("\n")
            }.onFailure { append("Configured  : unknown (${it.message})\n") }

            runCatching {
                val proc = ProcessBuilder("sh", "-c", "dumpsys display | grep -E 'mDisplayId=|uniqueId' | head -40")
                    .redirectErrorStream(true)
                    .start()
                val text = proc.inputStream.bufferedReader().use { it.readText() }
                proc.waitFor()
                if (text.isBlank()) {
                    append("Displays    : (dumpsys 无输出，可能无权限)\n")
                } else {
                    append("Displays    :\n")
                    text.lineSequence().filter { it.isNotBlank() }.take(30).forEach {
                        append("  ").append(it.trim()).append("\n")
                    }
                }
            }.onFailure { append("Displays    : query failed (${it.message})\n") }

            runCatching {
                val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                @Suppress("DEPRECATION")
                val dims = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    wm.maximumWindowMetrics.bounds.let { it.width() to it.height() }
                } else {
                    val p = Point().also(wm.defaultDisplay::getRealSize)
                    p.x to p.y
                }
                val ratio = maxOf(dims.first, dims.second).toFloat() /
                    minOf(dims.first, dims.second).toFloat()
                append("Screen Ratio: ").append("%.3f".format(Locale.US, ratio))
                append(" (虚拟屏 ").append("%.3f".format(Locale.US, 16f / 9f)).append(")\n")
                if (ratio > 16f / 9f + 0.15f) {
                    append("⚠️ Long-screen device：物理屏比例与虚拟屏差异大，")
                    append("部分 ROM 会按物理屏比例渲染导致拉伸\n")
                }
            }.onFailure { append("Screen Ratio: unknown (${it.message})\n") }
        }

    private val gameVersionInfo: String
        get() = runCatching {
            val pkg = Packages[taskChainState.clientType] ?: return@runCatching "unknown package"
            val info = context.packageManager.getPackageInfo(pkg, 0)
            "$pkg ${info.versionName ?: "?"}"
        }.getOrElse { "${Packages[taskChainState.clientType] ?: "?"} not installed" }

    private val shizukuStatus: String
        get() = runCatching {
            if (!ShizukuManager.isShizukuAvailable()) return@runCatching "unavailable"
            val granted = if (ShizukuManager.isGranted()) "granted" else "not granted"
            val api = runCatching { Shizuku.getVersion() }.getOrNull()
            val uid = runCatching { Shizuku.getUid() }.getOrNull()
            val identity = if (uid == 0) "root" else "adb"
            "available, $granted, API $api, uid $uid ($identity)"
        }.getOrElse { "unknown" }

    private val screenInfo: String
        get() = runCatching {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            @Suppress("DEPRECATION")
            val (w, h) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                wm.maximumWindowMetrics.bounds.let { it.width() to it.height() }
            } else {
                val size = Point().also(wm.defaultDisplay::getRealSize)
                size.x to size.y
            }
            @Suppress("DEPRECATION")
            val refresh = wm.defaultDisplay.refreshRate
            "$w x $h @ ${"%.0f".format(Locale.US, refresh)}Hz"
        }.getOrElse { "unknown" }

    private val memoryInfo: String
        get() = runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mi = ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            "${formatGb(mi.totalMem)} total, ${formatGb(mi.availMem)} free"
        }.getOrElse { "unknown" }

    private val storageInfo: String
        get() = runCatching {
            val dir = File(pathConfig.rootDir)
            "${formatGb(dir.usableSpace)} usable / ${formatGb(dir.totalSpace)} total"
        }.getOrElse { "unknown" }

    private val batteryOptimized: String
        get() = runCatching {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (pm.isIgnoringBatteryOptimizations(context.packageName)) {
                "ignored (app exempt)"
            } else {
                "NOT ignored (schedule may be killed)"
            }
        }.getOrElse { "unknown" }

    private val selinuxMode: String
        get() = runCatching {
            when (File("/sys/fs/selinux/enforce").readText().trim()) {
                "1" -> "enforcing"
                "0" -> "permissive"
                else -> "unknown"
            }
        }.getOrDefault("unknown")

    private fun formatGb(bytes: Long): String =
        "%.1f GB".format(Locale.US, bytes / 1024f / 1024f / 1024f)

    private fun createShareIntent(zipFile: File): Intent {
        val authority = "${context.packageName}.fileprovider"
        val uri = FileProvider.getUriForFile(context, authority, zipFile)

        return Intent(Intent.ACTION_SEND).apply {
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "M7A 日志导出")
            putExtra(
                Intent.EXTRA_TEXT,
                "M7A 日志文件导出于 ${
                    ZonedDateTime.now()
                        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss (Z)"))
                }"
            )
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
        }
    }

    /**
     * 清理旧的导出包。
     *
     * 同时匹配**新前缀和旧前缀**：用户升级到 0.0.3alpha 之后，
     * 旧版留下的 `maa_logs_*.zip` 也要能清掉，否则会一直占着空间。
     */
    private fun cleanupOldExports(dir: File) {
        try {
            val oldPrefix = "maa_logs_"
            dir.listFiles { file ->
                file.isFile && file.name.endsWith(".zip") &&
                    (file.name.startsWith(EXPORT_PREFIX) || file.name.startsWith(oldPrefix))
            }?.forEach { it.delete() }
        } catch (e: Exception) {
            Timber.w(e, "Failed to cleanup old exports")
        }
    }
}
