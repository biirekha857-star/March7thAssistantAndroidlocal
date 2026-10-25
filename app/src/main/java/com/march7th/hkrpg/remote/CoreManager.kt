package com.march7th.hkrpg.remote

import android.content.Context
import com.march7th.hkrpg.core.CoreNativeLib
import com.march7th.hkrpg.starrail.StarRailLocalEngine
import com.march7th.hkrpg.third.Ln

/**
 * 内核管理器。
 *
 * ## 当前状态：已换芯为崩铁
 *
 * 原版这里 `Native.load("MaaCore")` 加载明日方舟引擎，并把 [CoreServiceImpl]
 * 作为 `M7aCoreService` 的实现。
 *
 * 现在 [maaService] 返回 [StarRailCoreServiceImpl]，把同一个 AIDL 接口
 * 转发给崩铁引擎 [StarRailLocalEngine]。**外壳一行没改**。
 *
 * [MaaContext] 仍保留：一是 `RemoteServiceImpl.version()` 会读它，
 * 二是将来若要并行支持方舟可以复用。MaaCore 缺失时安静返回 null。
 */
object CoreManager {
    private const val TAG = "CoreManager"

    /** App 上下文。由 [install] 注入。 */
    @Volatile
    private var appContext: Context? = null

    fun install(context: Context) {
        appContext = context.applicationContext ?: context
        Ln.i("$TAG: install(context) 完成")
    }

    /**
     * 前代内核（MaaCore）的原生库 —— **已废弃，恒为 null**。
     *
     * ## 为什么删掉真正的加载
     *
     * 原来这里会 `Native.load("MaaCore", CoreNativeLib::class.java)`，
     * 但崩铁内核（[StarRailLocalEngine]）**完全不依赖它** ——
     * 识别用 OpenCV + ONNX，采集和注入用自己的 `libbridge.so`。
     *
     * 而那次加载每次启动都失败：
     *
     * ```
     * UnsatisfiedLinkError: Unable to load library 'MaaCore':
     *   dlopen failed: library "libMaaCore.so" not found
     * ```
     *
     * 失败虽被兜住，但线上出现的问题很一致：
     * **服务连上 40~50 秒后 `:shizuku_service` 无堆栈消失**
     * （`child exited with status=6`），表现就是"任务服务异常终止"。
     * 而每次启动日志末尾都恰好停在这段 JNA 加载失败上。
     *
     * 加上 JNA 会往 `/data/local/tmp` 解压 jnidispatch、失败后留下
     * 半初始化的静态状态，对后面毫无意义还增加不确定性。
     *
     * 所以直接返回 null。将来真要支持方舟，再单独加回来。
     */
    val MaaContext: CoreNativeLib? = null

    /** 崩铁引擎实例（提权进程内持有，直连采集与注入）。 */
    val starRailEngine: StarRailLocalEngine by lazy { StarRailLocalEngine(appContext) }

    /** 外壳看到的 `M7aCoreService` 实现 —— 现在是崩铁内核。 */
    val maaService: StarRailCoreServiceImpl by lazy { StarRailCoreServiceImpl(starRailEngine) }

    fun destroy() {
        Ln.i("$TAG: destroy()")
        runCatching { starRailEngine.destroy() }
            .onFailure { Ln.w("$TAG: 崩铁引擎销毁失败: ${it.message}") }

        // 前代内核（MaaCore）现在是恒 null 的常量，永远不需要销毁。
        // 这里保留判断是为了将来真加回方舟内核时不用改逻辑。
        val legacy = MaaContext
        if (legacy == null) {
            Ln.i("$TAG: 前代内核不存在，跳过其销毁")
            return
        }
        runCatching { CoreServiceImpl(legacy).DestroyInstance() }
    }
}
