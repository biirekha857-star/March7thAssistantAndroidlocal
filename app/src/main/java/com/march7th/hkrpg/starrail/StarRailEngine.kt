package com.march7th.hkrpg.starrail

import com.march7th.hkrpg.M7aCoreCallback

/**
 * 崩铁引擎接口 —— **外壳与内核之间的边界**。
 *
 * 外壳（Compose UI / 任务编排 / 调度 / 虚拟屏 / 输入注入）只认
 * [com.march7th.hkrpg.M7aCoreService] 这个 AIDL 插槽，
 * 而 [com.march7th.hkrpg.remote.StarRailCoreServiceImpl] 把那些调用翻译成本接口。
 *
 * 这样设计的好处：内核实现可以完全独立地演进/替换，
 * 外壳一行都不用改（外壳不知道跑的是明日方舟还是崩铁）。
 */
interface StarRailEngine {

    companion object {
        /** 版本号。外壳会显示/比较它。 */
        const val VERSION = "starrail-0.1.0"
    }

    /**
     * 一条已排队的任务。
     *
     * [id] 由外壳的 `AppendTask` 分配，**必须原样带进回调** ——
     * 外壳的 `TaskChainStatusTracker` 用它在 UI 上把任务标成进行中/完成/失败。
     */
    data class QueuedTask(val id: Int, val type: String, val params: String)

    // ---- 生命周期 ----

    fun setUserDir(path: String?): Boolean
    fun loadResource(path: String?): Boolean
    fun create(): Boolean
    fun destroy()
    fun hasInstance(): Boolean
    fun shutdown()
    fun uuid(): String

    // ---- 任务 ----

    /**
     * 顺序执行一批任务。
     *
     * ⚠️ **返回值语义 = "任务链已被受理"，不是"任务成功"**。
     * 与 MaaCore 的 `AsstStart` 对齐：外壳拿到 true 就切 RUNNING，
     * 真正的成败通过回调（`TaskChainCompleted` / `TaskChainError` /
     * `AllTasksCompleted`）异步汇报。
     *
     * @param tasks 按外壳给出的顺序执行
     * @param callback 进度回调（可空）。外层靠它更新 UI 与写日志
     */
    fun runTasks(tasks: List<QueuedTask>, callback: M7aCoreCallback?): Boolean

    fun stop(): Boolean
    fun isRunning(): Boolean

    /** 回到游戏主界面。 */
    fun backToHome(): Boolean

    // ---- 画面与输入 ----

    fun screencapWidth(): Int
    fun screencapHeight(): Int

    /** 抓一帧到内部缓冲。 */
    fun screencapToBuffer(): Boolean

    /** 内部缓冲的 PNG 字节。 */
    fun framePng(): ByteArray?

    /** 内部缓冲的 BGRA 原始字节（宽*高*4）。 */
    fun frameBgra(): ByteArray?

    fun click(x: Int, y: Int): Boolean
}
