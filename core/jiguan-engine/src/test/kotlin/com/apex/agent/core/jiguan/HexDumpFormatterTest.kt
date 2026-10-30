package com.apex.agent.core.jiguan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hex 视图格式化器回归 —— 三栏文本 / 截断声明 / 多类型解释。
 */
class HexDumpFormatterTest {

    @Test
    fun `format renders classic three columns`() {
        val buffer = "Hi!".toByteArray(Charsets.US_ASCII)
        val text = HexDumpFormatter.format(buffer, baseAddress = 0x1000)
        assertTrue(text.contains("00001000"))
        assertTrue(text.contains("48 69 21"))
        assertTrue(text.contains("Hi!"))
    }

    @Test
    fun `format truncates large buffers with notice`() {
        val buffer = ByteArray(1000) { (it % 251).toByte() }
        val text = HexDumpFormatter.format(buffer, 0, maxBytes = 128)
        assertTrue(text.contains("more bytes not shown"))
        assertTrue(text.contains("total 1000"))
    }

    @Test
    fun `format pads short lines`() {
        val text = HexDumpFormatter.format(byteArrayOf(1, 2, 3), 0)
        // 3 字节行：3 个 hex 对 + 13 个空位填充。
        assertTrue(text.contains("01 02 03"))
        assertTrue(text.endsWith("..."))
    }

    @Test
    fun `interpretations detects int float and utf8`() {
        // int32 1337 → float 位模式解释 + "AB" UTF-8。
        val buffer = 1337.toLe4() + "AB".toByteArray()
        val views = HexDumpFormatter.interpretations(buffer)
        assertTrue(views.any { it.startsWith("int32le@0 = 1337") })
        assertTrue(views.any { it.startsWith("float32le@0 = ") })
        assertTrue(views.any { it.contains("utf8@0") && it.contains("AB") })
    }

    @Test
    fun `interpretations detects utf16 ascii`() {
        // "AB" UTF-16LE = 41 00 42 00。
        val buffer = "AB".toByteArray(Charsets.UTF_16LE) + byteArrayOf(0, 0, 0, 0)
        val views = HexDumpFormatter.interpretations(buffer)
        assertTrue(views.any { it.startsWith("utf16le@0 = \"AB\"") })
    }

    @Test
    fun `view assembles pid address and interpretations`() {
        val view = HexDumpFormatter.view(pid = 42, buffer = byteArrayOf(0x7F, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte()), address = 0xABC)
        assertEquals(42, view.pid)
        assertEquals(0xABC, view.address)
        assertEquals(4, view.size)
        assertTrue(view.text.contains("7F 45 4C 46"))
    }

    private fun Int.toLe4(): ByteArray = byteArrayOf(
        (this and 0xFF).toByte(), ((this shr 8) and 0xFF).toByte(),
        ((this shr 16) and 0xFF).toByte(), ((this shr 24) and 0xFF).toByte(),
    )
}
