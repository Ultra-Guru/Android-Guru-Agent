package com.apex.agent.core.jiguan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * pcap 解析 + HTTP 提取回归 —— 手工构造 pcap 字节流（全局头 + 记录头 +
 * Ethernet/IPv4/TCP 帧），验证分帧 / 重组 / HTTP 抽取 / 畸形容忍全链路。
 */
class PcapHttpExtractorTest {

    // ── 合成 pcap 构造原语 ────────────────────────────────────────

    private fun pcapGlobalHeader(): ByteArray = byteArrayOf(
        0xD4.toByte(), 0xC3.toByte(), 0xB2.toByte(), 0xA1.toByte(), // 魔数（LE 微秒）
        2, 0, 4, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xFF.toByte(), 0xFF.toByte(), 0, 0, 1, 0, 0, 0,
    )

    private fun pcapRecord(payload: ByteArray): ByteArray {
        val header = ByteArray(16)
        writeLeInt(header, 8, payload.size) // incl_len
        writeLeInt(header, 12, payload.size) // orig_len
        return header + payload
    }

    /** Ethernet + IPv4 + TCP 帧。 */
    private fun tcpFrame(srcIp: String, dstIp: String, srcPort: Int, dstPort: Int, seq: Int, payload: ByteArray): ByteArray {
        val ipHeader = ByteArray(20)
        ipHeader[0] = 0x45 // v4, ihl=5
        ipHeader[9] = 6 // TCP
        // IPv4 total length（大端）：20 IP + 20 TCP + payload。
        val total = 20 + 20 + payload.size
        ipHeader[2] = ((total shr 8) and 0xFF).toByte()
        ipHeader[3] = (total and 0xFF).toByte()
        val src = srcIp.split('.').map { it.toInt() }
        val dst = dstIp.split('.').map { it.toInt() }
        ipHeader[12] = src[0].toByte(); ipHeader[13] = src[1].toByte()
        ipHeader[14] = src[2].toByte(); ipHeader[15] = src[3].toByte()
        ipHeader[16] = dst[0].toByte(); ipHeader[17] = dst[1].toByte()
        ipHeader[18] = dst[2].toByte(); ipHeader[19] = dst[3].toByte()

        val tcpHeader = ByteArray(20)
        tcpHeader[0] = (srcPort shr 8).toByte(); tcpHeader[1] = srcPort.toByte()
        tcpHeader[2] = (dstPort shr 8).toByte(); tcpHeader[3] = dstPort.toByte()
        writeLeInt(tcpHeader, 4, seq)
        tcpHeader[12] = 0x50 // data offset 5
        return byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0x08.toByte(), 0) + ipHeader + tcpHeader + payload
    }

    private fun writeLeInt(b: ByteArray, off: Int, v: Int) {
        b[off] = (v and 0xFF).toByte()
        b[off + 1] = ((v shr 8) and 0xFF).toByte()
        b[off + 2] = ((v shr 16) and 0xFF).toByte()
        b[off + 3] = ((v shr 24) and 0xFF).toByte()
    }


    // ── 测试 ──────────────────────────────────────────────────────

    @Test
    fun `parse extracts http request from single packet`() {
        val http = "POST /api/login HTTP/1.1\r\nHost: api.example.com\r\nContent-Type: json\r\n\r\npassword=abc".toByteArray(Charsets.ISO_8859_1)
        val pcap = pcapGlobalHeader() +
            pcapRecord(tcpFrame("10.0.0.2", "10.0.0.1", 52300, 80, 1, http))
        val summary = PcapHttpExtractor.parse(pcap)
        assertEquals(1, summary.packetCount)
        assertEquals(0, summary.malformedCount)
        assertEquals(1, summary.httpMessages.size)
        val message = summary.httpMessages[0]
        assertEquals("request", message.direction)
        assertEquals("POST", message.method)
        assertEquals("/api/login", message.url)
        assertEquals("api.example.com", message.host)
        assertTrue(message.bodyPreview.contains("password=abc"))
    }

    @Test
    fun `parse reassembles two ordered segments of one stream`() {
        val part1 = "GET /a HTTP/1.1\r\nHost: h\r\n"
        val part2 = "\r\nbody-tail"
        val frame1 = tcpFrame("10.0.0.2", "10.0.0.1", 5000, 80, 1, part1.toByteArray(Charsets.ISO_8859_1))
        val frame2 = tcpFrame("10.0.0.2", "10.0.0.1", 5000, 80, 1 + part1.length, part2.toByteArray(Charsets.ISO_8859_1))
        val pcap = pcapGlobalHeader() + pcapRecord(frame1) + pcapRecord(frame2)
        val summary = PcapHttpExtractor.parse(pcap)
        assertEquals(1, summary.httpMessages.size)
        assertTrue(summary.httpMessages[0].bodyPreview.contains("body-tail"))
    }

    @Test
    fun `parse extracts http response`() {
        val http = "HTTP/1.1 200 OK\r\nHost: h\r\nContent-Type: json\r\n\r\n{\"ok\":true}".toByteArray(Charsets.ISO_8859_1)
        val pcap = pcapGlobalHeader() + pcapRecord(tcpFrame("10.0.0.1", "10.0.0.2", 80, 5000, 1, http))
        val message = PcapHttpExtractor.parse(pcap).httpMessages.single()
        assertEquals("response", message.direction)
        assertEquals(200, message.status)
        assertTrue(message.bodyPreview.contains("ok"))
    }

    @Test
    fun `parse skips udp and non-ip frames`() {
        // ARP 帧（ethType 0x0806）—— 只算包不进 HTTP 面。
        val arp = ByteArray(42)
        arp[12] = 0x08.toByte(); arp[13] = 0x06.toByte()
        val pcap = pcapGlobalHeader() + pcapRecord(arp)
        val summary = PcapHttpExtractor.parse(pcap)
        assertEquals(1, summary.packetCount)
        assertTrue(summary.httpMessages.isEmpty())
    }

    @Test
    fun `parse handles truncated tail gracefully`() {
        val http = "GET /x HTTP/1.1\r\nHost: h\r\n\r\n".toByteArray(Charsets.ISO_8859_1)
        val pcap = pcapGlobalHeader() + pcapRecord(tcpFrame("10.0.0.2", "10.0.0.1", 5, 80, 1, http)) + byteArrayOf(1, 2, 3)
        val summary = PcapHttpExtractor.parse(pcap)
        assertEquals(1, summary.httpMessages.size)
        assertTrue(summary.malformedCount >= 1)
    }

    @Test
    fun `parse rejects wrong magic`() {
        val summary = PcapHttpExtractor.parse(ByteArray(64) { 0 })
        assertEquals(0, summary.packetCount)
        assertTrue(summary.httpMessages.isEmpty())
    }
}
