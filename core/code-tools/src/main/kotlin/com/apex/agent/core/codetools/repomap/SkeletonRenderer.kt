package com.apex.agent.core.codetools.repomap

import kotlin.math.abs

/**
 * # 骨架渲染 + token 预算装配（Aider TreeContext / 二分搜索的 Kotlin 移植）
 *
 * Aider 用 TreeContext 渲染「关注行 + 父作用域 + `...` 省略」，再在
 * 「取前 N 条排名条目」上二分搜索，直到渲染结果的 token 数恰好落入
 * 预算 ±15%。这里按同款语义移植：
 *
 * - **骨架**：关注行（def 行）整行保留（超宽截断），连续非关注行折叠为
 *   `... (N lines)` 一行 —— 比 TreeContext 简化（不展开父作用域），但
 *   「以最少 token 交代每个文件定义了什么」的目标一致；
 * - **token 估算**：chars/4 启发（与项目 TokenEstimator 英文口径一致；
 *   Aider 用 tiktoken 精确计数，JVM 侧不引 tokenizer 依赖）；
 * - **二分**：middle 从 `maxTokens/25` 起步（Aider 同款初值 —— 平均每条
 *   约 25 token 的经验值），±15% 容差内即停。
 */
object SkeletonRenderer {

    /** 估算文本 token 数（英文 ≈ 4 chars/token 的启发式口径）。 */
    fun estimateTokens(text: String): Int = (text.length + 3) / 4

    /**
     * 渲染单个文件骨架：`... (N lines)` 折叠非关注行。
     *
     * @param lines 文件全部行（0 基下标 = 行号 - 1）
     * @param linesOfInterest 1 基关注行号集合
     */
    fun renderFile(relPath: String, lines: List<String>, linesOfInterest: Set<Int>): String {
        val sb = StringBuilder()
        sb.append(relPath).append(":\n")
        var skipped = 0
        var wroteAny = false
        for ((index, line) in lines.withIndex()) {
            val lineNo = index + 1
            if (lineNo in linesOfInterest) {
                if (skipped > 0) {
                    sb.append("... (").append(skipped).append(skippedLineWord(skipped)).append('\n')
                    skipped = 0
                }
                val trimmed = line.trimEnd()
                sb.append(
                    if (trimmed.length > MAX_LINE_WIDTH) trimmed.take(MAX_LINE_WIDTH) + "…"
                    else if (trimmed.isEmpty()) "(blank)" else trimmed
                ).append('\n')
                wroteAny = true
            } else {
                skipped++
            }
        }
        if (!wroteAny && linesOfInterest.isEmpty()) {
            // 无关注行：整个文件折叠为单行占位（Aider 裸名文件同款处理）
            return "$relPath:\n... (${lines.size}${skippedLineWord(lines.size)})\n"
        }
        if (skipped > 0) {
            sb.append("... (").append(skipped).append(skippedLineWord(skipped)).append('\n')
        }
        return sb.toString()
    }

    /**
     * 装配最终地图：在「取前 N 条排名条目」上二分搜索 token 预算。
     *
     * @param entries 排名条目（重要文件裸名已前置）
     * @param fileLines 渲染源：相对路径 → 文件行（缺失的文件退化为裸名条目）
     * @param maxTokens token 预算
     * @return (地图文本, 估算 token 数)；entries 为空返回 null
     */
    fun assembleWithBudget(
        entries: List<RepoMapBuilder.RankedEntry>,
        fileLines: Map<String, List<String>>,
        maxTokens: Int
    ): Pair<String, Int>? {
        if (entries.isEmpty()) return null
        var lower = 0
        var upper = entries.size
        var middle = minOf(maxTokens / 25, entries.size)
        var best: String? = null
        var bestTokens = 0
        while (lower <= upper) {
            val tree = render(entries.subList(0, middle.coerceIn(0, entries.size)), fileLines)
            val numTokens = estimateTokens(tree)
            val pctErr = abs(numTokens - maxTokens).toDouble() / maxTokens
            if ((numTokens <= maxTokens && numTokens > bestTokens) || pctErr < TOLERANCE) {
                best = tree
                bestTokens = numTokens
                if (pctErr < TOLERANCE) break
            }
            if (numTokens < maxTokens) lower = middle + 1 else upper = middle - 1
            middle = (lower + upper) / 2
        }
        return best?.let { it to bestTokens }
    }

    /** 按条目列表渲染地图（分组：同文件条目合并出一个骨架块）。 */
    fun render(
        entries: List<RepoMapBuilder.RankedEntry>,
        fileLines: Map<String, List<String>>
    ): String {
        val sb = StringBuilder()
        var currentFile: String? = null
        var loi: MutableSet<Int>? = null
        fun flush() {
            val file = currentFile ?: return
            val lines = fileLines[file]
            if (loi != null && lines != null && loi!!.isNotEmpty()) {
                sb.append(renderFile(file, lines, loi!!))
            } else {
                sb.append(file).append('\n')
            }
            loi = null
        }
        // Aider to_tree 同款：sorted(tags) 先按文件名稳定分组，保证每个文件
        // 恰好一个块（排名截断已在上游完成，此处只做分组不影响选择集）。
        val grouped = entries.sortedBy { it.relPath }
        // 哨兵：末尾假条目触发最后一次 flush（Aider 的 dummy_tag 同款技巧）
        for (entry in grouped + RepoMapBuilder.RankedEntry(SENTINEL, null, null, 0.0)) {
            if (entry.relPath != currentFile) {
                flush()
                currentFile = entry.relPath
                loi = if (entry.identifier != null) mutableSetOf() else null
            }
            if (entry.identifier != null && loi != null) {
                entry.line?.let { loi!!.add(it) }
            }
        }
        return sb.toString().trimEnd('\n')
    }

    private fun skippedLineWord(count: Int): String = if (count == 1) " line)" else " lines)"

    private const val SENTINEL = "\u0000sentinel"
    private const val MAX_LINE_WIDTH = 120
    private const val TOLERANCE = 0.15
}
