package com.march7th.hkrpg.data

import com.march7th.hkrpg.data.api.RedeemCodeService
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `codes.md` 的解析规则。
 *
 * 这个文件是手工维护的，格式错一点就整份作废的代价太大（用户会以为「没有新码」），
 * 所以解析必须宽容；同时分组和过期又不能猜错。下面按「站点上可能写成什么样」
 * 逐条钉住。
 */
class RedeemCodeParserTest {

    private val today = LocalDate.of(2026, 11, 1)

    @Test
    fun parse_readsGroupNameAndExpiry() {
        val md = """
            ## 3.8 前瞻直播 · 2026-11-15
            - STAR2026HAPPY
        """.trimIndent()

        val codes = RedeemCodeService.parse(md, today)

        assertEquals(1, codes.size)
        assertEquals("STAR2026HAPPY", codes[0].code)
        assertEquals("3.8 前瞻直播", codes[0].group)
        assertEquals(LocalDate.of(2026, 11, 15), codes[0].expiresAt)
    }

    @Test
    fun parse_bareDateHasNoGroup() {
        val codes = RedeemCodeService.parse("## 2026-11-15\n- ABC12345", today)

        assertEquals("", codes[0].group)
        assertEquals(LocalDate.of(2026, 11, 15), codes[0].expiresAt)
    }

    @Test
    fun parse_longTermHasNoExpiry() {
        val codes = RedeemCodeService.parse("## 长期\n- WELCOME7TH", today)

        assertEquals("", codes[0].group)
        assertNull(codes[0].expiresAt)
    }

    @Test
    fun parse_namedSectionWithoutDateIsLongTerm() {
        val codes = RedeemCodeService.parse("## 社群福利\n- RAIL7THANKS", today)

        assertEquals("社群福利", codes[0].group)
        assertNull(codes[0].expiresAt)
    }

    @Test
    fun parse_dropsExpiredCodes() {
        val md = """
            ## 旧活动 · 2026-10-20
            - OLD12345
            ## 版本福利 · 2026-12-31
            - NEW12345
        """.trimIndent()

        val codes = RedeemCodeService.parse(md, today)

        assertEquals(listOf("NEW12345"), codes.map { it.code })
    }

    @Test
    fun parse_keepsCodeExpiringToday() {
        val codes = RedeemCodeService.parse("## 今天到期 · 2026-11-01\n- TODAY123", today)

        assertEquals(listOf("TODAY123"), codes.map { it.code })
    }

    @Test
    fun parse_ignoresHtmlComments() {
        val md = """
            <!-- 示例：- FAKE12345 -->
            ## 长期
            - REAL12345
        """.trimIndent()

        val codes = RedeemCodeService.parse(md, today)

        assertEquals(listOf("REAL12345"), codes.map { it.code })
    }

    @Test
    fun parse_uppercasesAndDedupes() {
        val codes = RedeemCodeService.parse(
            "## 长期\n- abc12345\nABC12345\n* ABC12345\n",
            today,
        )

        assertEquals(listOf("ABC12345"), codes.map { it.code })
    }

    @Test
    fun parse_documentTitleIsNotAGroup() {
        val md = """
            # 兑换码
            ## 长期
            - WELCOME7TH
        """.trimIndent()

        val codes = RedeemCodeService.parse(md, today)

        assertEquals("", codes[0].group)
        assertTrue(codes[0].expiresAt == null)
    }

    @Test
    fun parse_ignoresProseLines() {
        val md = """
            ## 长期
            有效期至 2026 年底
            - WELCOME7TH
        """.trimIndent()

        val codes = RedeemCodeService.parse(md, today)

        assertEquals(listOf("WELCOME7TH"), codes.map { it.code })
    }

    @Test
    fun parse_acceptsChineseCodeWithListMarker() {
        val codes = RedeemCodeService.parse("## 长期\n- 永远不会抛弃你", today)

        assertEquals(listOf("永远不会抛弃你"), codes.map { it.code })
    }

    @Test
    fun parse_skipsBareChineseProseLine() {
        // 没有 "- " 的纯中文行一律当说明文字：放宽字符集之后，
        // 「有效期至年底」这种句子会匹配上码的字符集，只能靠列表符号区分。
        val codes = RedeemCodeService.parse("## 长期\n有效期至年底\n- WELCOME7TH", today)

        assertEquals(listOf("WELCOME7TH"), codes.map { it.code })
    }
}
