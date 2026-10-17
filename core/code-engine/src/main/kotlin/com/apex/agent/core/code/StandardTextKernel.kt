package com.apex.agent.core.code

import com.apex.standardkit.loop.KitSession

/**
 * # Standard Text Kernel — 标准引擎的文本加速核（可选 SPI）
 *
 * 标准任务循环里的三个高频纯文本操作抽成 SPI，供 native（JNI）实现
 * 加速（`core/code-native` 的 C++17 实现）：
 *
 * | 操作 | 用途 | 纯 Kotlin 回退 |
 * |---|---|---|
 * | [estimateTokens] | 上下文仪表 / 压缩触发 | [StandardSession.defaultTokenEstimator] |
 * | [diffStat] | 运行报告的改动行统计 | 逐行比对（O(n) 哈希对齐） |
 * | [fuzzyLocate] | 编辑参数锚点诊断（old_string 未精确命中时给出最像的位置） | 子序列评分器 |
 *
 * ## 契约
 *
 * - 实现必须**无副作用、线程安全**（多会话并发调用）；
 * - 任何异常由实现自吞并**返回 null / 回退值**——加速核绝不拖垮引擎
 *   （[NativeTextKernel] 的 JNI 层已按此约定包底）；
 * - null 返回 = 「本次算不了」→ 调用方走纯 Kotlin 路径。
 */
interface StandardTextKernel {

    /** token 估算（≥0；实现不可抛异常——失败返回 0 视为"未知"）。 */
    fun estimateTokens(text: String): Int

    /**
     * 行级 diff 统计：old → new 的增/删行数。
     *
     * @return null = 本次不可用（回退逐行比对）
     */
    fun diffStat(oldText: String, newText: String): LineDiffStat?

    /**
     * 模糊定位：needle 在 haystack 中"最像出现"的位置（子序列评分）。
     *
     * @return null = 不可用或无合理匹配（评分 < 阈值）
     */
    fun fuzzyLocate(haystack: String, needle: String): FuzzyMatch?

    /** 行级 diff 统计结果。 */
    data class LineDiffStat(
        val addedLines: Int,
        val removedLines: Int
    ) {
        val changedLines: Int get() = addedLines + removedLines
    }

    /** 模糊匹配结果。 */
    data class FuzzyMatch(
        /** haystack 内最佳起点（字符下标）。 */
        val index: Int,
        /** 评分 0..1（1 = 精确子串）。 */
        val score: Float
    )

    /**
     * 纯 Kotlin 参考实现（也是 native 不可用时的回退核）。
     *
     * 语义与 native 侧一一对应（apex/codenative 同名算法），保证
     * 单测可以交叉验证两侧行为一致。
     */
    object PureKotlin : StandardTextKernel {

        override fun estimateTokens(text: String): Int =
            // 估算公式随标准任务循环迁入库（KitSession 同名伴生函数，公式不变）
            KitSession.defaultTokenEstimator(text)

        override fun diffStat(oldText: String, newText: String): LineDiffStat {
            val oldLines = oldText.lineSequence().toList()
            val newLines = newText.lineSequence().toList()
            // 简化 LCS：哈希计数差（对"整行增删"精确；对行内微改计为一增一删）
            val oldCounts = oldLines.groupingBy { it }.eachCount()
            val newCounts = newLines.groupingBy { it }.eachCount()
            var removed = 0
            var added = 0
            (oldCounts.keys + newCounts.keys).forEach { line ->
                val o = oldCounts[line] ?: 0
                val n = newCounts[line] ?: 0
                if (n > o) added += n - o else removed += o - n
            }
            return LineDiffStat(addedLines = added, removedLines = removed)
        }

        override fun fuzzyLocate(haystack: String, needle: String): FuzzyMatch? {
            if (needle.isEmpty() || haystack.isEmpty()) return null
            val exact = haystack.indexOf(needle)
            if (exact >= 0) return FuzzyMatch(exact, 1.0f)

            // 滑窗 + 子序列贪心评分：以 needle 长度 ± 松弛为窗口，逐位置
            // 计算"窗口内能按序吃到 needle 多少字符"，取最高分。
            val window = (needle.length * 3 / 2).coerceAtLeast(8)
            var bestIndex = -1
            var bestScore = 0f
            var i = 0
            while (i < haystack.length) {
                val end = (i + window).coerceAtMost(haystack.length)
                var score = 0f
                var ni = 0
                for (j in i until end) {
                    if (ni < needle.length && haystack[j] == needle[ni]) {
                        score += 1f / needle.length
                        ni++
                    }
                }
                // 连续性奖励：全部按序吃完（ni == length）且窗口紧凑 → 高分
                if (ni == needle.length) {
                    val consumed = (end - i).coerceAtLeast(1)
                    score *= (needle.length.toFloat() / consumed)
                }
                if (score > bestScore) {
                    bestScore = score
                    bestIndex = i
                }
                if (bestScore >= 0.999f) break
                i += (window / 2).coerceAtLeast(1)
            }
            if (bestIndex < 0 || bestScore < 0.6f) return null
            return FuzzyMatch(bestIndex, bestScore.coerceAtMost(1.0f))
        }
    }
}
