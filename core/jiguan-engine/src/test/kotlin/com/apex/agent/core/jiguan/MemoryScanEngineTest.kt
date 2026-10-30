package com.apex.agent.core.jiguan

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 内存扫描引擎回归 —— 编码 / 精确 / 通配 / 模糊 / 高层 scanChunk 全路径。
 */
class MemoryScanEngineTest {

    // ── 模式解析 ──────────────────────────────────────────────────

    @Test
    fun `parseBytePattern handles hex spaces wildcards lowercase`() {
        assertEquals(
            listOf<Byte?>(0xDE.toByte(), 0xAD.toByte(), null, null, 0xEF.toByte()),
            MemoryScanEngine.parseBytePattern("de ad ?? ef"),
        )
        assertEquals(listOf<Byte?>(1, 2), MemoryScanEngine.parseBytePattern("01,02"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `parseBytePattern rejects empty`() {
        MemoryScanEngine.parseBytePattern("   ")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `parseBytePattern rejects bad token`() {
        MemoryScanEngine.parseBytePattern("DE ZZ")
    }

    // ── 精确扫描 ──────────────────────────────────────────────────

    @Test
    fun `scanBufferExact finds all non-overlapping hits`() {
        val buffer = byteArrayOf(1, 2, 3, 1, 2, 3, 9, 1, 2, 3)
        val hits = MemoryScanEngine.scanBufferExact(buffer, byteArrayOf(1, 2, 3), 100)
        assertEquals(listOf(0, 3, 7), hits)
    }

    @Test
    fun `scanBufferExact respects maxResults`() {
        val buffer = byteArrayOf(7, 7, 7, 7, 7, 7)
        val hits = MemoryScanEngine.scanBufferExact(buffer, byteArrayOf(7), 3)
        assertEquals(3, hits.size)
    }

    @Test
    fun `scanBufferExact empty needle yields nothing`() {
        assertTrue(MemoryScanEngine.scanBufferExact(byteArrayOf(1, 2), ByteArray(0), 10).isEmpty())
    }

    // ── 通配扫描 ──────────────────────────────────────────────────

    @Test
    fun `scanBufferPattern honors wildcards`() {
        // 0x90 0xDE 0xAD 0x00 0x00 0xEF → 命中 DE AD ?? ?? EF @1
        val buffer = byteArrayOf(0x90.toByte(), 0xDE.toByte(), 0xAD.toByte(), 0, 0, 0xEF.toByte())
        val pattern = listOf<Byte?>(0xDE.toByte(), 0xAD.toByte(), null, null, 0xEF.toByte())
        assertEquals(listOf(1), MemoryScanEngine.scanBufferPattern(buffer, pattern, 10))
    }

    @Test
    fun `scanBufferPattern all-wildcard matches every position`() {
        val hits = MemoryScanEngine.scanBufferPattern(byteArrayOf(1, 2, 3), listOf<Byte?>(null, null), 10)
        assertEquals(listOf(0, 1, 2).take(2), hits.take(2).sorted())
    }

    // ── 模糊扫描 ──────────────────────────────────────────────────

    @Test
    fun `scanBufferFuzzyFloat matches within tolerance`() {
        // 1.0f 的 LE 位模式 + 1.05f + 2.0f。
        val buffer = Float.toRawBits(1.0f).toLe4() + Float.toRawBits(1.05f).toLe4() + Float.toRawBits(2.0f).toLe4()
        val hits = MemoryScanEngine.scanBufferFuzzyFloat(buffer, target = 1.0, byteCount = 4, fuzzyRange = 0.1, maxResults = 10)
        assertEquals(listOf(0, 4), hits)
    }

    @Test
    fun `scanBufferFuzzyInt matches integer tolerance`() {
        val buffer = 100.toLe4() + 102.toLe4() + 200.toLe4()
        val hits = MemoryScanEngine.scanBufferFuzzyInt(buffer, target = 100, byteCount = 4, fuzzyRange = 2.0, maxResults = 10)
        assertEquals(listOf(0, 4), hits)
    }

    // ── 值编码 ────────────────────────────────────────────────────

    @Test
    fun `encodeValue little endian across families`() {
        assertArrayEquals(byteArrayOf(0xE8.toByte(), 0x03, 0, 0), MemoryScanEngine.encodeValue(scanReq("1000", ScanValueType.INT32)))
        assertArrayEquals(
            byteArrayOf(0x39, 0x30.toByte(), 0, 0, 0, 0, 0, 0),
            MemoryScanEngine.encodeValue(scanReq("12345", ScanValueType.INT64)),
        )
        assertArrayEquals("hi".toByteArray(Charsets.UTF_8), MemoryScanEngine.encodeValue(scanReq("hi", ScanValueType.UTF8)))
    }

    // ── 高层 scanChunk ────────────────────────────────────────────

    @Test
    fun `scanChunk produces absolute addresses and value text`() {
        // int32 值 1337 @ offset 8，region base 0x1000。
        val buffer = ByteArray(16)
        val v = 1337
        for (i in 0 until 4) buffer[8 + i] = ((v shr (8 * i)) and 0xFF).toByte()
        val hits = MemoryScanEngine.scanChunk(
            scanReq("1337", ScanValueType.INT32), buffer, regionBase = 0x1000, regionPath = "[heap]",
        )
        assertEquals(1, hits.size)
        assertEquals(0x1008L, hits[0].address)
        assertEquals("1337", hits[0].valueText)
        assertEquals("[heap]", hits[0].regionPath)
    }

    @Test
    fun `scanChunk utf16 finds string and decodes`() {
        val text = "金币"
        val bytes = text.toByteArray(Charsets.UTF_16LE) + byteArrayOf(0, 0)
        val hits = MemoryScanEngine.scanChunk(
            scanReq(text, ScanValueType.UTF16), bytes, regionBase = 0, regionPath = "",
        )
        assertEquals(1, hits.size)
        assertEquals("金币", hits[0].valueText)
        assertEquals("[anon]", hits[0].regionPath)
    }

    // ── helpers ───────────────────────────────────────────────────

    private fun scanReq(value: String, type: ScanValueType) = ScanRequest(pid = 1, value = value, type = type)

    private fun Int.toLe4(): ByteArray = byteArrayOf(
        (this and 0xFF).toByte(), ((this shr 8) and 0xFF).toByte(),
        ((this shr 16) and 0xFF).toByte(), ((this shr 24) and 0xFF).toByte(),
    )

    private fun Float.toRawBitsLe4(): ByteArray = Float.toRawBits(this).toLe4()
}
