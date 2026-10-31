package com.apex.agent.terminalview

import com.apex.agent.terminalemulator.KeyModifiers
import com.apex.agent.terminalemulator.TerminalKey
import com.apex.agent.terminalview.TerminalKeyInputModel.HardwareKey
import com.apex.agent.terminalview.TerminalKeyInputModel.KeyModifier
import com.apex.agent.terminalview.TerminalKeyInputModel.MappedInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T88（2-a）：硬件键映射单测 —— Ctrl 字母全表、xterm 控制区符号、特殊键身份、
 * Android 命名陷阱（KEYCODE_DEL=Backspace）、修饰位透传。
 */
class TerminalKeyInputModelTest {

    private fun key(label: String, vararg mods: KeyModifier, unicode: Int = 0) =
        HardwareKey(label, mods.toSet(), unicode)

    // ─── Ctrl+字母全表（0x01..0x1A）───

    @Test
    fun `ctrl plus lowercase letter full table`() {
        for (i in 0 until 26) {
            val ch = 'a' + i
            val mapped = TerminalKeyInputModel.map(
                key("KEYCODE_${ch.uppercaseChar()}", KeyModifier.CTRL, unicode = ch.code)
            )
            assertTrue("Ctrl+$ch 应为控制字符", mapped is MappedInput.ControlChar)
            assertEquals((i + 1).toLong(), (mapped as MappedInput.ControlChar).code.toLong())
        }
    }

    @Test
    fun `ctrl plus uppercase letter same codes`() {
        for (i in 0 until 26) {
            val ch = 'A' + i
            val mapped = TerminalKeyInputModel.map(
                key("${ch}", KeyModifier.CTRL, unicode = ch.code)
            )
            assertEquals((i + 1).toLong(), (mapped as MappedInput.ControlChar).code.toLong())
        }
    }

    // ─── Ctrl 控制区符号 ───

    @Test
    fun `ctrl symbols map to c0 control codes`() {
        fun ctrl(ch: Char): Int =
            (TerminalKeyInputModel.map(key("SYM", KeyModifier.CTRL, unicode = ch.code))
                as MappedInput.ControlChar).code
        assertEquals(0x00, ctrl(' '))
        assertEquals(0x00, ctrl('@'))
        assertEquals(0x1B, ctrl('['))
        assertEquals(0x1C, ctrl('\\'))
        assertEquals(0x1D, ctrl(']'))
        assertEquals(0x1E, ctrl('^'))
        assertEquals(0x1F, ctrl('_'))
        assertEquals(0x7F, ctrl('?'))
    }

    // ─── 特殊键身份 ───

    @Test
    fun `arrow keys map with mods passthrough`() {
        val up = TerminalKeyInputModel.map(key("DPAD_UP"))
        assertEquals(TerminalKey.UP, (up as MappedInput.TerminalKeyInput).key)
        assertEquals(0, up.mods)

        val ctrlLeft = TerminalKeyInputModel.map(
            key("DPAD_LEFT", KeyModifier.CTRL, KeyModifier.SHIFT, unicode = 0)
        )
        val mapped = ctrlLeft as MappedInput.TerminalKeyInput
        assertEquals(TerminalKey.LEFT, mapped.key)
        assertEquals(KeyModifiers.CTRL or KeyModifiers.SHIFT, mapped.mods)
    }

    @Test
    fun `function keys f1 to f12`() {
        for (i in 1..12) {
            val mapped = TerminalKeyInputModel.map(key("F$i"))
            assertEquals(TerminalKey.entries[i + 14], (mapped as MappedInput.TerminalKeyInput).key)
        }
    }

    @Test
    fun `android del is backspace and forward del is delete`() {
        // Android 命名陷阱：KEYCODE_DEL 语义 = Backspace
        assertEquals(
            TerminalKey.BACKSPACE,
            (TerminalKeyInputModel.map(key("KEYCODE_DEL")) as MappedInput.TerminalKeyInput).key
        )
        assertEquals(
            TerminalKey.DELETE,
            (TerminalKeyInputModel.map(key("KEYCODE_FORWARD_DEL")) as MappedInput.TerminalKeyInput).key
        )
    }

