package com.apex.agent.ui.screen.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [RawStreamSanitizer] 契约测试（T96）。
 *
 * 断言值全部从真实链路形态推导（mksh/bash PS1 家族、OSC 633 marker 协议、
 * PTY 行编辑回显）—— 不发明输入。
 */
class RawStreamSanitizerTest {

    private val wrappedEcho = "ls -alF; printf '\\033]633;APEX;j=7;e=%d\\007' 7 \$?"

    @Test
    fun `ansi csi and osc633 stripped`() {
        val s = RawStreamSanitizer(expectedEcho = null)
        val lines = s.feed("\u001b]633;APEX;j=7;e=0\u0007\u001b[32mOK\u001b[0m\n")
        assertEquals(listOf("OK"), lines)
    }

    @Test
    fun `crlf normalized and lone cr splits`() {
        val s = RawStreamSanitizer(expectedEcho = null)
        val lines = s.feed("a\r\nb\rc\n")
        assertEquals(listOf("a", "b", "c"), lines)
    }

    @Test
    fun `partial line across chunks joins on completion`() {
        val s = RawStreamSanitizer(expectedEcho = null)
        assertTrue(s.feed("hel").isEmpty())
        assertTrue(s.feed("lo w").isEmpty())
        assertEquals(listOf("hello world"), s.feed("orld\n"))
    }

    @Test
    fun `echo suppressed when first line equals wrapped command`() {
        val s = RawStreamSanitizer(expectedEcho = wrappedEcho)
        val out = s.feed(wrappedEcho + "\r\nreal-out-1\nreal-out-2\n")
        assertEquals(listOf("real-out-1", "real-out-2"), out)
    }

    @Test
    fun `echo wrapped across physical lines still suppressed`() {
        val s = RawStreamSanitizer(expectedEcho = wrappedEcho)
        // 80 列折行：回显被拆成两个物理行（截断点任意）
        val part1 = wrappedEcho.substring(0, 24)
        val part2 = wrappedEcho.substring(24)
        assertTrue(s.feed(part1 + "\r\n").isEmpty())
        val out = s.feed(part2 + "\r\nafter-echo\n")
        assertEquals(listOf("after-echo"), out)
    }

    @Test
    fun `non-matching first line falls through to output`() {
        val s = RawStreamSanitizer(expectedEcho = wrappedEcho)
        val out = s.feed("totally-different\n")
        assertEquals(listOf("totally-different"), out)
    }

    @Test
    fun `prompt tails dropped at finish`() {
        val mkshPrompt = "user@android:/data $ "
        val bashPrompt = "root@ubuntu:~# "
        val zshPrompt = "% "
        RawStreamSanitizer(expectedEcho = null).let { s ->
            s.feed("out\n")
            assertTrue(s.finish().isEmpty())
            Unit
        }
        // mksh 家族
        RawStreamSanitizer(expectedEcho = null).let { s ->
            s.feed("out\n" + mkshPrompt)
            assertTrue(s.finish().isEmpty())
        }
        // bash 家族（GuestUserHome root PS1）
        RawStreamSanitizer(expectedEcho = null).let { s ->
            s.feed("out\n" + bashPrompt)
            assertTrue(s.finish().isEmpty())
        }
        // zsh 家族
        RawStreamSanitizer(expectedEcho = null).let { s ->
            s.feed("out\n" + zshPrompt)
            assertTrue(s.finish().isEmpty())
        }
    }

    @Test
    fun `non-prompt carry emitted at finish`() {
        val s = RawStreamSanitizer(expectedEcho = null)
        s.feed("line1\ntrailing-without-newline")
        assertEquals(listOf("trailing-without-newline"), s.finish())
    }

    @Test
    fun `osc marker mid-stream between output lines`() {
        val s = RawStreamSanitizer(expectedEcho = null)
        val out = s.feed("before\u001b]633;APEX;j=7;e=0\u0007after\n")
        assertEquals(listOf("beforeafter"), out)
    }

    @Test
    fun `empty chunk yields nothing`() {
        val s = RawStreamSanitizer(expectedEcho = wrappedEcho)
        assertTrue(s.feed("").isEmpty())
        assertTrue(s.finish().isEmpty())
    }
}
