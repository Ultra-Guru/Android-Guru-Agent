package com.apex.agent.terminalemulator

import org.junit.Assert.*
import org.junit.Test

/**
 * v0.3 窗口操作（CSI t）测试 —— 任务的「Window ops」组：
 *  - `CSI 8;rows;cols t` 尺寸请求（**只上报**，经 snapshot.requestedResize /
 *    drainResizeRequest 两条通道；绝不直接改缓冲）；
 *  - `CSI 18 t` 字符尺寸查询 → 应答 `CSI 8;rows;cols t`；
 *  - `CSI 14 t` 像素尺寸查询 → 应答 `CSI 4;0;0t`（像素是宿主渲染层事实）；
 *  - `CSI 22/23 t` 标题栈（有界 8 层，防泄漏纪律）；
 *  - 私有标记/未知操作 → 安全忽略。
 */
class WindowOpsTest {

    private fun core(rows: Int = 4, cols: Int = 10): TerminalCore = TerminalCore(rows, cols)
    private fun feed(c: TerminalCore, s: String) = c.feed(s.toByteArray())

    private class Sink {
        val replies = mutableListOf<String>()
        fun attach(c: TerminalCore) { c.responseSink = { replies.add(String(it, Charsets.US_ASCII)) } }
        fun count(): Int = replies.size
        fun last(): String = replies.last()
    }

    // ═══ CSI 8 —— 尺寸请求 ═══

    @Test fun `resize request is exposed via snapshot`() {
        val c = core()
        feed(c, "\u001B[8;24;80t")
        assertEquals(Pair(24, 80), c.renderSnapshot().requestedResize)
    }

    @Test fun `drainResizeRequest is consumptive`() {
        val c = core()
        feed(c, "\u001B[8;24;80t")
        assertEquals(Pair(24, 80), c.drainResizeRequest())
        assertNull("第二次消费为空", c.drainResizeRequest())
        assertNull("快照镜像同样清空", c.renderSnapshot().requestedResize)
    }

    @Test fun `snapshot requestedResize is a peek and keeps the request`() {
        val c = core()
        feed(c, "\u001B[8;24;80t")
        c.renderSnapshot()
        c.renderSnapshot()
        assertEquals("快照不消费", Pair(24, 80), c.drainResizeRequest())
    }

    @Test fun `resize request does not change the buffer`() {
        val c = core(4, 10)
        feed(c, "hi")
        feed(c, "\u001B[8;60;200t")
        assertEquals("行数不变", 4, c.rows)
        assertEquals("列数不变", 10, c.cols)
        assertEquals("屏幕内容不变", "hi", c.snapshot().renderedText.trimEnd())
    }

    @Test fun `resize request params are clamped to sane bounds`() {
        val c = core()
        feed(c, "\u001B[8;99999;99999t")
        assertEquals(Pair(4096, 4096), c.drainResizeRequest())
    }

    @Test fun `resize request with default params echoes current size`() {
        val c = core(4, 10)
        feed(c, "\u001B[8t")
        assertEquals("缺省 = 当前尺寸", Pair(4, 10), c.drainResizeRequest())
    }

    @Test fun `resize request with zero params falls back to defaults`() {
        // paramOrDefault：0 视为缺省 —— 与 xterm「参数 0 = 缺省」一致。
        val c = core(4, 10)
        feed(c, "\u001B[8;0;0t")
        assertEquals(Pair(4, 10), c.drainResizeRequest())
    }

    @Test fun `private marker CSI t is not a window op`() {
        val c = core(); val s = Sink(); s.attach(c)
        feed(c, "\u001B[?8;1;1t")
        assertNull(c.renderSnapshot().requestedResize)
        assertEquals(0, s.count())
    }

    // ═══ CSI 18 —— 字符尺寸查询 ═══

    @Test fun `CSI 18 t reports character cell size`() {
        val c = core(7, 33); val s = Sink(); s.attach(c)
        feed(c, "\u001B[18t")
        assertEquals(listOf("\u001B[8;7;33t"), s.replies)
    }

