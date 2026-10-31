package com.apex.agent.update

import org.tukaani.xz.XZInputStream
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.Arrays
import java.util.zip.Adler32

/**
 * 纯 Kotlin 的 VCDIFF（RFC 3284）解码器 —— 应用内增量更新的执行核心。
 *
 * 把「旧 APK + xdelta3 增量补丁」直接合成「新 APK」，全程无命令行、无
 * root、无外部依赖进程。发布仓库 CI 用 `xdelta3 -e -s` 生成的补丁
 * （Ubuntu 发行版默认启用 LZMA 二级压缩）由此类在 App 内原位应用。
 *
 * 支持的格式面（与 CI 生成参数一一对应，超出即抛 [VcdiffFormatException]）：
 * - 头部：标准 magic（D6 C3 C4 / 版本 0）；VCD_APPHEADER 跳过；
 *   VCD_CODETABLE 拒绝（CI 永不产生）；二级压缩仅支持 xdelta3 的 LZMA
 *   （.xz 容器，VCD_LZMA_ID=2）——DJW/FGK 拒绝；
 * - 窗口：VCD_SOURCE（源文件段）/ VCD_TARGET（已解码目标段，经输出文件
 *   回读支持）/ VCD_ADLER32（标准 Adler32，逐窗校验）；
 * - 指令：默认码表（s_near=4 / s_same=3，9 种寻址模式）的 ADD/RUN/COPY，
 *   含双指令条目与分别编码的尺寸；重叠 COPY（周期序列）按 RFC 语义
 *   逐字节展开；
 * - varint：RFC 3284 §2 的大端 7 位组序（首字节为最高位组）。
 *
 * ## xdelta3 LZMA 二级压缩的流拓扑（本实现的关键洞察）
 *
 * xdelta3 为 DATA/INST/ADDR 三类 section 各维护**一条跨窗口持续复用**的
 * .xz 流（编码侧 sec_stream_d/i/a，仅在首次压缩时初始化，逐段
 * LZMA_SYNC_FLUSH）。因此窗口 N 的同类段是窗口 N-1 的**续流**——首段
 * 带 xz 魔数，后续段直接以新块头开始，且整条流永远没有 index/footer。
 * 解码侧必须为三类 section 各保持一个持久 XZ 解码器，把各段压缩字节
 * 按窗口顺序喂入，恰好读出各段声明的解压字节数（段前缀 varint）。
 * 本实现两遍走：先全文解析出所有窗口的段区间，再为每类压缩段构建
 * 串联 [SectionFeedInputStream]（零拷贝跳读 patch），三个惰性
 * [XZInputStream] 跨窗口顺序解码。
 *
 * 内存策略：补丁整体载入（≤ ~20MB 量级）；每个目标窗口缓冲一次
 * （xdelta3 默认 8MB 窗口）；源段 ≤ [MAX_CACHED_SOURCE_SEGMENT] 时驻留
 * 内存、更大时按「段过大」拒绝（本 CI 的源段 ≤ 66MB，留足余量）。
 *
 * 健壮性：恶意/损坏输入全部折叠为 [VcdiffFormatException]（长度校验先行，
 * 再碰数组）；窗口 Adler32 与调用方的终局 SHA-256 双保险；输出经
 * [RandomAccessFile] 落盘，失败由调用方负责清理残留文件。
 */
class VcdiffDecoder {

    /** 格式不符 / 补丁损坏 —— 面向用户的失败原因。 */
    class VcdiffFormatException(message: String) : Exception(message)

    /** 解码进度回调：decodedBytes 为已合成字节数（IO 线程调用）。 */
    fun interface ProgressListener {
        fun onProgress(decodedBytes: Long)
    }

