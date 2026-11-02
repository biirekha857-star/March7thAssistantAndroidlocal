package com.march7th.hkrpg.data.model

import com.march7th.hkrpg.core.task.CoreTaskParams
import com.march7th.hkrpg.core.task.CoreTaskType
import com.march7th.hkrpg.utils.i18n.UiText
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.Serializable

/**
 * 自定义任务（导入的任务包）的节点配置。
 *
 * 只存 `packId`，流程本体留在 `taskpacks/<packId>/task.json` 里 ——
 * 任务链要序列化进 DataStore，把几百步流程抄进去会让配置膨胀，
 * 而且任务包升级后链里的旧副本会和磁盘上的新版本打架。
 *
 * `packName` 是**展示用缓存**：包被删掉后，任务卡片上还能显示
 * 「Daily Echo（已删除）」而不是一片空白。
 */
@Serializable
data class CustomTaskConfig(
    @SerialName("taskType")
    val type: String = "Custom",
    val packId: String = "",
    val packName: String = "",
    /**
     * 用户在卡片表单里填的参数值：`key` → 文本。
     *
     * 只存**用户改过的**那一份；没填的键执行时回落到包里声明的 `default`，
     * 这样包升级改了默认值，没动过的参数会自动跟着更新。
     */
    val values: Map<String, String> = emptyMap(),
) : TaskParamProvider {

    override fun toTaskParams(ctx: TaskParamContext): List<CoreTaskParams> {
        if (packId.isBlank()) {
            ctx.appendLog(UiText.Dynamic("自定义任务还没有选择任务包"), LogLevel.ERROR)
            return emptyList()
        }
        // values 用 JSON 对象带给内核；执行器拿它覆盖包里的 default。
        // 这里手写而不是序列化 data class：只是两个字段，没必要为一个
        // 中间态再引入一个 @Serializable 包装类。
        val payload = buildJsonObject {
            put("packId", packId)
            putJsonObject("values") {
                values.forEach { (k, v) -> put(k, v) }
            }
        }
        return listOf(CoreTaskParams(CoreTaskType.CUSTOM, payload.toString()))
    }

    companion object {
        const val TASK_TYPE = "Custom"
    }
}
