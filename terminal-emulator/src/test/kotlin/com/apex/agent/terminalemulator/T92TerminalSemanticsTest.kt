package com.apex.agent.terminalemulator

import org.junit.Assert.*
import org.junit.Test

/**
 * T92：终端内核语义精修回归 —— LF/IND/NEL 滚区外只下移不滚屏（Termux
 * doLinefeed）、CHT 补齐、DECOM 归位、CSI 1048、VtParser C0/CAN/SUB 状态机、
 * C1 控制与 UTF-8 解码文本的区分（RAW_C1_TAG）、OSC 码点级字符串、OSC 畸形
 * 中止丢弃、eraseRow 全宽断链、Reflow 底部锚定零丢弃。
 */
class T92TerminalSemanticsTest {

    private fun feed(c: TerminalCore, s: String) = c.feed(s.toByteArray())
    private fun text(c: TerminalCore, row: Int): String =
        c.renderSnapshot().lines.getOrNull(row)?.joinToString("") { it.text } ?: ""
    private fun textTrim(c: TerminalCore, row: Int): String = text(c, row).trimEnd()

    // ═══ LF / IND / NEL：光标在滚区外只下移、不滚区 ═══

    @Test fun `LF below scroll region moves down without scrolling the region`() {
        val c = TerminalCore(5, 10)
        feed(c, "AAA")                     // row0 = "AAA"（滚区设后是区外内容基准）
        feed(c, "\u001B[2;3r")             // DECSTBM 滚区 [1,2]
        feed(c, "\u001B[4;1H")             // 光标绝对定位到 row3（滚区下方）
        val cursorBefore = c.snapshot()
        assertEquals(3, cursorBefore.cursorRow)
        feed(c, "\n")                      // LF：光标在区外 → 只下移（row3→row4），滚区不卷
        val s = c.snapshot()
        assertEquals("区外 LF 光标下移一行", 4, s.cursorRow)
        // 滚区内容不得被卷走：把光标移回 row1 打字符应仍在 row1 落位
        feed(c, "\u001B[2;1H")
        feed(c, "Z")
        assertEquals("滚区首行未被 LF 卷动", "Z", textTrim(c, 1))
    }

    @Test fun `LF at scroll region bottom scrolls only the region`() {
        val c = TerminalCore(5, 10)
        feed(c, "L1\r\nL2\r\nL3")          // rows 0-2
        feed(c, "\u001B[1;3r")             // 滚区 [0,2]
        feed(c, "\u001B[3;1H")             // 光标在滚区底（row2）
        feed(c, "\n")                      // LF：滚屏，光标留滚区底
        val s = c.snapshot()
        assertEquals("滚区底 LF 光标钉在滚区底", 2, s.cursorRow)
        assertEquals("滚区内容上卷一行", "L2", textTrim(c, 0))
    }

    @Test fun `LF at screen bottom outside region stays at screen bottom`() {
        val c = TerminalCore(4, 10)
        feed(c, "\u001B[1;2r")             // 滚区 [0,1]，屏 4 行
        feed(c, "\u001B[4;1H")             // 光标屏底（区外）
        feed(c, "\n")
        val s = c.snapshot()
        assertEquals("屏底区外 LF 不越界不滚区", 3, s.cursorRow)
    }

    @Test fun `IND below scroll region moves down without scrolling`() {
        val c = TerminalCore(5, 10)
        feed(c, "\u001B[2;3r")
        feed(c, "\u001B[4;1H")
        feed(c, "\u001BD")                 // IND
        assertEquals("区外 IND 只下移", 4, c.snapshot().cursorRow)
    }

    @Test fun `NEL below scroll region moves down and resets column`() {
        val c = TerminalCore(5, 10)
        feed(c, "\u001B[2;3r")
        feed(c, "\u001B[4;5H")
        feed(c, "\u001BE")                 // NEL
        val s = c.snapshot()
        assertEquals("区外 NEL 只下移", 4, s.cursorRow)
        assertEquals("NEL 列归零", 0, s.cursorCol)
    }

    // ═══ CHT（CSI I）═══

    @Test fun `CHT advances cursor to next tab stop`() {
        val c = TerminalCore(3, 40)
        feed(c, "\u001B[1;1H")
        feed(c, "\u001B[3I")               // CHT 3：0 → 8 → 16 → 24
        assertEquals(24, c.snapshot().cursorCol)
    }

    // ═══ DECOM 归位 ═══

