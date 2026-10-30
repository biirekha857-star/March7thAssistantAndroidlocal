package com.march7th.hkrpg.remote.internal

import com.march7th.hkrpg.third.Ln
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 画面 / 触控 双链路现场快照。
 *
 * ## 为什么需要它
 *
 * 反馈「虚拟屏画面错位」的用户**没有电脑**，拿不到 `adb shell dumpsys`，
 * 而目前日志包里只有：
 * - `engine/engine.log` —— 引擎自己的 `Ln` 输出
 * - `logcat/full.txt` —— App 身份抓的 logcat，Android 12+ 只能看到自己进程
 *
 * 恰恰能定性「错位」的三类证据**一条都不在**：
 *
 * 1. **合成侧**：`dumpsys SurfaceFlinger` 里游戏 layer 的 buffer 尺寸 / transform
 * 2. **窗口侧**：`dumpsys window displays` 里游戏窗口在 display 空间的 frame
 * 3. **输入侧**：`dumpsys input` 里各 display 的 `Viewport.physicalFrame` 与
 *    `deviceWidth/deviceHeight`
 *
 * 现场已知「画面按左上角放大、右侧和下缘被切掉，但**触控落点准确**」——
 * 这正是合成变换与输入映射**分叉**的典型特征：输入走 window frame（正确），
 * 合成走另一套 scale（错误）。要证实它，必须同时拿到第 2 和第 3 项，
 * 比对同一个 display 的 `Viewport` 与窗口 frame 是否一致。
 *
 * ## 落点
 *
 * 全部写到 `<userDir>/debug/diag/`（特权进程身份，shell 可读），
 * 由 `LogExportService.appendEngineDebugDir` 通过现有 AIDL 拉进日志包，
 * 不需要用户开 USB 调试、不需要电脑。
 *
 * ## 开销
 *
 * 只在虚拟屏存活期间采样（无 VD 时线程空转，5 秒一次读一个 int）。
 * - `timeline.log`：每 15 秒追加一行摘要（VD id / 尺寸 / 游戏窗口所在 display）
 * - 全量快照：VD 起止、搬屏、以及每 2 分钟一次，保留最近 [MAX_FULL_SNAPSHOTS] 份
 */
object DiagDumper {

    private const val TAG = "DiagDumper"

    /** 全量快照保留份数（每份几十~几百 KB，压缩后更小） */
    private const val MAX_FULL_SNAPSHOTS = 12

    /** 单条命令最多读取的字节数，防止某个 dumpsys 把 zip 撑爆 */
    private const val MAX_BYTES_PER_CMD = 400 * 1024

    /** 单条命令超时 */
    private const val CMD_TIMEOUT_SEC = 8L

    /** timeline 文件上限，超过滚一份 .old */
    private const val MAX_TIMELINE_BYTES = 1_500_000L

    private const val TICK_INTERVAL_MS = 15_000L

    /** 全量快照间隔（tick 只写摘要，全量按这个周期） */
    private const val FULL_SNAPSHOT_INTERVAL_MS = 120_000L

    private val started = AtomicBoolean(false)

    @Volatile
    private var diagDir: File? = null

    @Volatile
    private var lastFullAt = 0L

    /** 上一轮 VD 是否存活，用于识别起止跳变（DISPLAY_NONE = -1） */
    @Volatile
    private var lastVdActive = false

    private val tsFormat = SimpleDateFormat("MMdd_HHmmss_SSS", Locale.US)
    private val lineFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    /**
     * 绑定落盘目录。必须在 [start] 之前调用，通常在 `RemoteServiceImpl.setup()`。
     *
     * @param userDir 引擎 userDir（`pathConfig.coreRootDir`），其下 `debug/` 与
     *                `engine.log`、`screenshots/` 同级，导出时一并打包
     */
    fun init(userDir: File) {
        val dir = File(File(userDir, "debug"), "diag")
        if (!dir.isDirectory && !dir.mkdirs()) {
            Ln.w("$TAG: 无法创建诊断目录 $dir")
            return
        }
        diagDir = dir
        Ln.i("$TAG: 诊断目录就绪 -> ${dir.absolutePath}")
    }