    @Test
    fun `navigation cluster maps to terminal keys`() {
        assertEquals(TerminalKey.HOME, (TerminalKeyInputModel.map(key("MOVE_HOME")) as MappedInput.TerminalKeyInput).key)
        assertEquals(TerminalKey.END, (TerminalKeyInputModel.map(key("MOVE_END")) as MappedInput.TerminalKeyInput).key)
        assertEquals(TerminalKey.PAGE_UP, (TerminalKeyInputModel.map(key("PAGE_UP")) as MappedInput.TerminalKeyInput).key)
        assertEquals(TerminalKey.PAGE_DOWN, (TerminalKeyInputModel.map(key("PAGE_DOWN")) as MappedInput.TerminalKeyInput).key)
        assertEquals(TerminalKey.ESCAPE, (TerminalKeyInputModel.map(key("ESCAPE")) as MappedInput.TerminalKeyInput).key)
        assertEquals(TerminalKey.INSERT, (TerminalKeyInputModel.map(key("INSERT")) as MappedInput.TerminalKeyInput).key)
        assertEquals(TerminalKey.TAB, (TerminalKeyInputModel.map(key("TAB")) as MappedInput.TerminalKeyInput).key)
        assertEquals(TerminalKey.ENTER, (TerminalKeyInputModel.map(key("ENTER")) as MappedInput.TerminalKeyInput).key)
        assertEquals(
            TerminalKey.NUMPAD_ENTER,
            (TerminalKeyInputModel.map(key("NUMPAD_ENTER")) as MappedInput.TerminalKeyInput).key
        )
    }

    @Test
    fun `numpad digits map to numpad identities`() {
        for (i in 0..9) {
            val mapped = TerminalKeyInputModel.map(key("NUMPAD_$i"))
            assertEquals(
                TerminalKey.entries[27 + i],
                (mapped as MappedInput.TerminalKeyInput).key
            )
        }
    }

    // ─── 字符输入 ───

    @Test
    fun `plain letter maps to char input`() {
        val mapped = TerminalKeyInputModel.map(key("A", unicode = 'a'.code))
        assertTrue(mapped is MappedInput.CharInput)
        assertEquals("a", (mapped as MappedInput.CharInput).text)
        assertEquals(0, mapped.mods)
    }

    @Test
    fun `alt plus letter keeps alt bit for esc prefix`() {
        val mapped = TerminalKeyInputModel.map(
            key("B", KeyModifier.ALT, unicode = 'b'.code)
        )
        val ci = mapped as MappedInput.CharInput
        assertEquals("b", ci.text)
        assertEquals(KeyModifiers.ALT, ci.mods)
    }

    @Test
    fun `symbol keys fall back to label table when unicode missing`() {
        val mapped = TerminalKeyInputModel.map(key("SLASH"))
        assertEquals("/", (mapped as MappedInput.CharInput).text)
        val mapped2 = TerminalKeyInputModel.map(key("MINUS"))
        assertEquals("-", (mapped2 as MappedInput.CharInput).text)
    }

    @Test
    fun `ctrl plus digit passes through as plain char`() {
        // xterm 语义：Ctrl+1 无控制映射 → 发原字符
        val mapped = TerminalKeyInputModel.map(key("1", KeyModifier.CTRL, unicode = '1'.code))
        val ci = mapped as MappedInput.CharInput
        assertEquals("1", ci.text)
        assertEquals(0, ci.mods and KeyModifiers.CTRL) // CTRL 位被剥
    }

    @Test
    fun `non printable system keys are unmapped`() {
        assertEquals(MappedInput.Unmapped, TerminalKeyInputModel.map(key("VOLUME_UP")))
        assertEquals(MappedInput.Unmapped, TerminalKeyInputModel.map(key("CAMERA")))
        assertEquals(MappedInput.Unmapped, TerminalKeyInputModel.map(key("HOME_BUTTON")))
    }

    // ─── 修饰位换算 ───

    @Test
    fun `modifier mask conversion matches emulator bits`() {
        assertEquals(0, KeyModifier.maskOf(emptySet()))
        assertEquals(
            KeyModifiers.SHIFT or KeyModifiers.ALT or KeyModifiers.CTRL or KeyModifiers.META,
            KeyModifier.maskOf(setOf(KeyModifier.SHIFT, KeyModifier.ALT, KeyModifier.CTRL, KeyModifier.META))
        )
        assertEquals(KeyModifiers.CTRL, KeyModifier.maskOf(setOf(KeyModifier.CTRL)))
    }

    @Test
    fun `label normalization strips KEYCODE prefix case insensitive`() {
        val a = TerminalKeyInputModel.map(HardwareKey("KEYCODE_DPAD_UP"))
        val b = TerminalKeyInputModel.map(HardwareKey("dpad_up"))
        assertEquals((a as MappedInput.TerminalKeyInput).key, (b as MappedInput.TerminalKeyInput).key)
    }
}
