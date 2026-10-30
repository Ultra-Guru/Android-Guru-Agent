package com.apex.agent.core.jiguan

/**
 * # Hex 视图格式化器（纯 Kotlin）
 *
 * `memory_hex_dump` 的展示层：经典三栏（偏移 / 十六进制 / ASCII）+ 多类型
 * 数值解释。LLM 拿到的不只是字节墙 —— [interpretations] 给出小端 int/float/
 * UTF 视角，直接支撑「分析 JSON 字符串 / Protobuf 序列化数据」的场景。
 */
object HexDumpFormatter {

    /** 经典 hexdump 文本（16 字节/行）。 */
    fun format(buffer: ByteArray, baseAddress: Long, maxBytes: Int = 512): String {
        val n = minOf(buffer.size, maxBytes)
        if (n <= 0) return "(empty)"
        val sb = StringBuilder()
        var i = 0
        while (i < n) {
            val lineEnd = minOf(i + 16, n)
            sb.append(String.format("%08X  ", baseAddress + i))
            for (j in 0 until 16) {
                if (i + j < lineEnd) {
                    sb.append(String.format("%02X ", buffer[i + j]))
                } else {
                    sb.append("   ")
                }
                if (j == 7) sb.append(' ')
            }
            sb.append(' ')
            for (j in i until lineEnd) {
                val c = buffer[j].toInt() and 0xFF
                sb.append(if (c in 0x20..0x7E) c.toChar() else '.')
            }
            sb.append('\n')
            i = lineEnd
        }
        if (n < buffer.size) {
            sb.append("… (${buffer.size - n} more bytes not shown, total ${buffer.size})\n")
        }
        return sb.toString().trimEnd()
    }

    /**
     * 前 16 字节的多类型解释（LLM 数据结构分析线索）。
     * 永远不抛异常 —— 长度不足的形态跳过。
     */
    fun interpretations(buffer: ByteArray): List<String> {
        val out = ArrayList<String>()
        if (buffer.size >= 4) {
            val i32 = MemoryScanEngine.readLeInt(buffer, 0)
            out.add("int32le@0 = $i32 (0x${Integer.toHexString(i32)})")
            out.add("float32le@0 = ${Float.fromBits(i32)}")
        }
        if (buffer.size >= 8) {
            val i64 = MemoryScanEngine.readLeLong(buffer, 0)
            out.add("int64le@0 = $i64 (0x${java.lang.Long.toHexString(i64)})")
            out.add("float64le@0 = ${Double.fromBits(i64)}")
        }
        val printablePrefix = buffer.takeWhile { b ->
            val c = b.toInt() and 0xFF
            c == 0x0A || c in 0x20..0x7E
        }.size
        if (printablePrefix >= 4) {
            out.add(
                "utf8@0 = \"" +
                    String(buffer, 0, printablePrefix, Charsets.UTF_8).take(64).replace("\n", "\\n") +
                    "\"",
            )
        }
        // UTF-16LE 线索：偶数位可打印 + 奇数位为 0（ASCII 范围）；尾部 NUL 对视为填充。
        if (buffer.size >= 8) {
            var printablePairs = 0
            var index = 0
            var looksUtf16 = true
            while (index + 1 < buffer.size && index < 8) {
                val c = buffer[index].toInt() and 0xFF
                val high = buffer[index + 1].toInt()
                when {
                    high == 0 && c in 0x20..0x7E -> printablePairs++
                    high == 0 && c == 0 -> Unit // 尾部 NUL 填充对。
                    else -> { looksUtf16 = false }
                }
                if (!looksUtf16) break
                index += 2
            }
            if (looksUtf16 && printablePairs >= 1) {
                // 尾部 NUL 是填充噪声 —— trim 后再呈现。
                out.add("utf16le@0 = \"${String(buffer, 0, 8, Charsets.UTF_16LE).trimEnd('\u0000')}\"")
            }
        }
        return out
    }

    /** 完整 HexDumpView 组装。 */
    fun view(pid: Int, buffer: ByteArray, address: Long, maxBytes: Int = 512): HexDumpView =
        HexDumpView(
            pid = pid,
            address = address,
            size = buffer.size,
            text = format(buffer, address, maxBytes),
            interpretations = interpretations(buffer),
        )
}
