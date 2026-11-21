package com.apex.agent.terminalview

import com.apex.agent.terminalemulator.RenderCell
import kotlin.math.roundToInt

/**
 * T88（2-a）：终端调色板 —— 256 色 + 语义色，以及**渲染 cell 颜色 Long 的完整解码管线**。
 *
 * ## 颜色管线（与引擎/旧渲染器逐字节对齐）
 *
 * `TerminalCore.cellToRender` 把 cell 颜色打包成 Long：
 *
 * ```
 * 0L                       → 主题默认（fg 默认 / bg 透明）
 * 0xFF000000L or rgb       → 引擎已按「标准板」解析的 ARGB：
 *   - SGR 30-37/90-97(索引 0-15) → BASIC_16 槽位
 *   - SGR 38;5;n(16-231)        → 6×6×6 立方（r=(i/36)%6*51 …）
 *   - SGR 38;5;n(232-255)       → 灰阶 (n-232)*10+8
 *   - SGR 38;2;r;g;b            → 真 24bit RGB
 * ```
 *
 * 本类按 app 旧渲染链（`TerminalAnsiRemapper` + `spanStyleFor`）的**同一套语义**解码：
 *  1. `0L` → 语义默认（fg → [foreground]，bg → [background]）；
 *  2. RGB 命中引擎标准 16 色 → **scheme 槽位**（[ansi] —— 换肤零引擎改动）；
 *  3. **bold-as-bright**（xterm 传统，Termux 默认开）：FLAG_BOLD 且命中基础 8 色
 *     → 亮色槽位（i+8）；bg 侧不适用；
 *  4. FLAG_INVERSE → 先解析默认再交换（双默认反显 = 亮底深字，浅色 scheme 反之）；
 *     反显路径**不做** bold-as-bright（与旧 `mapInverse` 一致）；
 *  5. FLAG_DIM → 有效 fg 向有效 bg 50% 混合（Termux 语义；旧渲染器用降透明度，
 *     在彩底上会糊掉，混合法更稳 —— 有意的行为改进，KDoc 记录于此）。
 *
 * 纯 Kotlin（Int/Long 运算）—— JVM 单测直测（fixture 用引擎同款 Long 编码）。
 */
