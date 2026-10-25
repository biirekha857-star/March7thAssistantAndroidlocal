package com.march7th.hkrpg.data.model

import com.march7th.hkrpg.R
import com.march7th.hkrpg.core.task.CoreTaskParams
import com.march7th.hkrpg.core.task.CoreTaskType
import com.march7th.hkrpg.utils.i18n.UiText
import com.march7th.hkrpg.utils.i18n.uiTextOf
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

@Serializable
data class SwitchThemeConfig(
    val themes: List<String> = emptyList(),
) : TaskParamProvider {
    override fun toTaskParams(ctx: TaskParamContext): List<CoreTaskParams> {
        val candidates = themes.map(String::trim).filter(String::isNotEmpty)
        if (candidates.isEmpty()) {
            ctx.appendLog(uiTextOf(R.string.maa_switch_theme_skipped))
            return emptyList()
        }
        val params = buildJsonObject {
            put("themes", JsonArray(candidates.map(::JsonPrimitive)))
        }
        return listOf(
            CoreTaskParams(
                CoreTaskType.SWITCH_THEME,
                params.toString(),
                logName = UiText.Dynamic(ctx.node.name),
            )
        )
    }
}
