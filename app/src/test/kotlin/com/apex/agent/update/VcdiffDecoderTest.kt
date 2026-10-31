package com.apex.agent.update

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * [VcdiffDecoder] 单元测试 —— 与发布仓库 CI 同源的补丁格式全覆盖。
 *
 * fixtures（src/test/resources/vcdiff/）由本地 xdelta3 3.1.0 生成：
 * - `plain_v1_to_v2 / plain_v2_to_v3`：无二级压缩 VCDIFF（原版默认），
 *   两者串起来验证跨版本链式应用；
 * - `lzma_multi_v1_to_v3`：`-S lzma -B 524288`（Ubuntu 发行版 CI 默认
 *   行为 + 强制多窗口），头部 `05 02` 与真实发布补丁逐字节同构 ——
 *   覆盖「三类 section 持久 xz 续流」这一关键拓扑；
 * - 损坏用例在运行时对 fixture 做字节级篡改，不额外存文件。
 */
class VcdiffDecoderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val decoder = VcdiffDecoder()

    // ── 资源装载 ─────────────────────────────────────────────────────────

    private fun resource(name: String): ByteArray =
        javaClass.classLoader!!.getResourceAsStream("vcdiff/$name")!!.use { it.readBytes() }

    private fun resourceFile(name: String): File {
        val file = tmp.newFile(name)
        file.writeBytes(resource(name))
        return file
    }

    private fun source(name: String): File? =
        resourceFile(name).takeIf { it.length() > 0L }

    // ── 正常路径 ─────────────────────────────────────────────────────────

    @Test
    fun `plain patch decodes to exact target`() {
        val output = tmp.newFile("out.bin")
        val decoded = decoder.decode(
            source = source("sample_v1.bin"),
            patch = resource("plain_v1_to_v2.vcdiff"),
            output = output,
            expectedSize = resource("sample_v2.bin").size.toLong()
        )
        assertEquals(resource("sample_v2.bin").size.toLong(), decoded)
        assertArrayEquals(resource("sample_v2.bin"), output.readBytes())
    }

    @Test
    fun `lzma multi-window patch decodes to exact target`() {
        // CI 同款格式：VCD_SECONDARY|VCD_APPHEADER + LZMA id=2，多窗口续流
        val patch = resource("lzma_multi_v1_to_v3.vcdiff")
        val magicHex = patch.take(4).joinToString("") { "%02x".format(it) }
        assertEquals("d6c3c400", magicHex)
        assertEquals(5, patch[4].toInt() and 0xFF)   // SECONDARY | APPHEADER
        assertEquals(2, patch[5].toInt() and 0xFF)   // VCD_LZMA_ID

        val output = tmp.newFile("out.bin")
        val decoded = decoder.decode(
            source = source("sample_v1.bin"),
            patch = patch,
            output = output,
            expectedSize = resource("sample_v3.bin").size.toLong()
        )
        assertEquals(resource("sample_v3.bin").size.toLong(), decoded)
        assertArrayEquals(resource("sample_v3.bin"), output.readBytes())
    }

    @Test
    fun `cross-version chain applies two patches sequentially`() {
        // v1 + p12 → v2；v2 + p23 → v3（跨版本增量的最小缩影）
        val step1 = tmp.newFile("step1.bin")
        decoder.decode(
            source("sample_v1.bin"), resource("plain_v1_to_v2.vcdiff"),
            step1, resource("sample_v2.bin").size.toLong()
        )
        val step2 = tmp.newFile("step2.bin")
        decoder.decode(
            step1, resource("plain_v2_to_v3.vcdiff"),
            step2, resource("sample_v3.bin").size.toLong()
        )
        assertArrayEquals(resource("sample_v3.bin"), step2.readBytes())
    }

    @Test
    fun `progress callback reports monotonic growth`() {
        val seen = mutableListOf<Long>()
        val output = tmp.newFile("out.bin")
        decoder.decode(
            source("sample_v1.bin"), resource("lzma_multi_v1_to_v3.vcdiff"),
            output, resource("sample_v3.bin").size.toLong()
        ) { bytes -> seen.add(bytes) }
        assertTrue(seen.isNotEmpty())
        assertEquals(seen.sorted(), seen)                       // 单调不减
        assertEquals(resource("sample_v3.bin").size.toLong(), seen.last())
    }

    // ── 防御路径（恶意/损坏输入折叠为异常，绝不崩溃）────────────────────

    @Test
    fun `bad magic is rejected`() {
        val patch = resource("plain_v1_to_v2.vcdiff").copyOf()
        patch[0] = 0x00
        val thrown = runCatching {
            decoder.decode(source("sample_v1.bin"), patch, tmp.newFile("o"), 0)
        }.exceptionOrNull()
        assertNotNull(thrown)
        assertTrue(thrown is VcdiffDecoder.VcdiffFormatException)
    }

    @Test
    fun `truncated patch is rejected`() {
        val patch = resource("plain_v1_to_v2.vcdiff")
        val cut = patch.copyOf(patch.size / 2)
        val thrown = runCatching {
            decoder.decode(source("sample_v1.bin"), cut, tmp.newFile("o"), 0)
        }.exceptionOrNull()
        assertNotNull(thrown)
    }

    @Test
    fun `corrupt window checksum is rejected`() {
        // 篡改段内一个字节 → 产物错位 → Adler32 失配或结构异常
        val patch = resource("plain_v1_to_v2.vcdiff").copyOf()
        patch[patch.size / 2] = (patch[patch.size / 2].toInt() xor 0x55).toByte()
        val thrown = runCatching {
            decoder.decode(source("sample_v1.bin"), patch, tmp.newFile("o"), 0)
        }.exceptionOrNull()
        assertNotNull(thrown)
    }

    @Test
    fun `size mismatch against expected is rejected`() {
        val thrown = runCatching {
            decoder.decode(
                source("sample_v1.bin"), resource("plain_v1_to_v2.vcdiff"),
                tmp.newFile("o"), expectedSize = 12345L
            )
        }.exceptionOrNull()
        assertNotNull(thrown)
        assertTrue(
            thrown?.message?.contains("expected") == true ||
                thrown is VcdiffDecoder.VcdiffFormatException
        )
    }

    @Test
    fun `missing source file for source window is rejected`() {
        // 无源窗口的补丁配 null 源 → 明确异常（而非 NPE）
        val thrown = runCatching {
            decoder.decode(null, resource("plain_v1_to_v2.vcdiff"), tmp.newFile("o"), 0)
        }.exceptionOrNull()
        assertNotNull(thrown)
        assertTrue(thrown is VcdiffDecoder.VcdiffFormatException)
    }

    @Test
    fun `unsupported secondary compressor id is rejected`() {
        // DJW id=1 —— CI 永不产生，但防御路径必须明确拒绝
        val patch = resource("plain_v1_to_v2.vcdiff")
        val crafted = byteArrayOf(0xD6.toByte(), 0xC3.toByte(), 0xC4.toByte(), 0, 0x05, 0x01) +
            patch.copyOfRange(4, patch.size)
        val thrown = runCatching {
            decoder.decode(source("sample_v1.bin"), crafted, tmp.newFile("o"), 0)
        }.exceptionOrNull()
        assertNotNull(thrown)
        assertTrue(thrown?.message?.contains("secondary") == true)
    }
}
