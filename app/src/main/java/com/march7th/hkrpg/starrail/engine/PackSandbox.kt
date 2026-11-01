package com.march7th.hkrpg.starrail.engine

import com.march7th.hkrpg.remote.CoreDataDir
import java.io.File

/**
 * 自定义任务包的**运行时沙箱边界**。
 *
 * 导入时的校验（[com.march7th.hkrpg.data.resource.CustomTaskPackValidator]）是
 * 「告诉作者哪里写错了」；这里是「就算校验被绕过也不能出事」。
 * 两者职责不同，**不能只靠其中一个**：校验器可以被旧版本回退、也可以被
 * 直接改磁盘上 task.json 的人绕过，所以运行期必须再拦一次。
 *
 * ## 任务包能做什么
 *
 * - 看屏幕：模板匹配、OCR、数颜色块、算颜色占比；
 * - 动手：点、长按、滑、等；
 * - 算数：变量、四则运算、比较、布尔；
 * - 读自己的 `assets/` 图片。
 *
 * ## 任务包**不能**做什么（不是"目前没实现"，是"结构上不存在这条路径"）
 *
 * | 能力 | 为什么没有 |
 * | --- | --- |
 * | 执行任意代码 | 没有 `eval` 类 op；执行器是硬编码的 `when`，任务文件只是数据 |
 * | 启动 / 唤起其它 App | 没有 `am start` 类 op，执行器只拿到 `ShellAutomation` 的注入方法，没有任何 App 启动入口 |
 * | 执行 shell 命令 | 同上；`Runtime.exec` 只存在于 `inputText` 内部，且没开放成 op |
 * | 访问网络 | 执行器不持有任何网络客户端 |
 * | 读写包外文件 | 见 [assetRel]，模板路径必须落在本包 `assets/` 内 |
 *
 * 新增 op 时，**先读这张表**：任何一条落在"不能做"里的，都不该被加进来。
 * 需要更自由的能力时，正确做法是加**参数化的识别原语**（比如再加一种计数方式），
 * 而不是加"能执行任意东西"的通道。
 */
object PackSandbox {

    /** 单次执行总时长上限。超过就中止 —— 卡死的流程不该无限点下去 */
    const val MAX_TOTAL_MS = 20 * 60 * 1000L

    /**
     * 单次执行的总步数预算（含循环展开）。
     *
     * 步骤上限 200 只是**静态**的，`repeat 20 × 嵌套 repeat 20` 就能到几万次。
     * 这个预算按"实际执行到第几步"算，循环和分支都计入。
     */
    const val MAX_EXEC_STEPS = 5_000

    /** `whileExpr` 单次最多循环多少轮 */
    const val MAX_WHILE_ITER = 200

    /** 流程嵌套深度上限（导入时校验一次，运行期再拦一次） */
    const val MAX_NEST_DEPTH = 4

    /** 变量值最长多少字符，防止 OCR 出一整屏文本撑爆内存 */
    const val MAX_VAR_TEXT = 256

    /**
     * 校验模板路径，返回可交给 [ImageUtils] 的相对路径。
     *
     * 返回 `taskpacks/<id>/assets/x.png` 这种相对 core 根的形式 ——
     * [ImageUtils.setDiskRoot] 已经指到 core 根，直接拼即可。
     *
     * 三道闸：
     * 1. 必须以 `assets/` 开头，不能含 `..`（挡路径穿越的字面写法）；
     * 2. `canonicalPath` 必须仍在包目录内（挡符号链接、`./` 变形等）；
     * 3. 文件必须真实存在（否则模板匹配会静默失败，日志看不懂）。
     *
     * @return `null` 表示路径非法或文件不存在
     */
    fun assetRel(packRoot: File, packId: String, rel: String): String? {
        val clean = rel.trim().replace('\\', '/')
        if (!clean.startsWith("assets/")) return null
        if (clean.split('/').any { it == ".." || it.isEmpty() }) return null

        val base = runCatching { packRoot.canonicalFile }.getOrNull() ?: return null
        val target = runCatching { File(packRoot, clean).canonicalFile }.getOrNull() ?: return null
        if (!target.path.startsWith(base.path + File.separator)) return null
        if (!target.isFile) return null

        return "${CoreDataDir.TASKPACKS_DIR}/$packId/$clean"
    }

    /** 变量值入库前的收口：裁长度、去控制字符。 */
    fun clampVarText(raw: String): String =
        raw.replace(Regex("""[\u0000-\u001F\u007F]"""), " ")
            .trim()
            .let { if (it.length > MAX_VAR_TEXT) it.substring(0, MAX_VAR_TEXT) else it }

    // ==================== 执行预算 ====================

    /**
     * 一次执行的预算账本。
     *
     * 单独做成类而不是 runner 里的两个计数器：`ScriptedTaskRunner` 是长生命周期
     * 对象（Koin single），字段残留会让第二次执行一开始就超预算。
     */
    class Budget(private val isCancelled: () -> Boolean) {
        private val startedAt = System.currentTimeMillis()
        private var steps = 0

        /**
         * 每执行一步调用一次。
         *
         * @throws Exceeded 预算用完 / 用户取消
         */
        fun tick() {
            if (isCancelled()) throw Exceeded("用户已停止")
            if (++steps > MAX_EXEC_STEPS) {
                throw Exceeded("任务包执行步数超过上限 $MAX_EXEC_STEPS（疑似死循环）")
            }
            val elapsed = System.currentTimeMillis() - startedAt
            if (elapsed > MAX_TOTAL_MS) {
                val min = MAX_TOTAL_MS / 60_000
                throw Exceeded("任务包执行超过 $min 分钟，已强制中止")
            }
        }

        /** 剩余可用毫秒数，给轮询型 op 当作自己的 deadline 上限。 */
        fun remainingMs(): Long {
            val elapsed = System.currentTimeMillis() - startedAt
            return (MAX_TOTAL_MS - elapsed).coerceAtLeast(0)
        }
    }

    /** 预算耗尽。执行器把它转成正常的失败结果，不当作崩溃。 */
    class Exceeded(message: String) : Exception(message)
}
