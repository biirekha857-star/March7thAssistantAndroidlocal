package com.march7th.hkrpg.starrail

import android.content.Context
import com.march7th.hkrpg.M7aCoreCallback
import com.march7th.hkrpg.core.AsstMsg
import com.march7th.hkrpg.starrail.engine.GameLauncher
import com.march7th.hkrpg.starrail.engine.ImageUtils
import com.march7th.hkrpg.starrail.engine.OcrEngine
import com.march7th.hkrpg.starrail.engine.ScreenNavigator
import com.march7th.hkrpg.starrail.engine.ShellAutomation
import com.march7th.hkrpg.third.Ln
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 崩铁本地引擎 —— [StarRailEngine] 的实现。
 *
 * ## 它跑在哪
 *
 * 在**特权进程**内（由 `RemoteServiceImpl` 持有），因此可以直接调
 * `libbridge.so`（采集）与 `InputControlUtils`（注入），不经过 AIDL。
 *
 * ## 与 MaaCore 的关系
 *
 * 原版 `CoreServiceImpl` 把 26 个方法转发给 MaaCore（明日方舟原生引擎，JNA 调用）。
 * 本类实现**同一个 AIDL 接口**，但转发给崩铁引擎 —— 外壳完全不知道内核换了。
 *
 * ## 未实现的方法
 *
 * 明确返回失败，而不是静默假装成功。这样外壳的表现是"任务失败"，
 * 而不是"跑完了但什么都没做"。
 */
