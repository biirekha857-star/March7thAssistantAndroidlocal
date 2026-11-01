package com.march7th.hkrpg.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 表达式引擎的行为锁定。
 *
 * 这个类决定了任务包里"能不能算对"，而且**所有校验都在导入时跑一遍**，
 * 写错一个容错分支就会让整类包装不上。所以正例、负例各覆盖一遍。
 */
class PackExprTest {

    private fun num(src: String, vars: Map<String, PackExpr.Value> = emptyMap()): Long =
        PackExpr.eval(src, vars)?.asLong() ?: error("求值失败：$src")

    private fun flag(src: String, vars: Map<String, PackExpr.Value> = emptyMap()): Boolean =
        PackExpr.eval(src, vars)?.asBool() ?: error("求值失败：$src")

    private val vars = mapOf(
        "left" to PackExpr.Value.Num(3),
        "total" to PackExpr.Value.Num(12),
        "used" to PackExpr.Value.Num(5),
        "name" to PackExpr.Value.Str("第 09 层"),
    )

    // ---- 算术 ----

    @Test
    fun `四则运算按优先级`() {
        assertEquals(7L, num("1 + 2 * 3"))
        assertEquals(9L, num("(1 + 2) * 3"))
        assertEquals(2L, num("7 / 3"))
        assertEquals(1L, num("7 % 3"))
        assertEquals(-4L, num("-(2 + 2)"))
    }

    @Test
    fun `变量参与运算`() {
        assertEquals(7L, num("total - used", vars))
        assertEquals(2L, num("left - 1", vars))
    }

    @Test
    fun `除零不崩但判失败`() {
        assertNull(PackExpr.eval("1 / 0", emptyMap()))
        assertNull(PackExpr.eval("1 % 0", emptyMap()))
    }

    // ---- 比较与布尔 ----

    @Test
    fun `比较返回真假`() {
        assertTrue(flag("3 >= 3"))
        assertTrue(!flag("3 > 3"))
        assertTrue(flag("left < 5", vars))
        assertTrue(flag("total == 12", vars))
        assertTrue(flag("total != 11", vars))
    }

    @Test
    fun `布尔组合与优先级`() {
        // && 比 || 紧，所以等价于 true || (false && false)
        assertTrue(flag("1 > 0 || 1 < 0 && 1 < 0"))
        assertTrue(flag("1 > 0 && 2 > 1"))
        assertTrue(!flag("!((1 > 0))"))
    }

    @Test
    fun `字符串比较按文本`() {
        assertTrue(flag("""name == "第 09 层"""", vars))
        assertTrue(flag("""name != "第 10 层"""", vars))
    }

    @Test
    fun `字符串拼接`() {
        assertEquals("第 09 层-3", PackExpr.eval("""name + "-" + left""", vars)?.asText())
    }

    // ---- 函数 ----

    @Test
    fun `白名单函数可用`() {
        assertEquals(3L, num("min(3, 9)"))
        assertEquals(9L, num("max(3, 9)"))
        assertEquals(4L, num("abs(0 - 4)"))
        assertEquals(6L, num("clamp(99, 0, 6)"))
        assertEquals(0L, num("clamp(0 - 1, 0, 6)"))
        assertEquals(6L, num("""len("第 09 层")"""))
    }

    @Test
    fun `未知函数被拒`() {
        assertNull(PackExpr.eval("exec(1)", emptyMap()))
        assertNull(PackExpr.eval("""System("rm -rf /")""", emptyMap()))
    }

    // ---- 越界与恶意输入 ----

    @Test
    fun `未定义变量求值失败`() {
        assertNull(PackExpr.eval("nope + 1", emptyMap()))
        // 但语法检查不看变量值，应当通过
        assertNull(PackExpr.checkSyntax("nope + 1"))
    }

    @Test
    fun `语法错误能给出原因`() {
        assertNotNull(PackExpr.checkSyntax("1 +"))
        assertNotNull(PackExpr.checkSyntax("(1 + 2"))
        assertNotNull(PackExpr.checkSyntax("1 2"))
        assertNotNull(PackExpr.checkSyntax(""))
        assertNotNull(PackExpr.checkSyntax("1 # 2"))
    }

    @Test
    fun `超长表达式被拒`() {
        val long = "1" + " + 1".repeat(200)
        assertNull(PackExpr.eval(long, emptyMap()))
        assertNotNull(PackExpr.checkSyntax(long))
    }

    @Test
    fun `深层括号不会栈溢出`() {
        val deep = "(".repeat(200) + "1" + ")".repeat(200)
        // 超深要么解析失败、要么算出来，都不能抛异常
        val r = runCatching { PackExpr.eval(deep, emptyMap()) }
        assertTrue(r.isSuccess)
        assertNull(r.getOrNull())
    }

    @Test
    fun `语法检查不会因为除零误报`() {
        // 校验期没有任何变量值，`x / d` 里 d 是 0 属于正常，不该报错
        assertNull(PackExpr.checkSyntax("left / d"))
        assertNull(PackExpr.checkSyntax("total % rest"))
    }

    @Test
    fun `对象访问与调用一律拒绝`() {
        assertNull(PackExpr.eval("foo.bar", emptyMap()))
        assertNull(PackExpr.eval("foo.bar()", emptyMap()))
        assertNull(PackExpr.eval("foo[0]", emptyMap()))
    }
}
