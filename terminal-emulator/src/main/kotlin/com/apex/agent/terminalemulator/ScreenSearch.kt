package com.apex.agent.terminalemulator

/**
 * ═══ 屏内搜索核心 —— v0.3 从 TerminalCore 拆出（纯函数）═══
 *
 * 拆分动机（Task 2-b 文件预算）：朴素子串搜索 + 全词边界判定是纯算法，
 * 不依赖引擎状态 —— 抽出后 TerminalCore.search() 只剩「行文本供给 + 结果
 * 登记」的接线，算法本体可独立单测。
 *
 * 全局行坐标约定：0 = 最老保留行（scrollback 头），scrollback 之后是可见屏。
 * 纯 JVM、无 Android 依赖。
 */
internal object ScreenSearch {

    /**
     * 朴素子串搜索（大小写不敏感/全词可控）。
     *
     * @param lineText 全局行号 → 行文本（null = 行不可用，跳过）
     * @param totalLines 全局总行数（0 until totalLines 遍历）
     * @return 命中列表（[TerminalSearchMatch] 全局行坐标）
     */
    fun findMatches(
        pattern: String,
        lineText: (Long) -> String?,
        totalLines: Long,
        caseInsensitive: Boolean,
        wholeWord: Boolean
    ): List<TerminalSearchMatch> {
        if (pattern.isEmpty() || totalLines <= 0) return emptyList()
        val needle = if (caseInsensitive) pattern.lowercase() else pattern
        val found = ArrayList<TerminalSearchMatch>()
        for (line in 0L until totalLines) {
            val raw = lineText(line) ?: continue
            val hay = if (caseInsensitive) raw.lowercase() else raw
            var from = 0
            while (true) {
                val at = hay.indexOf(needle, from)
                if (at < 0) break
                val end = at + needle.length
                if (wholeWord && !isWordBoundary(hay, at, end)) { from = at + 1; continue }
                found.add(TerminalSearchMatch(line, at, line, end))
                from = end
            }
        }
        return found
    }

    /** 全词边界：命中前后都不是 [isWordChar]（字母/数字/下划线）。 */
    fun isWordBoundary(hay: String, start: Int, end: Int): Boolean {
        fun wordChar(i: Int): Boolean {
            val c = hay[i]
            return c.isLetterOrDigit() || c == '_'
        }
        val before = start > 0 && wordChar(start - 1)
        val after = end < hay.length && wordChar(end)
        return !before && !after
    }
}