class StarRailLocalEngine(
    private val context: Context?,
) : StarRailEngine {

    companion object {
        private const val TAG = "StarRailLocalEngine"

        /** 采集分辨率（与虚拟屏配置一致）。 */
        const val CAPTURE_WIDTH = 1920
        const val CAPTURE_HEIGHT = 1080

        /** 模板图在 assets 中的目录（不含 images/ 后缀）。 */
        const val ASSET_IMAGE_PREFIX = "starrail/images"

        /**
         * OCR 模型的释放目录。
         *
         * ⚠️ **必须是 shell(uid 2000) 可写的位置**：引擎跑在特权进程里，
         * 写应用私有目录会 EACCES（实测踩过：
         * `/data/user/0/<pkg>/files/ocr/... open failed: EACCES`）。
         * `/data/local/tmp` 是 shell 可写的，也是本外壳其它组件的惯例目录。
         */
        const val OCR_CACHE_DIR = "/data/local/tmp/maameow_ocr"

        /**
         * **校准模式**：开启后每次取帧都落盘。
         *
         * ## 什么时候开
         *
         * 需要批量采集坐标/模板时（比如新做一个模块，要量十几个界面）。
         * 开一次任务就拿到整套帧，离线量完即可，
         * 不用"改一个坐标 → 构建 → 装机 → 实测"来回几十轮。
         *
         * ## 什么时候关
         *
         * 1. 坐标已经量完、写进代码之后。
         * 2. **发版前必须关**：开着会持续写盘（每轮上限 300 张），
         *    既占空间又拖慢任务（每次取帧多一次 PNG 编码）。
         *
         * 关掉后 [ScreenNavigator.captureAll] 不会被打开，
         * 只有显式 `trace()` 的关键步骤还会导出。
         *
         * ## ⚠️ 当前已关闭
         *
         * 坐标都量完了，这个开关**每取一帧就要写一个 ~1.5MB 的 PNG**，
         * 实测把「清体力」的导航阶段拖慢了 40% 以上（取帧从几十毫秒
         * 变成几百毫秒）。发版前必须关，现在已经关掉。
         *
         * 以后若要重新校准新界面，临时改回 true 再跑一次即可。
         */
        const val CALIBRATION_CAPTURE = false
    }

    private val running = AtomicBoolean(false)

    /**
     * "实例是否已创建"。
     *
     * ⚠️ **不能用 [loaded]（资源是否加载）代替**。
     *
     * 外壳的逻辑是：
     * ```
     * if (maa.hasInstance()) return null        // 已创建就跳过
     * if (!maa.CreateInstance(callback)) ...    // 否则创建并注册回调
     * ```
     *
     * 若 `LoadResource` 后就让 `hasInstance()` 返回 true，外壳会**跳过
     * `CreateInstance`**，回调从未注册，后续 `AsyncConnect` 的完成回调
     * 发不出去 → 2 秒超时 → "启动 MaaCore 超时"。
     *
     * 所以这里必须反映"实例已创建"，与资源加载解耦。
     */
    private val instanceCreated = AtomicBoolean(false)

    private var loaded = false
    private var userId: String? = null
    private var packageName: String = GameLauncher.PKG_CN

    /** 屏控（采集 + 触控）。 */
    private val auto = ShellAutomation()

    /** 界面导航器（首次执行任务时懒建）。 */
    @Volatile
    private var navigator: ScreenNavigator? = null

    // ---- 资源 ----

    override fun setUserDir(path: String?): Boolean {
        userId = path
        Ln.i("$TAG: setUserDir($path)")
        return true
    }

    /**
     * 加载资源。
     *
     * 与 MaaCore 的 `LoadResource` 语义对齐（都是"把识别资源准备好"），
     * 但崩铁的资源（模板图 + OCR 模型）随 APK 打包，不需要外部目录。
     */
    override fun loadResource(path: String?): Boolean {
        return runCatching {
            // OpenCV 是模板匹配的前提
            if (!org.opencv.android.OpenCVLoader.initLocal()) {
                Ln.e("$TAG: OpenCV 加载失败")
                return false
            }
            Ln.i("$TAG: OpenCV 已加载")

            // 模板图在 APK 的 assets/starrail/images/ 下。
            //
            // ⚠️ 两条来源，缺一不可：
            //   - App 进程：有 Context -> 走 AssetManager
            //   - 提权进程：**没有 Context**（app_process 拉起，无 AssetManager）
            //     -> 直接读自己的 APK。引擎实际就跑在这里，所以这条是主路径。
            val ctx = context
            val apk = ImageUtils.selfApkPath()
            if (ctx != null) {
                ImageUtils.init(ctx.assets, ASSET_IMAGE_PREFIX)
            } else if (apk != null) {
                ImageUtils.initFromApk(apk, "assets/$ASSET_IMAGE_PREFIX")
            } else {
                Ln.w("$TAG: 既无 context 也无 APK 路径，模板资源未初始化（模板匹配会全部失败）")
            }

            // ---- OCR（PP-OCRv6 det + rec）----
            // 和模板同一套思路：App 进程走 assets，提权进程读 APK。
            // 模型要落到磁盘才能交给 ONNX Runtime，落点必须 shell(uid 2000) 可写。
            initOcr(ctx, apk)

            loaded = true
            true
        }.onFailure { Ln.e("$TAG: loadResource 失败: ${it.message}") }.getOrDefault(false)
    }

    /**
     * 初始化 OCR。
     *
     * ⚠️ **失败不算 loadResource 失败**：没有 OCR 时模板类任务照样能跑
     * （启动游戏、领邮件、关闭游戏都不需要文字），只是按名字选东西的任务
     * 会明确报错。把整条链拖死在这里没有任何好处。
     */
    private fun initOcr(ctx: Context?, apk: String?) {
        val dir = runCatching { java.io.File(OCR_CACHE_DIR).also { it.mkdirs() } }.getOrNull()
        if (dir == null) {
            Ln.w("$TAG: 无法创建 OCR 缓存目录 $OCR_CACHE_DIR")
            return
        }
        OcrEngine.setCacheDir(dir)
        val ok = when {
            ctx != null -> OcrEngine.init(ctx.assets, OcrEngine.ASSET_PREFIX)
            apk != null -> OcrEngine.initFromApk(apk, "assets/${OcrEngine.ASSET_PREFIX}")
            else -> false
        }
        if (ok) Ln.i("$TAG: OCR 已就绪（模型缓存 ${dir.absolutePath}）")
        else Ln.w("$TAG: OCR 初始化失败 —— 需要按文字识别的任务会不可用")
    }

    // ---- 生命周期 ----

    override fun create(): Boolean {
        if (!loaded && !loadResource(null)) return false
        instanceCreated.set(true)
        Ln.i("$TAG: create() 就绪（实例已创建）")
        return true
    }

    override fun destroy() {
        Ln.i("$TAG: destroy()")
        running.set(false)
        auto.releaseCapturer()
        instanceCreated.set(false)
        loaded = false
    }

    /** 见 [instanceCreated] 的说明：语义是"实例已创建"，不是"资源已加载"。 */
    override fun hasInstance(): Boolean = instanceCreated.get()

    override fun shutdown() {
        Ln.i("$TAG: shutdown()")
        running.set(false)
        auto.releaseCapturer()
    }

    override fun uuid(): String = userId ?: "starrail-local"

    // ---- 屏控接入（由 RemoteServiceImpl 调用）----

    /** 虚拟屏创建好后，把 displayId 接进来（复用已存在的采集通道）。 */
    fun attachDisplay(displayId: Int): android.view.Surface? {
        // VirtualDisplayManager 已经 setup 过采集器，这里只接管
        auto.attachExistingCapturer(displayId, CAPTURE_WIDTH, CAPTURE_HEIGHT)
        Ln.i("$TAG: 已接入 display=$displayId, 采集=${CAPTURE_WIDTH}x$CAPTURE_HEIGHT")
        return null
    }

    fun detachDisplay() {
        auto.releaseCapturer()
        auto.setDisplayId(-1)
    }

    fun automation(): ShellAutomation = auto

    fun setPackage(pkg: String) {
        packageName = pkg
        Ln.i("$TAG: 目标游戏包名 = $pkg")
    }

    /** 当前目标游戏包名。 */
    fun targetPackage(): String = packageName

    // ---- 任务 ----

    /**
     * 受理一批任务，**立刻返回**，真正的执行在工作线程上。
     *
     * ## 为什么必须异步
     *
     * 外壳的调用链是 `AppendTask` → `Start()` → 切 RUNNING，全程走 Binder。
     * 而 `Start()` 是从**主线程**发起的：如果在这里同步跑完整个任务链，
     * 崩铁的启动 + 导航要几十秒，外壳会直接 ANR。
     *
     * MaaCore 同样是异步的（`AsstStart` 只是投递到 core 的线程池），
     * 所以这里保持同一语义：**返回值 = 已受理**，成败靠回调。
     */
    override fun runTasks(
        tasks: List<StarRailEngine.QueuedTask>,
        callback: M7aCoreCallback?,
    ): Boolean {
        if (tasks.isEmpty()) {
            Ln.w("$TAG: runTasks 收到空任务链")
            return false
        }
        if (running.getAndSet(true)) {
            Ln.w("$TAG: 已有任务在跑，拒绝新的 Start")
            return false
        }
        Thread({
            try {
                executeChain(tasks, callback)
            } catch (t: Throwable) {
                Ln.e("$TAG: 任务链线程异常退出: ${t.message}")
                emit(callback, AsstMsg.AllTasksCompleted, null)
            } finally {
                running.set(false)
            }
        }, "starrail-task").start()
        return true
    }

    /**
     * 任务链实际执行体（工作线程）。
     *
     * 回调序列与 MaaCore 对齐，外壳的
     * [com.march7th.hkrpg.core.callback.TaskChainHandler] 才认得出进度：
     * ```
     * 每个任务: TaskChainStart → (TaskChainCompleted | TaskChainError)
     * 全部结束: AllTasksCompleted
     * 被停止:   TaskChainStopped + AllTasksCompleted
     * ```
     */
    private fun executeChain(
        tasks: List<StarRailEngine.QueuedTask>,
        callback: M7aCoreCallback?,
    ) {
        Ln.i("$TAG: 开始执行 ${tasks.size} 个任务")
        val nav = navigator ?: ScreenNavigator(auto).also { navigator = it }
        // 把导航每一步的画面导出到 core 根目录，便于采集模板。
        // 虚拟屏画面抓不到（screencap -d 不支持 VD），只能从引擎内部导出。
        val dumpRoot = java.io.File(com.march7th.hkrpg.remote.CoreDataDir.ROOT)
        nav.debugDumpRoot = dumpRoot
        // ---- 首次校准：全量记录沿途每一帧 ----
        // 目的：避免"改代码→构建→装机→实测"这个十几分钟一轮的循环。
        // 打开后用户只要正常跑一次任务，就能一次性拿到整套界面帧，
        // 然后离线把所有坐标/模板都量完。
        //
        // 每轮开始前清空旧帧，保证这一轮采到的就是完整的一套。
        if (CALIBRATION_CAPTURE) {
            runCatching {
                java.io.File(dumpRoot, "debug/screenshots").listFiles()
                    ?.filter { it.name.startsWith("cap_") }
                    ?.forEach { it.delete() }
            }
            nav.captureAll = true
            Ln.i("$TAG: 校准模式已开启 —— 本轮所有取帧都会存到 ${dumpRoot}/debug/screenshots/cap_*.png")
        }
        // 把"是否已取消"透传给任务实现：
        // 引擎的 running 标志只在任务之间检查，任务内部（清体力要跑
        // 几分钟）中途取消拦不住 —— 用户点停止后仍在操作游戏。
        // 导航层的等待也要能被打断（它内部有大量 900ms 的 SETTLE_MS）
        nav.isCancelled = { !running.get() }
        val impl = StarRailTasks(auto, packageName, nav, isCancelled = { !running.get() })
        var stopped = false
        // 是否因 StartUp 失败而中止（区别于用户手动停止）
        var remainedAborted = false

        for (task in tasks) {
            if (!running.get()) {
                Ln.w("$TAG: 已被停止，中断执行")
                stopped = true
                break
            }
            val def = StarRailTaskRegistry.find(task.type)
            if (def == null) {
                Ln.w("$TAG: 跳过未知任务 ${task.type}")
                continue
            }
            Ln.i("$TAG: 执行「${def.label}」(${task.type}) 来源=${def.source}")

            emit(callback, AsstMsg.TaskChainStart, chainJson(task))

            val r = runCatching { impl.run(task.type, task.params) }
                .onFailure { Ln.e("$TAG: 「${def.label}」异常: ${it.message}") }
                .getOrElse { StarRailTasks.TaskResult(task.type, false, "异常: ${it.message}") }

            Ln.i("$TAG: 「${def.label}」结果=${if (r.ok) "成功" else "失败"} (${r.detail})")
            emit(
                callback,
                if (r.ok) AsstMsg.TaskChainCompleted else AsstMsg.TaskChainError,
                chainJson(task, error = if (r.ok) null else r.detail),
            )

            // ---- StartUp 失败 = 后面全部没意义，直接中止 ----
            //
            // 实测：没装游戏（或包名不对）时 StartUp 失败，但「邮件」
            // 仍然继续跑，对着黑屏做 OCR、点坐标，日志刷几十行
            // 「取帧失败（3 次）」，最后才报一堆莫名其妙的错误。
            //
            // 所有任务的前提都是"游戏在大厅"，StartUp 失败就说明这个
            // 前提不成立。继续跑纯属浪费时间和刷屏，而且用户看到
            // 一连串失败会以为是权限或兼容性问题。
            if (task.type == "StartUp" && !r.ok && running.get()) {
                Ln.w("$TAG: 启动游戏失败，中止后续 ${tasks.size - tasks.indexOf(task) - 1} 个任务")
                stopped = true
                remainedAborted = true
                break
            }

            // 任务之间留间隔，避免界面还在转场就发下一步
            if (running.get()) Thread.sleep(600)
        }

        if (stopped) emit(callback, AsstMsg.TaskChainStopped, null)
        emit(callback, AsstMsg.AllTasksCompleted, null)
        Ln.i("$TAG: 任务链结束（stopped=$stopped）")
    }

    /** 构造 `TaskChainStart` / `TaskChainCompleted` / `TaskChainError` 的 details。 */
    private fun chainJson(task: StarRailEngine.QueuedTask, error: String? = null): String =
        buildString {
            append("""{"taskid":${task.id},"taskchain":"${task.type}"""")
            if (error != null) {
                // 外壳读 details.details.error（WPF 读根级 error，两处都写更稳）
                val esc = error.replace("\\", "\\\\").replace("\"", "\\\"")
                append(""","error":"$esc","details":{"error":"$esc"}""")
            }
            append("}")
        }

    /** 发一条回调；Binder 对端可能已死，绝不能让异常打断任务链。 */
    private fun emit(callback: M7aCoreCallback?, msg: AsstMsg, json: String?) {
        if (callback == null) return
        runCatching { callback.onCallback(msg.value, json) }
            .onFailure { Ln.w("$TAG: 回调 ${msg.name} 失败: ${it.message}") }
    }

    override fun stop(): Boolean {
        Ln.i("$TAG: stop()")
        running.set(false)
        auto.cancelGesture()
        return true
    }

    override fun isRunning(): Boolean = running.get()

    override fun backToHome(): Boolean {
        // TODO: 接入界面导航图后实现（沿导航图走到 main）
        Ln.w("$TAG: backToHome 待实现（需界面导航图）")
        return false
    }

    // ---- 画面与输入 ----

    override fun screencapWidth(): Int = auto.width

    override fun screencapHeight(): Int = auto.height

    override fun screencapToBuffer(): Boolean = auto.takeScreenshot()

    override fun framePng(): ByteArray? = auto.framePng()

    /** BGRA 原始字节。外壳的 `GetImageBgr` 会用它。 */
    override fun frameBgra(): ByteArray? {
        val bmp = auto.screenshot ?: return null
        return runCatching {
            val w = bmp.width
            val h = bmp.height
            val pixels = IntArray(w * h)
            bmp.getPixels(pixels, 0, w, 0, 0, w, h)
            val out = ByteArray(w * h * 4)
            var i = 0
            for (p in pixels) {
                // ARGB -> BGRA
                out[i++] = (p and 0xFF).toByte()          // B
                out[i++] = ((p shr 8) and 0xFF).toByte()   // G
                out[i++] = ((p shr 16) and 0xFF).toByte()  // R
                out[i++] = ((p shr 24) and 0xFF).toByte()  // A
            }
            out
        }.onFailure { Ln.e("$TAG: BGRA 转换失败: ${it.message}") }.getOrNull()
    }

    override fun click(x: Int, y: Int): Boolean = auto.tap(x, y)
}
