package com.apex.agent.terminalemulator

/**
 * ═══ DECLRMM / DECSLRM 左右边距状态（v0.3）═══
 *
 * xterm 全语义（DEC STD 070）：
 *  - `CSI ?69h/l` —— DECLRMM 开关（Left/Right Margin Mode）。开启后：
 *  - `CSI Pl;Pr s` —— DECSLRM 设置左右边距（**仅 DECLRMM 开启时**；关闭时
 *    `CSI s` 回落为 SCOSC 保存光标 —— TerminalCore 调度层分流）；
 *  - 光标钳制在 [left, right]；打印在右边距处软换行、回到左边距；
 *  - IL/DL/ICH/DCH/ECH/EL/SU/SD/IND/RI 的作用域被边距窗口裁剪
 *    （见 [CsiOps] 各调用点传入 effectiveLeft/right）。
 *
 * 状态三字段（left/right 为 0 基绝对列；enabled == false 时一律全宽）。
 * 序列化友好：[toArray]/[fromArray] 供 session 保存。
 */
class MarginState {

    /** 左边距（0 基绝对列）。仅 [enabled] 时生效。 */
    var left: Int = 0
        private set

    /** 右边距（0 基绝对列，含）。仅 [enabled] 时生效。 */
    var right: Int = 0
        private set

    /** DECLRMM（DECSET 69）开关。 */
    var enabled: Boolean = false
        private set

    /** 生效左界（未启用 = 0）。 */
    fun effectiveLeft(): Int = if (enabled) left else 0

    /** 生效右界（未启用 = cols-1）。 */
    fun effectiveRight(cols: Int): Int = if (enabled) right else cols - 1

    /**
     * DECSLRM：设置左右边距（1 基参数 [pl]/[pr]）。
     *
     * xterm 校验（ctlseqs DEC private modes / DEC STD 070）：
     *  - pl 缺省 = 1，pr 缺省 = cols（调用方以 paramOrDefault 传入）；
     *  - **越界（pl < 1 或 pr > cols）或 pl >= pr → 整条序列忽略**
     *    （返回 false，调用方不得移动光标、不得改动边距）。
     * 注意旧实现把越界参数 coerce 进合法域（如 `CSI 12;20s` 在 10 列屏被
     * 静默改写成 `9;10s`）—— xterm 是「作废」，这里对齐 xterm。
     * 成功后调用方负责光标归位（margin home，见 TerminalCore 's' 分支）。
     */
    fun set(pl: Int, pr: Int, cols: Int): Boolean {
        if (cols < 2) return false
        if (pl < 1 || pr > cols) return false      // 越界 → 整条忽略（xterm）
        val l = pl - 1
        val r = pr - 1
        if (l >= r) return false                   // left >= right → 整条忽略
        left = l
        right = r
        return true
    }

    /** 边距回到全宽（resize / RIS / DECSTR / DECLRMM 关闭）。[enabled] 不变。 */
    fun resetBounds(cols: Int) {
        left = 0
        right = cols - 1
    }

    /**
     * DECLRMM 开关迁移。
     *
     * xterm/Termux 语义：**开关两个方向都把边距回全宽** —— 开启时若此前
     * 未设过 DECSLRM，边距 = 全屏宽（`CSI ?69h` 单独出现绝不裁剪任何东西）；
     * 关闭时边距即刻失效。旧实现开启时保留 [0..0] 初值 → 光标被钳到第 0 列
     * （MarginModeTest 4 项失败的根因之一）。
     */
    fun setEnabled(value: Boolean, cols: Int) {
        enabled = value
        resetBounds(cols)
    }

    /** 序列化（left/right/enabled）。 */
    fun toArray(): IntArray = intArrayOf(left, right, if (enabled) 1 else 0)

    /** 反序列化（长度/范围校验失败 → 全宽缺省，绝不抛）。 */
    fun fromArray(a: IntArray, cols: Int) {
        if (a.size < 3 || cols < 2) { resetBounds(cols); return }
        val l = a[0].coerceIn(0, cols - 2)
        val r = a[1].coerceIn(l + 1, cols - 1)
        left = l
        right = r
        enabled = a[2] != 0
        if (!enabled) resetBounds(cols)
    }
}
