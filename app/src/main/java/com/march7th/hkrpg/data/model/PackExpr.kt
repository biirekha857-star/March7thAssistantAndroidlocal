package com.march7th.hkrpg.data.model

/**
 * 任务包里的表达式求值。
 *
 * ## 为什么自己写 parser，而不是用 JS 引擎
 *
 * 任务文件的「像写代码一样」只到**算术 + 比较 + 布尔**这一层就够了：
 * `starCount >= 3`、`cnt + 1`、`left > 0 && tries < 10`。
 * 一旦引入 Rhino / QuickJS 这类引擎，任务包就能：
 *
 * - 构造对象、`while(true)`、递归到栈溢出；
 * - 通过反射摸到宿主类（`java.lang.Runtime`）—— 这是 Android 上最经典的逃逸路径。
 *
 * 也就是说，**真正的风险不在表达式，在于「谁来执行它」**。这里是一个
 * 手写的递归下降求值器，能算的东西就是下面这些，没有对象、没有函数调用外部、
 * 没有循环（循环由 `whileExpr` 这个 op 控制，且带迭代上限）。
 *
 * ## 闸门
 *
 * - token 数上限 [MAX_TOKENS]，防止 `((((((…` 这种构造拖死解析；
 * - 递归深度上限 [MAX_DEPTH]；
 * - 整数溢出回绕到 Long，不会抛异常；
 * - 除零 / 模零返回 `null`（调用方按失败处理），不是崩。
 */
object PackExpr {

    /** 表达式字符长度上限（比 token 上限先拦，省得先跑一遍 tokenizer） */
    const val MAX_LENGTH = 512
    const val MAX_TOKENS = 400
    const val MAX_DEPTH = 24

    /** 求值结果。只有这三种，任务包里不存在对象和函数。 */
    sealed interface Value {
        /** 数字（Long）。bool 需要数字时按 0/1 用 */
        data class Num(val v: Long) : Value

        /** 布尔。数字需要 bool 时按「非 0 为真」用 */
        data class Flag(val v: Boolean) : Value

        /** 字符串。只用于 readText → 拼接 / 比较，不参与算术 */
        data class Str(val v: String) : Value

        fun asLong(): Long = when (this) {
            is Num -> v
            is Flag -> if (v) 1L else 0L
            is Str -> v.trim().toLongOrNull() ?: 0L
        }

        fun asBool(): Boolean = when (this) {
            is Num -> v != 0L
            is Flag -> v
            is Str -> v.isNotBlank() && v != "0" && !v.equals("false", true)
        }

        fun asText(): String = when (this) {
            is Num -> v.toString()
            is Flag -> if (v) "1" else "0"
            is Str -> v
        }
    }

    /**
     * 语法检查（导入时用，不需要变量值）。
     *
     * 只验证能不能解析，不判断变量是否存在 —— 变量由 `setVar` / `readNumber`
     * 在运行期建立，静态做不到完全推断。
     *
     * @return 普通错误文案；`null` 表示语法正确
     */
    fun checkSyntax(src: String): String? {
        val expr = src.trim()
        if (expr.isEmpty()) return "表达式为空"
        if (expr.length > MAX_LENGTH) return "表达式超过 $MAX_LENGTH 字符"
        return try {
            Parser(tokenize(expr), emptyMap(), syntaxOnly = true).parse()
            null
        } catch (e: PackExprError) {
            e.message
        } catch (e: Exception) {
            // coerceIn 之类的参数错也会走到这里，一律当成"表达式写错了"
            e.message ?: "表达式无法解析"
        }
    }

    /** 求值。返回 `null` 表示语法错 / 用了未定义的变量 / 除零。 */
    fun eval(src: String, vars: Map<String, Value>): Value? {
        val expr = src.trim()
        if (expr.isEmpty() || expr.length > MAX_LENGTH) return null
        return try {
            Parser(tokenize(expr), vars, syntaxOnly = false).parse()
        } catch (e: PackExprError) {
            null
        } catch (e: Exception) {
            null
        }
    }

    // ==================== 词法 ====================

    private sealed interface Tok {
        data class Num(val v: Long) : Tok
        data class Str(val v: String) : Tok
        data class Ident(val name: String) : Tok
        data class Op(val symbol: String) : Tok
    }