    @Test fun `DECOM set homes cursor to region top`() {
        val c = TerminalCore(5, 10)
        feed(c, "\u001B[3;5r")             // 滚区 [2,4]
        feed(c, "\u001B[5;9H")             // 光标 (4,8)
        feed(c, "\u001B[?6h")              // DECOM on → home 到滚区顶 + 左边距
        val s = c.snapshot()
        assertEquals(2, s.cursorRow)
        assertEquals(0, s.cursorCol)
    }

    @Test fun `DECOM reset homes cursor to screen top`() {
        val c = TerminalCore(5, 10)
        feed(c, "\u001B[3;5r")
        feed(c, "\u001B[?6h")
        feed(c, "\u001B[5;9H")             // DECOM 相对坐标 → 绝对 (5,8)
        feed(c, "\u001B[?6l")              // DECOM off → home 屏顶
        val s = c.snapshot()
        assertEquals(0, s.cursorRow)
        assertEquals(0, s.cursorCol)
    }

    // ═══ CSI 1048（保存/恢复光标）═══

    @Test fun `CSI 1048 saves and restores cursor`() {
        val c = TerminalCore(5, 10)
        feed(c, "\u001B[2;4H")             // 光标 (1,3)
        feed(c, "\u001B[?1048h")           // save
        feed(c, "\u001B[5;9H")             // 移走
        feed(c, "\u001B[?1048l")           // restore
        val s = c.snapshot()
        assertEquals(1, s.cursorRow)
        assertEquals(3, s.cursorCol)
    }

    // ═══ VtParser：CAN/SUB 作废、C0 立即执行 ═══

    @Test fun `CAN aborts CSI and following text lands on screen`() {
        val c = TerminalCore(3, 20)
        // ESC [ 3 0x18(CAN) H e l l o —— 旧实现 H 被 CSI_IGNORE 吃掉
        feed(c, "\u001B[3")
        c.feed(byteArrayOf(0x18.toByte()))
        feed(c, "Hello")
        assertEquals("CAN 后文本完整落屏", "Hello", textTrim(c, 0))
    }

    @Test fun `SUB aborts CSI and following text lands on screen`() {
        val c = TerminalCore(3, 20)
        feed(c, "\u001B[3")
        c.feed(byteArrayOf(0x1A.toByte()))
        feed(c, "Hi")
        assertEquals("SUB 后文本完整落屏", "Hi", textTrim(c, 0))
    }

    @Test fun `CAN aborts OSC mid-string`() {
        val c = TerminalCore(3, 20)
        feed(c, "\u001B]0;half")
        c.feed(byteArrayOf(0x18.toByte()))
        feed(c, "X")
        assertNull("半截 OSC 被作废（无标题）", c.snapshot().title)
        assertEquals("X", textTrim(c, 0))
    }

    @Test fun `C0 control inside CSI executes immediately`() {
        val c = TerminalCore(3, 20)
        feed(c, "A")                        // row0: A
        feed(c, "\u001B[")                  // CSI 开始
        // CR+LF 混进 CSI 参数位 —— 立即执行（C0 在任何序列态生效；序列继续积累）
        c.feed(byteArrayOf(0x0D.toByte(), 0x0A.toByte()))
        feed(c, "J")                        // 0x4A = 'J' final → ED（CSI 正常终结）
        feed(c, "B")
        // CR+LF 已把光标移到 row1 列首 → B 落在 row1（若 C0 被吞则 B 在 row0 追加）
        assertEquals("A", textTrim(c, 0))
        assertEquals("B", textTrim(c, 1))
    }

    // ═══ C1：原始字节 vs 解码文本 ═══

    @Test fun `raw 8-bit CSI 0x9B is still parsed as CSI`() {
        val c = TerminalCore(3, 20)
        c.feed(byteArrayOf(0x9B.toByte(), '2'.code.toByte(), 'J'.code.toByte()))
        assertEquals("原始 8 位 CSI 仍清屏", "", textTrim(c, 0))
    }

    @Test fun `decoded U+009B in UTF-8 text is dropped not CSI`() {
        val c = TerminalCore(3, 20)
        // C2 9B = U+009B（合法解码）—— xterm ctlseqs / Termux：C1 控制不可能来自
        // UTF-8 解码，直接丢弃（不当 CSI、不当文本、不当组合符）
        c.feed(byteArrayOf(0xC2.toByte(), 0x9B.toByte(), 'X'.code.toByte()))
        assertEquals("解码出的 C1 区字符被丢弃", "X", textTrim(c, 0))
    }

