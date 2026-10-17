package com.apex.agent.core.tools.fusion.rea

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import java.io.File
import java.io.RandomAccessFile

/**
 * # REA 原生二进制格式解析器（ELF / PE / Mach-O / fat）
 *
 * 纯 Kotlin、零依赖、设备直跑 —— inspect_binary_layout / inspect_macho /
 * list_architectures / ELF 符号枚举 / vaddr 映射（address_to_file_offset）/
 * 字符串提取（list_strings / search）的底层。
 *
 * 解析原则（与上游证据哲学一致）：**只声明读到的**—— 每个结论都从真实
 * 字节来；格式不完整时给 limitation 而不是编造。
 */
object ReaBinaryFormat {

    // ── 魔数 ────────────────────────────────────────────────────────────

    const val MAGIC_ELF = 0x7F
    const val MAGIC_MZ = 0x4D5A
    const val MAGIC_MACHO64 = 0xFEEDFACF.toInt()
    const val MAGIC_MACHO32 = 0xFEEDFACE.toInt()
    const val MAGIC_MACHO64_BE = -445058251 // 0xCFFAEDFE
    const val MAGIC_MACHO32_BE = -443266274 // 0xCEFAEDFE
    const val MAGIC_FAT = -17958192 // 0xCAFEBABE（有符号表示）
    const val MAGIC_FAT_BE = 1226060114 // 0xBEBAFECA
    const val MAGIC_WASM = 0x6D736100 // \0asm
    const val MAGIC_DEX = 0x78656400 // dex\n0

    /** 目标类型识别（open_binary 的第一步）。 */
    enum class Kind(val label: String) {
        ELF("ELF"), PE("PE"), MACHO("Mach-O"), FAT("Mach-O universal"),
        ZIP_ARCHIVE("ZIP archive"), APK("Android APK"), ASAR("Electron ASAR"),
        DEX("Dalvik classes.dex"), WASM("WebAssembly"), SQLITE("SQLite database"),
        PLIST_XML("XML plist"), PLIST_BINARY("binary plist"), CLASS("Java class"),
        UNKNOWN("unknown")
    }

    fun detectKind(file: File): Kind {
        if (file.isDirectory) {
            return if (File(file, "package.json").exists() ||
                File(file, "node_modules").exists()
            ) Kind.ASAR else Kind.UNKNOWN
        }
        val head = ByteArray(16)
        val n = file.inputStream().use { it.read(head) }
        if (n < 4) return Kind.UNKNOWN
        val b0 = head[0].toInt() and 0xFF
        if (b0 == MAGIC_ELF) return Kind.ELF
        if (b0 == 'M'.code && head[1].toInt() == 'Z'.code) {
            return if (file.name.endsWith(".apk", true)) Kind.APK else Kind.PE
        }
        if (b0 == 'd'.code && head[1].toInt() == 'e'.code &&
            head[2].toInt() == 'x'.code
        ) return Kind.DEX
        if (b0 == 0xCA) return Kind.FAT
        if (b0 == 0xCE || b0 == 0xCF) return Kind.MACHO
        if (head[0].toInt() == 0x00 && head[1].toInt() == 'a'.code &&
            head[2].toInt() == 's'.code && head[3].toInt() == 'm'.code
        ) return Kind.WASM
        if (b0 == 'P'.code && head[1].toInt() == 'K'.code) {
            // ZIP 家族细分：APK / 普通 zip
            return if (file.name.endsWith(".apk", true)) Kind.APK else Kind.ZIP_ARCHIVE
        }
        if (b0 == 'S'.code && head[1].toInt() == 'Q'.code &&
            head[2].toInt() == 'L'.code && head[3].toInt() == 'i'.code
        ) return Kind.SQLITE
        if (b0 == 0x62 && head[1].toInt() == 'p'.code &&
            head[2].toInt() == 'l'.code && head[3].toInt() == 'i'.code
        ) return Kind.PLIST_BINARY
        // ASAR：pickle 头 04 00 00 00 + 非零 headerSize
        if (b0 == 0x04 && head[4] != 0.toByte()) return Kind.ASAR
        if (file.name.endsWith(".plist", true)) return Kind.PLIST_XML
        if (file.name.endsWith(".class", true) && b0 == 0xCA) return Kind.CLASS
        return Kind.UNKNOWN
    }

