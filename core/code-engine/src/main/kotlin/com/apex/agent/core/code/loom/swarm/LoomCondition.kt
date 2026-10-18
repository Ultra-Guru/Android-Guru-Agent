package com.apex.agent.core.code.loom.swarm

/**
 * # Loom Condition — 条件表达式求值器
 *
 * 子线程工具的 `expect` 条件与编排判定共用的小 DSL：
 *
 * ```
 * 字段 操作符 值
 * status == ok          全等（大小写不敏感）
 * status != error       不等
 * reply contains ERROR  子串包含
 * rounds >= 3           数值比较（两侧皆数字才按数值，否则字典序文本比较）
 * path exists           一元存在（字段在场且非空）
 * ```
 *
 * ## 防御纪律
 *
 * - 表达式 malformed（缺段 / 未知操作符 / 空串）→ **false**（宁可误判
 * 不满足也不放行编排分支）；
 * - 值里的空格：操作符按**词法**切分（split 空白）——含空格的比较值
 * 需用下划线转义（`reply contains OUT_OF_MEMORY` 天然单词级）；
 * - 数值比较双侧可解析为 Double 才数值化。
 */
object LoomCondition {

    /** 二元操作符集合（小写）。 */
    private val BINARY_OPS = setOf("==", "!=", "contains", ">=", "<=", ">", "<")

    /**
     * 求值：表达式 → 布尔。
     */
    fun evaluate(expression: String, context: Map<String, String>): Boolean {
        val tokens = expression.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return false

        // 一元：field exists
        if (tokens.size == 2 && tokens[1].equals("exists", ignoreCase = true)) {
            val v = context[tokens[0]]
            return v != null && v.isNotBlank()
        }

        if (tokens.size != 3) return false
        val field = tokens[0]
        val op = tokens[1].lowercase()
        val expected = tokens[2]
        if (op !in BINARY_OPS) return false
        val actual = context[field] ?: return false // 字段缺席 → false（含 != 语义）

        return when (op) {
            "==" -> actual.equals(expected, ignoreCase = true)
            "!=" -> !actual.equals(expected, ignoreCase = true)
            "contains" -> actual.contains(expected, ignoreCase = true)
            else -> compareOrdered(actual, expected, op)
        }
    }

    /** 有序比较（数值化优先，字典序兜底）。 */
    private fun compareOrdered(actual: String, expected: String, op: String): Boolean {
        val a = actual.toDoubleOrNull()
        val b = expected.toDoubleOrNull()
        val cmp = if (a != null && b != null) a.compareTo(b) else actual.compareTo(expected)
        return when (op) {
            ">" -> cmp > 0
            ">=" -> cmp >= 0
            "<" -> cmp < 0
            "<=" -> cmp <= 0
            else -> false
        }
    }

    /** 条件合法性预检（工具参数校验用——不合法直接报参数错误）。 */
    fun isValid(expression: String): Boolean {
        val tokens = expression.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return false
        if (tokens.size == 2) return tokens[1].equals("exists", ignoreCase = true)
        if (tokens.size != 3) return false
        return BINARY_OPS.contains(tokens[1].lowercase())
    }
}