    @Test fun `CSI 18 t reflects resize`() {
        val c = core(7, 33); val s = Sink(); s.attach(c)
        c.resize(9, 21)
        feed(c, "\u001B[18t")
        assertEquals("\u001B[8;9;21t", s.last())
    }

    // ═══ CSI 14 —— 像素尺寸查询 ═══

    @Test fun `CSI 14 t reports placeholder pixel size`() {
        val c = core(); val s = Sink(); s.attach(c)
        feed(c, "\u001B[14t")
        assertEquals(listOf("\u001B[4;0;0t"), s.replies)
    }

    @Test fun `CSI 14 with sub-param also reports placeholder`() {
        val c = core(); val s = Sink(); s.attach(c)
        feed(c, "\u001B[14;2t")
        assertEquals("\u001B[4;0;0t", s.last())
    }

    // ═══ CSI 22 / 23 —— 标题栈 ═══

    @Test fun `title push and pop restore the previous title`() {
        val c = core()
        feed(c, "\u001B]0;first\u0007")
        feed(c, "\u001B[22t")                 // 推栈：保存 "first"
        feed(c, "\u001B]0;second\u0007")
        assertEquals("second", c.snapshot().title)
        feed(c, "\u001B[23t")                 // 弹栈：恢复 "first"
        assertEquals("first", c.snapshot().title)
    }

    @Test fun `push with param 0 1 2 all push the title stack`() {
        val c = core()
        feed(c, "\u001B]0;A\u0007")
        feed(c, "\u001B[22;0t")
        feed(c, "\u001B]0;B\u0007")
        feed(c, "\u001B[22;1t")
        feed(c, "\u001B]0;C\u0007")
        feed(c, "\u001B[22;2t")
        feed(c, "\u001B]0;D\u0007")
        feed(c, "\u001B[23t"); assertEquals("C", c.snapshot().title)
        feed(c, "\u001B[23t"); assertEquals("B", c.snapshot().title)
        feed(c, "\u001B[23t"); assertEquals("A", c.snapshot().title)
    }

    @Test fun `popping an empty stack keeps the current title`() {
        val c = core()
        feed(c, "\u001B]0;only\u0007")
        feed(c, "\u001B[23t")                 // 空栈 → 无动作
        assertEquals("only", c.snapshot().title)
    }

    @Test fun `title stack is bounded to capacity 8`() {
        val c = core()
        // 9 次推栈（每次推当前标题）→ 栈深 8，最旧被挤掉。
        for (i in 0..9) {
            feed(c, "\u001B]0;T$i\u0007")
            feed(c, "\u001B[22t")
        }
        // 栈内应为 [T2..T9]（T0/T1 被挤掉 —— 第 9、10 次推栈各丢一个）。
        repeat(8) { feed(c, "\u001B[23t") }
        assertEquals("8 次弹栈后恢复到最早的存活标题", "T2", c.snapshot().title)
        feed(c, "\u001B[23t")                 // 栈已空 → 标题不动
        assertEquals("T2", c.snapshot().title)
    }

    @Test fun `null title is a restorable state`() {
        val c = core()
        feed(c, "\u001B[22t")                 // 推「无标题」状态
        feed(c, "\u001B]0;temp\u0007")
        feed(c, "\u001B[23t")
        assertEquals("恢复为无标题", null, c.snapshot().title)
    }

    @Test fun `title stack clears on RIS`() {
        val c = core()
        feed(c, "\u001B]0;A\u0007")
        feed(c, "\u001B[22t")
        feed(c, "\u001B]0;B\u0007")
        feed(c, "\u001Bc")                    // RIS
        feed(c, "\u001B[23t")                 // 栈已清 → 弹空
        assertEquals(null, c.snapshot().title)
    }

    // ═══ 未知 / 未实现操作 → 安全忽略 ═══

    @Test fun `iconify and other unimplemented ops are ignored`() {
        val c = core(); val s = Sink(); s.attach(c)
        feed(c, "\u001B[1t")                  // 图标化
        feed(c, "\u001B[2t")                  // 最小化
        feed(c, "\u001B[3;5;5t")              // 像素定位
        feed(c, "\u001B[9;0t")                // maximize
        assertNull(c.renderSnapshot().requestedResize)
        assertEquals(0, s.count())
    }
}
