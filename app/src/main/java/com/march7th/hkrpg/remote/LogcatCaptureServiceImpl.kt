package com.march7th.hkrpg.remote

import com.march7th.hkrpg.ILogcatService
import com.march7th.hkrpg.third.Ln
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.system.exitProcess

/**
 * 持久化 logcat 抓取（跑在提权进程里，shell/root 身份）。
 *
 * ## 为什么不能用 App 身份抓
 *
 * Android 12+ 起普通应用只能读**自己进程**的日志。而真正需要排查的东西
 * 全在别的进程：
 *
 * ```
 * com.march7th.hkrpg                  ← UI 进程（App 能读）
 * com.march7th.hkrpg:shizuku_service  ← 引擎进程（MaaMeow）
 * native 层                            ← LibBridge（帧尺寸校验）
 * ```
 *
 * 结果就是用户导出的 `logcat/full.txt` 里只有 47 行 ImeTracker 之类的噪声，
 * `AImageReader 尺寸不匹配` / `AImageReader 尺寸正常` 一条都没有 ——
 * 而这两行恰好是判断「画面被拉伸/裁切」的第一手证据。
 *
 * ## 抓取策略
 *
 * 按 **tag 白名单**抓，不按 pid：
 * - `MaaMeow`：引擎全部业务日志（`Ln` 门面）
 * - `LibBridge`：native 采集层（帧尺寸、buffer 尺寸）
 * - `AndroidRuntime` / `DEBUG`：崩溃与 ANR 栈
 *
 * 落盘到 `<userDir>/debug/logcat/engine/`，由 `LogExportService` 打包。
 */
class LogcatCaptureServiceImpl : ILogcatService.Stub() {

    companion object {
        private const val TAG = "LogcatCapture"

        /** 抓取任务键：只需要一份，重复调用直接返回 */
        private const val ENGINE_KEY = "engine"

        /** 只保留最近这么多天的抓取文件 */
        private const val KEEP_DAYS = 3

        /**
         * tag 白名单。
         *
         * `-T 200` 先倒带最近 200 行，保证抓取起点不是空的；
         * `-v time` 带时间戳，和 engine.log 对时间线用。
         */
        private val LOGCAT_CMD = arrayOf(
            "logcat", "-v", "time", "-T", "200",
            "-s", "MaaMeow:V", "LibBridge:V", "AndroidRuntime:E", "DEBUG:V",
        )
    }

    private val targets = ConcurrentHashMap<String, Process>()

    init {
        Thread {
            while (true) {
                try {
                    Thread.sleep(5000)
                    targets.forEach { (key, process) ->
                        if (!process.isAlive) {
                            val code = runCatching { process.exitValue() }.getOrNull()
                            Ln.w("$TAG: 抓取进程 $key 已退出 exit=$code")
                            targets.remove(key)
                        }
                    }
                } catch (t: Throwable) {
                    // 看门狗自身不能死
                    runCatching { Ln.w("$TAG: watchdog: ${t.message}") }
                }
            }
        }.apply { name = "logcat-watchdog"; isDaemon = true }.start()
    }

    override fun destroy() {
        exitProcess(0)
    }

    /**
     * 开始抓取。幂等：重复调用不会起第二个 logcat。
     *
     * @param appPid 仅用于日志标注（抓取本身不按 pid 过滤）
     * @param servicePid 同上
     * @param userDir 引擎 userDir，抓取文件落在其 `debug/logcat/engine/`
     */
    override fun startCapture(appPid: Int, servicePid: Int, userDir: String) {
        if (targets.containsKey(ENGINE_KEY)) return

        val debugDir = File(userDir, "debug").apply { mkdirs() }
        val outDir = File(debugDir, "logcat/engine").apply { mkdirs() }
        pruneOld(outDir)

        val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        val outFile = File(outDir, "logcat_$timestamp.log")

        Ln.i(
            "$TAG: 开始抓取 tag=[MaaMeow,LibBridge,AndroidRuntime,DEBUG] " +
                "appPid=$appPid servicePid=$servicePid -> ${outFile.absolutePath}"
        )
        targets[ENGINE_KEY] = pipe(outFile)
    }

    private fun pipe(outFile: File): Process {
        val process = ProcessBuilder(*LOGCAT_CMD)
            .redirectErrorStream(true)
            .start()

        Thread({
            try {
                process.inputStream.use { input ->
                    FileOutputStream(outFile, true).use { output ->
                        input.copyTo(output, 32 * 1024)
                    }
                }
                Ln.i("$TAG: 抓取流结束 -> ${outFile.name}")
            } catch (e: Exception) {
                Ln.e("$TAG: 抓取写入失败: ${e.message}")
            }
        }, "logcat-reader").apply { isDaemon = true }.start()

        return process
    }

    /** 删掉超过 [KEEP_DAYS] 天的抓取文件，避免 debug 目录无限增长 */
    private fun pruneOld(dir: File) {
        val cutoff = System.currentTimeMillis() - KEEP_DAYS * 24L * 60 * 60 * 1000
        runCatching {
            dir.listFiles { f -> f.isFile && f.name.endsWith(".log") }
                ?.filter { it.lastModified() < cutoff }
                ?.forEach { it.delete() }
        }
    }
}