    /**
     * 单补丁应用：source + patch → output。
     *
     * @param source 基础文件（null = 无源纯压缩补丁）
     * @param patch 补丁字节（整体在内存）
     * @param output 输出文件（已存在则覆写）
     * @param expectedSize 预期产物大小（进度分母与终局长度校验；0 = 不校验）
     * @param onProgress 进度回调（调用方自行切主线程）
     * @return 合成字节数
     */
    fun decode(
        source: File?,
        patch: ByteArray,
        output: File,
        expectedSize: Long,
        onProgress: ProgressListener? = null
    ): Long {
        val parsed = PatchParser.parse(patch)

        // 三类 section 的持久 xz 解码器（按需创建；无二级压缩则全不创建）
        val dataFeed = SectionFeedInputStream(patch, parsed.compressedSections(DATA_KIND))
        val instFeed = SectionFeedInputStream(patch, parsed.compressedSections(INST_KIND))
        val addrFeed = SectionFeedInputStream(patch, parsed.compressedSections(ADDR_KIND))
        var dataXz: XZInputStream? = null
        var instXz: XZInputStream? = null
        var addrXz: XZInputStream? = null

        val sourceHandle = source?.let {
            RandomAccessFile(it, "r").also { handle ->
                if (runCatching { handle.length() }.getOrDefault(0L) <= 0L) {
                    runCatching { handle.close() }
                    throw VcdiffFormatException("source file is empty: $it")
                }
            }
        }
        var outputHandle: RandomAccessFile? = null
        try {
            outputHandle = RandomAccessFile(output, "rw").also { it.setLength(0L) }
            var total = 0L
            for (window in parsed.windows) {
                val dataSection = sectionBytes(
                    patch, window.data, dataFeed, dataXz
                ) { stream -> dataXz = stream }
                val instSection = sectionBytes(
                    patch, window.inst, instFeed, instXz
                ) { stream -> instXz = stream }
                val addrSection = sectionBytes(
                    patch, window.addr, addrFeed, addrXz
                ) { stream -> addrXz = stream }

                val target = executeWindow(
                    window, sourceHandle, outputHandle, total,
                    dataSection, instSection, addrSection
                )
                total += target.size
                onProgress?.onProgress(total)
            }
            if (total <= 0L) throw VcdiffFormatException("patch contains no window")
            if (expectedSize > 0 && total != expectedSize) {
                throw VcdiffFormatException("decoded size $total != expected $expectedSize")
            }
            return total
        } finally {
            runCatching { dataXz?.close() }
            runCatching { instXz?.close() }
            runCatching { addrXz?.close() }
            runCatching { sourceHandle?.close() }
            runCatching { outputHandle?.close() }
        }
    }

    /** 取某窗口某 section 的解码后字节：raw 直拷 / 压缩段从持久 xz 流读。 */
    private fun sectionBytes(
        patch: ByteArray,
        section: PatchParser.Section,
        feed: SectionFeedInputStream,
        existing: XZInputStream?,
        created: (XZInputStream) -> Unit
    ): ByteArray {
        if (!section.compressed) {
            return patch.copyOfRange(section.offset, section.offset + section.length)
        }
        val stream = existing ?: XZInputStream(feed).also { created(it) }
        val output = ByteArray(section.decodedSize)
        var done = 0
        while (done < output.size) {
            val read = stream.read(output, done, output.size - done)
            if (read < 0) {
                throw VcdiffFormatException(
                    "xz stream exhausted at $done/${output.size} bytes"
                )
            }
            done += read
        }
        return output
    }

    /** 单窗口执行：读源段 → 跑指令 → 校验 Adler32 → 落盘。 */
    private fun executeWindow(
        window: PatchParser.Window,
        sourceHandle: RandomAccessFile?,
        outputHandle: RandomAccessFile,
        outputOffsetBase: Long,
        dataSection: ByteArray,
        instSection: ByteArray,
        addrSection: ByteArray
    ): ByteArray {
        val copyLength = window.copyLength
        val copyOffset = window.copyOffset
        val sourceSegment: ByteArray? = when {
            window.winIndicator and VCD_SOURCE != 0 ->
                readSegment(sourceHandle, copyOffset, copyLength)

            window.winIndicator and VCD_TARGET != 0 ->
                readSegment(outputHandle, copyOffset, copyLength)

            else -> null
        }
        if (copyLength > 0 && sourceSegment == null) {
            throw VcdiffFormatException(
                "window needs a $copyLength-byte segment at offset $copyOffset"
            )
        }

        val target = executeInstructions(
            dataSection, instSection, addrSection,
            sourceSegment, copyLength, window.targetLength.toInt()
        )

        if (target.size.toLong() != window.targetLength) {
            throw VcdiffFormatException(
                "window length mismatch: ${target.size} != ${window.targetLength}"
            )
        }
        window.checksum?.let { expected ->
            val adler = Adler32().apply { update(target, 0, target.size) }
            if (adler.value != expected) {
                throw VcdiffFormatException(
                    "window adler32 mismatch: " +
                        "0x${adler.value.toString(16)} != 0x${expected.toString(16)}"
                )
            }
        }
        outputHandle.seek(outputOffsetBase)
        outputHandle.write(target)
        return target
    }

