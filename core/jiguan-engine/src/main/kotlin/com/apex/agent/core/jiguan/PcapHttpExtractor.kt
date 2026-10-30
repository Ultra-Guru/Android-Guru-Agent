package com.apex.agent.core.jiguan

/**
 * # pcap 解析与 HTTP 提取器（纯 Kotlin）
 *
 * 网络域的「抓包落盘 → LLM 可读报文」管线：tcpdump（PRoot / root）只负责
 * 落 pcap，**解析全在引擎内** —— 零外部依赖、JVM 单测用真实字节流全覆盖。
 *
 * 支持面（小黄鸟对等的只读分析面）：
 * - pcap 全局头（微秒/纳秒魔数）+ Ethernet/IPv4/TCP/UDP 基本分帧；
 * - TCP 流重组（同 (src,sport,dst,dport) 聚合，按 seq 升序近似拼装）；
 * - HTTP 请求/响应抽取（请求行 + 头 + body 预览 / 状态行 + 头 + body 预览）。
 *
 * 防御式纪律：任何畸形包跳过，绝不抛异常 —— 坏 pcap 也要给出「读到了多少」。
 */
object PcapHttpExtractor {

    // ── pcap 文件层 ──────────────────────────────────────────────

    data class PcapSummary(
        val packetCount: Int,
        val malformedCount: Int,
        /** 提取到的 HTTP 报文。 */
        val httpMessages: List<HttpMessage>,
    )

    /** 解析 pcap 字节流（pcap 经典格式；pcapng 不支持 —— 返回 0 包 + 提示性消息）。 */
    fun parse(pcap: ByteArray, maxHttpMessages: Int = 40): PcapSummary {
        if (pcap.size < 24) return PcapSummary(0, 0, emptyList())
        val magic = readLeInt(pcap, 0)
        // 四种经典 pcap 魔数（微秒/纳秒 × 大小端）；pcapng 魔数不在其中 → 空结果。
        if (magic !in listOf(
                0xA1B2C3D4.toInt(), 0xD4C3B2A1.toInt(),
                0xA1B23C4D.toInt(), 0x4D3CB2A1.toInt(),
            )
        ) {
            return PcapSummary(0, 0, emptyList())
        }
        var offset = 24
        var packets = 0
        var malformed = 0
        val segments = ArrayList<SegmentData>()
        while (offset + 16 <= pcap.size) {
            val inclLen = readLeInt(pcap, offset + 8)
            if (inclLen < 0 || offset + 16 + inclLen > pcap.size) {
                malformed++
                break
            }
            val frame = pcap.copyOfRange(offset + 16, offset + 16 + inclLen)
            offset += 16 + inclLen
            packets++
            val seg = parseEthernetFrame(frame)
            if (seg == null) {
                malformed++
            } else {
                segments.add(seg)
            }
        }
        // 尾部残留 < 16 字节 = 截断的记录头 —— 如实计入 malformed。
        if (offset < pcap.size) malformed++
        // ── TCP 流重组 + HTTP 抽取 ─────────────────────────────
        val messages = extractHttp(segments, maxHttpMessages)
        return PcapSummary(packets, malformed, messages)
    }

    private fun parseEthernetFrame(frame: ByteArray): SegmentData? {
        if (frame.size < 14) return null
        val ethType = ((frame[12].toInt() and 0xFF) shl 8) or (frame[13].toInt() and 0xFF)
        var ipOffset = 14
        if (ethType == 0x8100) { // VLAN
            if (frame.size < 18) return null
            ipOffset = 18
        } else if (ethType != 0x0800) { // 非 IPv4
            return null
        }
        if (frame.size < ipOffset + 20) return null
        val ihl = (frame[ipOffset].toInt() and 0x0F) * 4
        if (ihl < 20 || frame.size < ipOffset + ihl) return null
        val protocol = frame[ipOffset + 9].toInt() and 0xFF
        if (protocol != 6) return null // 只处理 TCP
        val srcIp = "%d.%d.%d.%d".format(
            frame[ipOffset + 12].toInt() and 0xFF, frame[ipOffset + 13].toInt() and 0xFF,
            frame[ipOffset + 14].toInt() and 0xFF, frame[ipOffset + 15].toInt() and 0xFF,
        )
        val dstIp = "%d.%d.%d.%d".format(
            frame[ipOffset + 16].toInt() and 0xFF, frame[ipOffset + 17].toInt() and 0xFF,
            frame[ipOffset + 18].toInt() and 0xFF, frame[ipOffset + 19].toInt() and 0xFF,
        )
        val tcpBase = ipOffset + ihl
        if (frame.size < tcpBase + 20) return null
        val srcPort = ((frame[tcpBase].toInt() and 0xFF) shl 8) or (frame[tcpBase + 1].toInt() and 0xFF)
        val dstPort = ((frame[tcpBase + 2].toInt() and 0xFF) shl 8) or (frame[tcpBase + 3].toInt() and 0xFF)
        val dataOffset = ((frame[tcpBase + 12].toInt() and 0xF0) shr 4) * 4
        val payloadStart = tcpBase + dataOffset
        if (payloadStart > frame.size) return null
        val seq = readLeUInt32(frame, tcpBase + 4).toLong()
        val payload = frame.copyOfRange(payloadStart, frame.size)
        if (payload.isEmpty()) return null
        return SegmentData(srcIp, dstIp, srcPort, dstPort, seq, payload)
    }