    private fun tokenize(src: String): List<Tok> {
        val out = ArrayList<Tok>(32)
        var i = 0
        while (i < src.length) {
            val c = src[i]
            when {
                c.isWhitespace() -> i++

                c.isDigit() -> {
                    val start = i
                    while (i < src.length && src[i].isDigit()) i++
                    out += Tok.Num(src.substring(start, i).toLongOrNull() ?: 0L)
                }

                c == '"' || c == '\'' -> {
                    val quote = c
                    i++
                    val sb = StringBuilder()
                    while (i < src.length && src[i] != quote) {
                        if (src[i] == '\\' && i + 1 < src.length) i++
                        sb.append(src[i])
                        i++
                    }
                    if (i >= src.length) fail("字符串没有闭合的引号")
                    i++
                    out += Tok.Str(sb.toString())
                }

                c.isLetter() || c == '_' -> {
                    val start = i
                    while (i < src.length && (src[i].isLetterOrDigit() || src[i] == '_')) i++
                    out += Tok.Ident(src.substring(start, i))
                }

                else -> {
                    val two = if (i + 1 < src.length) src.substring(i, i + 2) else ""
                    if (two in TWO_CHAR_OPS) {
                        out += Tok.Op(two)
                        i += 2
                    } else if (c.toString() in ONE_CHAR_OPS) {
                        out += Tok.Op(c.toString())
                        i++
                    } else {
                        fail("出现了不认识的字符「$c」")
                    }
                }
            }
            if (out.size > MAX_TOKENS) fail("表达式过长（超过 $MAX_TOKENS 个记号）")
        }
        if (out.isEmpty()) fail("表达式为空")
        return out
    }

    private val TWO_CHAR_OPS = setOf(">=", "<=", "==", "!=", "&&", "||")
    private val ONE_CHAR_OPS = setOf("+", "-", "*", "/", "%", ">", "<", "!", "(", ")", ",")

    // ==================== 语法 + 求值 ====================

