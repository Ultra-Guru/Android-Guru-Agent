package com.apex.agent.terminalemulator

import org.junit.Assert.*
import org.junit.Test

/**
 * v0.3 会话序列化测试 —— 任务的「Session save/restore」组。
 *
 * 契约：`TerminalCore.saveSession()` → 紧凑二进制；`TerminalCore.restoreSession()`
 * 重建等价引擎（buffer cells + 样式 + 光标 + 模式 + scrollback（≤500 行）+
 * 制表位 + 边距 + 动态色 + 字符集 + 标题 + cwd）。篡改/截断 → **null**
 * （绝不抛 —— 防崩纪律）；宽字符整对存活。
 */
class SessionSerializationTest {

    private fun feed(c: TerminalCore, s: String) = c.feed(s.toByteArray())

    private fun core(rows: Int = 4, cols: Int = 10): TerminalCore = TerminalCore(rows, cols)

    // ═══ 基本往返 ═══

    @Test fun `screen text and cursor round-trip`() {
        val c = core()
        feed(c, "hello\r\nworld")
        feed(c, "\u001B[2;5H")
        val blob = c.saveSession()!!
        val r = TerminalCore.restoreSession(blob)!!
        assertEquals("hello", r.snapshot().renderedText.lines()[0])
        assertEquals("world", r.snapshot().renderedText.lines()[1])
        assertEquals(1, r.snapshot().cursorRow)
        assertEquals(4, r.snapshot().cursorCol)
    }

    @Test fun `styled cells survive the round trip`() {
        val c = core()
        // T88 修正：期望 0xFFFF0000 是亮红 → SGR 91（SGR 31 是 0x800000 标准红）。
        feed(c, "\u001B[91;44mAB\u001B[0mCD")
        val blob = c.saveSession()!!
        val snap = TerminalCore.restoreSession(blob)!!.renderSnapshot()
        assertEquals(0xFFFF0000L, snap.lines[0][0].fg)   // A 亮红（SGR 91 → Indexed(9)）
        assertEquals(0xFF000080L, snap.lines[0][0].bg)   // A 蓝底（SGR 44 → Indexed(4)）
        assertEquals(0L, snap.lines[0][2].fg)            // C 默认
    }

    @Test fun `underline color (SGR 58) survives the round trip`() {
        val c = core()
        feed(c, "\u001B[4m\u001B[58;2;1;2;3mX")
        val blob = c.saveSession()!!
        val snap = TerminalCore.restoreSession(blob)!!.renderSnapshot()
        assertEquals(0xFF010203L, snap.lines[0][0].underlineColorLong)
    }

    @Test fun `wide chars survive the round trip`() {
        val c = core()
        feed(c, "中文abc")
        val blob = c.saveSession()!!
        val r = TerminalCore.restoreSession(blob)!!
        assertEquals("中文abc", r.snapshot().renderedText.lines()[0])
        val cell = r.renderSnapshot().lines[0][0]
        assertTrue("宽字符 lead 仍是宽格", cell.flags and RenderCell.FLAG_WIDE != 0)
    }

    @Test fun `combining marks survive the round trip`() {
        val c = core()
        feed(c, "e\u0301")                 // e + combining acute
        val blob = c.saveSession()!!
        val r = TerminalCore.restoreSession(blob)!!
        // T88 修正：断言走 renderSnapshot（组合标记在 RenderCell.text 里），
        // 且期望串必须是**分解形**（e + U+0301）—— cell 文本是「基元 + 组合标记」
        // 原样拼接，不做 NFC 规范化（assertEquals 按码点比较，视觉相同 ≠ 相等）。
        assertEquals("e\u0301", r.renderSnapshot().lines[0][0].text)
    }

    @Test fun `modes round-trip`() {
        val c = core()
        feed(c, "\u001B[?1h\u001B[?25l\u001B[?2004h\u001B[?5h\u001B[?6h\u001B[4h\u001B[20h")
        val blob = c.saveSession()!!
        val r = TerminalCore.restoreSession(blob)!!
        val snap = r.renderSnapshot()
        assertTrue("DECCKM", snap.applicationCursor)
        assertFalse("DECTCEM 关", snap.cursorVisible)
        assertTrue("2004", snap.bracketedPaste)
        assertTrue("DECSCNM", snap.reverseVideo)
        // IRM/LNM 的行为验证：LNM 开 → LF 同时回列首。
        feed(r, "\u001B[1;5H\r\n")
        assertEquals("LNM：LF 回列首", 0, r.snapshot().cursorCol)
    }