    // ── 通用小端读取助手（RandomAccessFile 按 offset 读）───────────────

    // 注意：RandomAccessFile.readInt/readUnsignedShort 是大端 —— 而本引擎
    // 解析的 ELF/PE/Mach-O/ASAR 头全部小端，这里必须手工按小端组装。
    private fun u16(f: RandomAccessFile, off: Long): Int {
        f.seek(off)
        val b0 = f.readUnsignedByte()
        val b1 = f.readUnsignedByte()
        return b0 or (b1 shl 8)
    }

    private fun u32(f: RandomAccessFile, off: Long): Long {
        f.seek(off)
        val b0 = f.readUnsignedByte().toLong()
        val b1 = f.readUnsignedByte().toLong()
        val b2 = f.readUnsignedByte().toLong()
        val b3 = f.readUnsignedByte().toLong()
        return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
    }

    private fun u64(f: RandomAccessFile, off: Long): Long =
        u32(f, off) or (u32(f, off + 4) shl 32)

    private fun ascii(f: RandomAccessFile, off: Long, len: Int): String {
        f.seek(off)
        val buf = ByteArray(len)
        f.readFully(buf)
        return buf.toString(Charsets.US_ASCII).trimEnd('\u0000')
    }

    // ── ELF ─────────────────────────────────────────────────────────────

    /** ELF 结构化布局（segments / sections / 动态依赖 / 动态符号）。 */
    data class ElfLayout(
        val is64: Boolean,
        val machine: String,
        val type: String,
        val entry: Long,
        val segments: List<Seg>,
        val sections: List<Sect>,
        val needed: List<String>,
        val dynamicSymbols: List<String>,
        val soname: String
    ) {
        data class Seg(val type: String, val flags: Int, val vaddr: Long, val fileOffset: Long, val fileSize: Long)
        data class Sect(val name: String, val type: Int, val vaddr: Long, val offset: Long, val size: Long)
    }

    private val ELF_MACHINE = mapOf(
        0x03L to "x86", 0x3EL to "x86-64", 0xB7L to "aarch64", 0x28L to "arm",
        0xF3L to "riscv64", 0x08L to "mips", 0x14L to "powerpc64"
    )

    private val ELF_TYPE = mapOf(1L to "REL", 2L to "EXEC", 3L to "DYN (PIE)", 4L to "CORE")

    private val PT_NAMES = mapOf(1L to "LOAD", 2L to "DYNAMIC", 3L to "INTERP", 4L to "NOTE", 6L to "PHDR", 7L to "TLS")

