package com.apex.agent.core.jiguan

/**
 * # 沙盒校验器（Local Sandbox Executor 的静态面）
 *
 * Agent 生成的脚本（模板产物 / 用户自定义 JS）在上机前必须过本关 ——
 * 「先沙盒试运行、再挂载目标 App」的两段式纪律在纯 Kotlin 层的落地面：
 * 结构校验（括号/引号平衡）、体量预算、高危 API 黑名单。
 *
 * 语义：**fail-closed** —— 校验不过绝不放行（宁可用不上，不可炸目标进程）。
 */
object SandboxValidator {

    /** 单脚本体量上限（字节）—— Frida 注入面的 Context 与稳定性预算。 */
    const val MAX_SCRIPT_BYTES = 64 * 1024

    /** 高危 API 黑名单：会杀进程 / 死循环刷屏 / 拖垮目标的一律拒绝。 */
    private val BANNED_PATTERNS = listOf(
        // 结束进程面 —— 目标 App / 自身 / 全局。
        "Process.kill" to "killing processes",
        "Process.exit" to "exiting process",
        "java.lang.System.exit" to "exiting process",
        "System.exit(" to "exiting process",
        "Dalvik.system.VMRuntime" to "VM runtime tampering",
        // 死循环刷屏面 —— console.log 在循环体里无上限刷。
        "while(true)console.log" to "unbounded console spam",
        "while(true)console" to "unbounded console spam",
        // fork 炸弹面。
        "fork()" to "fork bomb risk",
    )

    /** 校验结论。 */
    data class Verdict(
        val ok: Boolean,
        /** 违规原因（ok=false 时非空，面向 Agent 的可执行修复指引）。 */
        val reason: String,
        /** 统计信息（调试 / 审计面）。 */
        val scriptBytes: Int,
        val braceBalance: Int,
        val parenBalance: Int,
    ) {
        fun failureText(): String =
            if (ok) "" else "SandboxValidator rejected script ($reason, ${scriptBytes}B)"
    }

    /** 主入口：结构 + 体量 + 黑名单三关。 */
    fun validate(script: String): Verdict {
        val bytes = script.toByteArray(Charsets.UTF_8).size
        if (script.isBlank()) {
            return Verdict(false, "empty script", bytes, 0, 0)
        }
        if (bytes > MAX_SCRIPT_BYTES) {
            return Verdict(false, "script exceeds ${MAX_SCRIPT_BYTES / 1024}KB budget", bytes, 0, 0)
        }
        // ── 括号 / 引号平衡（字符串与注释感知） ──────────────────
        val balance = bracketBalance(script)
        if (balance.brace != 0) {
            return Verdict(
                false,
                "unbalanced braces (${if (balance.brace > 0) "missing" else "extra"} " +
                    "${kotlin.math.abs(balance.brace)} close)",
                bytes, balance.brace, balance.paren,
            )
        }
        if (balance.paren != 0) {
            return Verdict(
                false,
                "unbalanced parentheses (${if (balance.paren > 0) "missing" else "extra"} " +
                    "${kotlin.math.abs(balance.paren)} close)",
                bytes, balance.brace, balance.paren,
            )
        }
        if (balance.unterminatedString) {
            return Verdict(false, "unterminated string literal", bytes, balance.brace, balance.paren)
        }
        // ── 黑名单 ─────────────────────────────────────────────
        for ((pattern, why) in BANNED_PATTERNS) {
            if (script.contains(pattern)) {
                return Verdict(false, "banned API: $pattern ($why)", bytes, balance.brace, balance.paren)
            }
        }
        return Verdict(true, "", bytes, balance.brace, balance.paren)
    }

    /**
     * 字符串/注释感知的括号平衡扫描（JS 语义近似）：
     * - `"` `'` 反引号 三种引号内的括号不计；
     * - 双斜杠行注释与「斜杠星」块注释内的不计（JS 块注释不嵌套，
     *   按首个闭合计 —— 对 Frida 脚本足够）。
     */
    data class Balance(val brace: Int, val paren: Int, val unterminatedString: Boolean)

    fun bracketBalance(script: String): Balance {
        var brace = 0
        var paren = 0
        var i = 0
        val n = script.length
        while (i < n) {
            val c = script[i]
            when {
                c == '"' || c == '\'' -> {
                    val quote = c
                    i++
                    var closed = false
                    while (i < n) {
                        when (script[i]) {
                            '\\' -> i++
                            quote -> { closed = true; i++; break }
                            else -> i++
                        }
                    }
                    if (!closed && i >= n) return Balance(brace, paren, true)
                }
                c == '/' && i + 1 < n && script[i + 1] == '/' -> {
                    while (i < n && script[i] != '\n') i++
                }
                c == '/' && i + 1 < n && script[i + 1] == '*' -> {
                    i += 2
                    while (i + 1 < n && !(script[i] == '*' && script[i + 1] == '/')) i++
                    i += 2
                }
                c == '{' -> { brace++; i++ }
                c == '}' -> { brace--; i++ }
                c == '(' -> { paren++; i++ }
                c == ')' -> { paren--; i++ }
                else -> i++
            }
        }
        return Balance(brace, paren, false)
    }
}
