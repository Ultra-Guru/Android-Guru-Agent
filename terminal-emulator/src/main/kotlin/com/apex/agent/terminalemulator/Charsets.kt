package com.apex.agent.terminalemulator

/**
 * ═══ G0–G3 字符集 + SI/SO 移位状态机（v0.3，Termux 对齐）═══
 *
 * 历史 VT 机制，现代程序仍依赖（ncurses 的 ACS 制图、老牌多语言 NRC）：
 *
 * ```
 * guest: ESC ( 0        ← 指定 G0 = DEC Special Graphics（制图线）
 * guest: ESC ) K        ← 指定 G1 = German NRC
 * guest: 0x0E (SO)      ← 移入 G1 —— 之后可打印字符按 G1 表重映射
 * guest: 0x0F (SI)      ← 移回 G0
 * ```
 *
 * 指定（designation）中间字节 → 槽位：`(`→G0、`)`→G1、`*`→G2、`+`→G3。
 * 终止字节 → 字符集：`B`=US-ASCII、`A`=UK、`0`=DEC Special Graphics、
 * `K`=German（DIN 66003）、`R`=French（NF Z 62-010）—— 至少 US/UK/DE/FR
 * （Termux 同集）。G2/G3 的单次移位（SS2/SS3）暂不实现（无已知消费者）。
 *
 * **v0.3 行为修正**：旧实现把 ESC ( A（UK）也归为 ASCII —— 本实现按
 * xterm 归为 UK NRC（仅 '#' → '£'，其余同 ASCII）。既有测试只覆盖
 * '0'/'B'，无回归面。
 *
 * DECSC/DECRC 保存/恢复活跃字符集（[snapshot]/[restore]，xterm 语义）。
 * 纯 JVM、无 Android 依赖。
 */
enum class CharsetName {
    /** US-ASCII（B）—— 恒等映射。 */
    ASCII,
    /** UK NRC（A）—— '#' → '£'。 */
    UK,
    /** DEC Special Graphics（0）—— 制图/符号字形表。 */
    DEC_GRAPHICS,
    /** German NRC（K，DIN 66003）—— @[]{}|~ → §ÄÖÜäöüß。 */
    GERMAN,
    /** French NRC（R，NF Z 62-010）—— #@[]{}|~ → à 英镑 °ç§éùè¨。 */
    FRENCH
}

/** 字符集重映射表 + 指定字节解析。纯函数，无状态。 */
object CharsetTables {

    /**
     * DEC Special Graphics —— ESC ( 0 选中后的字形替换表（xterm 标准）。
     * 索引为 ASCII 码点，值为替换后的 Unicode 码点。
     * v0.3 自 TerminalCore 伴生对象迁入（原处是文件预算压力点；仅同包引用，无外部 API 变化）。
     */
    val DEC_SPECIAL_GRAPHICS: Map<Int, Int> = mapOf(
        0x60 to 0x25C6, 0x61 to 0x2592, 0x62 to 0x2409, 0x63 to 0x240C, 0x64 to 0x240D,
        0x65 to 0x240A, 0x66 to 0x00B0, 0x67 to 0x00B1, 0x68 to 0x2424, 0x69 to 0x240B,
        0x6A to 0x2518, 0x6B to 0x2510, 0x6C to 0x250C, 0x6D to 0x2514, 0x6E to 0x253C,
        0x6F to 0x23BA, 0x70 to 0x23BB, 0x71 to 0x2500, 0x72 to 0x23BC, 0x73 to 0x23BD,
        0x74 to 0x251C, 0x75 to 0x2524, 0x76 to 0x2534, 0x77 to 0x252C, 0x78 to 0x2502,
        0x79 to 0x2264, 0x7A to 0x2265, 0x7B to 0x03C0, 0x7C to 0x2260, 0x7D to 0x00A3,
        0x7E to 0x00B7
    )

    /** UK NRC：仅 '#' 替换为 '£'。 */
    private val UK_NRC: Map<Int, Int> = mapOf(0x23 to 0x00A3)

    /** German NRC（DIN 66003）。 */
    private val GERMAN_NRC: Map<Int, Int> = mapOf(
        0x40 to 0x00A7, 0x5B to 0x00C4, 0x5C to 0x00D6, 0x5D to 0x00DC,
        0x7B to 0x00E4, 0x7C to 0x00F6, 0x7D to 0x00FC, 0x7E to 0x00DF
    )

    /** French NRC（NF Z 62-010）。 */
    private val FRENCH_NRC: Map<Int, Int> = mapOf(
        0x23 to 0x00A3, 0x40 to 0x00E0, 0x5B to 0x00B0, 0x5C to 0x00E7, 0x5D to 0x00A7,
        0x7B to 0x00E9, 0x7C to 0x00F9, 0x7D to 0x00E8, 0x7E to 0x00A8
    )