    /** 读 [offset, offset+length) 段；句柄为 null 或越界返回 null。 */
    private fun readSegment(
        handle: RandomAccessFile?,
        offset: Long,
        length: Long
    ): ByteArray? {
        if (length <= 0L) return ByteArray(0)
        if (handle == null) return null
        val fileLength = runCatching { handle.length() }.getOrDefault(-1L)
        if (fileLength < 0 || offset + length > fileLength) return null
        val buffer = ByteArray(length.toInt())
        handle.seek(offset)
        var done = 0
        while (done < buffer.size) {
            val read = handle.read(buffer, done, buffer.size - done)
            if (read < 0) return null
            done += read
        }
        return buffer
    }

    // ── 指令执行 ─────────────────────────────────────────────────────────

    private fun executeInstructions(
        dataSection: ByteArray,
        instSection: ByteArray,
        addrSection: ByteArray,
        sourceSegment: ByteArray?,
        copyLength: Long,
        targetLength: Int
    ): ByteArray {
        val data = SectionReader(dataSection)
        val inst = SectionReader(instSection)
        val addr = SectionReader(addrSection)
        val cache = AddressCache()
        val target = ByteArray(targetLength)
        var written = 0

        while (inst.remaining() > 0) {
            val code = inst.next()
            val entry = CODE_TABLE[code]

            for (slot in 0..1) {
                val type = entry.types[slot]
                if (type == TYPE_NOOP) continue
                var size = entry.sizes[slot]
                if (size == 0) size = inst.nextVarint()
                if (size < 0) throw VcdiffFormatException("negative instruction size")
                val mode = entry.modes[slot]
                val here = copyLength + written

                when (type) {
                    TYPE_ADD -> {
                        if (written + size > targetLength) {
                            throw VcdiffFormatException("ADD overflows target window")
                        }
                        data.readInto(target, written, size)
                        written += size
                    }

                    TYPE_RUN -> {
                        if (written + size > targetLength) {
                            throw VcdiffFormatException("RUN overflows target window")
                        }
                        val value = data.next().toByte()
                        Arrays.fill(target, written, written + size, value)
                        written += size
                    }

                    TYPE_COPY -> {
                        val address = cache.decode(mode, addr, here)
                        // RFC 3284：地址须为非负且早于当前位置（重叠拷贝允许）
                        if (address < 0 || address >= here) {
                            throw VcdiffFormatException("copy address out of range: $address")
                        }
                        if (written + size > targetLength) {
                            throw VcdiffFormatException("copy overflows target window")
                        }
                        if (address < copyLength) {
                            // 源段侧：区间不得越过段尾
                            if (address + size > copyLength) {
                                throw VcdiffFormatException(
                                    "copy range crosses source segment end"
                                )
                            }
                            val from = sourceSegment ?: throw VcdiffFormatException(
                                "source copy without source segment"
                            )
                            System.arraycopy(from, address.toInt(), target, written, size)
                            written += size
                        } else {
                            val from = (address - copyLength).toInt()
                            if (from + size <= written) {
                                // 无重叠：整块搬移
                                System.arraycopy(target, from, target, written, size)
                                written += size
                            } else {
                                // 重叠拷贝（周期序列）：逐字节自扩展
                                var cursor = from
                                var sink = written
                                repeat(size) { target[sink++] = target[cursor++] }
                                written += size
                            }
                        }
                    }

                    else -> throw VcdiffFormatException("bad instruction type $type")
                }
            }
        }
        if (written != targetLength) {
            throw VcdiffFormatException(
                "window underflow: produced $written of $targetLength bytes"
            )
        }
        return target
    }

    // ── 补丁两遍解析 ─────────────────────────────────────────────────────

    /** section 类别（对应 xdelta3 的三条持久压缩流；常量在 companion）。 */

    /** 全量预解析结果：头部 + 窗口列表（段以 patch 内区间表达，零拷贝）。 */
    private object PatchParser {