    private data class SegmentData(
        val srcIp: String, val dstIp: String,
        val srcPort: Int, val dstPort: Int,
        val seq: Long, val payload: ByteArray,
    )

    // ── HTTP 抽取 ───────────────────────────────────────────────

    private fun extractHttp(segments: List<SegmentData>, maxMessages: Int): List<HttpMessage> {
        if (segments.isEmpty()) return emptyList()
        // 双向流聚合：stream key 忽略方向。
        data class Stream(
            val id: String,
            val parts: MutableList<Pair<Long, ByteArray>>,
        )
        val streams = LinkedHashMap<String, Stream>()
        for (seg in segments) {
            val key = listOf("${seg.srcIp}:${seg.srcPort}", "${seg.dstIp}:${seg.dstPort}").sorted().joinToString("<->")
            val stream = streams.getOrPut(key) { Stream(key, mutableListOf()) }
            stream.parts.add(seg.seq to seg.payload)
        }
        val out = ArrayList<HttpMessage>()
        for (stream in streams.values) {
            if (out.size >= maxMessages) break
            // 按 seq 稳定排序后拼接（近似重组：覆盖/重传取后到者）。
            val ordered = stream.parts.sortedBy { it.first }
            val buffer = ByteArray(ordered.sumOf { it.second.size })
            var pos = 0
            for ((_, payload) in ordered) {
                payload.copyInto(buffer, pos)
                pos += payload.size
            }
            out.addAll(parseHttpFromStream(buffer, stream.id, maxMessages - out.size))
        }
        return out
    }

    /** 从一条重组流里抽取 HTTP 报文（简单二分：请求行 / 状态行开头）。 */
    private fun parseHttpFromStream(buffer: ByteArray, streamId: String, budget: Int): List<HttpMessage> {
        val text = String(buffer, Charsets.ISO_8859_1)
        val messages = ArrayList<HttpMessage>()
        var searchFrom = 0
        while (messages.size < budget) {
            val reqIdx = text.indexOfRegex(Regex("(^|\\n)(GET|POST|PUT|DELETE|PATCH|HEAD|OPTIONS) "), searchFrom)
            val respIdx = text.indexOfRegex(Regex("(^|\\n)HTTP/1\\.[01] "), searchFrom)
            val isRequest = reqIdx >= 0 && (respIdx < 0 || reqIdx <= respIdx)
            val start = if (isRequest) reqIdx else respIdx
            if (start < 0) break
            val lineStart = if (text[start] == '\n') start + 1 else start
            val headerEnd = text.indexOf("\r\n\r\n", lineStart)
            if (headerEnd < 0) break
            val headerBlock = text.substring(lineStart, headerEnd)
            val firstLine = headerBlock.substringBefore('\n').trimEnd('\r')
            val headers = headerBlock.substringAfter('\n', "").trimEnd('\r')
            val bodyStart = headerEnd + 4
            // body 到下一个报文起点或缓冲尾。
            val nextReq = text.indexOfRegex(Regex("\\n(GET|POST|PUT|DELETE|PATCH|HEAD|OPTIONS) "), bodyStart)
            val nextResp = text.indexOfRegex(Regex("\\nHTTP/1\\.[01] "), bodyStart)
            val bodyEnd = listOf(nextReq, nextResp).filter { it >= 0 }.minOrNull() ?: text.length
            val body = text.substring(bodyStart, bodyEnd.coerceAtMost(bodyStart + 2048))
            if (isRequest) {
                val parts = firstLine.split(' ')
                val host = headerLine(headers, "Host") ?: ""
                messages.add(
                    HttpMessage(
                        streamId = streamId,
                        direction = "request",
                        method = parts.getOrElse(0) { "?" },
                        url = parts.getOrElse(1) { "?" },
                        host = host,
                        status = 0,
                        headers = headers.take(1200),
                        bodyPreview = body.take(600).replace('\r', ' '),
                    ),
                )
            } else {
                val parts = firstLine.split(' ')
                messages.add(
                    HttpMessage(
                        streamId = streamId,
                        direction = "response",
                        method = "",
                        url = "",
                        host = headerLine(headers, "Host") ?: "",
                        status = parts.getOrNull(1)?.toIntOrNull() ?: 0,
                        headers = headers.take(1200),
                        bodyPreview = body.take(600).replace('\r', ' '),
                    ),
                )
            }
            searchFrom = bodyEnd
        }
        return messages
    }

    private fun headerLine(headers: String, name: String): String? {
        for (line in headers.lineSequence()) {
            val idx = line.indexOf(':')
            if (idx > 0 && line.substring(0, idx).trim().equals(name, ignoreCase = true)) {
                return line.substring(idx + 1).trim()
            }
        }
        return null
    }

    private fun String.indexOfRegex(regex: Regex, from: Int): Int {
        val m = regex.find(this, from) ?: return -1
        return m.range.first
    }

    private fun readLeUInt32(b: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 3 downTo 0) v = (v shl 8) or (b[off + i].toLong() and 0xFF)
        return v
    }

    private fun readLeInt(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or
            ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or
            ((b[off + 3].toInt() and 0xFF) shl 24)
}
