package com.march7th.hkrpg.data.model

import com.march7th.hkrpg.core.task.CoreTaskParams
import com.march7th.hkrpg.core.task.CoreTaskType
import com.march7th.hkrpg.utils.i18n.UiText
import kotlinx.serialization.SerialName
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
) : TaskParamProvider {

    override fun toTaskParams(ctx: TaskParamContext): List<CoreTaskParams> {
        if (packId.isBlank()) {
            ctx.appendLog(UiText.Dynamic("自定义任务还没有选择任务包"), LogLevel.ERROR)
            return emptyList()
        }
        return listOf(
            CoreTaskParams(
                CoreTaskType.CUSTOM,
                """{"packId":"$packId"}""",
            )
        )
    }

    companion object {
        const val TASK_TYPE = "Custom"
    }
}
