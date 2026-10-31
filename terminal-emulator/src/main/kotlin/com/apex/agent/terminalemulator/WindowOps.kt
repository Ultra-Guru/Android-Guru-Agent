package com.apex.agent.terminalemulator

/**
 * ═══ 窗口操作（CSI t）—— 标题栈 + 尺寸请求解析（v0.3）═══
 *
 * xterm `CSI Ps ; … t` 的安全子集（其余全部忽略 —— 置顶/图标化/像素定位
 * 等对宿主无意义或有滥用面）：
 *  - `CSI 8 ; rows ; cols t` —— guest 请求改窗尺寸（如 vim 调整后恢复）。
 *    **只产生 [WindowOp.ResizeRequest]，绝不直接改缓冲** —— 真实 resize
 *    必须走宿主量算 → [TerminalCore.resize]（像素字体度量只有宿主知道）。
 *    TerminalCore 经 snapshot.requestedResize 透出 / drainResizeRequest 消费。
 *  - `CSI 18 t` —— 查询字符尺寸 → 回 `CSI 8;rows;cols t`（responseSink）。
 *  - `CSI 14 t` / `CSI 14;2 t` —— 查询像素尺寸 → 回 `CSI 4;0;0 t`
 *    （0;0 占位：像素尺寸是宿主渲染层事实，VT 层无从得知）。
 *  - `CSI 22 ; 0/1/2 t` —— 推标题栈（上限 [TitleStack.CAPACITY]，防泄漏）。
 *  - `CSI 23 ; 0/1/2 t` —— 弹标题栈（恢复保存的标题）。
 *
 * 纯 JVM、无 Android 依赖。
 */

/**
 * 有界标题栈（CSI 22 t / CSI 23 t）。超上限丢最旧 —— 卡死的 guest 循环
 * 也无法无限增长内存（与 OSC 52 / 超链接表同一纪律）。
 */
internal class TitleStack(private val capacity: Int = CAPACITY) {

    companion object {
        /** xterm 未定上限；Termux/kitty 用 8 —— 取 8。 */
        const val CAPACITY = 8
    }

    private val stack = ArrayDeque<String?>()

    /** 推当前标题（null 也入栈 —— “无标题”是可恢复状态）。 */
    fun push(title: String?) {
        if (stack.size >= capacity) stack.removeFirst()
        stack.addLast(title)
    }

    /**
     * 弹出并返回栈顶标题。
     *
     * T88 修复：返回 sealed 结果而非裸 String? —— 旧签名空栈与「压栈的 null」
     * 不可区分，弹出一个合法的无标题状态会被误当空栈丢弃（WindowOpsTest
     * 「null title is a restorable state」失败根因）。
     */
    fun popResult(): PopResult =
        if (stack.isEmpty()) PopResult.Empty else PopResult.Title(stack.removeLast())

    /** 兼容旧调用（测试用）：弹栈值或 null（含歧义，见 [popResult]）。 */
    fun pop(): String? = stack.removeLastOrNull()

    /** 弹栈结果：[Empty]（栈空无动作）或 [Title]（恢复到该值，可为 null）。 */
    sealed interface PopResult {
        data object Empty : PopResult
        data class Title(val value: String?) : PopResult
    }

    /** 当前深度（诊断/测试）。 */
    val size: Int get() = stack.size

    /** 清空（RIS）。 */
    fun clear() = stack.clear()
}

/** CSI t 解析结果（TerminalCore 按类型施效）。 */
internal sealed interface WindowOp {
    /** 无操作（未知/未实现的窗口操作）。 */
    data object None : WindowOp

    /** `CSI 8;rows;cols t` —— guest 请求的新尺寸（已 clamp 到 1..4096 防滥用）。 */
    data class ResizeRequest(val rows: Int, val cols: Int) : WindowOp

    /** 需要经 responseSink 回写的应答串（18/14 查询）。 */
    data class Report(val response: String) : WindowOp

    /** `CSI 22 t` —— 推标题栈。 */
    data object PushTitle : WindowOp

    /** `CSI 23 t` —— 弹标题栈（标题由 TerminalCore 更新）。 */
    data object PopTitle : WindowOp
}

/** 纯解析器：给定 [VtParser.CSISequence]（final 't'）与当前尺寸，产出 [WindowOp]。 */
internal object WindowOps {

    fun parse(seq: VtParser.CSISequence, rows: Int, cols: Int): WindowOp {
        // 安全护栏：任何带 '?' 或 '>' 私有标记的 CSI t 都不是窗口操作语义。
        if (seq.privateMarker != null) return WindowOp.None
        return when (seq.param(0, 0)) {
            8 -> {
                val r = seq.paramOrDefault(1, rows).coerceIn(1, 4096)
                val c = seq.paramOrDefault(2, cols).coerceIn(1, 4096)
                WindowOp.ResizeRequest(r, c)
            }
            18 -> WindowOp.Report("\u001B[8;${rows};${cols}t")
            14 -> WindowOp.Report("\u001B[4;0;0t")   // 像素尺寸未知 → 0;0 占位
            22 -> WindowOp.PushTitle
            23 -> WindowOp.PopTitle
            else -> WindowOp.None
        }
    }
}