    fun parseElf(file: File): ElfLayout? {
        RandomAccessFile(file, "r").use { f ->
            val ident = ByteArray(16)
            f.seek(0)
            f.readFully(ident)
            if (ident[0].toInt() != MAGIC_ELF) return null
            val is64 = ident[4].toInt() == 2
            val machine = ELF_MACHINE[u16(f, 18).toLong()] ?: "machine-${u16(f, 18)}"
            val type = ELF_TYPE[u16(f, 16).toLong()] ?: "type-${u16(f, 16)}"
            val entry: Long
            val phoff: Long
            val shoff: Long
            val phentsize: Int
            val phnum: Int
            val shentsize: Int
            val shnum: Int
            val shstrndx: Int
            if (is64) {
                entry = u64(f, 24); phoff = u64(f, 32); shoff = u64(f, 40)
                phentsize = u16(f, 54); phnum = u16(f, 56)
                shentsize = u16(f, 58); shnum = u16(f, 60); shstrndx = u16(f, 62)
            } else {
                entry = u32(f, 24); phoff = u32(f, 28); shoff = u32(f, 32)
                phentsize = u16(f, 42); phnum = u16(f, 44)
                shentsize = u16(f, 46); shnum = u16(f, 48); shstrndx = u16(f, 50)
            }

            // Program headers（segments）
            val segs = mutableListOf<ElfLayout.Seg>()
            for (i in 0 until phnum) {
                val base = phoff + i.toLong() * phentsize
                val ptype = u32(f, base)
                val flags: Int
                val vaddr: Long
                val off: Long
                val size: Long
                if (is64) {
                    flags = u32(f, base + 4).toInt(); vaddr = u64(f, base + 16)
                    off = u64(f, base + 8); size = u64(f, base + 32)
                } else {
                    // 32 位：offset@+4, vaddr@+8, filesz@+16, flags@+24
                    off = u32(f, base + 4); vaddr = u32(f, base + 8)
                    size = u32(f, base + 16); flags = u32(f, base + 24).toInt()
                }
                segs.add(
                    ElfLayout.Seg(
                        type = PT_NAMES[ptype] ?: "pt-$ptype",
                        flags = flags, vaddr = vaddr, fileOffset = off, fileSize = size
                    )
                )
            }

            // Section headers（名称经 shstrtab）
            val shstrtabOff: Long = run {
                if (shnum == 0 || shoff == 0L) return@run 0L
                val first = shoff + shstrndx.toLong() * shentsize
                if (is64) u64(f, first + 24) else u32(f, first + 20)
            }
            fun sectName(nameOff: Long): String =
                if (shstrtabOff > 0 && nameOff >= 0) readCString(f, shstrtabOff + nameOff) else ""

            val sects = mutableListOf<ElfLayout.Sect>()
            for (i in 0 until shnum) {
                val base = shoff + i.toLong() * shentsize
                val nameOff = u32(f, base)
                val stype = u32(f, base + 4).toInt()
                val vaddr: Long; val off: Long; val size: Long
                if (is64) {
                    vaddr = u64(f, base + 16); off = u64(f, base + 24); size = u64(f, base + 32)
                } else {
                    vaddr = u32(f, base + 12); off = u32(f, base + 16); size = u32(f, base + 20)
                }
                sects.add(ElfLayout.Sect(sectName(nameOff), stype, vaddr, off, size))
            }

            // 动态段：DT_NEEDED（依赖）+ DT_SONAME
            val needed = mutableListOf<String>()
            var soname = ""
            val dynSect = sects.firstOrNull { it.name == ".dynamic" }
            val dynSeg = segs.firstOrNull { it.type == "DYNAMIC" }
            val dynBase = (dynSect?.offset ?: dynSeg?.fileOffset ?: -1L)
            if (dynBase >= 0) {
                val strTabOff: Long
                val strTabSize: Long
                // 先扫一遍 dynamic 找 DT_STRTAB（5）/ DT_STRSZ（10）
                var cursor = dynBase
                val entrySize = if (is64) 16 else 8
                var strtab = -1L
                var strsz = 0L
                while (true) {
                    val tag = if (is64) u64(f, cursor) else u32(f, cursor)
                    if (tag == 0L) break
                    val value = if (is64) u64(f, cursor + 8) else u32(f, cursor + 4)
                    if (tag == 5L) strtab = value
                    if (tag == 10L) strsz = value
                    cursor += entrySize
                    if (cursor - dynBase > 8192) break // 防异常循环
                }
                // strtab 是 vaddr —— 转 file offset（经 LOAD 段映射）
                fun vaddrToFile(va: Long): Long =
                    segs.firstOrNull { va >= it.vaddr && va < it.vaddr + it.fileSize }
                        ?.let { it.fileOffset + (va - it.vaddr) } ?: va
                strTabOff = if (strtab >= 0) vaddrToFile(strtab) else -1L
                strTabSize = strsz
                cursor = dynBase
                while (true) {
                    val tag = if (is64) u64(f, cursor) else u32(f, cursor)
                    if (tag == 0L) break
                    val value = if (is64) u64(f, cursor + 8) else u32(f, cursor + 4)
                    if (tag == 1L && strTabOff >= 0) {
                        needed.add(readCString(f, strTabOff + value))
                    }
                    if (tag == 14L && strTabOff >= 0) {
                        soname = readCString(f, strTabOff + value)
                    }
                    cursor += entrySize
                    if (cursor - dynBase > 8192) break
                }
                if (strTabOff >= 0 && strTabSize > 0) { /* strsz 校验位（保留给后续精确截断） */ }
            }

            // 动态符号（.dynsym + .dynstr）：导入（UNDEF）与导出（定义）名
            val symbols = mutableListOf<String>()
            val dynsym = sects.firstOrNull { it.name == ".dynsym" }
            val dynstr = sects.firstOrNull { it.name == ".dynstr" }
            if (dynsym != null && dynstr != null) {
                val entrySize = if (is64) 24 else 16
                val count = (dynsym.size / entrySize).toInt().coerceAtMost(20_000)
                for (i in 0 until count) {
                    val base = dynsym.offset + i.toLong() * entrySize
                    val nameOff = u32(f, base)
                    if (nameOff > 0 && nameOff < dynstr.size) {
                        val nm = readCString(f, dynstr.offset + nameOff)
                        if (nm.isNotBlank()) symbols.add(nm)
                    }
                }
            }

            return ElfLayout(
                is64 = is64, machine = machine, type = type, entry = entry,
                segments = segs, sections = sects, needed = needed,
                dynamicSymbols = symbols, soname = soname
            )
        }
    }

