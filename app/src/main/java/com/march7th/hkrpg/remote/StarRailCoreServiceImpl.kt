package com.march7th.hkrpg.remote

import android.os.MemoryFile
import android.os.ParcelFileDescriptor
import com.march7th.hkrpg.M7aCoreCallback
import com.march7th.hkrpg.M7aCoreService
import com.march7th.hkrpg.core.AsstMsg
import com.march7th.hkrpg.starrail.StarRailEngine
import com.march7th.hkrpg.starrail.StarRailTaskRegistry
import com.march7th.hkrpg.third.Ln
import java.util.concurrent.atomic.AtomicReference

/**
 * **崩铁内核** —— `M7aCoreService` 这个 AIDL 插槽的另一种实现。
 *
 * ## 这是什么
 *
 * 原版 [CoreServiceImpl] 把 24 个方法全部转发给 **MaaCore**
 * （明日方舟的原生引擎，JNA 调用）。
 * 本类实现**同一个 AIDL 接口**，但转发给 [StarRailEngine]（崩铁引擎）。
 *
 * 这就是「**模具外壳不动，只换电路板**」的落点：
 * 外壳（Compose UI / Shizuku / 虚拟屏 / 任务编排 / 调度）完全不知道内核换了，
 * 仍然对着 `M7aCoreService` 这个插槽说话。
 *
 * ## 接口语义对照
 *
 * | M7aCoreService 方法 | 明日方舟语义 | 崩铁语义（本实现） |
 * |---|---|---|
 * | `LoadResource(path)` | 加载 MaaCore 资源包 | 初始化 OpenCV + 模板/OCR 资源 |
 * | `AppendTask(type, params)` | 追加 Roguelike/Fight/Infrast… | 追加 邮件/每日实训/成就… |
 * | `AsyncScreencap` / `GetImage` | 向 MaaCore 取画面 | 取虚拟屏帧 |
 * | `AsyncClick` | 点击 | 触控注入（定向 displayId） |
 * | `Start` / `Stop` / `Running` | 任务链控制 | 同左 |
 */
