package com.apex.agent.terminalemulator

import org.junit.Assert.*
import org.junit.Test

/**
 * v0.3 guest 工作目录上报测试 —— OSC 7（file URI）/ OSC 9;9（ConEmu）→
 * snapshot.guestCwd（任务的「GuestCwd」组）。
 *
 * 纯解析矩阵（[GuestCwd]）+ 引擎集成（OSC 消费 → 快照透出；畸形上报 →
 * 状态不动，绝不抛）。路径按 %XX 百分号解码（UTF-8）。
 */
class GuestCwdTest {

    // ═══ OSC 7：file URI 解析 ═══

    @Test fun `file URI with empty host decodes path`() {
        assertEquals("/root/project", GuestCwd.parseOsc7("file:///root/project"))
    }

    @Test fun `file URI with host strips the host`() {
        assertEquals("/root/project", GuestCwd.parseOsc7("file://myhost/root/project"))
    }

    @Test fun `scheme is case-insensitive`() {
        assertEquals("/X", GuestCwd.parseOsc7("FILE:///X"))
    }

    @Test fun `percent-encoded space decodes`() {
        assertEquals("/home/user/my dir", GuestCwd.parseOsc7("file:///home/user/my%20dir"))
    }

    @Test fun `percent-encoded UTF-8 decodes to text`() {
        assertEquals("/root/中", GuestCwd.parseOsc7("file:///root/%E4%B8%AD"))
    }

    @Test fun `plain path without scheme is rejected`() {
        assertNull(GuestCwd.parseOsc7("/root/project"))
    }

    @Test fun `non-file scheme is rejected`() {
        assertNull(GuestCwd.parseOsc7("http://example.com/path"))
    }

    @Test fun `empty path is rejected`() {
        assertNull(GuestCwd.parseOsc7("file://"))
        assertNull(GuestCwd.parseOsc7("file://hostonly"))
        // T88 修正：`file:///`（path="/"）是合法的根目录 cwd，不是空路径 ——
        // 旧断言按 null 期望错误地拒绝根目录。
        assertEquals("根路径是合法 cwd", "/", GuestCwd.parseOsc7("file:///"))
    }

    @Test fun `lone percent is rejected conservatively`() {
        assertNull(GuestCwd.parseOsc7("file:///a%"))
        assertNull(GuestCwd.parseOsc7("file:///a%2"))
    }

    @Test fun `non-hex escape is rejected`() {
        assertNull(GuestCwd.parseOsc7("file:///a%zz"))
    }

    @Test fun `whitespace around the URI is trimmed`() {
        assertEquals("/x", GuestCwd.parseOsc7(" file:///x "))
    }

    // ═══ OSC 9;9：ConEmu 形式 ═══

    @Test fun `conemu form parses plain path`() {
        assertEquals("/root/project", GuestCwd.parseOsc9("9;/root/project"))
    }

    @Test fun `conemu form strips surrounding quotes`() {
        assertEquals("/root/project", GuestCwd.parseOsc9("9;\"/root/project\""))
    }

    @Test fun `conemu form percent-decodes`() {
        assertEquals("/a b", GuestCwd.parseOsc9("9;/a%20b"))
    }

    @Test fun `conemu form without 9 prefix is rejected`() {
        assertNull(GuestCwd.parseOsc9("/root"))
    }

    @Test fun `conemu form with wrong subcode is rejected`() {
        assertNull(GuestCwd.parseOsc9("8;/root"))
    }

    @Test fun `conemu empty path is rejected`() {
        assertNull(GuestCwd.parseOsc9("9;"))
        assertNull(GuestCwd.parseOsc9("9;\"\""))
    }

    // ═══ decodePercent 单元矩阵 ═══

    @Test fun `plain string without percent passes through`() {
        assertEquals("/abc", GuestCwd.decodePercent("/abc"))
    }

    @Test fun `adjacent escapes decode byte-wise`() {
        assertEquals("AB", GuestCwd.decodePercent("%41%42"))
    }

    @Test fun `mixed text and escapes`() {
        assertEquals("a-b", GuestCwd.decodePercent("a%2Db"))
    }

    // ═══ 引擎集成 ═══

    private fun core(): TerminalCore = TerminalCore(3, 10)
    private fun feed(c: TerminalCore, s: String) = c.feed(s.toByteArray())

    @Test fun `OSC 7 sets guestCwd in snapshot`() {
        val c = core()
        feed(c, "\u001B]7;file:///root/project\u001B\\")
        assertEquals("/root/project", c.snapshot().guestCwd)
    }

    @Test fun `OSC 9 9 sets guestCwd in snapshot`() {
        val c = core()
        feed(c, "\u001B]9;9;/data/local/tmp\u001B\\")
        assertEquals("/data/local/tmp", c.snapshot().guestCwd)
    }

    @Test fun `OSC 7 with percent-escaped path decodes in engine`() {
        val c = core()
        feed(c, "\u001B]7;file:///home/u/my%20dir\u001B\\")
        assertEquals("/home/u/my dir", c.snapshot().guestCwd)
    }

    @Test fun `malformed OSC 7 keeps previous cwd`() {
        val c = core()
        feed(c, "\u001B]7;file:///good\u001B\\")
        feed(c, "\u001B]7;file:///bad%\u001B\\")
        assertEquals("畸形上报不落半截目录", "/good", c.snapshot().guestCwd)
    }

    @Test fun `RIS clears guestCwd`() {
        val c = core()
        feed(c, "\u001B]7;file:///x\u001B\\")
        feed(c, "\u001Bc")
        assertNull(c.snapshot().guestCwd)
    }

    @Test fun `latest report wins`() {
        val c = core()
        feed(c, "\u001B]7;file:///first\u001B\\")
        feed(c, "\u001B]9;9;/second\u001B\\")
        assertEquals("/second", c.snapshot().guestCwd)
    }
}
