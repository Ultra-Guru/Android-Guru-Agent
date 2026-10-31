package com.apex.agent.terminalemulator

/**
 * ═══ SGR（Select Graphic Rendition）参数解释器 —— v0.3 从 TerminalCore 拆出 ═══
 *
 * 拆分动机（Task 2-b 文件预算）：TerminalCore.kt 逼近 1200 行门禁，而 SGR 是
 * 最大的纯函数块（无缓冲/光标副作用，输入 [VtParser.CSISequence] → 输出新
 * [TerminalStyle]）—— 独立成文件后可单测（本文件被 SgrApplierTest 直接驱动），
 * TerminalCore 只保留一行调度。
 *
 * 语义（xterm/ECMA-48，T85 审计 C-1 之后的行为原样迁移）：
 *  - 分号扩展与冒号子参数**并存**：`38;5;n` 与 `38:5:n`、`38;2;r;g;b` 与
 *    `38:2:r:g:b` / `38:2:cs:r:g:b`（kitty colorspace 前缀，忽略 cs）；
 *  - `4:x` 下划线样式族（0 无 / 1 单 / 2 双 / 3 波状 / 4 点 / 5 虚线）；
 *  - v0.3 新增 **SGR 58/59 下划线描色**：与 38/48 完全对称
 *    （`58;5;n`、`58;2;r;g;b` 及冒号形式；`59` 归 Default）；
 *  - SGR 0 重置属性但**不清除 OSC 8 链接编号**（xterm 语义，见 TerminalStyle.linkIndex）；
 *  - 参数硬边界（P1 纪律）：索引 clamp 0..255、RGB 分量 clamp 0..255 ——
 *    程序化构造的畸形参数绝不触发 BASIC_16[负] / 灰度溢出。
 *
 * 纯 JVM、无 Android 依赖。
 */
internal object SgrApplier {

    /**
     * 把一条 SGR 序列应用到 [style] 上，返回新样式。
     * 空参数（`ESC[m`）= SGR 0 全重置（链接编号保留）。
     */
    fun apply(style: TerminalStyle, seq: VtParser.CSISequence): TerminalStyle {
        var s = style
        val params = seq.params
        if (params.isEmpty()) return TerminalStyle.DEFAULT.copy(linkIndex = s.linkIndex)
        var i = 0
        while (i < params.size) {
            val p = params[i]
            // 冒号子参数形式：整 token 消费，跳到下一分号项。
            if (seq.hasSubParams(i)) {
                val subs = seq.subParams.getValue(i)
                when (p) {
                    4 -> if (subs.size >= 2) s = s.copy(underline = underlineFromSub(subs[1]))
                    38, 48, 58 -> {
                        val c = colorFromColonSubs(subs)
                        if (c != null) s = applyColorSlot(s, p, c)
                    }
                }
                // T88 修复：冒号 token 在 params 里只占 **一个** 槽位
                //（VtParser 契约：`4:3;58:2:9:8:7` → params=[4,58]，
                // subParams={0:[4,3], 1:[58,2,9,8,7]}）。旧代码 `i += subs.size-1`
                // 把它当成分号扩展的多槽消费 —— `4:3` 之后的参数（如 58）被
                // 整体跳过，颜色静默丢失（SgrApplierTest「curly underline +
                // 58 color coexist」失败的根因）。推进只需尾部 i++。
            } else when (p) {
                0 -> s = TerminalStyle.DEFAULT.copy(linkIndex = s.linkIndex)
                1 -> s = s.copy(bold = true)
                2 -> s = s.copy(dim = true)
                3 -> s = s.copy(italic = true)
                4 -> s = s.copy(underline = UnderlineStyle.SINGLE)
                5 -> s = s.copy(blink = true)
                7 -> s = s.copy(inverse = true)
                8 -> s = s.copy(hidden = true)
                9 -> s = s.copy(strikethrough = true)
                21 -> s = s.copy(underline = UnderlineStyle.DOUBLE)  // T85：SGR 21 双下划线
                22 -> s = s.copy(bold = false, dim = false)
                23 -> s = s.copy(italic = false)
                24 -> s = s.copy(underline = UnderlineStyle.NONE)
                25 -> s = s.copy(blink = false)
                27 -> s = s.copy(inverse = false)
                28 -> s = s.copy(hidden = false)
                29 -> s = s.copy(strikethrough = false)
                in 30..37 -> s = s.copy(foreground = TerminalColor.Indexed(p - 30))
                in 40..47 -> s = s.copy(background = TerminalColor.Indexed(p - 40))
                in 90..97 -> s = s.copy(foreground = TerminalColor.Indexed(p - 90 + 8))
                in 100..107 -> s = s.copy(background = TerminalColor.Indexed(p - 100 + 8))
                39 -> s = s.copy(foreground = TerminalColor.Default)
                49 -> s = s.copy(background = TerminalColor.Default)
                // v0.3：SGR 59 —— 下划线描色归位（与 39/49 对称的“默认色”）。
                59 -> s = s.copy(underlineColor = TerminalColor.Default)
                38, 48, 58 -> {
                    // 38;5;n (256) / 38;2;r;g;b (TrueColor) —— 38/48/58 同构。
                    // P1 fix（边界值）：参数无合法性保证（程序化构造可为任意 Int），
                    // 统一 clamp 到合法色域（负索引/巨值在 TerminalColor.toRgb 会
                    // 越界或溢出）。
                    if (i + 1 < params.size) {
                        when (params[i + 1]) {
                            5 -> {
                                if (i + 2 < params.size) {
                                    val c = TerminalColor.Indexed(params[i + 2].coerceIn(0, 255))
                                    s = applyColorSlot(s, p, c)
                                }
                                i += 2
                            }
                            2 -> {
                                if (i + 4 < params.size) {
                                    val c = TerminalColor.RGB(
                                        params[i + 2].coerceIn(0, 255),
                                        params[i + 3].coerceIn(0, 255),
                                        params[i + 4].coerceIn(0, 255)
                                    )
                                    s = applyColorSlot(s, p, c)
                                }
                                i += 4
                            }
                        }
                    }
                }
            }
            i++
        }
        return s
    }