    @Test fun `scroll region and margins round-trip`() {
        val c = core()
        feed(c, "\u001B[2;3r\u001B[?69h\u001B[2;8s")
        val blob = c.saveSession()!!
        val r = TerminalCore.restoreSession(blob)!!
        // 滚区语义：光标在滚区底行 LF → 只滚滚区（行数不变）。
        feed(r, "\u001B[3;1H\n")
        assertEquals("滚区 [1..2] 保留：光标钉在 bottom=2", 2, r.snapshot().cursorRow)
        // 边距语义：CUP 1;20 → 被右边距 col7 钳住。
        feed(r, "\u001B[1;20H")
        assertEquals("右边距 col7 保留", 7, r.snapshot().cursorCol)
    }

    @Test fun `tab stops round-trip`() {
        val c = core()
        feed(c, "\u001B[3g")                // 清全部制表位
        feed(c, "\u001B[1;4H\u001BH")       // col3 设一个 stop
        val blob = c.saveSession()!!
        val r = TerminalCore.restoreSession(blob)!!
        feed(r, "\u001B[1;1H\t")            // HT → 唯一的 stop col3
        assertEquals(3, r.snapshot().cursorCol)
    }

    @Test fun `dynamic colors round-trip`() {
        val c = core()
        feed(c, "\u001B]10;#ffcc00\u001B\\\u001B]11;#101010\u001B\\")
        val blob = c.saveSession()!!
        val snap = TerminalCore.restoreSession(blob)!!.renderSnapshot()
        assertEquals(0xFFFFCC00L, snap.dynamicForeground)
        assertEquals(0xFF101010L, snap.dynamicBackground)
    }

    @Test fun `title and guestCwd round-trip`() {
        val c = core()
        feed(c, "\u001B]0;My Session\u0007")
        feed(c, "\u001B]7;file:///root/project\u001B\\")
        val blob = c.saveSession()!!
        val r = TerminalCore.restoreSession(blob)!!
        assertEquals("My Session", r.snapshot().title)
        assertEquals("/root/project", r.snapshot().guestCwd)
    }

    @Test fun `charset state round-trip`() {
        val c = core()
        feed(c, "\u001B(0\u001B)K\u000E")   // G0 graphics；G1 German；SO → G1
        val blob = c.saveSession()!!
        val r = TerminalCore.restoreSession(blob)!!
        feed(r, "[")                        // G1 German：[ → Ä
        assertEquals("SO/G1 German 状态存活", "Ä", r.snapshot().renderedText.lines()[0].trim())
    }

    @Test fun `saved cursor (DECSC) round-trip`() {
        val c = core()
        feed(c, "AB")
        feed(c, "\u001B7")                  // 保存 (0,2)
        feed(c, "CD")
        val blob = c.saveSession()!!
        val r = TerminalCore.restoreSession(blob)!!
        feed(r, "\u001B8")                  // 恢复保存位
        assertEquals(2, r.snapshot().cursorCol)
        assertEquals(0, r.snapshot().cursorRow)
    }

    @Test fun `wrapPending state round-trip`() {
        val c = core(cols = 5)
        feed(c, "ABCDE")                    // 打满 → wrapPending
        val blob = c.saveSession()!!
        val r = TerminalCore.restoreSession(blob)!!
        feed(r, "F")                        // 应折到 row1 col0
        assertEquals(1, r.snapshot().cursorRow)
        assertEquals(1, r.snapshot().cursorCol)
        assertEquals("F", r.snapshot().renderedText.lines()[1])
    }

    @Test fun `cursor style round-trip`() {
        val c = core()
        feed(c, "\u001B[2 q")               // DECSCUSR 2 = 块
        val blob = c.saveSession()!!
        assertEquals(CursorStyle.BLOCK, TerminalCore.restoreSession(blob)!!.renderSnapshot().cursorStyle)
    }

    @Test fun `alternate screen bit round-trips as blank alt`() {
        val c = core()
        feed(c, "\u001B[?1049h")
        val blob = c.saveSession()!!
        val r = TerminalCore.restoreSession(blob)!!
        assertTrue("备用屏模式位存活", r.snapshot().alternateScreen)
        assertEquals("备用屏内容是瞬态 —— 恢复为空白", "", r.snapshot().renderedText.trim())
    }

    // ═══ scrollback 往返 + 500 上限 ═══

