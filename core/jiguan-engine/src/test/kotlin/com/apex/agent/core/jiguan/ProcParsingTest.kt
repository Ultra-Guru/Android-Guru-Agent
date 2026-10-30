package com.apex.agent.core.jiguan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * /proc 解析器回归 —— 真实样本行（含匿名段 / [heap] / APK 路径 / 坏行容忍）。
 */
class ProcParsingTest {

    @Test
    fun `parseMapsLine parses heap line`() {
        val region = ProcParsing.parseMapsLine(
            "12c00000-12e00000 rw-p 00000000 fd:01 1234      [heap]",
        )
        assertNotNull(region)
        assertEquals(0x12c00000L, region!!.start)
        assertEquals(0x12e00000L, region.end)
        assertEquals("rw-p", region.perms)
        assertTrue(region.writable)
        assertFalse(region.executable)
        assertEquals("[heap]", region.path)
        assertEquals(0x200000L, region.sizeBytes)
    }

    @Test
    fun `parseMapsLine parses apk file mapping`() {
        val region = ProcParsing.parseMapsLine(
            "7a8b9c0000-7a8ba00000 r--p 00000000 fd:01 5678   /data/app/~~x/base.apk",
        )
        assertNotNull(region)
        assertEquals("/data/app/~~x/base.apk", region!!.path)
        assertTrue(region.readable)
        assertFalse(region.writable)
    }

    @Test
    fun `parseMapsLine parses anonymous region`() {
        val region = ProcParsing.parseMapsLine(
            "7f0000000000-7f0000100000 rw-p 00000000 00:00 0  [anon:libc_malloc]",
        )
        assertNotNull(region)
        assertEquals("[anon:libc_malloc]", region!!.path)
    }

    @Test
    fun `parseMapsLine rejects malformed lines`() {
        assertNull(ProcParsing.parseMapsLine(""))
        assertNull(ProcParsing.parseMapsLine("not a maps line at all"))
        assertNull(ProcParsing.parseMapsLine("zzz-zzz rw-p 00000000 00:00 0"))
        // end <= start 拒绝。
        assertNull(ProcParsing.parseMapsLine("2000-1000 rw-p 00000000 00:00 0"))
    }

    @Test
    fun `parseMaps skips bad lines and keeps good ones`() {
        val text = """
            garbage line
            1000-2000 rw-p 00000000 00:00 0
            another garbage
            3000-4000 r-xp 00000000 00:00 0  /system/lib64/libc.so
        """.trimIndent()
        val regions = ProcParsing.parseMaps(text)
        assertEquals(2, regions.size)
        assertEquals("/system/lib64/libc.so", regions[1].path)
    }

    @Test
    fun `aggregateModules merges same path segments`() {
        val regions = listOf(
            ProcParsing.parseMapsLine("1000-2000 r--p 00000000 fd:01 1  /system/lib64/libc.so")!!,
            ProcParsing.parseMapsLine("2000-3000 r-xp 00000000 fd:01 1  /system/lib64/libc.so")!!,
            ProcParsing.parseMapsLine("4000-5000 rw-p 00000000 fd:01 2  /data/app/x/libfoo.so")!!,
        )
        val modules = ProcParsing.aggregateModules(regions)
        assertEquals(2, modules.size)
        val libc = modules.first { it.name == "libc.so" }
        assertEquals(0x1000L, libc.baseAddress)
        assertEquals(0x2000L, libc.sizeBytes)
        assertTrue(libc.executable)
        assertFalse(libc.writable)
    }

    @Test
    fun `parseStatus extracts name uid ppid state rss`() {
        val status = """
            Name:   com.example.app
            Umask:  0077
            State:  S (sleeping in wait)
            Tgid:   12345
            Pid:    12345
            PPid:   1234
            Uid:    10123 10123 10123 10123
            VmRSS:     345678 kB
        """.trimIndent()
        val entry = ProcParsing.parseStatus(status)
        assertNotNull(entry)
        assertEquals("com.example.app", entry!!.name)
        assertEquals(10123, entry.uid)
        assertEquals(1234, entry.ppid)
        assertEquals("S", entry.state)
        assertEquals(345678L, entry.rssKb)
    }

    @Test
    fun `parseStatus returns null when no name`() {
        assertNull(ProcParsing.parseStatus("Pid: 1\nUid: 0 0 0 0\n"))
    }

    @Test
    fun `parseCmdline splits NUL separated argv`() {
        assertEquals("com.example.app --flag", ProcParsing.parseCmdline("com.example.app\u0000--flag\u0000"))
        assertEquals("", ProcParsing.parseCmdline(""))
    }

    @Test
    fun `extractPackage recognizes package shapes`() {
        assertEquals("com.example.app", ProcParsing.extractPackage("com.example.app", ""))
        assertEquals("cn.x.y:remote", ProcParsing.extractPackage("", "cn.x.y:remote"))
        assertNull(ProcParsing.extractPackage("/system/bin/logd", "logd"))
        assertNull(ProcParsing.extractPackage("u0_a123 ps -e", "ps"))
    }
}
