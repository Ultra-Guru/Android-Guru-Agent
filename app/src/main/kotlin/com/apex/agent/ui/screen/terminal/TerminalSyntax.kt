package com.apex.agent.ui.screen.terminal

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString

/**
 * 控制台输出语法高亮（T96 —— 真实输出渲染）。
 *
 * 两类输入、两种策略（单一职责，互不混用）：
 *  - CMD 行（用户提交的命令）—— shell 语法着色：关键字/参数/字符串/数字/
 *    路径/操作符，与真实终端提示行同一语义；
 *  - OUT 行（真实命令输出）—— 轻量高亮：绝对路径、URL、数字、错误词。
 *    输出来自任意程序（编译器、apt、ls），不能按 shell 语法硬套，只做
 *    保守的词级点缀，保证可读性优先。
 *
 * 单遍扫描 + 词边界切分；无正则回溯（行长任意时保持线性）。
 */
internal object TerminalSyntax {

    /** shell 关键字（CMD 行着色用）。 */
    private val KEYWORDS = setOf(
        "if", "then", "else", "elif", "fi", "for", "while", "until", "do",
        "done", "case", "esac", "in", "function", "select", "time", "return",
        "export", "local", "readonly", "set", "unset", "shift", "source",
        "true", "false", "cd", "exit", "help"
    )

    /** OUT 行错误词（程序输出的高频错误标记，红色点缀）。 */
    private val ERROR_WORDS = setOf(
        "error", "failed", "failure", "denied", "refused", "not found",
        "permission denied", "fatal", "segmentation fault", "traceback",
        "exception", "warning"
    )

    /**
     * CMD 行着色：shell 语法（关键字红 / 参数琥珀 / 字符串蓝 / 数字蓝 /
     * 路径绿 / 操作符红）。
     */
    fun highlightCommand(command: String): AnnotatedString = buildAnnotatedString {
        var i = 0
        val n = command.length
        var plainStart = 0
        fun flushPlain(end: Int) {
            if (end > plainStart) append(command.substring(plainStart, end))
        }
        while (i < n) {
            val c = command[i]
            when {
                c == '"' || c == '\'' -> {
                    // 字符串字面量：吞到闭合引号（无闭合则吞到行尾）
                    flushPlain(i)
                    var j = i + 1
                    while (j < n && command[j] != c) j++
                    val end = (j + 1).coerceAtMost(n)
                    pushStyle(SpanStyle(color = ConsoleTokens.syntaxString))
                    append(command.substring(i, end))
                    pop()
                    i = end
                    plainStart = end
                }
                c == '$' && i + 1 < n && command[i + 1] == '{' -> {
                    flushPlain(i)
                    var j = i + 2
                    while (j < n && command[j] != '}') j++
                    val end = (j + 1).coerceAtMost(n)
                    pushStyle(SpanStyle(color = ConsoleTokens.syntaxString))
                    append(command.substring(i, end))
                    pop()
                    i = end
                    plainStart = end
                }
                c.isWhitespace() -> {
                    // 空白保持原色（不产生 span —— 词间不着色，视觉降噪）
                    flushPlain(i)
                    append(c)
                    i++
                    plainStart = i
                }
                c == '|' || c == ';' || c == '&' || c == '<' || c == '>' || c == '=' -> {
                    // 操作符/分隔符：红色
                    flushPlain(i)
                    pushStyle(SpanStyle(color = ConsoleTokens.syntaxOperator))
                    append(c)
                    pop()
                    i++
                    plainStart = i
                }
                else -> {
                    // 词级判定：吞一个完整 token 再决定着色
                    var j = i
                    while (j < n) {
                        val cj = command[j]
                        if (cj.isWhitespace() || cj == '|' || cj == ';' || cj == '&' ||
                            cj == '<' || cj == '>' || cj == '=' || cj == '"' || cj == '\''
                        ) break
                        j++
                    }
                    val token = command.substring(i, j)
                    val style = when {
                        token in KEYWORDS -> SpanStyle(color = ConsoleTokens.syntaxKeyword)
                        token.startsWith("-") && token.length > 1 ->
                            SpanStyle(color = ConsoleTokens.syntaxFlag)
                        token.startsWith("/") -> SpanStyle(color = ConsoleTokens.syntaxPath)
                        token.toDoubleOrNull() != null -> SpanStyle(color = ConsoleTokens.syntaxNumber)
                        else -> null
                    }
                    flushPlain(i)
                    if (style != null) {
                        pushStyle(style)
                        append(token)
                        pop()
                    } else {
                        append(token)
                    }
                    i = j
                    plainStart = j
                }
            }
        }
        flushPlain(n)
    }

    /**
     * OUT 行轻量高亮：路径绿 / 数字蓝 / URL 蓝 / 错误词红 —— 其余保持
     * onSurface 原色（由调用方 Text 的 color 提供，不着 span）。
     */
    fun highlightOutput(text: String): AnnotatedString = buildAnnotatedString {
        var i = 0
        val n = text.length
        var plainStart = 0
        fun flushPlain(end: Int) {
            if (end > plainStart) append(text.substring(plainStart, end))
        }
        while (i < n) {
            val c = text[i]
            when {
                c == '/' -> {
                    // 绝对路径片段：吞到空白
                    var j = i + 1
                    while (j < n && !text[j].isWhitespace()) j++
                    flushPlain(i)
                    pushStyle(SpanStyle(color = ConsoleTokens.syntaxPath))
                    append(text.substring(i, j))
                    pop()
                    i = j
                    plainStart = j
                }
                c == 'h' && text.startsWith("http://", i) ||
                    c == 'h' && text.startsWith("https://", i) -> {
                    var j = i + 1
                    while (j < n && !text[j].isWhitespace()) j++
                    flushPlain(i)
                    pushStyle(SpanStyle(color = ConsoleTokens.syntaxString))
                    append(text.substring(i, j))
                    pop()
                    i = j
                    plainStart = j
                }
                c.isDigit() && (i == 0 || !text[i - 1].isLetterOrDigit()) -> {
                    // 数字（独立 token）：吞字母数字串再判定是否纯数字
                    var j = i
                    while (j < n && text[j].isLetterOrDigit()) j++
                    val token = text.substring(i, j)
                    flushPlain(i)
                    if (token.toDoubleOrNull() != null) {
                        pushStyle(SpanStyle(color = ConsoleTokens.syntaxNumber))
                        append(token)
                        pop()
                    } else {
                        append(token)
                    }
                    i = j
                    plainStart = j
                }
                c.isLetter() -> {
                    var j = i
                    while (j < n && text[j].isLetterOrDigit() || text[j] == '-' || text[j] == '_') j++
                    val token = text.substring(i, j).lowercase()
                    flushPlain(i)
                    if (token in ERROR_WORDS) {
                        pushStyle(SpanStyle(color = ConsoleTokens.lineError))
                        append(text.substring(i, j))
                        pop()
                    } else {
                        append(text.substring(i, j))
                    }
                    i = j
                    plainStart = j
                }
                else -> {
                    append(c)
                    i++
                    plainStart = i
                }
            }
        }
        flushPlain(n)
    }
}
