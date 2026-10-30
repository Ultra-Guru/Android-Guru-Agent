package com.apex.agent.core.jiguan

/**
 * # 内存扫描引擎（纯 Kotlin）
 *
 * 在引擎内做字节级匹配（通道只负责把区域**分块**搬进来）—— Native .so 与
 * Root shell 两条通道都复用同一套匹配语义，单测可在 JVM 全覆盖。
 *
 * 支持模式（GameGuardian 对等面）：
 * - 精确数值：INT8/16/32/64（小端）、FLOAT32/64（位模式精确 + 数值容差）；
 * - 模糊数值：fuzzyRange ∈ (0, +∞) 的 ± 容差匹配；
 * - 字符串：UTF-8 / UTF-16LE（带终止符与不带两种都允许命中）；
 * - AOB：`DE AD ?? EF` 十六进制通配模式。
 */
object MemoryScanEngine {

    // ── 模式编译 ──────────────────────────────────────────────────

    /** AOB 模式解析：`DE AD ?? EF` → 每字节 Option（null = 通配）。坏 token 抛 IllegalArgumentException。 */
    fun parseBytePattern(pattern: String): List<Byte?> {
        val tokens = pattern.trim().split(Regex("[\\s,]+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) throw IllegalArgumentException("empty pattern")
        return tokens.map { token ->
            when {
                token == "??" || token == "?" -> null
                token.length == 2 && token.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' } ->
                    token.toInt(16).toByte()
                else -> throw IllegalArgumentException("bad pattern token: $token")
            }
        }
    }

    /** 把请求的字面量值编码为字节（数值家族小端）。 */
    fun encodeValue(request: ScanRequest): ByteArray = when (request.type) {
        ScanValueType.INT8 -> byteArrayOf(request.value.trim().toByte())
        ScanValueType.INT16 -> request.value.trim().toShort().toLeBytes()
        ScanValueType.INT32 -> request.value.trim().toInt().toLeBytes()
        ScanValueType.INT64 -> request.value.trim().toLong().toLeBytes()
        ScanValueType.FLOAT32 -> request.value.trim().toFloat().toRawBits().toLeBytes()
        ScanValueType.FLOAT64 -> request.value.trim().toDouble().toRawBits().toLeBytes()
        ScanValueType.UTF8 -> request.value.toByteArray(Charsets.UTF_8)
        ScanValueType.UTF16 -> request.value.toByteArray(Charsets.UTF_16LE)
        ScanValueType.BYTE_PATTERN ->
            parseBytePattern(request.bytePattern).map { it ?: 0.toByte() }.toByteArray()
    }

    // ── 缓冲区扫描（核心匹配 —— 两条通道共用） ─────────────────────

    /**
     * 在一块缓冲内查找精确字节序列（AOB 无通配 / 数值 / 字符串共用）。
     * @return 缓冲内偏移列表（升序，封顶 [maxResults]）。
     */
    fun scanBufferExact(buffer: ByteArray, needle: ByteArray, maxResults: Int): List<Int> {
        if (needle.isEmpty() || buffer.size < needle.size || maxResults <= 0) return emptyList()
        val hits = ArrayList<Int>()
        val first = needle[0]
        val lastPossible = buffer.size - needle.size
        var i = 0
        while (i <= lastPossible) {
            if (buffer[i] == first) {
                var matched = true
                for (j in 1 until needle.size) {
                    if (buffer[i + j] != needle[j]) { matched = false; break }
                }
                if (matched) {
                    hits.add(i)
                    if (hits.size >= maxResults) return hits
                    i += needle.size
                    continue
                }
            }
            i++
        }
        return hits
    }

    /** AOB 通配扫描（模式字节为 null 的位置任意）。 */
    fun scanBufferPattern(buffer: ByteArray, pattern: List<Byte?>, maxResults: Int): List<Int> {
        if (pattern.isEmpty() || buffer.size < pattern.size || maxResults <= 0) return emptyList()
        val size = pattern.size
        // 锚点 = 第一个非通配位置（全通配时锚 0、字节任意 → 逐位命中，语义保留）。
        var anchor = 0
        while (anchor < size && pattern[anchor] == null) anchor++
        val anchorByte = if (anchor < size) pattern[anchor] else null
        val hits = ArrayList<Int>()
        val lastPossible = buffer.size - size
        var i = 0
        while (i <= lastPossible) {
            val ok = if (anchorByte == null) {
                true
            } else {
                buffer[i + anchor] == anchorByte
            }
            if (ok) {
                var matched = true
                for (j in pattern.indices) {
                    val want = pattern[j] ?: continue
                    if (buffer[i + j] != want) { matched = false; break }
                }
                if (matched) {
                    hits.add(i)
                    if (hits.size >= maxResults) return hits
                    i += size
                    continue
                }
            }
            i++
        }
        return hits
    }

    /** 模糊数值扫描：命中 |decoded - target| ≤ fuzzyRange（FLOAT32/64 家族）。 */
    fun scanBufferFuzzyFloat(
        buffer: ByteArray,
        target: Double,
        byteCount: Int,
        fuzzyRange: Double,
        maxResults: Int,
    ): List<Int> {
        if (fuzzyRange <= 0.0 || buffer.size < byteCount || maxResults <= 0) return emptyList()
        val hits = ArrayList<Int>()
        var i = 0
        while (i + byteCount <= buffer.size) {
            // 两分支统一为 Double（Float 提升），否则 when 推断公共超类型致减法不可解。
            val decoded: Double = when (byteCount) {
                4 -> Float.fromBits(readLeInt(buffer, i)).toDouble()
                8 -> Double.fromBits(readLeLong(buffer, i))
                else -> break
            }
            if (kotlin.math.abs(decoded - target) <= fuzzyRange) {
                hits.add(i)
                if (hits.size >= maxResults) return hits
            }
            i += byteCount
        }
        return hits
    }

    /** 模糊整数扫描：命中 |decoded - target| ≤ fuzzyRange（四舍五入为整数容差）。 */
    fun scanBufferFuzzyInt(
        buffer: ByteArray,
        target: Long,
        byteCount: Int,
        fuzzyRange: Double,
        maxResults: Int,
    ): List<Int> {
        if (fuzzyRange <= 0.0 || buffer.size < byteCount || maxResults <= 0) return emptyList()
        val tolerance = fuzzyRange.toLong().coerceAtLeast(0L)
        val hits = ArrayList<Int>()
        var i = 0
        while (i + byteCount <= buffer.size) {
            val decoded = when (byteCount) {
                1 -> buffer[i].toLong()
                2 -> readLeShort(buffer, i).toLong()
                4 -> readLeInt(buffer, i).toLong()
                8 -> readLeLong(buffer, i)
                else -> break
            }
            if (kotlin.math.abs(decoded - target) <= tolerance) {
                hits.add(i)
                if (hits.size >= maxResults) return hits
            }
            i += byteCount
        }
        return hits
    }

    // ── 高层：整请求驱动一次区域块扫描 ────────────────────────────

    /**
     * 对一块已读入的缓冲执行 [request] 语义的扫描，命中折叠为 [ScanHit]
     * （regionBase = 缓冲在进程内的起始地址）。
     */
    fun scanChunk(request: ScanRequest, buffer: ByteArray, regionBase: Long, regionPath: String): List<ScanHit> {
        val max = request.maxResults
        val offsets: List<Int> = when {
            request.type == ScanValueType.BYTE_PATTERN -> {
                val pattern = try {
                    parseBytePattern(request.bytePattern)
                } catch (e: IllegalArgumentException) {
                    return emptyList()
                }
                if (pattern.any { it == null }) {
                    scanBufferPattern(buffer, pattern, max)
                } else {
                    scanBufferExact(buffer, pattern.filterNotNull().toByteArray(), max)
                }
            }
            request.fuzzyRange > 0.0 && request.type == ScanValueType.FLOAT32 ->
                scanBufferFuzzyFloat(buffer, request.value.trim().toDouble(), 4, request.fuzzyRange, max)
            request.fuzzyRange > 0.0 && request.type == ScanValueType.FLOAT64 ->
                scanBufferFuzzyFloat(buffer, request.value.trim().toDouble(), 8, request.fuzzyRange, max)
            request.fuzzyRange > 0.0 && request.type in listOf(
                ScanValueType.INT8, ScanValueType.INT16, ScanValueType.INT32, ScanValueType.INT64,
            ) ->
                scanBufferFuzzyInt(buffer, request.value.trim().toLong(), request.type.byteCount, request.fuzzyRange, max)
            else -> scanBufferExact(buffer, encodeValue(request), max)
        }
        return offsets.map { offset ->
            ScanHit(
                address = regionBase + offset,
                valueText = decodeAt(buffer, offset, request.type),
                regionPath = regionPath.ifBlank { "[anon]" },
            )
        }
    }

    // ── 解码（命中值的人类/LLM 可读形态） ─────────────────────────

    fun decodeAt(buffer: ByteArray, offset: Int, type: ScanValueType): String = when (type) {
        ScanValueType.INT8 -> buffer[offset].toString()
        ScanValueType.INT16 -> readLeShort(buffer, offset).toString()
        ScanValueType.INT32 -> readLeInt(buffer, offset).toString()
        ScanValueType.INT64 -> readLeLong(buffer, offset).toString()
        ScanValueType.FLOAT32 -> Float.fromBits(readLeInt(buffer, offset)).toString()
        ScanValueType.FLOAT64 -> Double.fromBits(readLeLong(buffer, offset)).toString()
        ScanValueType.UTF8 -> decodeUtf8At(buffer, offset)
        ScanValueType.UTF16 -> decodeUtf16At(buffer, offset)
        ScanValueType.BYTE_PATTERN -> {
            val sb = StringBuilder()
            for (i in offset until minOf(offset + 8, buffer.size)) {
                if (sb.isNotEmpty()) sb.append(' ')
                sb.append(String.format("%02X", buffer[i]))
            }
            sb.toString()
        }
    }

    private fun decodeUtf8At(buffer: ByteArray, offset: Int): String {
        var end = offset
        while (end < buffer.size && buffer[end] != 0.toByte() && end - offset < 128) end++
        return String(buffer, offset, end - offset, Charsets.UTF_8)
    }

    private fun decodeUtf16At(buffer: ByteArray, offset: Int): String {
        var end = offset
        while (end + 1 < buffer.size &&
            !(buffer[end] == 0.toByte() && buffer[end + 1] == 0.toByte()) &&
            end - offset < 256
        ) end += 2
        return String(buffer, offset, end - offset, Charsets.UTF_16LE)
    }

    // ── 小端读取原语 ──────────────────────────────────────────────

    fun readLeShort(b: ByteArray, off: Int): Short =
        (((b[off].toInt() and 0xFF)) or ((b[off + 1].toInt() and 0xFF) shl 8)).toShort()

    fun readLeInt(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or
            ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or
            ((b[off + 3].toInt() and 0xFF) shl 24)

    fun readLeLong(b: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 7 downTo 0) v = (v shl 8) or (b[off + i].toLong() and 0xFF)
        return v
    }

    private fun Short.toLeBytes(): ByteArray = byteArrayOf(
        (toInt() and 0xFF).toByte(), ((toInt() shr 8) and 0xFF).toByte(),
    )

    private fun Int.toLeBytes(): ByteArray = byteArrayOf(
        (this and 0xFF).toByte(), ((this shr 8) and 0xFF).toByte(),
        ((this shr 16) and 0xFF).toByte(), ((this shr 24) and 0xFF).toByte(),
    )

    private fun Long.toLeBytes(): ByteArray = byteArrayOf(
        (this and 0xFF).toByte(), ((this shr 8) and 0xFF).toByte(),
        ((this shr 16) and 0xFF).toByte(), ((this shr 24) and 0xFF).toByte(),
        ((this shr 32) and 0xFF).toByte(), ((this shr 40) and 0xFF).toByte(),
        ((this shr 48) and 0xFF).toByte(), ((this shr 56) and 0xFF).toByte(),
    )
}