    private class Parser(
        private val toks: List<Tok>,
        private val vars: Map<String, Value>,
        /**
         * 只检查语法、不求值。
         *
         * 导入校验时没有任何变量值，也不能因为 `x / d` 里 `d` 恰好是 0
         * 就报"除以 0" —— 那不是语法错。所以这个模式下：
         * 未定义变量当 0、除零当 0，只保留真正的语法错误。
         */
        private val syntaxOnly: Boolean,
    ) {
        private var pos = 0
        private var depth = 0

        fun parse(): Value {
            val v = or()
            if (pos < toks.size) fail("多余的内容「${describe(toks[pos])}」")
            return v
        }

        private fun peek(): Tok? = toks.getOrNull(pos)

        private fun matchOp(vararg symbols: String): String? {
            val t = peek() as? Tok.Op ?: return null
            if (t.symbol !in symbols) return null
            pos++
            return t.symbol
        }

        private fun or(): Value {
            var left = and()
            while (matchOp("||") != null) {
                val right = and()
                left = Value.Flag(left.asBool() || right.asBool())
            }
            return left
        }

        private fun and(): Value {
            var left = compare()
            while (matchOp("&&") != null) {
                val right = compare()
                left = Value.Flag(left.asBool() && right.asBool())
            }
            return left
        }

        private fun compare(): Value {
            val left = add()
            val op = matchOp(">=", "<=", "==", "!=", ">", "<") ?: return left
            val right = add()
            return Value.Flag(compareValues(left, op, right))
        }

        private fun compareValues(a: Value, op: String, b: Value): Boolean {
            // 两边任一是字符串且 op 是相等判断 → 按文本比；其余一律按数字
            if (op == "==" || op == "!=") {
                val same = if (a is Value.Str || b is Value.Str) {
                    a.asText() == b.asText()
                } else {
                    a.asLong() == b.asLong()
                }
                return if (op == "==") same else !same
            }
            val x = a.asLong()
            val y = b.asLong()
            return when (op) {
                ">=" -> x >= y
                "<=" -> x <= y
                ">" -> x > y
                "<" -> x < y
                else -> false
            }
        }

        private fun add(): Value {
            var left = mul()
            while (true) {
                when (matchOp("+", "-")) {
                    "+" -> {
                        val right = mul()
                        // 任一侧是字符串就做拼接，跟常见脚本语言的直觉一致
                        left = if (left is Value.Str || right is Value.Str) {
                            Value.Str(left.asText() + right.asText())
                        } else {
                            Value.Num(left.asLong() + right.asLong())
                        }
                    }

                    "-" -> left = Value.Num(left.asLong() - mul().asLong())
                    else -> return left
                }
            }
        }

        private fun mul(): Value {
            var left = unary()
            while (true) {
                when (matchOp("*", "/", "%")) {
                    "*" -> left = Value.Num(left.asLong() * unary().asLong())
                    "/" -> {
                        val d = unary().asLong()
                        if (d == 0L) {
                            if (!syntaxOnly) fail("除以 0")
                            left = Value.Num(0)
                        } else {
                            left = Value.Num(left.asLong() / d)
                        }
                    }

                    "%" -> {
                        val d = unary().asLong()
                        if (d == 0L) {
                            if (!syntaxOnly) fail("对 0 取余")
                            left = Value.Num(0)
                        } else {
                            left = Value.Num(left.asLong() % d)
                        }
                    }

                    else -> return left
                }
            }
        }

        private fun unary(): Value {
            if (matchOp("!") != null) return Value.Flag(!unary().asBool())
            if (matchOp("-") != null) return Value.Num(-unary().asLong())
            if (matchOp("+") != null) return unary()
            return primary()
        }

        private fun primary(): Value {
            if (++depth > MAX_DEPTH) fail("括号嵌套过深（超过 $MAX_DEPTH 层）")
            try {
                when (val t = peek()) {
                    is Tok.Num -> {
                        pos++
                        return Value.Num(t.v)
                    }

                    is Tok.Str -> {
                        pos++
                        return Value.Str(t.v)
                    }

                    is Tok.Ident -> {
                        pos++
                        // 函数调用：只有下面这几个白名单，其余一律拒绝
                        if ((peek() as? Tok.Op)?.symbol == "(") {
                            pos++
                            val args = ArrayList<Value>(2)
                            if ((peek() as? Tok.Op)?.symbol != ")") {
                                while (true) {
                                    args += or()
                                    if (matchOp(",") != null) continue
                                    break
                                }
                            }
                            if (matchOp(")") == null) fail("函数 ${t.name} 的括号没有闭合")
                            return callFn(t.name, args)
                        }
                        if (t.name == "true") return Value.Flag(true)
                        if (t.name == "false") return Value.Flag(false)
                        return vars[t.name]
                            ?: if (syntaxOnly) Value.Num(0) else fail("变量「${t.name}」还没有值")
                    }

                    is Tok.Op -> {
                        if (t.symbol == "(") {
                            pos++
                            val v = or()
                            if (matchOp(")") == null) fail("括号没有闭合")
                            return v
                        }
                        fail("这里不该出现「${t.symbol}」")
                    }

                    null -> fail("表达式不完整")
                }
            } finally {
                depth--
            }
        }

        private fun callFn(name: String, args: List<Value>): Value = when (name) {
            "min" -> {
                need(name, args, 2)
                Value.Num(minOf(args[0].asLong(), args[1].asLong()))
            }

            "max" -> {
                need(name, args, 2)
                Value.Num(maxOf(args[0].asLong(), args[1].asLong()))
            }

            "abs" -> {
                need(name, args, 1)
                Value.Num(kotlin.math.abs(args[0].asLong()))
            }

            "clamp" -> {
                need(name, args, 3)
                Value.Num(
                    args[0].asLong().coerceIn(args[1].asLong(), args[2].asLong())
                )
            }

            "len" -> {
                need(name, args, 1)
                Value.Num(args[0].asText().length.toLong())
            }

            else -> fail("不认识的函数「$name」（只支持 min / max / abs / clamp / len）")
        }

        private fun need(name: String, args: List<Value>, n: Int) {
            if (args.size != n) fail("函数 $name 需要 $n 个参数，实际 ${args.size} 个")
        }

        private fun describe(t: Tok): String = when (t) {
            is Tok.Num -> t.v.toString()
            is Tok.Str -> "\"${t.v}\""
            is Tok.Ident -> t.name
            is Tok.Op -> t.symbol
        }
    }

    /** 解析/求值期的错误。内部用，对外只暴露 `null` 和文案。 */
    private class PackExprError(override val message: String) : Exception(message)

    internal fun fail(msg: String): Nothing = throw PackExprError(msg)
}