        class Section(
            /** patch 内起始偏移（压缩段含 varint 前缀）。 */
            val offset: Int,
            /** 段总长（压缩段含 varint 前缀）。 */
            val length: Int,
            /** 是否经过二级压缩（窗口 del_ind 对应位）。 */
            val compressed: Boolean,
            /** 压缩段的解压后字节数（raw 段无意义）。 */
            val decodedSize: Int
        )

        class Window(
            val winIndicator: Int,
            val copyLength: Long,
            val copyOffset: Long,
            val targetLength: Long,
            val checksum: Long?,
            val data: Section,
            val inst: Section,
            val addr: Section
        )

        class Parsed(val windows: List<Window>, val sections: List<List<Section>>) {
            /** kind ∈ {0=data,1=inst,2=addr} 的压缩段序列（窗口顺序）。 */
            fun compressedSections(kind: Int): List<Section> =
                sections[kind].filter { it.compressed }
        }

        fun parse(patch: ByteArray): Parsed {
            val reader = Reader(patch)
            if (reader.next() != VCDIFF_MAGIC1 || reader.next() != VCDIFF_MAGIC2 ||
                reader.next() != VCDIFF_MAGIC3
            ) {
                throw VcdiffFormatException("not a VCDIFF patch (bad magic)")
            }
            if (reader.next() != 0) {
                throw VcdiffFormatException("unsupported VCDIFF version")
            }
            val headerIndicator = reader.next()
            if (headerIndicator and VCD_CODETABLE != 0) {
                throw VcdiffFormatException("custom code table is unsupported")
            }
            if (headerIndicator and VCD_DECOMPRESS != 0) {
                val secondaryId = reader.next()
                // DJW(1)/FGK(16) 需要各自熵解码器，CI 不产生 —— 明确拒绝并回退全量
                if (secondaryId != VCD_LZMA_ID) {
                    throw VcdiffFormatException(
                        "unsupported secondary compressor id $secondaryId"
                    )
                }
            }
            if (headerIndicator and VCD_APPHEADER != 0) {
                val appHeaderLength = reader.nextVarint()
                if (appHeaderLength > MAX_SKIP_BYTES) {
                    throw VcdiffFormatException("app header too large: $appHeaderLength")
                }
                reader.skip(appHeaderLength)
            }

            val windows = ArrayList<Window>()
            val dataSections = ArrayList<Section>()
            val instSections = ArrayList<Section>()
            val addrSections = ArrayList<Section>()
            while (reader.remaining() > 0) {
                val winIndicator = reader.next()
                if (winIndicator and VCD_INVWIN != 0) {
                    throw VcdiffFormatException("unrecognized window indicator bits")
                }
                var copyLength = 0L
                var copyOffset = 0L
                if (winIndicator and (VCD_SOURCE or VCD_TARGET) != 0) {
                    copyLength = reader.nextVarint()
                    copyOffset = reader.nextVarint()
                    if (copyLength < 0 || copyOffset < 0) {
                        throw VcdiffFormatException("negative source segment bounds")
                    }
                    if (copyLength > MAX_CACHED_SOURCE_SEGMENT) {
                        throw VcdiffFormatException("source segment too large: $copyLength")
                    }
                }
                reader.nextVarint() // length of delta encoding —— 冗余字段，跳过
                val targetLength = reader.nextVarint()
                if (targetLength < 0 || targetLength > MAX_WINDOW_BYTES) {
                    throw VcdiffFormatException(
                        "target window length out of range: $targetLength"
                    )
                }
                val deltaIndicator = reader.next()
                if (deltaIndicator and VCD_INVDEL != 0) {
                    throw VcdiffFormatException("unrecognized delta indicator bits")
                }
                if (deltaIndicator != 0 && headerIndicator and VCD_DECOMPRESS == 0) {
                    throw VcdiffFormatException(
                        "delta indicator set without secondary compressor"
                    )
                }
                val dataLength = reader.nextVarint().toIntInRange("data section length")
                val instLength = reader.nextVarint().toIntInRange("inst section length")
                val addrLength = reader.nextVarint().toIntInRange("addr section length")
                // xdelta3 布局：校验和位于三个 section 长度之后、section 数据之前
                var checksum: Long? = null
                if (winIndicator and VCD_ADLER32 != 0) {
                    checksum = (reader.next().toLong() and 0xFF shl 24) or
                        (reader.next().toLong() and 0xFF shl 16) or
                        (reader.next().toLong() and 0xFF shl 8) or
                        (reader.next().toLong() and 0xFF)
                }

                val data = readSectionSpec(reader, deltaIndicator and VCD_DATACOMP != 0, dataLength)
                val inst = readSectionSpec(reader, deltaIndicator and VCD_INSTCOMP != 0, instLength)
                val addr = readSectionSpec(reader, deltaIndicator and VCD_ADDRCOMP != 0, addrLength)

                windows.add(
                    Window(winIndicator, copyLength, copyOffset, targetLength, checksum, data, inst, addr)
                )
                dataSections.add(data)
                instSections.add(inst)
                addrSections.add(addr)
            }
            if (windows.isEmpty()) throw VcdiffFormatException("patch contains no window")
            return Parsed(windows, listOf(dataSections, instSections, addrSections))
        }