    /**
     * 按字符集重映射一个可打印码点（恒等 = 返回原值）。
     * 表外码点恒等（xterm：NRC 只重定义列间少数位置）。
     */
    fun map(cp: Int, charset: CharsetName): Int = when (charset) {
        CharsetName.ASCII -> cp
        CharsetName.UK -> UK_NRC[cp] ?: cp
        CharsetName.DEC_GRAPHICS -> DEC_SPECIAL_GRAPHICS[cp] ?: cp
        CharsetName.GERMAN -> GERMAN_NRC[cp] ?: cp
        CharsetName.FRENCH -> FRENCH_NRC[cp] ?: cp
    }

    /** 终止字节 → 字符集；未知设计符返回 null（保持当前指定不动）。 */
    fun designatorFor(final: Char): CharsetName? = when (final) {
        'B' -> CharsetName.ASCII
        'A' -> CharsetName.UK
        '0' -> CharsetName.DEC_GRAPHICS
        'K' -> CharsetName.GERMAN
        'R' -> CharsetName.FRENCH
        else -> null
    }

    /** 指定中间字节 → 槽位（0..3）；非指定序列返回 -1。 */
    fun slotFor(intermediate: Char): Int = when (intermediate) {
        '(' -> 0
        ')' -> 1
        '*' -> 2
        '+' -> 3
        else -> -1
    }
}

/**
 * 引擎持有的字符集状态（G0–G3 + SI/SO 移位 + DECSC 快照）。
 * 终端语义：GL（生效集）= G0（SI 之后）或 G1（SO 之后）。
 */
class CharsetState {

    private val g = arrayOf(
        CharsetName.ASCII, CharsetName.ASCII, CharsetName.ASCII, CharsetName.ASCII
    )

    /** SI/SO 移位：0 = G0（SI / 缺省），1 = G1（SO）。 */
    var shift: Int = 0
        private set

    /** 指定槽位 [slot]（0..3）为 [name]（越界忽略）。 */
    fun designate(slot: Int, name: CharsetName) {
        if (slot in 0..3) g[slot] = name
    }

    /** SO（0x0E）：移入 G1。 */
    fun shiftOut() { shift = 1 }

    /** SI（0x0F）：移回 G0。 */
    fun shiftIn() { shift = 0 }

    /** 当前生效字符集（GL）。 */
    fun active(): CharsetName = g[shift]

    /** 经当前生效集重映射 [cp]（[CharsetTables.map]）。 */
    fun map(cp: Int): Int = CharsetTables.map(cp, active())

    /** 重置（RIS / DECSTR）：G0–G3 = ASCII，移位 G0。 */
    fun reset() {
        g.fill(CharsetName.ASCII)
        shift = 0
        saved = null
    }

    // ── DECSC / DECRC 快照 ──

    private var saved: Snapshot? = null

    private class Snapshot(val g: Array<CharsetName>, val shift: Int)

    /** DECSC：保存 G0–G3 + 移位（与光标/样式快照同刻）。 */
    fun save() {
        saved = Snapshot(g.copyOf(), shift)
    }

    /** DECRC：恢复最近一次保存的字符集状态（无保存 = no-op）。 */
    fun restore() {
        val s = saved ?: return
        for (i in 0..3) g[i] = s.g[i]
        shift = s.shift
    }

    /** 序列化（4 个槽 + 移位 + 已保存快照 5 项）。 */
    fun toArray(): IntArray {
        val base = IntArray(5)
        for (i in 0..3) base[i] = g[i].ordinal
        base[4] = shift
        val s = saved ?: return base
        return base + intArrayOf(1, s.g[0].ordinal, s.g[1].ordinal, s.g[2].ordinal, s.g[3].ordinal, s.shift)
    }

    /** 反序列化（长度/序数校验失败 → reset，绝不抛）。 */
    fun fromArray(a: IntArray) {
        reset()
        if (a.size < 5) return
        for (i in 0..3) g[i] = ordinalOf(a[i]) ?: CharsetName.ASCII
        shift = if (a[4] == 1) 1 else 0
        if (a.size >= 10 && a[5] != 0) {
            saved = Snapshot(
                arrayOf(
                    ordinalOf(a[6]) ?: CharsetName.ASCII,
                    ordinalOf(a[7]) ?: CharsetName.ASCII,
                    ordinalOf(a[8]) ?: CharsetName.ASCII,
                    ordinalOf(a[9]) ?: CharsetName.ASCII
                ),
                if (a.size > 10 && a[10] == 1) 1 else 0
            )
        }
    }

    private fun ordinalOf(v: Int): CharsetName? =
        if (v in CharsetName.entries.indices) CharsetName.entries[v] else null
}