    /** 38/48/58 → 前景/背景/下划线描色 三槽分发。 */
    private fun applyColorSlot(s: TerminalStyle, slot: Int, c: TerminalColor): TerminalStyle = when (slot) {
        38 -> s.copy(foreground = c)
        48 -> s.copy(background = c)
        else -> s.copy(underlineColor = c)   // 58
    }

    /** 冒号子参数下划线样式（SGR 4:x）：0 无 / 1 单 / 2 双 / 3 波状 / 4 点 / 5 虚线。 */
    internal fun underlineFromSub(styleCode: Int): UnderlineStyle = when (styleCode) {
        0 -> UnderlineStyle.NONE
        2 -> UnderlineStyle.DOUBLE
        3 -> UnderlineStyle.CURLY
        4 -> UnderlineStyle.DOTTED
        5 -> UnderlineStyle.DASHED
        else -> UnderlineStyle.SINGLE
    }

    /** 冒号子参数颜色（38:x:… / 48:x:… / 58:x:…）：[38,5,n] / [38,2,r,g,b] / [38,2,cs,r,g,b]。 */
    internal fun colorFromColonSubs(subs: IntArray): TerminalColor? {
        if (subs.size < 2) return null
        return when (subs[1]) {
            5 -> if (subs.size >= 3) TerminalColor.Indexed(subs[2].coerceIn(0, 255)) else null
            2 -> when {
                // 38:2:cs:r:g:b —— 带色彩空间前缀（kitty 形式），忽略 cs。
                subs.size >= 6 -> TerminalColor.RGB(
                    subs[3].coerceIn(0, 255), subs[4].coerceIn(0, 255), subs[5].coerceIn(0, 255)
                )
                // 38:2:r:g:b —— 无色彩空间。
                subs.size >= 5 -> TerminalColor.RGB(
                    subs[2].coerceIn(0, 255), subs[3].coerceIn(0, 255), subs[4].coerceIn(0, 255)
                )
                else -> null
            }
            else -> null
        }
    }
}