class StarRailCoreServiceImpl(
    private val engine: StarRailEngine,
) : M7aCoreService.Stub() {

    companion object {
        private const val TAG = "StarRailCore"
    }

    private val callback = AtomicReference<M7aCoreCallback?>()

    /** 已追加但未开始的任务：taskId -> (类型, 参数) */
    private val pendingTasks = LinkedHashMap<Int, Pair<String, String>>()
    private var nextTaskId = 1
    private val tasksLock = Any()

    // ==================== 生命周期 ====================

    override fun destroy() {
        Ln.i("$TAG: destroy()")
        runCatching { engine.shutdown() }
        callback.set(null)
        synchronized(tasksLock) { pendingTasks.clear() }
    }

    override fun SetUserDir(path: String?): Boolean {
        Ln.i("$TAG: SetUserDir($path)")
        return runCatching { engine.setUserDir(path) }.getOrDefault(false)
    }

    override fun LoadResource(path: String?): Boolean {
        Ln.i("$TAG: LoadResource($path)")
        return runCatching { engine.loadResource(path) }
            .onFailure { Ln.e("$TAG: LoadResource 失败: ${it.message}") }
            .getOrDefault(false)
    }

    override fun SetStaticOption(key: Int, value: String?): Boolean {
        // MaaCore 的静态选项（CPU 占用策略、GPU 加速等）。
        // 崩铁引擎没有对应概念，记录后忽略即可 —— 不能让外壳因此报错。
        Ln.i("$TAG: SetStaticOption($key, $value) [已忽略]")
        return true
    }

    override fun CreateInstance(callback: M7aCoreCallback?): Boolean {
        Ln.i("$TAG: CreateInstance()")
        this.callback.set(callback)
        return runCatching { engine.create() }
            .onFailure { Ln.e("$TAG: CreateInstance 失败: ${it.message}") }
            .getOrDefault(false)
    }

    override fun DestroyInstance() {
        Ln.i("$TAG: DestroyInstance()")
        runCatching { engine.destroy() }
        callback.set(null)
    }

    override fun hasInstance(): Boolean = engine.hasInstance()

    override fun SetInstanceOption(key: Int, value: String?): Boolean {
        Ln.i("$TAG: SetInstanceOption($key, $value) [已忽略]")
        return true
    }

    // ==================== 连接 ====================
    // 崩铁是单机手游，没有 MaaCore 那套 adb 连接概念。
    // 外壳仍会调用这几个方法来判断"是否连上"，所以要在就绪时返回成功。

    override fun AsyncConnect(
        path: String?, address: String?, config: String?, block: Boolean,
    ): Int {
        Ln.i("$TAG: AsyncConnect($path, $address) -> 由本地引擎代替（无需 adb 连接）")
        // ⚠️ 外壳不是看返回值，而是等一条 AsyncCallInfo 回调（details.ret=true），
        // 2 秒收不到就判 MAA_CONNECT_ERROR。所以必须主动回调。
        //
        // 关键：**必须同步发出**。外壳是 `AsyncConnect(...)` 之后才 `deferred.await()`，
        // 若这里另开线程延迟回调，存在两个风险：
        //   1) Binder 线程池耗尽时不调度
        //   2) 回调比 await 先到达（外壳还没注册 deferred）
        // 同步回调由外壳的 `onAsyncConnectCallback` 直接 complete 已注册的 deferred。
        val payload = """{"what":"AsyncCallInfo","details":{"ret":true}}"""
        val cb = callback.get()
        if (cb == null) {
            Ln.e("$TAG: 连接回调失败 —— callback 为 null（CreateInstance 未调用）")
        } else {
            runCatching {
                Ln.i("$TAG: 发出连接成功回调 msg=${AsstMsg.AsyncCallInfo.value}")
                cb.onCallback(AsstMsg.AsyncCallInfo.value, payload)
                Ln.i("$TAG: 连接回调已发出")
            }.onFailure { Ln.e("$TAG: 连接回调异常: ${it.message}") }
        }
        return 1
    }

    override fun SetConnectionExtras(name: String?, extras: String?) {
        Ln.i("$TAG: SetConnectionExtras($name) [已忽略]")
    }

    /**
     * 是否已连接。
     *
     * 崩铁没有 adb 连接概念，实例建好且资源就绪即视为已连接。
     * 用 [StarRailEngine.hasInstance]（实例已创建）而不是 `loaded`，
     * 避免外壳在 `LoadResource` 后误判"已连接"从而跳过 `CreateInstance`。
     */
    override fun Connected(): Boolean = engine.hasInstance()

    // ==================== 任务 ====================

    /**
     * **换内核的核心入口**。
     *
     * 外壳按 `CoreTaskType.value` 传 `type`，我们把它映射到崩铁任务
     * （见 [StarRailTaskRegistry]）。
     */
    override fun AppendTask(type: String?, params: String?): Int {
        val t = type ?: return -1
        if (!StarRailTaskRegistry.isKnown(t)) {
            Ln.w("$TAG: AppendTask 收到未知任务类型 '$t'（崩铁内核不支持）")
            return -1
        }
        val id = synchronized(tasksLock) {
            val n = nextTaskId++
            pendingTasks[n] = t to (params ?: "{}")
            n
        }
        Ln.i("$TAG: AppendTask($t) -> taskId=$id")
        return id
    }

    override fun SetTaskParams(taskId: Int, params: String?): Boolean {
        synchronized(tasksLock) {
            val cur = pendingTasks[taskId] ?: return false
            pendingTasks[taskId] = cur.first to (params ?: "{}")
        }
        Ln.i("$TAG: SetTaskParams(taskId=$taskId)")
        return true
    }

    override fun GetTasksList(): IntArray =
        synchronized(tasksLock) { pendingTasks.keys.toIntArray() }

    /**
     * 开始执行任务链。
     *
     * ⚠️ **返回值 = "已受理"，不是"已成功"**（与 MaaCore 的 `AsstStart` 一致）。
     *
     * 外壳拿到 `true` 就切 RUNNING，随后靠回调
     * （`TaskChainStart/Completed/Error` + `AllTasksCompleted`）收尾。
     * 早先这里直接返回 `runTasks` 的**执行结果**，导致任何一个任务失败
     * （例如账号没有可领邮件）都会被外壳报成 "MaaCore 启动失败"，
     * 且会话永远等不到 `AllTasksCompleted`，卡在 RUNNING。
     */
    override fun Start(): Boolean {
        val snapshot = synchronized(tasksLock) {
            val s = pendingTasks.map { (id, v) -> StarRailEngine.QueuedTask(id, v.first, v.second) }
            pendingTasks.clear()
            s
        }
        if (snapshot.isEmpty()) {
            Ln.w("$TAG: Start() 时没有待执行任务")
            return false
        }
        Ln.i("$TAG: Start() 共 ${snapshot.size} 个任务: ${snapshot.joinToString { it.type }}")
        return runCatching { engine.runTasks(snapshot, callback.get()) }
            .onFailure { Ln.e("$TAG: 投递失败: ${it.message}") }
            .getOrDefault(false)
    }

    override fun Stop(): Boolean {
        Ln.i("$TAG: Stop()")
        return runCatching { engine.stop() }.getOrDefault(false)
    }

    override fun Running(): Boolean = engine.isRunning()

    /** 回到主界面（外壳在任务前后会调用）。 */
    override fun BackToHome(): Boolean {
        Ln.i("$TAG: BackToHome()")
        return runCatching { engine.backToHome() }.getOrDefault(false)
    }

    // ==================== 画面与输入 ====================
    // 注意：壳里 RemoteServiceImpl 已经有完整的截图/多指触控实现，
    // 这几个方法主要用于满足外壳的调用契约（如画面预览）。

    override fun AsyncScreencap(block: Boolean): Int {
        val w = engine.screencapWidth()
        val h = engine.screencapHeight()
        engine.screencapToBuffer()
        Ln.i("$TAG: AsyncScreencap($block) -> ${w}x$h")
        return 0
    }

    override fun AsyncClick(x: Int, y: Int, block: Boolean): Int {
        return if (engine.click(x, y)) 0 else -1
    }

    /** 返回画面（PNG 流）。外壳用它做预览。 */
    override fun GetImage(): ParcelFileDescriptor? = makeImageFd(bgra = false)

    /** 返回画面（BGRA 原始字节）。 */
    override fun GetImageBgr(): ParcelFileDescriptor? = makeImageFd(bgra = true)

    /**
     * 把引擎缓冲的画面包成 `ParcelFileDescriptor`。
     *
     * 用 [MemoryFile] 而非临时文件：这是**每帧都要走**的热路径，
     * 落盘再读回会带来明显的延迟与闪存磨损。
     */
    private fun makeImageFd(bgra: Boolean): ParcelFileDescriptor? = runCatching {
        val bytes = if (bgra) engine.frameBgra() else engine.framePng()
        if (bytes == null || bytes.isEmpty()) {
            Ln.w("$TAG: 取画面为空")
            return null
        }
        val mf = MemoryFile(null, bytes.size)
        mf.writeBytes(bytes, 0, 0, bytes.size)
        val method = MemoryFile::class.java.getDeclaredMethod("getFileDescriptor")
        method.isAccessible = true
        val fd = method.invoke(mf) as java.io.FileDescriptor
        ParcelFileDescriptor.dup(fd)
    }.onFailure { Ln.e("$TAG: 构造画面 FD 失败: ${it.message}") }.getOrNull()

    // ==================== 杂项 ====================

    override fun GetVersion(): String = StarRailEngine.VERSION

    override fun GetUUID(): String = engine.uuid()
}