    /**
     * 启动采样线程。幂等。
     *
     * 采样由**虚拟屏状态驱动**，不依赖任何业务埋点：
     * VD 出现 → 记录 start 快照；存活期间 tick；消失 → 记录 stop 快照。
     */
    fun start() {
        if (diagDir == null) {
            Ln.w("$TAG: start() 前未 init，跳过")
            return
        }
        if (!started.compareAndSet(false, true)) return
        Thread({
            Ln.i("$TAG: 采样线程启动，间隔 ${TICK_INTERVAL_MS}ms")
            while (true) {
                try {
                    Thread.sleep(TICK_INTERVAL_MS)
                    sampleOnce()
                } catch (t: Throwable) {
                    // 诊断绝不能影响业务
                    runCatching { Ln.w("$TAG: 采样异常: ${t.message}") }
                }
            }
        }, "diag-dumper").apply { isDaemon = true }.start()
    }

    private fun sampleOnce() {
        val dir = diagDir ?: return
        val vdId = runCatching { VirtualDisplayManager.getDisplayId() }.getOrDefault(-1)
        val active = vdId >= 0

        // VD 起止跳变
        if (active != lastVdActive) {
            lastVdActive = active
            writeFull(dir, if (active) "vd_start" else "vd_stop", "displayId=$vdId")
        }

        if (!active) return

        appendTimeline(dir, vdId)

        val now = System.currentTimeMillis()
        if (now - lastFullAt >= FULL_SNAPSHOT_INTERVAL_MS) {
            lastFullAt = now
            writeFull(dir, "vd_${vdId}_periodic", "")
        }
    }

    /**
     * 外部埋点：搬屏等关键动作前后各记一次全量快照。
     *
     * 只做**异步**提交，不阻塞 AIDL 调用线程（`dumpsys` 单条就要几百毫秒）。
     */
    fun snapshot(reason: String, note: String = "") {
        val dir = diagDir ?: return
        Thread({
            runCatching { writeFull(dir, reason, note) }
                .onFailure { Ln.w("$TAG: snapshot($reason) 失败: ${it.message}") }
        }, "diag-snap-$reason").apply { isDaemon = true }.start()
    }

    // ---- 内部实现 ----

    /**
     * 一行摘要，用来还原「错位发生的确切时间点」。
     *
     * 内容刻意压缩成单行：时间 + VD id + 游戏窗口所在 display + 虚拟屏尺寸。
     * 用户复现后只要说「大概 xx:xx 看到错位」，就能在 timeline 里对齐。
     */
    private fun appendTimeline(dir: File, vdId: Int) {
        val out = runCatching {
            // 只跑一条轻量命令：`dumpsys input` 单次数百毫秒，不能每 15 秒来一次
            exec(
                "dumpsys window displays 2>/dev/null | grep -E 'Display: mDisplayId=|hkrpg' | head -12",
                limit = 8 * 1024,
            )
        }.getOrNull().orEmpty()

        val head = buildString {
            append(lineFormat.format(Date()))
            append(" vd=").append(vdId)
            append(" | ")
        }
        val body = out.lineSequence()
            .filter { it.isNotBlank() }
            .joinToString(" ; ") { it.trim() }
            .take(1200)

        runCatching {
            val file = File(dir, "timeline.log")
            if (file.exists() && file.length() > MAX_TIMELINE_BYTES) {
                val old = File(dir, "timeline.log.old")
                old.delete()
                file.renameTo(old)
            }
            file.appendText(head + body + "\n", Charsets.UTF_8)
        }.onFailure { Ln.w("$TAG: 写 timeline 失败: ${it.message}") }
    }

    /**
     * 全量现场。分段执行，单条失败不影响其它段。
     */
    private fun writeFull(dir: File, reason: String, note: String) {
        val ts = tsFormat.format(Date())
        val file = File(dir, "diag_${ts}_$reason.txt")

        val sb = StringBuilder(64 * 1024)
        sb.append("== M7A Diag Snapshot ==\n")
        sb.append("time   : ").append(lineFormat.format(Date())).append('\n')
        sb.append("reason : ").append(reason).append('\n')
        if (note.isNotBlank()) sb.append("note   : ").append(note).append('\n')
        sb.append("pid    : ").append(android.os.Process.myPid())
            .append(" uid=").append(android.os.Process.myUid()).append('\n')
        sb.append("vdId   : ")
            .append(runCatching { VirtualDisplayManager.getDisplayId() }.getOrDefault(-1)).append('\n')
        sb.append('\n')

        for ((title, cmd) in COMMANDS) {
            sb.append("===== ").append(title).append(" =====\n")
            sb.append("$ ").append(cmd).append('\n')
            val text = runCatching { exec(cmd, MAX_BYTES_PER_CMD) }.getOrNull()
            if (text.isNullOrBlank()) {
                sb.append("(empty / failed)\n")
            } else {
                sb.append(text)
                if (!text.endsWith("\n")) sb.append('\n')
            }
            sb.append('\n')
        }

        runCatching {
            file.writeText(sb.toString(), Charsets.UTF_8)
            Ln.i("$TAG: 已写入 $reason -> ${file.name} (${file.length()} B)")
            prune(dir)
        }.onFailure { Ln.w("$TAG: 写快照失败: ${it.message}") }
    }