        /** 解析单个 section 区间：压缩段先读 varint 解压长度（前缀计入段内）。 */
        private fun readSectionSpec(
            reader: Reader,
            compressed: Boolean,
            length: Int
        ): Section {
            val offset = reader.position
            if (reader.position + length > reader.size) {
                throw VcdiffFormatException("section length exceeds patch")
            }
            if (!compressed) {
                reader.skip(length.toLong())
                return Section(offset, length, false, 0)
            }
            val decodedSize = reader.nextVarint().toIntInRange("secondary section size")
            if (decodedSize <= 0 || decodedSize > MAX_SECONDARY_SIZE) {
                throw VcdiffFormatException(
                    "secondary section size out of range: $decodedSize"
                )
            }
            // varint 前缀已消费；段区间覆盖 [offset, offset+length)（含前缀）
            reader.skip((length - (reader.position - offset)).toLong())
            return Section(offset, length, true, decodedSize)
        }

        private class Reader(private val buffer: ByteArray) {
            var position: Int = 0
                private set

            val size: Int get() = buffer.size

            fun remaining(): Int = buffer.size - position

            fun next(): Int {
                if (position >= buffer.size) {
                    throw VcdiffFormatException("unexpected end of patch")
                }
                return buffer[position++].toInt() and 0xFF
            }

            fun nextVarint(): Long {
                var value = 0L
                while (true) {
                    val byte = next()
                    value = value * 128 + (byte and 0x7F)
                    if (byte and 0x80 == 0) return value
                    if (value > MAX_VARINT) throw VcdiffFormatException("varint too long")
                }
            }

            fun skip(count: Long) {
                if (count < 0 || position + count > buffer.size) {
                    throw VcdiffFormatException("skip beyond patch end")
                }
                position += count.toInt()
            }
        }

        private fun Long.toIntInRange(what: String): Int {
            if (this < 0 || this > Int.MAX_VALUE) {
                throw VcdiffFormatException("$what out of range: $this")
            }
            return toInt()
        }
    }

    // ── 持久压缩流的串联喂入器 ───────────────────────────────────────────

