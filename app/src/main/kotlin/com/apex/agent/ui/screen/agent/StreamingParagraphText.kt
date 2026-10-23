package com.apex.agent.ui.screen.agent

// ─────────────────────────────────────────────────────────────────────────────
// 流式文本分段增量渲染（流水式输出优化，2026-10）
//
// 问题：StreamingResponseBubble / ThinkingBubble 每 33ms flush 用单个 Text
// 渲染全量文本 —— Text 的测量/排版对整个字符串重做，回复越长单次 flush 越
// 贵（整流 O(n²)）：50KB 回复后期单次排版逼近整帧预算，低端机可感知掉帧。
//
// 方案：把流式文本增量切成「已定稿块 + 活动尾段」两级：
// - 已定稿块（completed lines 后随换行、且累计 ` / ** 标记配平的行组）：
//   只计算一次，作为独立 Text 渲染 —— 后续 flush 中其文本实例不变，Text
//   走跳过路径（不重测不重排）。单次 flush 成本从 O(全文) 降到 O(尾段)。
// - 活动尾段（最后的未配平行组 + 未闭合的开放行）：每次 flush 重排 ——
//   这是唯一真正变化的部分，也是旧实现唯一的渲染语义。
//
// 视觉零差异（按设计保证）：
// 1. Android 文本布局本就按 \n 逐行独立排版（行内软换行互不影响），
//    「多个含 \n 的 Text 纵向堆叠」与「单个含 \n 的 Text」逐行同高同宽；
// 2. 定稿块的切界取在「累计标记配平」处 —— 跨行 code/bold 组（含代码
//    围栏块）整体作为一个块定稿、整块样式化，与旧实现全量 styleInline
//    对该区域的产物一致；只有配平的行组才定稿，行内样式不会在切界处
//    撕开（未配平的行天然留在活动尾段）；
// 3. 活动尾段的样式化 = 旧实现对全文做的事，逐字符同源。
//
// 降级语义（graceful）：若模型输出永不闭合的 `（尾段标记永远配不平），
// 该行组留在活动尾段整体重排 —— 退化为旧实现的全量重排成本，不会更差。
//
// 纯字符串 API（无 Compose 依赖）→ 可直接 JVM 单测（见
// StreamingParagraphSlicerTest.kt）；样式化（styleInline）由组合层负责，
// 定稿块经 AnnotatedString 缓存复用（见 AgentChatMessages.kt 接线处）。
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 流式文本增量切分器：把逐次增长的文本切成「已定稿块列表 + 活动尾段」。
 *
 * 调用契约：每次 flush 用最新全文调 [update]；正常路径（append-only 增长）
 * 前缀校验通过后只处理增量。若新文本不以已消费前缀开头（新一轮回复从空
 * 开始 / 媒体尾段截除的收缩边界 / 任何改写），自动全量重建 —— 调用方无需
 * 显式 reset。
 *
 * 不变量：committed 区的累计标记配平（每块自身配平）；pendingRaw 是其后
 * 一切未定稿内容（未配平行组 + 开放尾行）。
 */
internal class StreamingParagraphSlicer {

    /** 一次 [update] 的产物：定稿块（通常单行，跨行标记组为多行块）+ 活动尾段。 */
    data class Slices(
        val committed: List<String>,
        val liveTail: String
    )

    private val committedRaw = StringBuilder()
    private val committedBlocks = mutableListOf<String>()
    private val pendingRaw = StringBuilder()

    /**
     * 增量更新并返回当前切片。
     *
     * 前缀校验 O(已消费长度)（早退 memcmp 语义）；定稿扫描 O(pendingRaw)
     * —— 均为纯字符串操作，远低于被省掉的 Text 全量排版成本。
     */
    fun update(text: String): Slices {
        if (prefixMatches(text)) {
            val consumed = committedRaw.length + pendingRaw.length
            if (text.length > consumed) {
                pendingRaw.append(text, consumed, text.length)
            }
        } else {
            // 收缩/改写：全量重建（新一轮回复 text="" 也走这里 → 状态清零）
            committedRaw.setLength(0)
            committedBlocks.clear()
            pendingRaw.setLength(0)
            pendingRaw.append(text)
        }
        drainCommitted()
        return Slices(committedBlocks.toList(), pendingRaw.toString())
    }

    /** 新文本是否以「已定稿 + 未定稿」为前缀（append-only 快速路径校验）。 */
    private fun prefixMatches(text: String): Boolean {
        val committedLen = committedRaw.length
        val pendingLen = pendingRaw.length
        if (text.length < committedLen + pendingLen) return false
        for (i in 0 until committedLen) {
            if (text[i] != committedRaw[i]) return false
        }
        for (i in 0 until pendingLen) {
            if (text[committedLen + i] != pendingRaw[i]) return false
        }
        return true
    }

    /**
     * 从 pendingRaw 头部尽可能多地定稿：
     * 从左向右单遍扫描累计 ` 与 ** 的奇偶，遇到「行边界（\n）处双配平」
     * 的最短前缀 → 该前缀（不含 \n）作为一个定稿块出队，重复直至无可定稿。
     * 无配平边界时整段留在 pendingRaw（降级语义，见文件头）。
     */
    private fun drainCommitted() {
        while (true) {
            val n = pendingRaw.length
            var backtickOdd = false
            var boldOdd = false
            var blockEnd = -1
            var i = 0
            while (i < n) {
                val c = pendingRaw[i]
                if (c == '`') {
                    backtickOdd = !backtickOdd
                } else if (c == '*' && i + 1 < n && pendingRaw[i + 1] == '*') {
                    boldOdd = !boldOdd
                    i++
                }
                if (c == '\n' && !backtickOdd && !boldOdd) {
                    blockEnd = i
                    break
                }
                i++
            }
            if (blockEnd < 0) return
            // \r\n 成对消费：定稿块不含尾部 \r（Text 对尾随 \r 会多渲染一个
            // 空行，破坏与单 Text 的逐行等价）；committedRaw 仍精确镜像已
            // 消费原文（前缀校验语义不变）。孤立 \r（非 \r\n）留在块内 ——
            // 单 Text 同样把它当换行渲染，行为一致。
            val hasCr = blockEnd > 0 && pendingRaw[blockEnd - 1] == '\r'
            val block = pendingRaw.substring(0, if (hasCr) blockEnd - 1 else blockEnd)
            committedBlocks.add(block)
            if (hasCr) {
                committedRaw.append(block).append('\r').append('\n')
            } else {
                committedRaw.append(block).append('\n')
            }
            pendingRaw.delete(0, blockEnd + 1)
        }
    }
}