    /** 只保留最近 [MAX_FULL_SNAPSHOTS] 份全量快照，避免日志包越来越大 */
    private fun prune(dir: File) {
        val files = dir.listFiles { f -> f.isFile && f.name.startsWith("diag_") && f.name.endsWith(".txt") }
            ?: return
        if (files.size <= MAX_FULL_SNAPSHOTS) return
        files.sortedBy { it.lastModified() }
            .take(files.size - MAX_FULL_SNAPSHOTS)
            .forEach { it.delete() }
    }

    /**
     * 以特权身份（shell/root）执行一条 shell 命令。
     *
     * ⚠️ 必须**先读流再 waitFor**：`dumpsys` 输出动辄几百 KB，超过管道缓冲区
     * 就会阻塞子进程，`waitFor` 永久等待 —— 同 `isAppAlive` 踩过的坑。
     */
    private fun exec(cmd: String, limit: Int = MAX_BYTES_PER_CMD): String {
        val process = ProcessBuilder("sh", "-c", cmd)
            .redirectErrorStream(true)
            .start()
        val text = StringBuilder()
        try {
            process.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                val buf = CharArray(8192)
                while (text.length < limit) {
                    val n = reader.read(buf)
                    if (n <= 0) break
                    text.append(buf, 0, n)
                }
            }
            if (!process.waitFor(CMD_TIMEOUT_SEC, TimeUnit.SECONDS)) {
                process.destroyForcibly()
            }
        } finally {
            runCatching { process.destroy() }
        }
        if (text.length >= limit) text.append("\n...(truncated at $limit chars)\n")
        return text.toString()
    }

    /**
     * 快照命令清单。
     *
     * 顺序按「定位错位」的证据链排列：
     * 1. display 配置 —— VD 声明的尺寸/密度/rotation
     * 2. 窗口 frame —— 游戏窗口在 display 空间的位置（触控命中就依赖它）
     * 3. SurfaceFlinger —— 合成到 VD buffer 的 layer 尺寸 / transform
     * 4. input Viewport —— 输入映射用的物理/逻辑 frame
     * 5. 进程与任务栈 —— 游戏到底在哪个 display
     * 6. 最近原生日志 —— `LibBridge` 的帧尺寸校验（原先只在 logcat，抓不到）
     */
    private val COMMANDS: List<Pair<String, String>> = listOf(
        "display" to "dumpsys display 2>&1 | head -c 300000",
        "window-displays" to "dumpsys window displays 2>&1 | head -c 300000",
        // 游戏窗口的 frame / surface 尺寸 —— 「触控对、画面错」的关键对照项
        "game-window" to
            "dumpsys window windows 2>&1 | grep -B2 -A30 -i 'hkrpg' | head -c 150000",
        "sf-display-id" to "dumpsys SurfaceFlinger --display-id 2>&1 | head -c 60000",
        "sf-layers" to "dumpsys SurfaceFlinger --list 2>&1 | head -c 60000",
        "sf-transform" to
            "dumpsys SurfaceFlinger 2>&1 | " +
                "grep -iE 'display|transform|viewport|scale|hkrpg|activeBuffer|buffers=' | head -c 150000",
        "input-viewport" to "dumpsys input 2>&1 | grep -A10 'Viewport' | head -c 120000",
        "wm" to "wm size; wm density; settings get global display_size_forced 2>/dev/null",
        "tasks" to
            "dumpsys activity activities 2>&1 | grep -iE 'hkrpg|Display #|mDisplayId|displayId=' | head -c 60000",
        "procs" to "ps -A 2>/dev/null | grep -iE 'hkrpg|maameow|surfaceflinger' | head -40",
        "native-logcat" to
            "logcat -d -v time -t 800 -s LibBridge:V M7A:V 2>&1 | tail -c 120000",
        "props" to
            "getprop 2>/dev/null | grep -iE 'display|density|resolution|screen|dfps|hw_sc' | head -60",
    )
}
