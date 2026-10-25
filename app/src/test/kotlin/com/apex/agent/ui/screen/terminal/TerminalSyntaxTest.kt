package com.apex.agent.ui.screen.terminal

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [TerminalSyntax] 高亮测试（T96）。
 *
 * 断言用 span 的 (start, end) 区间（跨版本稳定 API）而非 Range 字段名 ——
 * 避开 value/element 命名迁移；色板锚定独立断言（防意外漂白）。
 */
class TerminalSyntaxTest {

    /** span 区间摘要：`start..end` 列表（按出现顺序）。 */
    private fun spans(s: androidx.compose.ui.text.AnnotatedString): List<IntRange> =
        s.spanStyles.map { it.start..it.end }

    @Test
    fun `command highlight spans keyword operator path flag string`() {
        val cmd = "export PATH=/usr/bin -v 'x y'"
        val s = TerminalSyntax.highlightCommand(cmd)
        // span 预期（按出现顺序；end 为排他口径，与 AnnotatedString.Range 一致）：
        // export 关键字 / = 操作符 / /usr/bin 路径 / -v 参数 / 'x y' 字符串
        assertEquals(
            listOf(
                0..6,        // export（0..5，排他 end=6）
                11..12,      // =
                12..20,      // /usr/bin（12..19，排他 end=20）
                21..23,      // -v
                24..29       // 'x y'（24..28，排他 end=29）
            ),
            spans(s)
        )
        assertEquals(cmd, s.text)
    }

    @Test
    fun `command plain text has no spans`() {
        val s = TerminalSyntax.highlightCommand("plain words here")
        assertTrue(s.spanStyles.isEmpty())
        assertEquals("plain words here", s.text)
    }

    @Test
    fun `number token single span in command`() {
        val s = TerminalSyntax.highlightCommand("sleep 5")
        assertEquals(listOf(6..7), spans(s))
    }

    @Test
    fun `output highlight spans error path number`() {
        val s = TerminalSyntax.highlightOutput("failed /data/tmp 42 times")
        // failed(0..6) / /data/tmp(7..16) / 42(17..19)
        assertEquals(
            listOf(0..6, 7..16, 17..19),
            spans(s)
        )
    }

    @Test
    fun `output url span covers exactly the url`() {
        val text = "see https://example.com/x for details"
        val s = TerminalSyntax.highlightOutput(text)
        assertEquals(listOf(4..25), spans(s))
    }

    @Test
    fun `unterminated quote swallowed to end`() {
        val s = TerminalSyntax.highlightCommand("echo 'open")
        // 'open（无闭合）→ 整段字符串 span，文本保真
        assertEquals("echo 'open", s.text)
        assertEquals(listOf(5..10), spans(s))
    }

    @Test
    fun `colors are github dark palette`() {
        // 色板锚定：与真实终端色板一致性抽检（防意外漂白）
        assertEquals(Color(0xFF7EE787), ConsoleTokens.primary)
        assertEquals(Color(0xFF0D1117), ConsoleTokens.background)
        assertEquals(Color(0xFF161B22), ConsoleTokens.surfaceContainerHigh)
        assertEquals(Color(0xFFE6EDF3), ConsoleTokens.onSurface)
        assertEquals(Color(0xFF8B949E), ConsoleTokens.onSurfaceVariant)
    }
}