    // ═══ OSC：码点级字符串 / 畸形中止 / C0 不拼串 ═══

    @Test fun `OSC title with astral emoji is preserved`() {
        val c = TerminalCore(3, 20)
        feed(c, "\u001B]0;hi \uD83D\uDE00\u001B\\")   // 标题带 😀
        assertEquals("星面码点不被 toChar 截断", "hi 😀", c.snapshot().title)
    }

    @Test fun `OSC aborted by ESC and non-ST final is discarded`() {
        val c = TerminalCore(3, 20)
        // ESC ] 0 ; h i ESC [ 2 J —— 半截 OSC + 新序列（旧实现把 "hi" 设为标题）
        feed(c, "\u001B]0;hi\u001B[2J")
        assertNull("畸形 OSC 半截标题被丢弃", c.snapshot().title)
    }

    @Test fun `C0 inside OSC string does not pollute title`() {
        val c = TerminalCore(3, 20)
        // 标题中的 CR/LF（xterm 忽略）—— 旧行为拼进标题
        c.feed("\u001B]0;a\r\nb\u0007".toByteArray())
        assertEquals("CR/LF 不进标题", "ab", c.snapshot().title)
    }

    // ═══ eraseRow 全宽断链（reflow 契约）═══

    @Test fun `full-width EL breaks wrap chain before reflow`() {
        val c = TerminalCore(2, 5, maxScrollback = 20)
        feed(c, "AAAAABBBBB")              // row0 "AAAAA"(wrapped) row1 "BBBBB"
        feed(c, "\u001B[1;1H")
        feed(c, "\u001B[2K")               // 全宽擦除 row0 —— 断链
        c.resize(2, 10)                    // reflow：row0 空行不得与 "BBBBB" 拼接
        assertEquals("擦除行与下一逻辑行不粘连", "BBBBB", textTrim(c, 1))
    }

    // ═══ Reflow 底部锚定：光标窗口下方内容零丢弃 ═══

    @Test fun `reflow preserves content below the cursor window`() {
        val c = TerminalCore(4, 10, maxScrollback = 50)
        // 4 行内容，光标定位在 row1（内容中部）：
        feed(c, "AAAA\r\nBBBB\r\nCCCC\r\nDDDD")
        feed(c, "\u001B[2;1H")             // 光标到 row1
        c.resize(4, 5)                     // 每行拆成 2 行 → 8 行输出 > 屏 4 行
        // 底部锚定：屏 = 最新 4 行（"BB"/"CCCC"→? 具体拆分下逐一验证）
        val screen = (0 until 4).joinToString("") { textTrim(c, it) }
        val scrollback = c.scrollbackText(50).joinToString("")
        assertEquals(
            "光标下方内容全部保留（屏 + scrollback 总量无损）",
            "AAAABBBBCCCCDDDD",
            (scrollback + screen).replace(" ", "")
        )
        assertEquals("屏底是最新内容", "DDDD", textTrim(c, 3) + textTrim(c, 2).let { "" })
    }

    @Test fun `reflow screen shows the newest rows`() {
        val c = TerminalCore(2, 10, maxScrollback = 50)
        feed(c, "AAAAAAAAAA\r\nBBBBBBBBBB\r\nCCCCCCCCCC\r\nDDDDDDDDDD")
        c.resize(2, 5)                     // 4 条逻辑行各拆 2 行 = 8 行输出，屏 2 行 = 最新
        assertEquals("屏 = 最新 2 行（DDDDDDDDDD 拆 2 行）", "DDDDD", textTrim(c, 0))
        assertEquals("DDDDD", textTrim(c, 1))
        // 更老的内容全部回灌 scrollback（零丢弃：A/B/C 各 10 字符）
        assertEquals(
            "旧内容回灌 scrollback", "AAAAAAAAAABBBBBBBBBBCCCCCCCCCC",
            c.scrollbackText(50).joinToString("").replace(" ", "")
        )
    }

    @Test fun `reflow trailing blank rows do not push content into scrollback`() {
        val c = TerminalCore(3, 10)
        feed(c, "ABCDEFGHIJ")              // 单行铺满 row0，row1/2 空
        c.resize(3, 5)
        assertEquals("空屏尾行不计入锚定", "ABCDE", textTrim(c, 0))
        assertEquals("FGHIJ", textTrim(c, 1))
    }
}