    /** vaddr → 文件偏移（address_to_file_offset 的 ELF 路径）。 */
    fun elfVaddrToFileOffset(layout: ElfLayout, vaddr: Long): Long? =
        layout.segments.firstOrNull { vaddr >= it.vaddr && vaddr < it.vaddr + it.fileSize }
            ?.let { it.fileOffset + (vaddr - it.vaddr) }

    // ── PE ──────────────────────────────────────────────────────────────

    data class PeLayout(
        val machine: String,
        val is64: Boolean,
        val entry: Long,
        val sections: List<String>,
        val importDlls: List<String>,
        val managed: Boolean,
        val subsystem: String
    )

    private val PE_MACHINE = mapOf(0x14cL to "i386", 0x8664L to "x86-64", 0xAA64L to "arm64", 0x1c0L to "arm")

    fun parsePe(file: File): PeLayout? {
        RandomAccessFile(file, "r").use { f ->
            val mz = u16(f, 0)
            if (mz.toLong() != MAGIC_MZ.toLong()) return null
            val peOff = u32(f, 0x3C)
            if (u32(f, peOff) != 0x00004550L) return null // PE\0\0
            val machine = u16(f, peOff + 4).toLong()
            val numSections = u16(f, peOff + 6)
            val optSize = u16(f, peOff + 20)
            val opt = peOff + 24
            val optMagic = u16(f, opt)
            val is64 = optMagic == 0x20B
            val entry = u32(f, opt + 16)
            val subsystem = u16(f, opt + 68)
            // 数据目录：opt+112(64)/opt+96(32)，每项 8 字节；14 = COM 描述符（.NET）
            val ddBase = opt + (if (is64) 112L else 96L)
            val importRva = u32(f, ddBase + 8)
            val comRva = u32(f, ddBase + 14 * 8)

            // Section 表
            val sectBase = opt + optSize
            val sections = mutableListOf<String>()
            for (i in 0 until numSections) {
                val base = sectBase + i.toLong() * 40
                sections.add(ascii(f, base, 8))
            }

            // Import DLL 名（import 描述符表按文件偏移直读：NameRva@+12，全零结尾；
            // 小型 PE 的 RVA≈文件偏移近似成立，错位时循环上限兜底不越界读崩）
            val importDlls = mutableListOf<String>()
            if (importRva > 0 && importRva < f.length() - 20) {
                var desc = importRva
                var guard = 0
                while (guard++ < 512) {
                    val nameRva = u32(f, desc + 12)
                    if (nameRva == 0L) break
                    val dll = readCString(f, nameRva)
                    if (dll.isBlank()) break
                    importDlls.add(dll)
                    desc += 20
                }
            }

            return PeLayout(
                machine = PE_MACHINE[machine] ?: "machine-$machine",
                is64 = is64,
                entry = entry,
                sections = sections,
                importDlls = importDlls,
                managed = comRva > 0,
                subsystem = when (subsystem) {
                    2 -> "GUI"; 3 -> "console"; 9 -> "CE"; 14 -> "EFI" ; else -> "subsystem-$subsystem"
                }
            )
        }
    }

