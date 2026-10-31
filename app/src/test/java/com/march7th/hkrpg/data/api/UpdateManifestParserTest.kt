package com.march7th.hkrpg.data.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateManifestParserTest {

    private val sample = """
        ---
        versionCode: 8
        versionName: 0.5.7beta
        apkUrl: https://example.com/app.apk
        forceUpdate: false
        minSupportedVersionCode: 1
        publishedAt: 2026-10-31
        ---

        **界面重构**：首页与任务页合并

        - 修复进入主界面必然崩溃的问题
    """.trimIndent()

    @Test
    fun parse_readsFieldsAndBody() {
        val manifest = requireNotNull(UpdateManifestParser.parseMarkdown(sample))

        assertEquals(8, manifest.versionCode)
        assertEquals("0.5.7beta", manifest.versionName)
        assertEquals("https://example.com/app.apk", manifest.apkUrl)
        assertFalse(manifest.forceUpdate)
        assertEquals(1, manifest.minSupportedVersionCode)
        assertEquals("2026-10-31", manifest.publishedAt)
        // 正文整段就是 changelog，首尾空行被裁掉
        assertTrue(manifest.changelog.startsWith("**界面重构**"))
        assertTrue(manifest.changelog.endsWith("修复进入主界面必然崩溃的问题"))
    }

    @Test
    fun parse_toleratesBomCrlfAndBlankLines() {
        val text = "\uFEFF\r\n\r\n" + sample.replace("\n", "\r\n")
        val manifest = requireNotNull(UpdateManifestParser.parseMarkdown(text))
        assertEquals(8, manifest.versionCode)
    }

    @Test
    fun parse_acceptsLooseBooleansAndQuotes() {
        // 手写 md 很容易写成 True / "yes"，别让强制更新被悄悄降级
        for (value in listOf("true", "True", "TRUE", "yes", "1", "on", "\"true\"")) {
            val text = sample.replace("forceUpdate: false", "forceUpdate: $value")
            val manifest = requireNotNull(UpdateManifestParser.parseMarkdown(text))
            assertTrue("forceUpdate=$value 应解析为 true", manifest.forceUpdate)
        }
        val off = sample.replace("forceUpdate: false", "forceUpdate: no")
        assertFalse(requireNotNull(UpdateManifestParser.parseMarkdown(off)).forceUpdate)
    }

    @Test
    fun parse_ignoresCommentsAndUnknownFields() {
        val text = sample.replace(
            "versionCode: 8",
            "# 这一行是注释\nversionCode: 8\nfutureField: 以后再说",
        )
        val manifest = requireNotNull(UpdateManifestParser.parseMarkdown(text))
        assertEquals(8, manifest.versionCode)
    }

    @Test
    fun parse_defaultsWhenOptionalFieldsMissing() {
        val minimal = """
            ---
            versionCode: 9
            versionName: 0.6.0beta
            apkUrl: https://example.com/app.apk
            ---

            只有正文
        """.trimIndent()
        val manifest = requireNotNull(UpdateManifestParser.parseMarkdown(minimal))
        assertFalse(manifest.forceUpdate)
        assertEquals(0, manifest.minSupportedVersionCode)
        assertEquals("", manifest.publishedAt)
    }

    @Test
    fun parse_returnsNullWithoutFrontMatter() {
        // 没有 front matter 就是一份普通 md（比如公告），不能当成清单
        assertNull(UpdateManifestParser.parseMarkdown("# 公告\n\n正文"))
        // front matter 没闭合
        assertNull(UpdateManifestParser.parseMarkdown("---\nversionCode: 8\n"))
    }

    @Test
    fun parse_returnsNullWhenRequiredFieldsInvalid() {
        // 缺 versionName
        assertNull(
            UpdateManifestParser.parseMarkdown(
                "---\nversionCode: 8\napkUrl: https://example.com/a.apk\n---\n正文",
            ),
        )
        // versionCode 不是数字
        assertNull(
            UpdateManifestParser.parseMarkdown(
                "---\nversionCode: 八\nversionName: 0.5.7beta\n---\n正文",
            ),
        )
        // 空文本
        assertNull(UpdateManifestParser.parseMarkdown(""))
    }
}
