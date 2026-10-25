package com.march7th.hkrpg.data.model

import com.march7th.hkrpg.R
import com.march7th.hkrpg.utils.i18n.uiTextOf
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SwitchThemeConfigTest {
    @Test
    fun candidates_areTrimmedWithoutLosingDuplicateWeights() {
        val task = SwitchThemeConfig(themes = listOf(" 夜间 ", "", "  ", "银凇", "夜间"))
            .toTaskParams(testTaskParamContext())
            .single()

        assertEquals("SwitchTheme", task.type.value)
        assertEquals("{\"themes\":[\"夜间\",\"银凇\",\"夜间\"]}", task.params)
    }

    @Test
    fun emptyCandidates_skipWithAnExplanation() {
        val logs = CollectingPreflightLogSink()
        val tasks = SwitchThemeConfig(themes = listOf("", "  "))
            .toTaskParams(testTaskParamContext(logSink = logs))

        assertTrue(tasks.isEmpty())
        assertEquals(
            listOf(uiTextOf(R.string.maa_switch_theme_skipped) to LogLevel.INFO),
            logs.entries,
        )
    }

    @Test
    fun profileRoundTrip_preservesCandidateOrderAndWeights() {
        val config = SwitchThemeConfig(themes = listOf("夜间", "银凇", "夜间"))
        val profile = TaskProfile(
            name = "日常",
            chain = listOf(TaskChainNode(name = "主题", config = config)),
        )

        assertEquals(profile, Json.decodeFromString<TaskProfile>(Json.encodeToString(profile)))
        assertEquals(SwitchThemeConfig(), Json.decodeFromString<SwitchThemeConfig>("{}"))
    }
}
