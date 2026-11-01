package com.march7th.hkrpg.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * 自定义任务包清单（`task.json`）。
 *
 * 格式规范见 `docs/task-file.md`。这里刻意做成**一个大平铺结构**而不是
 * 多态 sealed class：
 *
 * - 任务文件是手写的，字段写错（`op` 拼错、`x` 写成字符串）时，
 *   多态判别器会让整份文件在反序列化阶段直接抛异常，用户只看到
 *   「解析失败」，不知道错在哪一处；
 * - 平铺 + 全部可空，则任何字段缺失都能先解出来，再由
 *   [com.march7th.hkrpg.data.resource.CustomTaskPackValidator] 逐条指出
 *   「第 7 步 tapTemplate 缺少 image」，报错质量完全不在一个量级。
 *
 * 代价是字段列表长，但这份代码只写一次，用户体验每天都用。
 */
@Serializable
data class CustomTaskPack(
    val schemaVersion: Int = 1,
    val id: String = "",
    val name: String = "",
    val author: String = "",
    val version: String = "",
    val description: String = "",
    /**
     * 低于此 versionCode 的 App 拒绝导入。
     *
     * 默认 11 = 1.0.0，也就是「第一个能跑自定义任务的版本」——
     * 更老的 App 根本没有执行器，装进去只会安静地跑空。
     */
    val minAppVersionCode: Int = 11,
    val params: List<PackParam> = emptyList(),
    val steps: List<PackStep> = emptyList(),
)

/** 用户可调参数。表单在任务卡片上渲染，值通过 `{key}` 在步骤里引用。 */
@Serializable
data class PackParam(
    val key: String = "",
    val label: String = "",
    /** `int` / `bool` / `string`，其它值按 `string` 处理 */
    val type: String = "string",
    /**
     * 默认值。
     *
     * 用 [JsonElement] 而不是 `String`：JSON 里 `"default": 1` 和
     * `"default": "1"` 都是合法的，声明成 String 会让前者整份解析失败。
     */
    val default: JsonElement? = null,
    val min: Int? = null,
    val max: Int? = null,
)

/**
 * 一个步骤。
 *
 * 所有动作共用一个结构，未使用的字段留空 —— 校验器按 `op` 检查必填项。
 */
@Serializable
data class PackStep(
    val op: String = "",
    /** 失败不中断流程（只记 WARN） */
    val optional: Boolean = false,
    val note: String = "",

    // ---- log / waitText / tapText / assertText ----
    val text: String? = null,
    // ---- wait ----
    val ms: Long? = null,

    // ---- tap / longPress ----
    val x: Double? = null,
    val y: Double? = null,
    val durationMs: Long? = null,

    // ---- swipe ----
    val fromX: Double? = null,
    val fromY: Double? = null,
    val toX: Double? = null,
    val toY: Double? = null,

    // ---- 模板类 ----
    /** 相对任务包根的路径，如 `assets/entry.png` */
    val image: String? = null,
    val threshold: Double? = null,
    /** `[x, y, w, h]`，0~1 归一化 */
    val region: List<Double>? = null,
    /** 连续命中几帧才算出现 */
    val stable: Int? = null,
    val offsetX: Double? = null,
    val offsetY: Double? = null,

    val timeoutMs: Long? = null,

    // ---- 颜色识别（ifCount / waitColor）----
    /**
     * 目标颜色，`#RRGGBB` 或 `RRGGBB`（大小写不敏感）。
     *
     * 写成 `43,230,255` 这种十进制也行 —— 见 `docs/task-file.md` 的颜色一节。
     */
    val color: String? = null,
    /** 单通道容差 0~128，默认 32。两个颜色各通道差都在容差内就算同色 */
    val tolerance: Int? = null,
    /** 连通块面积下限（像素，按 1920×1080 算），默认 80 */
    val minArea: Int? = null,
    /** 连通块面积上限，默认 4000 */
    val maxArea: Int? = null,
    /** 比较符：`>=` `>` `<=` `<` `==`，默认 `>=` */
    val compare: String? = null,
    /** 比较目标值（int） */
    val value: Int? = null,
    /** 颜色占比阈值 0~1（`waitColor` 用），默认 0.02 */
    val ratio: Double? = null,
    /** `swipeUntil` 每次滑动后的等待，默认 400ms */
    val intervalMs: Long? = null,
    /** `swipeUntil` 最多滑几次，默认 8，上限 30 */
    val maxTries: Int? = null,

    // ---- 变量 / 表达式（setVar / ifExpr / whileExpr）----
    /**
     * 表达式源码，如 `"starCount >= 3"`、`"left - 1"`。
     *
     * 语法见 `docs/task-file.md` 的「表达式」一节，只支持整数算术、比较、
     * 布尔和 `min/max/abs/clamp/len` 五个函数。**没有对象、没有循环、
     * 不能调外部** —— 详见 [PackExpr] 的说明。
     */
    val expr: String? = null,
    /** 结果写进哪个变量（`setVar` / `readNumber` / `readText` 用） */
    val to: String? = null,

    // ---- 流程控制 ----
    /** `repeat` 的次数：整数或 `"{key}"` 这种参数引用 */
    val times: JsonElement? = null,
    /** `repeat` 的子步骤 */
    val steps: List<PackStep>? = null,
    /** `ifTemplate` / `ifText` 的分支 */
    val then: List<PackStep>? = null,
    @SerialName("else") val elseSteps: List<PackStep>? = null,

    // ---- stop ----
    val ok: Boolean? = null,
)

/** 「已安装任务包」列表项。 */
data class InstalledTaskPack(
    val id: String,
    val name: String,
    val author: String,
    val version: String,
    val description: String,
    val stepCount: Int,
    /** 包目录（App 侧绝对路径） */
    val dir: java.io.File,
) {
    val displayName: String get() = name.ifBlank { id }
}
