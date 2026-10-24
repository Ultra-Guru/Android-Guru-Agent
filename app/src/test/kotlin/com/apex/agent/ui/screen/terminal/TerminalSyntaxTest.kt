package com.apex.agent.ui.screen.terminal

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [TerminalSyntax] 高亮测试（T96）—— 断言 span 数量与色值（色值取自
 * ConsoleTokens，与 GitHub Dark 色板一致）。
 */
class TerminalSyntaxTest {

    @Test
    fun `command highlight colors keyword flag and path`() {
        val s = TerminalSyntax.highlightCommand("export PATH=/usr/bin -v 'x y'")
        // span 预期：export(关键字) + =(操作符) + /usr/bin(路径) + -v(参数) + 'x y'(字符串)
        assertEquals(5, s.spanStyles.size)
        assertEquals(
            listOf(
                ConsoleTokens.syntaxKeyword,
                ConsoleTokens.syntaxOperator,
                ConsoleTokens.syntaxPath,
                ConsoleTokens.syntaxFlag,
                ConsoleTokens.syntaxString
            ),
            s.spanStyles.map { it.style.color }
        )
        assertEquals("export PATH=/usr/bin -v 'x y'", s.text)
    }

    @Test
    fun `command plain text has no spans`() {
        val s = TerminalSyntax.highlightCommand("plain words here")
        assertTrue(s.spanStyles.isEmpty())
        assertEquals("plain words here", s.text)
    }

    @Test
    fun `number token colored in command`() {
        val s = TerminalSyntax.highlightCommand("sleep 5")
        assertEquals(1, s.spanStyles.size)
        assertEquals(ConsoleTokens.syntaxNumber, s.spanStyles[0].style.color)
    }

    @Test
    fun `output highlight colors path number and error word`() {
        val s = TerminalSyntax.highlightOutput("failed /data/tmp 42 times")
        // span 预期：failed(错误词红) + /data/tmp(路径绿) + 42(数字蓝)
        assertEquals(3, s.spanStyles.size)
        assertEquals(
            listOf(
                ConsoleTokens.lineError,
                ConsoleTokens.syntaxPath,
                ConsoleTokens.syntaxNumber
            ),
            s.spanStyles.map { it.style.color }
        )
    }

    @Test
    fun `output url colored`() {
        val s = TerminalSyntax.highlightOutput("see https://example.com/x for details")
        assertEquals(1, s.spanStyles.size)
        assertEquals(ConsoleTokens.syntaxString, s.spanStyles[0].style.color)
        // span 覆盖整个 URL
        assertEquals("https://example.com/x", s.text.substring(
            s.spanStyles[0].start, s.spanStyles[0].end
        ))
    }

    @Test
    fun `unterminated quote swallowed to end`() {
        val s = TerminalSyntax.highlightCommand("echo 'open")
        // 'open（无闭合）→ 整段字符串 span，文本保真
        assertEquals("echo 'open", s.text)
        assertEquals(1, s.spanStyles.size)
        assertEquals(ConsoleTokens.syntaxString, s.spanStyles[0].style.color)
    }

    @Test
    fun `colors are github dark palette`() {
        // 色板锚定：与真实终端色板一致性抽检（防意外漂白）
        assertEquals(Color(0xFF7EE787), ConsoleTokens.primary)
        assertEquals(Color(0xFF0D1117), ConsoleTokens.background)
        assertEquals(Color(0xFF161B22), ConsoleTokens.surfaceContainerHigh)
    }
}