    /**
     * 把同类压缩段（跨窗口的续流字节）按窗口顺序串成一个逻辑输入流。
     *
     * 零拷贝：直接在 patch 数组上按区间读；每段跳过 varint 前缀。XZ 解码
     * 器的读-ahead 天然安全——它读到的就是同一条 xz 流的后续字节。
     */
    private class SectionFeedInputStream(
        private val patch: ByteArray,
        sections: List<PatchParser.Section>
    ) : InputStream() {
        private val ranges: IntArray
        private var rangeIndex = 0
        private var cursor = 0

        init {
            // 展开为 [start, end) 平铺区间表（跳过各段 varint 前缀）
            val list = ArrayList<Int>(sections.size * 2)
            for (section in sections) {
                val prefixLength = varintPrefixLength(section)
                val start = section.offset + prefixLength
                val end = section.offset + section.length
                if (end > start) {
                    list.add(start)
                    list.add(end)
                }
            }
            ranges = list.toIntArray()
            cursor = if (ranges.isNotEmpty()) ranges[0] else 0
        }

        /** 该段 varint(dec_size) 前缀的字节长度（1~5）。 */
        private fun varintPrefixLength(section: PatchParser.Section): Int {
            var i = section.offset
            var guard = 0
            while (i < section.offset + section.length && guard < 6) {
                if (patch[i].toInt() and 0x80 == 0) return i - section.offset + 1
                i++
                guard++
            }
            throw VcdiffFormatException("secondary section size varint too long")
        }

        private fun advanceIfExhausted(): Boolean {
            while (rangeIndex + 1 < ranges.size && cursor >= ranges[rangeIndex + 1]) {
                rangeIndex += 2
                cursor = if (rangeIndex < ranges.size) ranges[rangeIndex] else cursor
            }
            return rangeIndex < ranges.size && cursor < ranges[rangeIndex + 1]
        }

        override fun read(): Int {
            if (!advanceIfExhausted()) return -1
            return patch[cursor++].toInt() and 0xFF
        }

        override fun read(buffer: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (!advanceIfExhausted()) return -1
            val end = ranges[rangeIndex + 1]
            val take = minOf(len, end - cursor)
            System.arraycopy(patch, cursor, buffer, off, take)
            cursor += take
            return take
        }
    }

    /** section 专用读取：ADD/RUN 数据流、指令流、地址流。 */
    private class SectionReader(private val buffer: ByteArray) {
        private var position = 0

        fun remaining(): Int = buffer.size - position

        fun next(): Int {
            if (position >= buffer.size) {
                throw VcdiffFormatException("section underflow")
            }
            return buffer[position++].toInt() and 0xFF
        }

        fun nextVarint(): Int {
            var value = 0L
            while (true) {
                val byte = next()
                value = value * 128 + (byte and 0x7F)
                if (byte and 0x80 == 0) {
                    if (value > Int.MAX_VALUE) {
                        throw VcdiffFormatException("section varint out of int range")
                    }
                    return value.toInt()
                }
                if (value > MAX_VARINT) throw VcdiffFormatException("varint too long")
            }
        }

        fun readInto(destination: ByteArray, offset: Int, count: Int) {
            if (count < 0 || position + count > buffer.size) {
                throw VcdiffFormatException("data section underflow")
            }
            System.arraycopy(buffer, position, destination, offset, count)
            position += count
        }
    }

    // ── 地址缓存（near×4 / same×3，与编码器逐条同步）────────────────────

    private class AddressCache {
        private val near = IntArray(NEAR_SIZE)
        private val same = IntArray(SAME_SIZE * 256)
        private var nextSlot = 0

        fun decode(mode: Int, reader: SectionReader, here: Long): Long {
            val address: Long = when {
                mode == VCD_SELF -> reader.nextVarint().toLong()

                mode == VCD_HERE -> here - reader.nextVarint()

                mode in 2 until 2 + NEAR_SIZE ->
                    near[mode - 2].toLong() + reader.nextVarint()

                mode in 2 + NEAR_SIZE until 2 + NEAR_SIZE + SAME_SIZE -> {
                    val slot = mode - (2 + NEAR_SIZE)
                    val byte = reader.next()
                    same[slot * 256 + byte].toLong()
                }

                else -> throw VcdiffFormatException("invalid address mode $mode")
            }
            // 解码后立即更新缓存（保持与编码器同步）
            near[nextSlot] = address.toInt()
            nextSlot = (nextSlot + 1) % NEAR_SIZE
            val sameSlot = (address % (SAME_SIZE * 256L)).toInt().let {
                if (it < 0) it + SAME_SIZE * 256 else it
            }
            same[sameSlot] = address.toInt()
            return address
        }
    }

    // ── RFC 3284 §7 默认码表 ─────────────────────────────────────────────

    /** 码表条目：最多两条指令，各自 (类型, 尺寸, 地址模式)。 */
    private class CodeEntry(val types: IntArray, val sizes: IntArray, val modes: IntArray)