    // ── Mach-O ──────────────────────────────────────────────────────────

    data class MachoLayout(
        val magic: String,
        val cpuType: Long,
        val fileType: String,
        val commands: List<Cmd>,
        val dylibs: List<String>,
        val segments: List<String>
    ) {
        data class Cmd(val cmd: Long, val size: Int)
    }

    private val MH_TYPES = mapOf(
        1L to "object", 2L to "executable", 5L to "dylib", 6L to "dylinker",
        7L to "bundle", 8L to "core", 10L to "fileset", 12L to "dylib stub"
    )

    fun parseMacho(file: File, offset: Long = 0): MachoLayout? {
        RandomAccessFile(file, "r").use { f ->
            val magic = u32(f, offset).toInt()
            val swapped = magic == MAGIC_MACHO64_BE || magic == MAGIC_MACHO32_BE
            val is64 = magic == MAGIC_MACHO64 || magic == MAGIC_MACHO64_BE
            if (magic != MAGIC_MACHO64 && magic != MAGIC_MACHO32 &&
                magic != MAGIC_MACHO64_BE && magic != MAGIC_MACHO32_BE
            ) return null
            fun u32s(a: Long): Long = if (swapped) java.lang.Long.reverseBytes(u32(f, a)) else u32(f, a)
            val cpu = u32s(offset + 4)
            val fileType = MH_TYPES[u32s(offset + 12)] ?: "type-${u32s(offset + 12)}"
            val ncmds = u32s(offset + 16).toInt()
            val cmds = mutableListOf<MachoLayout.Cmd>()
            val dylibs = mutableListOf<String>()
            val segments = mutableListOf<String>()
            var cursor = offset + if (is64) 32 else 28
            for (i in 0 until ncmds.coerceAtMost(4096)) {
                val cmd = u32s(cursor)
                val size = u32s(cursor + 4).toInt()
                if (size <= 0) break
                cmds.add(MachoLayout.Cmd(cmd, size))
                when (cmd) {
                    0xCL -> { // LC_LOAD_DYLIB：name 在 cmd+24（offset 字段）
                        val nameOff = u32s(cursor + 24)
                        if (nameOff in 0L until size.toLong()) {
                            val nm = readCString(f, cursor + nameOff)
                            if (nm.isNotBlank()) dylibs.add(nm)
                        }
                    }
                    0x1L, 0x19L -> { // LC_SEGMENT / LC_SEGMENT_64
                        val segName = ascii(f, cursor + 8, 16)
                        if (segName.isNotBlank()) segments.add(segName)
                    }
                }
                cursor += size
            }
            return MachoLayout(
                magic = if (is64) "MH_MAGIC_64" else "MH_MAGIC",
                cpuType = cpu,
                fileType = fileType,
                commands = cmds,
                dylibs = dylibs,
                segments = segments
            )
        }
    }

    /** fat/universal 镜像的 slice 清单（list_architectures）。 */
    data class FatSlice(val cpuType: Long, val offset: Long, val size: Long)

