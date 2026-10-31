package com.apex.agent.terminalemulator

/**
 * ═══ DECRQM/DECRPM + DECREQTPARM 应答构造（v0.3）═══
 *
 * DECRQM（Request Mode，`CSI ? N $ p` 私有 / `CSI N $ p` ANSI）——
 * vim/tmux 探测能力位的标准通道（如 `focus-events` 前先问 1004）。
 *
 * 应答（DECRPM，`CSI ? N ; Pm $ y`）状态码 **按 xterm ctlseqs / DEC STD 070**：
 *   Pm = 0 该模式未识别（不支持）/ 1 该模式置位 / 2 该模式复位
 * （3/4 = 永久置位/复位 —— 本引擎不用。与 native vt_engine.cpp 的 decrqm()
 * 逐值对齐：`status = v ? 1 : 2`、未知 = 0。任务描述文字里的
 * 「set→2 / reset→0 / unknown→1」是笔误 —— 无任何真实终端这样实现；
 * 照搬会破坏与 native 引擎的互换性，vim 的能力探测也会误判，
 * 故以「与 xterm/vim 实际互通」为准，测试按 xterm 语义断言。）
 *
 * DECREQTPARM（`CSI Ps x`，Ps ∈ {0,1}）—— 老式参数协商探测，应答固定值：
 *   `CSI ? 2+Ps ; 1 ; 1 ; 120 ; 120 ; 1 ; 0 x`
 * （9600 波特等效、8 数据位、无校验 —— xterm 同款「死参数」应答；
 *  Ps 0/缺省 → ?2，Ps 1 → ?3；其他参数值忽略。请求**不带** `?`
 * 前缀（DEC 规范），应答带 —— 与 xterm/VTE 一致。）
 *
 * 纯格式化函数 —— 模式真值由 TerminalCore 汇聚后传入（保持本文件无状态可单测）。
 */
internal object TerminalReports {

    /** DECRPM 状态码（xterm ctlseqs / DEC STD 070 / native vt_engine 同款）。 */
    const val MODE_NOT_RECOGNIZED = 0
    const val MODE_SET = 1
    const val MODE_RESET = 2

    /**
     * DECRPM 应答串（私有模式带 `?`，ANSI 模式不带）。
     * [status] 取 [MODE_SET]/[MODE_RESET]/[MODE_NOT_RECOGNIZED]
     * （其他值按 not-recognized 归一）。0/1/2 语义见文件头。
     */
    fun decrqmResponse(mode: Int, isPrivate: Boolean, status: Int): String {
        val s = when (status) {
            MODE_SET -> MODE_SET
            MODE_RESET -> MODE_RESET
            else -> MODE_NOT_RECOGNIZED
        }
        val prefix = if (isPrivate) "?" else ""
        return "\u001B[$prefix$mode;${s}\$y"
    }

    /**
     * DECREQTPARM 应答串；[param] 非 0/1 → null（忽略该序列，xterm 同语义）。
     */
    fun decreqtparmResponse(param: Int): String? {
        val marker = when (param) {
            0, 1 -> 2 + param
            else -> return null
        }
        return "\u001B[?$marker;1;1;120;120;1;0x"
    }

    /**
     * 终端自生应答的统一出口约定（DA1/DA2/DSR/DECRQM/DECREQTPARM/窗口查询）：
     * 全部经 TerminalCore.responseSink 注入 PTY —— 纯 JVM 引擎不能自己写 PTY。
     */
}
