package com.apex.agent.core.jiguan

/**
 * # `ps -A -o …` 输出解析器（纯函数）
 *
 * Root 通道枚举进程的一次性快照：`ps -A -o PID,UID,RSS,NAME`（toybox ps），
 * 带宽友好且一次 root 调用拿全量。防御式：表头行自适应、坏行跳过。
 */
object PsParsing {

    /**
     * 解析 ps 输出。表头按列名识别（PID/UID/RSS/NAME/ARGS 任意子集），
     * 其余列忽略；NAME 与 ARGS 同有时 ARGS 优先（完整命令行）。
     */
    fun parse(output: String): List<ProcessEntry> {
        val lines = output.lineSequence().filter { it.isNotBlank() }.iterator()
        if (!lines.hasNext()) return emptyList()
        // 表头行：定位各列的起止列区间（按表头 token 的字符区间切列）。
        val header = lines.next()
        val columns = columnRanges(header)
        val pidCol = columns.firstOrNull { it.name == "PID" } ?: return emptyList()
        val uidCol = columns.firstOrNull { it.name == "UID" } ?: columns.firstOrNull { it.name == "USER" }
        val rssCol = columns.firstOrNull { it.name == "RSS" } ?: columns.firstOrNull { it.name == "RSS_SZ" }
        val nameCol = columns.firstOrNull { it.name == "NAME" } ?: columns.firstOrNull { it.name == "COMM" }
        val argsCol = columns.firstOrNull { it.name == "ARGS" } ?: columns.firstOrNull { it.name == "COMMAND" }
        val out = ArrayList<ProcessEntry>()
        while (lines.hasNext()) {
            val line = lines.next()
            if (line.isBlank()) continue
            val pid = line.substringSafe(pidCol).trim().toIntOrNull() ?: continue
            val nameRaw = nameCol?.let { line.substringSafe(it).trim() } ?: ""
            val args = argsCol?.let { line.substringSafe(it).trim() } ?: ""
            val name = when {
                args.isNotBlank() && !args.startsWith("[") -> args
                nameRaw.isNotBlank() -> nameRaw
                else -> continue
            }
            val cmdline = if (args.isNotBlank()) args else nameRaw
            out.add(
                ProcessEntry(
                    pid = pid,
                    name = name.take(64),
                    cmdline = cmdline.take(160),
                    uid = uidCol?.let { line.substringSafe(it).trim().removePrefix("u").removePrefix("a").toIntOrNull() } ?: -1,
                    rssKb = rssCol?.let { line.substringSafe(it).trim().toLongOrNull() } ?: -1L,
                    appPackage = ProcParsing.extractPackage(cmdline, nameRaw),
                ),
            )
        }
        return out
    }

    // ── 列区间推导 ───────────────────────────────────────────────

    private data class ColumnRange(val name: String, val start: Int, val endExclusive: Int)

    /**
     * 表头形如 `  PID   UID    RSS NAME`——token 的字符区间即数据列区间
     * （ps 右对齐数字列 / 左对齐文本列，均落在表头 token 的区间内；
     * 末列吞到行尾 —— 左对齐文本可能超出表头宽度）。
     */
    internal fun columnRanges(header: String): List<ColumnRange> {
        val ranges = ArrayList<ColumnRange>()
        var i = 0
        val n = header.length
        while (i < n) {
            if (header[i].isWhitespace()) { i++; continue }
            val start = i
            while (i < n && !header[i].isWhitespace()) i++
            ranges += ColumnRange(header.substring(start, i).uppercase(), start, i)
        }
        // 末列扩展到行尾（ARGS 常溢出表头宽度）。
        if (ranges.isNotEmpty()) {
            val last = ranges.removeAt(ranges.size - 1)
            ranges += last.copy(endExclusive = Int.MAX_VALUE)
        }
        return ranges
    }

    private fun String.substringSafe(col: ColumnRange): String {
        val from = col.start.coerceIn(0, length)
        return if (col.endExclusive == Int.MAX_VALUE) substring(from) else substring(from, col.endExclusive.coerceAtMost(length))
    }
}