data class TerminalPalette(
    /** ANSI 16 色（0-7 基础 + 8-15 亮色），ARGB Int，**已被 scheme 覆写**。 */
    val ansi: List<Int>,
    /** 扩展 240 色（索引 16-231 立方 + 232-255 灰阶），标准 xterm-256 生成。 */
    val extended: List<Int>,
    /** 默认前景（无 ANSI 着色的普通文本）。 */
    val foreground: Int,
    /** 终端底色。 */
    val background: Int,
    /** 光标色。 */
    val cursor: Int,
    /** 选区高亮底色（建议带透明度）。 */
    val selectionBackground: Int,
    /** 选区内文字色（null = 保持原色）。 */
    val selectionForeground: Int? = null,
    /** 链接下划线/强调色（OSC 8 与 URL 自动识别）。 */
    val linkColor: Int = 0xFF6BB8FF.toInt(),
    /** bold-as-bright 开关（FLAG_BOLD + 基础 8 色 → 亮色槽位）。 */
    val boldAsBright: Boolean = true,
    /** 深色底？（浅色 scheme 的指示器/浮标取色需要区分）。 */
    val dark: Boolean = true
) {
    init {
        require(ansi.size == 16) { "TerminalPalette.ansi needs 16 entries (got ${ansi.size})" }
        require(extended.size == 240) { "TerminalPalette.extended needs 240 entries (got ${extended.size})" }
    }

    /** 全 256 色按索引取值（0-15 = [ansi]，16-255 = [extended]）；越界 → 默认前景。 */
    fun color256(index: Int): Int = when (index) {
        in 0..15 -> ansi[index]
        in 16..255 -> extended[index - 16]
        else -> foreground
    }

    /**
     * 解析一个 cell 的**完整颜色对**（主入口 —— inverse/dim 需要两侧颜色才能算）。
     *
     * @return (fgArgb, bgArgb)，恒不透明。
     */
    fun resolveCell(fg: Long, bg: Long, flags: Int): Pair<Int, Int> {
        val inverse = flags and RenderCell.FLAG_INVERSE != 0
        val bold = flags and RenderCell.FLAG_BOLD != 0
        val dim = flags and RenderCell.FLAG_DIM != 0
        val fgResolved: Int
        val bgResolved: Int
        if (inverse) {
            // 交换后：新 fg = 旧 bg（默认 → background）；新 bg = 旧 fg（默认 →
            // foreground，bold-as-bright 不参与 —— 旧 mapInverse 同款）。
            fgResolved = decodeSlot(bg, isForeground = false, boost = false)
            bgResolved = decodeSlot(fg, isForeground = true, boost = false)
        } else {
            fgResolved = decodeSlot(fg, isForeground = true, boost = bold && boldAsBright)
            bgResolved = decodeSlot(bg, isForeground = false, boost = false)
        }
        return if (dim) {
            blend(fgResolved, bgResolved, DIM_RATIO) to bgResolved
        } else {
            fgResolved to bgResolved
        }
    }

    /**
     * 单色解码（规范签名）：适合只知道一侧颜色的调用方（滚动条取色/链接色等）。
     * inverse 时单色无对侧可换 —— 调用方应改用 [resolveCell]；此处退化语义：
     * isFg+inverse → 按背景侧解析（交换后它就是「底」）；同理 bg+inverse → 前景侧。
     * dim 时以 [background] 为对侧混合（前景 dim 的近似）。
     */
    fun resolve(colorLong: Long, isFg: Boolean, flags: Int): Int {
        val inverse = flags and RenderCell.FLAG_INVERSE != 0
        val effectiveFg = isFg != inverse // inverse 交换侧
        val boost = isFg && !inverse && (flags and RenderCell.FLAG_BOLD != 0) && boldAsBright
        val base = decodeSlot(colorLong, isForeground = effectiveFg, boost = boost)
        return if (flags and RenderCell.FLAG_DIM != 0 && effectiveFg) {
            blend(base, if (effectiveFg) background else foreground, DIM_RATIO)
        } else base
    }

    /** 单侧槽位解码：默认→语义色；标准 16 命中→scheme 槽；其余透传（强制 FF alpha）。 */
    private fun decodeSlot(colorLong: Long, isForeground: Boolean, boost: Boolean): Int {
        if (colorLong == 0L) return if (isForeground) foreground else background
        val rgb = (colorLong and 0xFFFFFFL).toInt()
        val idx = standard16Index(colorLong)
        if (idx != null) {
            return if (isForeground && boost && idx < 8) ansi[idx + 8] else ansi[idx]
        }
        return ALPHA_OPAQUE or rgb
    }

    companion object {
        private const val ALPHA_OPAQUE = 0xFF000000.toInt()
        private const val DIM_RATIO = 0.5f

        /** 引擎标准 16 色（`TerminalColor.BASIC_16` 同值；本模块不引引擎私有常量表）。 */
        val ENGINE_BASIC_16: IntArray = intArrayOf(
            0x000000, 0x800000, 0x008000, 0x808000,
            0x000080, 0x800080, 0x008080, 0xC0C0C0,
            0x808080, 0xFF0000, 0x00FF00, 0xFFFF00,
            0x0000FF, 0xFF00FF, 0x00FFFF, 0xFFFFFF
        )

        /** 标准板 RGB → 槽位（-1 无命中）。RGB 恰好等于标准色的 truecolor 同样命中
         *  —— 与旧 `TerminalAnsiRemapper` 语义一致（视觉等价，零歧义）。 */
        private val standard16IndexByRgb: Map<Long, Int> =
            ENGINE_BASIC_16.withIndex().associate { (i, c) -> c.toLong() to i }

        fun standard16Index(colorLong: Long): Int? =
            standard16IndexByRgb[colorLong and 0xFFFFFFL]

        /** xterm-256 扩展区（16-231 立方 + 232-255 灰阶），与 `TerminalColor.toRgb` 同式。 */
        fun standardExtended(index: Int): Int {
            val rgb = when {
                index < 16 -> ENGINE_BASIC_16[index]
                index < 232 -> {
                    val i = index - 16
                    val r = (i / 36) % 6 * 51
                    val g = (i / 6) % 6 * 51
                    val b = i % 6 * 51
                    (r shl 16) or (g shl 8) or b
                }
                else -> {
                    val v = (index - 232) * 10 + 8
                    (v shl 16) or (v shl 8) or v
                }
            }
            return ALPHA_OPAQUE or rgb
        }

        /** 240 扩展色表（索引 16-255 的标准值）。 */
        val STANDARD_EXTENDED_240: List<Int> = (16..255).map { standardExtended(it) }

        /** sRGB 通道混合（fraction=0 → a，1 → b；roundToInt —— 双向对称，
         *  0.5 混黑/白都是 0x80 而非截断的不对称 0x7F/0x80）。 */
        fun blend(a: Int, b: Int, fraction: Float): Int {
            val f = fraction.coerceIn(0f, 1f)
            fun ch(shift: Int): Int {
                val av = (a ushr shift) and 0xFF
                val bv = (b ushr shift) and 0xFF
                return (av + (bv - av) * f).roundToInt().coerceIn(0, 255)
            }
            return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }

        /**
         * 从「方案映射表」构建调色板 —— app 31 套 scheme 注入通道。
         *
         * 键约定（未命中 → 保守默认）：
         *  - `0..15`：ANSI 槽位；`16..255`：扩展色覆写；
         *  - `-1` background、`-2` foreground、`-3` cursor、`-4` selectionBackground、
         *    `-5` linkColor、`-6` selectionForeground；
         *  - `-9`：boldAsBright（1=true/0=false）；`-10`：dark（1/0）。
         */
        fun fromSchemeMap(map: Map<Int, Int>, dark: Boolean = true): TerminalPalette {
            val ansi = (0..15).map { map[it] ?: ENGINE_BASIC_16[it] or ALPHA_OPAQUE }
            val ext = (16..255).map { map[it] ?: standardExtended(it) }
            return TerminalPalette(
                ansi = ansi,
                extended = ext,
                foreground = map[-2] ?: if (dark) 0xFFFFFFFF.toInt() else 0xFF204020.toInt(),
                background = map[-1] ?: if (dark) 0xFF000000.toInt() else 0xFFFDF6E3.toInt(),
                cursor = map[-3] ?: if (dark) 0xFF00FF00.toInt() else 0xFF586E75.toInt(),
                selectionBackground = map[-4] ?: if (dark) 0x663399FF.toInt() else 0x662AA198.toInt(),
                selectionForeground = map[-6],
                // L7：浅色方案默认链接色取深蓝（旧版恒 0xFF6BB8FF 浅蓝，
                // 浅底下划线对比度不足）；-5 显式注入仍优先。
                linkColor = map[-5] ?: if (dark) 0xFF6BB8FF.toInt() else 0xFF0066CC.toInt(),
                boldAsBright = (map[-9] ?: 1) != 0,
                dark = (map[-10] ?: if (dark) 1 else 0) != 0
            )
        }

        /** 长整型方案值（app `TerminalColorScheme` 的 ARGB Long）→ Int。 */
        fun argbOf(long: Long): Int = long.toInt()

        // ─── 内置方案（Termux 深色 / 浅色 / 单色）───

        /** Termux 官方标准色（termux-app/termux-properties 同源 —— 黑底绿标、
         *  光标亮绿；`TerminalColorSchemeDefs.TERMUX` 逐值一致，宿主可无缝互换）。 */
        val TERMUX_DARK: TerminalPalette = TerminalPalette(
            ansi = listOf(
                0xFF000000, 0xFFCD3131, 0xFF10B44A, 0xFFE5C07B,
                0xFF4078F2, 0xFFC678DD, 0xFF00B8D4, 0xFFDFDFDF,
                0xFF5F6A6E, 0xFFEF5350, 0xFF35E86D, 0xFFFFCA41,
                0xFF64A6FF, 0xFFE48CE8, 0xFF00E5FF, 0xFFFFFFFF
            ).map { it.toInt() },
            extended = STANDARD_EXTENDED_240,
            foreground = 0xFFFFFFFF.toInt(),
            background = 0xFF000000.toInt(),
            cursor = 0xFF00FF00.toInt(),
            selectionBackground = 0x663399FF.toInt(),
            dark = true
        )

        /** 浅色底（Solarized Light 派生）。 */
        val TERMUX_LIGHT: TerminalPalette = TerminalPalette(
            ansi = listOf(
                0xFFEEE8D5, 0xFFDC322F, 0xFF859900, 0xFFB58900,
                0xFF268BD2, 0xFFD33682, 0xFF2AA198, 0xFF073642,
                0xFF93A1A1, 0xFFCB4B16, 0xFF586E75, 0xFF657B83,
                0xFF839496, 0xFF6C71C4, 0xFF93A1A1, 0xFF002B36
            ).map { it.toInt() },
            extended = STANDARD_EXTENDED_240,
            foreground = 0xFF657B83.toInt(),
            background = 0xFFFDF6E3.toInt(),
            cursor = 0xFF586E75.toInt(),
            selectionBackground = 0x662AA198.toInt(),
            dark = false
        )

        /** 单色（无 ANSI 色 —— 可读性/低视力场景；字符属性仍保留）。 */
        val MONOCHROME: TerminalPalette = TerminalPalette(
            ansi = listOf(
                0xFF2E2E2E, 0xFFB0B0B0, 0xFF909090, 0xFFC8C8C8,
                0xFF808080, 0xFFA8A8A8, 0xFF989898, 0xFFE0E0E0,
                0xFF6E6E6E, 0xFFD0D0D0, 0xFFB8B8B8, 0xFFE8E8E8,
                0xFFA0A0A0, 0xFFC0C0C0, 0xFFB0B0B0, 0xFFF8F8F8
            ).map { it.toInt() },
            extended = STANDARD_EXTENDED_240,
            foreground = 0xFFE0E0E0.toInt(),
            background = 0xFF0A0A0A.toInt(),
            cursor = 0xFFE0E0E0.toInt(),
            selectionBackground = 0x66A0A0A0.toInt(),
            linkColor = 0xFFE0E0E0.toInt(),
            boldAsBright = false,
            dark = true
        )
    }
}