    fun parseFat(file: File): List<FatSlice>? {
        RandomAccessFile(file, "r").use { f ->
            val magic = u32(f, 0).toInt()
            if (magic != MAGIC_FAT && magic != MAGIC_FAT_BE) return null
            val swapped = magic == MAGIC_FAT_BE
            fun u32s(a: Long): Long = if (swapped) java.lang.Long.reverseBytes(u32(f, a)) else u32(f, a)
            val n = u32s(4).toInt()
            val slices = mutableListOf<FatSlice>()
            for (i in 0 until n.coerceAtMost(16)) {
                val base = 8L + i * 20
                slices.add(FatSlice(u32s(base), u32s(base + 8), u32s(base + 12)))
            }
            return slices
        }
    }

    // ── 共用底层 ────────────────────────────────────────────────────────

    /** C 字符串读取（bounded 4KB）。 */
    internal fun readCString(f: RandomAccessFile, offset: Long): String {
        if (offset < 0) return ""
        f.seek(offset)
        val buf = ByteArray(4096)
        val n = f.read(buf)
        if (n <= 0) return ""
        val end = buf.indexOf(0.toByte())
        val len = if (end >= 0) end else n
        return String(buf, 0, len, Charsets.US_ASCII)
    }

    /** 可打印字符串提取（≥ minLen 的连续可打印段；上限条数防大文件爆内存）。 */
    fun extractStrings(file: File, minLen: Int = 5, maxCount: Int = 200_000): List<String> {
        val result = mutableListOf<String>()
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            val sb = StringBuilder()
            var carry = ""
            while (result.size < maxCount) {
                val n = input.read(buf)
                if (n <= 0) break
                for (i in 0 until n) {
                    val c = buf[i].toInt()
                    if (c in 32..126) {
                        sb.append(c.toChar())
                    } else {
                        if (sb.length >= minLen) result.add(carry + sb.toString())
                        sb.setLength(0)
                        carry = ""
                        if (result.size >= maxCount) break
                    }
                }
                // 跨块处理：块尾未终结的串暂存
                if (sb.isNotEmpty() && n == buf.size) {
                    carry = carry + sb.toString()
                    sb.setLength(0)
                }
            }
            if (sb.length >= minLen) result.add(carry + sb.toString())
        }
        return result
    }

    /** inspect_binary_layout 的渲染（ELF/PE/Mach-O 三合一）。 */
    fun renderLayout(tool: String, file: File): kotlinx.serialization.json.JsonObject {
        val digest = ReaEvidence.digestOfFile(file)
        val kind = detectKind(file)
        val findings = mutableListOf<ReaEvidence.Finding>()
        val evidence = mutableListOf<ReaEvidence.Item>()
        val limitations = mutableListOf<String>()
        when (kind) {
            Kind.ELF -> {
                val elf = parseElf(file)
                if (elf == null) {
                    limitations.add("ELF 头不完整，无法解析")
                } else {
                    findings.add(ReaEvidence.Finding("format", "ELF ${if (elf.is64) "64" else "32"}-bit ${elf.machine} ${elf.type}"))
                    findings.add(ReaEvidence.Finding("entry", "0x${elf.entry.toString(16)}"))
                    findings.add(ReaEvidence.Finding("segments", elf.segments.joinToString(", ") { it.type }))
                    findings.add(ReaEvidence.Finding("sections", elf.sections.size.toString() + " 个（" + elf.sections.take(12).joinToString(", ") { it.name } + "…）"))
                    if (elf.needed.isNotEmpty()) {
                        findings.add(ReaEvidence.Finding("needed", elf.needed.joinToString(", ")))
                    }
                    if (elf.dynamicSymbols.isNotEmpty()) {
                        findings.add(ReaEvidence.Finding("dynamic symbols", "${elf.dynamicSymbols.size} 个（前 20：${elf.dynamicSymbols.take(20).joinToString(", ")}）"))
                    }
                    evidence.add(ReaEvidence.Item("bytes", "0x0", "ELF 魔数 + class=${if (elf.is64) "64" else "32"}"))
                    evidence.add(ReaEvidence.Item("manifest", ".dynamic", "DT_NEEDED ×${elf.needed}，DT_SONAME=${elf.soname.ifBlank { "（无）" }}"))
                }
            }
            Kind.PE -> {
                val pe = parsePe(file)
                if (pe == null) {
                    limitations.add("PE 头不完整，无法解析")
                } else {
                    findings.add(ReaEvidence.Finding("format", "PE ${if (pe.is64) "32+64" else "32"} ${pe.machine} ${pe.subsystem}"))
                    findings.add(ReaEvidence.Finding("entry", "0x${pe.entry.toString(16)}"))
                    findings.add(ReaEvidence.Finding("sections", pe.sections.joinToString(", ")))
                    if (pe.importDlls.isNotEmpty()) {
                        findings.add(ReaEvidence.Finding("imports", pe.importDlls.joinToString(", ")))
                    }
                    if (pe.managed) findings.add(ReaEvidence.Finding("managed", "包含 CLR 头（.NET 程序集）"))
                    evidence.add(ReaEvidence.Item("bytes", "0x3c", "e_lfanew → PE 签名"))
                }
            }
            Kind.MACHO, Kind.FAT -> {
                if (kind == Kind.FAT) {
                    val slices = parseFat(file) ?: emptyList()
                    findings.add(ReaEvidence.Finding("format", "Mach-O universal（${slices.size} slices）"))
                    slices.forEachIndexed { i, s ->
                        findings.add(ReaEvidence.Finding("slice[$i]", "cpu=${s.cpuType} offset=0x${s.offset.toString(16)} size=${s.size}"))
                    }
                }
                val macho = parseMacho(file)
                if (macho != null) {
                    findings.add(ReaEvidence.Finding("cpu", "cputype=${macho.cpuType} ${macho.fileType}"))
                    findings.add(ReaEvidence.Finding("segments", macho.segments.joinToString(", ")))
                    if (macho.dylibs.isNotEmpty()) {
                        findings.add(ReaEvidence.Finding("dylibs", macho.dylibs.joinToString(", ")))
                    }
                    findings.add(ReaEvidence.Finding("load commands", "${macho.commands.size} 个"))
                }
            }
            else -> {
                limitations.add("非原生二进制（识别为 ${kind.label}）—— 用对应域工具（inspect_artifact / analyze_javascript_application 等）")
            }
        }
        return ReaEvidence.success(
            tool = tool, target = file.absolutePath, tier = "on_device",
            findings = findings, evidence = evidence, limitations = limitations,
            digest = digest
        )
    }

    /** 渲染 ELF 符号/字符串枚举（list_names / list_strings / search 共用底座）。 */
    fun renderSymbolScan(
        tool: String,
        file: File,
        query: String?,
        regex: Boolean
    ): kotlinx.serialization.json.JsonObject {
        val kind = detectKind(file)
        val names: List<String> = when (kind) {
            Kind.ELF -> parseElf(file)?.dynamicSymbols ?: emptyList()
            Kind.MACHO -> parseMacho(file)?.dylibs ?: emptyList()
            else -> emptyList()
        }
        val strings = extractStrings(file, minLen = 6, maxCount = 50_000)
        val pool = names + strings
        val hits = if (query.isNullOrBlank()) {
            pool.take(200)
        } else if (regex) {
            val re = runCatching { Regex(query) }.getOrNull()
            if (re == null) return ReaEvidence.error(tool, file.absolutePath, "非法正则: $query")
            pool.filter { re.containsMatchIn(it) }.take(200)
        } else {
            pool.filter { it.contains(query, ignoreCase = true) }.take(200)
        }
        return ReaEvidence.success(
            tool = tool, target = file.absolutePath, tier = "on_device",
            findings = listOf(
                ReaEvidence.Finding("matches", "${hits.size} 条命中（符号 ${names.size} + 字符串 ${strings.size} 池）")
            ),
            evidence = hits.take(50).map { ReaEvidence.Item("match", "", it) },
            limitations = if (pool.size > 50_000) listOf("字符串池截断至 50k") else emptyList(),
            digest = ReaEvidence.digestOfFile(file)
        )
    }

    /** read_bytes：从文件偏移读字节（hex 输出）。 */
    fun renderReadBytes(tool: String, file: File, offset: Long, length: Int): kotlinx.serialization.json.JsonObject {
        if (offset < 0 || length <= 0 || length > 4096) {
            return ReaEvidence.error(tool, file.absolutePath, "offset/length 越界（length ≤ 4096）")
        }
        val buf = ByteArray(length)
        val n = RandomAccessFile(file, "r").use { f ->
            if (f.length() <= offset) return ReaEvidence.error(tool, file.absolutePath, "offset 超出文件长度")
            f.seek(offset)
            f.read(buf)
        }
        val hex = buf.take(n).joinToString(" ") { "%02x".format(it) }
        return ReaEvidence.success(
            tool = tool, target = file.absolutePath, tier = "on_device",
            findings = listOf(ReaEvidence.Finding("bytes@0x${offset.toString(16)}", hex.take(2048))),
            evidence = listOf(ReaEvidence.Item("bytes", "0x${offset.toString(16)}", "$n 字节")),
            digest = ReaEvidence.digestOfFile(file)
        )
    }

    /** search_bytes：十六进制模式查找（支持 ? 通配 nibble 的简单形态）。 */
    fun renderSearchBytes(tool: String, file: File, pattern: String): kotlinx.serialization.json.JsonObject {
        val clean = pattern.removePrefix("0x").replace(" ", "")
        if (clean.length < 2 || clean.length % 2 != 0 || !clean.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) {
            return ReaEvidence.error(tool, file.absolutePath, "非法十六进制模式: $pattern")
        }
        val bytes = ByteArray(clean.length / 2) {
            ((Character.digit(clean[it * 2], 16) shl 4) or Character.digit(clean[it * 2 + 1], 16)).toByte()
        }
        val hits = mutableListOf<Long>()
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            var filePos = 0L
            var carry = ByteArray(0)
            outer@ while (hits.size < 500) {
                val n = input.read(buf)
                if (n <= 0) break
                val window = carry + buf.copyOf(n)
                for (i in 0..window.size - bytes.size) {
                    if (window.copyOfRange(i, i + bytes.size).contentEquals(bytes)) {
                        hits.add(filePos - carry.size + i)
                        if (hits.size >= 500) break@outer
                    }
                }
                carry = window.takeLast(bytes.size - 1).toByteArray()
                filePos += n
            }
        }
        return ReaEvidence.success(
            tool = tool, target = file.absolutePath, tier = "on_device",
            findings = listOf(ReaEvidence.Finding("matches", "${hits.size} 处命中（上限 500）")),
            evidence = hits.take(50).map { ReaEvidence.Item("offset", "0x${it.toString(16)}", "") },
            digest = ReaEvidence.digestOfFile(file)
        )
    }

    /** address_to_file_offset：ELF vaddr → 文件偏移。 */
    fun renderAddressToFileOffset(tool: String, file: File, address: String): kotlinx.serialization.json.JsonObject {
        val vaddr = address.removePrefix("0x").toLongOrNull(16)
            ?: return ReaEvidence.error(tool, file.absolutePath, "非法地址: $address")
        val kind = detectKind(file)
        val off: Long? = when (kind) {
            Kind.ELF -> parseElf(file)?.let { elfVaddrToFileOffset(it, vaddr) }
            Kind.MACHO -> null // Mach-O vaddr 映射待后续（LC_SEGMENT 表）
            else -> null
        }
        return if (off != null) {
            ReaEvidence.success(
                tool = tool, target = file.absolutePath, tier = "on_device",
                findings = listOf(ReaEvidence.Finding("file offset", "0x${off.toString(16)}")),
                evidence = listOf(ReaEvidence.Item("mapping", "vaddr 0x${vaddr.toString(16)}", "LOAD 段映射"))
            )
        } else {
            ReaEvidence.success(
                tool = tool, target = file.absolutePath, tier = "on_device",
                findings = emptyList(),
                limitations = listOf("地址未落在任何已映射段（或格式暂不支持 vaddr 映射）")
            )
        }
    }
}
