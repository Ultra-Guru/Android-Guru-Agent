package com.apex.agent.terminalemulator

/**
 * ═══ OSC 10/11/12 动态颜色 + OSC 104/110/111/112 重置（v0.3）═══
 *
 * vim `set termguicolors` + `hi Normal guifg/guibg`、`hi Cursor guifg`
 * 通过这条通道在**运行期**改终端默认前景/背景/光标色 —— 主题切换不重开会话：
 *
 * ```
 * guest: printf '\e]10;rgb:ffff/8080/0000\e\\'   ← 前景改金黄
 * guest: printf '\e]11;#1e1e2e\e\\'              ← 背景改卡片色（短格式）
 * guest: printf '\e]10;?\e\\'                    ← 查询 → host 回 OSC 10;rgb:…\e\\
 * guest: printf '\e]110\e\\'                     ← 重置前景（回宿主主题）
 * ```
 *
 * 接受的颜色格式（xterm 规范，[parse] 全覆盖）：
 *  - `rgb:RRRR/GGGG/BBBB` —— 每通道 1–4 个十六进制位，按位宽归一化到 8bit；
 *  - `rgba:RRRR/GGGG/BBBB/AAAA` —— alpha 忽略；
 *  - `#RRGGBB` / `#RGB` / `#RRGGBBAA` —— 数字格式（AA 忽略）。
 * 未识别格式（含 X 色名）→ null → **状态不动**（绝不半更新）。
 *
 * 查询应答格式（[format]）：`rgb:XXXX/XXXX/XXXX` 每通道 16bit（xterm 原生精度；
 * 8bit 值 × 0x101 扩展）。未设置项按各自缺省色应答（[defaultFor]）。
 * 纯 JVM、无 Android 依赖。
 */
class DynamicColors {

    /** OSC 10 前景（null = guest 未设置，宿主回退主题）。 */
    var foreground: TerminalColor? = null
        internal set

    /** OSC 11 背景。 */
    var background: TerminalColor? = null
        internal set

    /** OSC 12 光标描色。 */
    var cursor: TerminalColor? = null
        internal set

    /**
     * OSC 10/11/12 设置（[code] 10/11/12）。[spec] 解析失败返回 false（状态不变）。
     */
    fun set(code: Int, spec: String): Boolean {
        val c = parse(spec) ?: return false
        when (code) {
            10 -> foreground = c
            11 -> background = c
            12 -> cursor = c
            else -> return false
        }
        return true
    }

    /**
     * OSC 110/111/112 精确重置；OSC 104（无参/任意参）—— 重置**全部**动态色
     * （xterm：104 无参 = 重置所有色表项；我们只跟踪 10/11/12 三槽）。
     */
    fun reset(code: Int) {
        when (code) {
            104 -> { foreground = null; background = null; cursor = null }
            110 -> foreground = null
            111 -> background = null
            112 -> cursor = null
        }
    }

    /** 读槽位（10/11/12 之外返回 null）。 */
    fun colorOf(code: Int): TerminalColor? = when (code) {
        10 -> foreground
        11 -> background
        12 -> cursor
        else -> null
    }

    /** 全重置（RIS）。 */
    fun clear() {
        foreground = null; background = null; cursor = null
    }

    companion object {
        /** 未设置槽的查询缺省色：前景/光标 = 白、背景 = 黑（主题未知时的诚实应答）。 */
        fun defaultFor(code: Int): TerminalColor = when (code) {
            11 -> TerminalColor.RGB(0, 0, 0)
            else -> TerminalColor.RGB(255, 255, 255)
        }

        /**
         * 解析 xterm 颜色规格（rgb:/rgba:/#…）。非法/未知格式 → null。
         * 畸形输入（空串、奇数位 hex、越界字符）一律 null —— 绝不抛。
         */
        fun parse(spec: String): TerminalColor? {
            val s = spec.trim().lowercase()
            if (s.isEmpty() || s.length > 64) return null   // 长度防御（畸形输入纪律）
            if (s.startsWith('#')) return parseHash(s)
            val body = when {
                s.startsWith("rgb:") -> s.substring(4)
                s.startsWith("rgba:") -> s.substring(5)
                else -> return null
            }
            val parts = body.split('/')
            if (parts.size != 3 && parts.size != 4) return null
            val r = channel(parts[0]) ?: return null
            val g = channel(parts[1]) ?: return null
            val b = channel(parts[2]) ?: return null
            if (parts.size == 4 && channel(parts[3]) == null) return null  // alpha 也要合法
            return TerminalColor.RGB(r, g, b)
        }

        /** `#RRGGBB` / `#RGB` / `#RRGGBBAA`（alpha 忽略）。 */
        private fun parseHash(s: String): TerminalColor? {
            val body = s.substring(1)
            when (body.length) {
                3 -> {
                    val r = body[0].hexValue() ?: return null
                    val g = body[1].hexValue() ?: return null
                    val b = body[2].hexValue() ?: return null
                    return TerminalColor.RGB(r * 17, g * 17, b * 17)  // 0xA → 0xAA
                }
                6, 8 -> {
                    val r = body.substring(0, 2).hexByte() ?: return null
                    val g = body.substring(2, 4).hexByte() ?: return null
                    val b = body.substring(4, 6).hexByte() ?: return null
                    return TerminalColor.RGB(r, g, b)
                }
                else -> return null
            }
        }

        /** 单通道：1–4 个十六进制位 → 0..255（按位宽归一化：n 位的满量程映射到 255）。 */
        private fun channel(part: String): Int? {
            if (part.isEmpty() || part.length > 4) return null
            var v = 0
            for (ch in part) {
                v = v * 16 + (ch.hexValue() ?: return null)
            }
            val max = (1 shl (4 * part.length)) - 1
            return if (max == 255) v else (v * 255 + max / 2) / max
        }

        private fun Char.hexValue(): Int? = when (this) {
            in '0'..'9' -> this - '0'
            in 'a'..'f' -> this - 'a' + 10
            else -> null
        }

        private fun String.hexByte(): Int? {
            if (length != 2) return null
            val hi = this[0].hexValue() ?: return null
            val lo = this[1].hexValue() ?: return null
            return hi * 16 + lo
        }

        /** xterm 查询应答体（不含 `OSC 10;` 前缀/ST）：`rgb:XXXX/XXXX/XXXX`。 */
        fun format(c: TerminalColor): String {
            val rgb = TerminalColor.toRgb(c)
            fun ch16(v: Int): String = (v * 257).toString(16).padStart(4, '0')
            return "rgb:" + ch16(rgb shr 16 and 0xFF) + "/" + ch16(rgb shr 8 and 0xFF) + "/" + ch16(rgb and 0xFF)
        }
    }
}