    @Test fun `scrollback round-trips in order`() {
        val c = core(3, 10)
        repeat(9) { i -> feed(c, "line$i\r\n") }   // 7 行滚入 scrollback
        val blob = c.saveSession()!!
        val r = TerminalCore.restoreSession(blob)!!
        assertEquals(7, r.scrollbackLineCount())
        assertEquals(listOf("line0", "line1", "line2", "line3", "line4", "line5", "line6"),
            r.scrollbackText(20))
    }

    @Test fun `scrollback serialization is capped at 500 lines`() {
        val c = TerminalCore(3, 10, 2000)
        repeat(600) { i -> feed(c, "L$i\r\n") }    // 598 行入 scrollback
        assertEquals(598, c.scrollbackLineCount())
        val blob = c.saveSession()!!
        val r = TerminalCore.restoreSession(blob)!!
        assertEquals("blob 只携带最近 500 行", 500, r.scrollbackLineCount())
        assertEquals("最旧被截：从 L98 起", "L98", r.scrollbackText(600).first())
    }

    @Test fun `maxScrollbackRows argument bounds the payload`() {
        val c = TerminalCore(3, 10, 2000)
        repeat(40) { i -> feed(c, "L$i\r\n") }
        val blob = c.saveSession(maxScrollbackRows = 10)!!
        val r = TerminalCore.restoreSession(blob)!!
        assertEquals(10, r.scrollbackLineCount())
        assertEquals("L28", r.scrollbackText(50).first())
    }

    // ═══ 恢复后继续 feed：引擎活性 ═══

    @Test fun `restored engine keeps processing input`() {
        val c = core()
        feed(c, "abc")
        val r = TerminalCore.restoreSession(c.saveSession()!!)!!
        feed(r, "def")
        assertEquals("abcdef", r.snapshot().renderedText.lines()[0])
    }

    @Test fun `restored engine answers DA1`() {
        val c = core()
        val r = TerminalCore.restoreSession(c.saveSession()!!)!!
        val replies = mutableListOf<String>()
        r.responseSink = { replies.add(String(it, Charsets.US_ASCII)) }
        feed(r, "\u001B[c")
        assertEquals("\u001B[?6c", replies.last())
    }

    // ═══ 防崩：篡改/截断 → null（绝不抛）═══

    @Test fun `empty and garbage blobs return null`() {
        assertNull(TerminalCore.restoreSession(ByteArray(0)))
        assertNull(TerminalCore.restoreSession(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9)))
        assertNull(TerminalCore.restoreSession("hello".toByteArray()))
    }

    @Test fun `truncated blob returns null`() {
        val c = core()
        feed(c, "some content\r\nmore")
        val blob = c.saveSession()!!
        for (cut in listOf(1, 4, 8, 16, blob.size / 2, blob.size - 1)) {
            assertNull("截断到 $cut 字节 → null", TerminalCore.restoreSession(blob.copyOf(cut)))
        }
    }

    @Test fun `trailing extra bytes are rejected`() {
        val c = core()
        feed(c, "x")
        val blob = c.saveSession()!!
        val tampered = blob + byteArrayOf(0, 0, 0)
        assertNull("尾部拼接 = 非完整消费 → null", TerminalCore.restoreSession(tampered))
    }

    @Test fun `flipped magic is rejected`() {
        val c = core()
        feed(c, "x")
        val blob = c.saveSession()!!
        val bad = blob.copyOf()
        bad[0] = 'X'.code.toByte()
        assertNull(TerminalCore.restoreSession(bad))
    }

    @Test fun `future version is rejected`() {
        val c = core()
        val blob = c.saveSession()!!
        val bad = blob.copyOf()
        bad[4] = (SessionSerialization.VERSION + 1).toByte()
        assertNull(TerminalCore.restoreSession(bad))
    }

    @Test fun `random mutations never throw`() {
        val c = core(5, 20)
        repeat(30) { i -> feed(c, "row $i with 中文 wide\r\n") }
        val blob = c.saveSession()!!
        val rnd = java.util.Random(42)
        repeat(200) {
            val bad = blob.copyOf()
            repeat(1 + rnd.nextInt(4)) {
                bad[rnd.nextInt(bad.size)] = rnd.nextInt(256).toByte()
            }
            // 任何结果都允许（null 或成功），唯独不允许抛异常。
            TerminalCore.restoreSession(bad)
        }
    }

    @Test fun `saveSession never throws on hostile state`() {
        val c = core(2, 4)
        feed(c, "\u001B[999;999H")          // 越界光标 → clamp
        feed(c, "\u001B]7;file:///ok\u001B\\")
        val blob = c.saveSession()
        assertNotNull(blob)
        assertNotNull(TerminalCore.restoreSession(blob!!))
    }
}
