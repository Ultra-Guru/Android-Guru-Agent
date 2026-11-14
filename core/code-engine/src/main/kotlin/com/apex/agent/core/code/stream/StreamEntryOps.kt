package com.apex.agent.core.code.stream

/**
 * 时间轴条目纯工具（perf P0-3 自 CodeStreamSession 抽出保 1200 行预算）：
 * id 序号解析 / 代理对安全截断 / diff 与窗口常量。全部无状态顶层形态，
 * 宿主类调用点零改动（同包可见）。
 */

/** 时间轴条目 id 序号提取（"e-42" / "cycle-1-e-43" → 42；其余 → 0）。 */
internal fun streamEntrySeqOf(id: String): Long =
    STREAM_ENTRY_SEQ_REGEX.find(id)?.groupValues?.get(1)?.toLongOrNull() ?: 0L

private val STREAM_ENTRY_SEQ_REGEX = Regex("""(?:^|-)e-(\d+)$""")

/** 代理对安全截断：首字符若是低代理项则让位，防 emoji 被劈成豆腐块。 */
internal fun streamSafeTakeLast(s: String, n: Int): String = when {
    s.length <= n -> s
    n <= 0 -> ""
    Character.isLowSurrogate(s[s.length - n]) -> s.substring(s.length - n + 1)
    else -> s.substring(s.length - n)
}

/**
 * diff 原文内存/落盘上限（perf P0-3）：编辑/写类工具的 fullOutput 原文
 * （硬顶 150KB）随胶囊常驻 entries 并全量随检查点序列化，几十次编辑即
 * 数 MB 起步。diff 卡渲染只需头部预览，16KB 外由行数徽标承载（hunksTotal
 * 不受影响）。
 */
internal const val DIFF_TEXT_MAX_CHARS = 16_000

/** 时间轴条目内存滑动窗口上限（perf P0-3，见 StreamEntryIndex.trimWindow）。 */
internal const val MAX_ENTRIES_IN_MEMORY = 600