    private companion object {
        // section 类别（对应 xdelta3 的三条持久压缩流）
        const val DATA_KIND = 0
        const val INST_KIND = 1
        const val ADDR_KIND = 2

        // VCDIFF magic 与标志位（RFC 3284 + xdelta3 扩展）
        const val VCDIFF_MAGIC1 = 0xD6
        const val VCDIFF_MAGIC2 = 0xC3
        const val VCDIFF_MAGIC3 = 0xC4
        const val VCD_DECOMPRESS = 0x01   // 头部：启用二级压缩
        const val VCD_CODETABLE = 0x02    // 头部：自定义码表
        const val VCD_APPHEADER = 0x04    // 头部：应用数据（xdelta3 扩展）

        const val VCD_SOURCE = 0x01       // 窗口：源段来自源文件
        const val VCD_TARGET = 0x02       // 窗口：源段来自已解码目标
        const val VCD_ADLER32 = 0x04      // 窗口：带 Adler32 校验和
        const val VCD_INVWIN = 0xF8       // 除低三位外均非法

        const val VCD_DATACOMP = 0x01     // delta：DATA 段已压缩
        const val VCD_INSTCOMP = 0x02     // delta：INST 段已压缩
        const val VCD_ADDRCOMP = 0x04     // delta：ADDR 段已压缩
        const val VCD_INVDEL = 0xF8       // 高五位必须为零

        const val VCD_LZMA_ID = 2         // xdelta3 LZMA 二级压缩器 ID

        const val VCD_SELF = 0
        const val VCD_HERE = 1

        const val TYPE_NOOP = 0
        const val TYPE_ADD = 1
        const val TYPE_RUN = 2
        const val TYPE_COPY = 3

        const val NEAR_SIZE = 4           // RFC 默认码表 s_near
        const val SAME_SIZE = 3           // RFC 默认码表 s_same

        const val MAX_WINDOW_BYTES = 96L shl 20        // 恶意窗口长度守卫
        const val MAX_CACHED_SOURCE_SEGMENT = 96L shl 20
        const val MAX_SECONDARY_SIZE = 96L shl 20
        const val MAX_VARINT = 1L shl 56
        const val MAX_SKIP_BYTES = 1L shl 26

        /** RFC 3284 §7 默认码表（256 条，含双指令条目）。 */
        val CODE_TABLE: Array<CodeEntry> = buildDefaultCodeTable()

        fun buildDefaultCodeTable(): Array<CodeEntry> {
            val types = Array(256) { IntArray(2) { TYPE_NOOP } }
            val sizes = Array(256) { IntArray(2) }
            val modes = Array(256) { IntArray(2) }

            // 0：RUN 尺寸另行编码
            types[0][0] = TYPE_RUN
            // 1..18：ADD 尺寸 0..17（0 = 另行编码）
            for (size in 0..17) {
                val index = 1 + size
                types[index][0] = TYPE_ADD
                sizes[index][0] = size
            }
            // 19..162：COPY 尺寸 {0, 4..18} × 模式 0..8
            for (mode in 0..8) {
                val base = 19 + mode * 16
                types[base][0] = TYPE_COPY
                modes[base][0] = mode
                for (size in 4..18) {
                    val index = base + 1 + (size - 4)
                    types[index][0] = TYPE_COPY
                    sizes[index][0] = size
                    modes[index][0] = mode
                }
            }
            // 163..234：ADD 1..4（外层）+ COPY 4..6（内层），模式 0..5
            for (mode in 0..5) {
                var index = 163 + mode * 12
                for (addSize in 1..4) {
                    for (copySize in 4..6) {
                        types[index][0] = TYPE_ADD
                        sizes[index][0] = addSize
                        types[index][1] = TYPE_COPY
                        sizes[index][1] = copySize
                        modes[index][1] = mode
                        index++
                    }
                }
            }
            // 235..246：ADD 1..4 + COPY 4 模式 6..8
            for (mode in 6..8) {
                for (addSize in 1..4) {
                    val index = 235 + (mode - 6) * 4 + (addSize - 1)
                    types[index][0] = TYPE_ADD
                    sizes[index][0] = addSize
                    types[index][1] = TYPE_COPY
                    sizes[index][1] = 4
                    modes[index][1] = mode
                }
            }
            // 247..255：COPY 4 模式 0..8 + ADD 1
            for (mode in 0..8) {
                val index = 247 + mode
                types[index][0] = TYPE_COPY
                sizes[index][0] = 4
                modes[index][0] = mode
                types[index][1] = TYPE_ADD
                sizes[index][1] = 1
            }
            return Array(256) { CodeEntry(types[it], sizes[it], modes[it]) }
        }
    }

}
