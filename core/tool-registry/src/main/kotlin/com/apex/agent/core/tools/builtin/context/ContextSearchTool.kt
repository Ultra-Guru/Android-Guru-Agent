package com.apex.agent.core.tools.builtin.context

import com.apex.agent.core.tools.ToolAnnotations
import com.apex.agent.core.tools.ToolArguments
import com.apex.agent.core.tools.ToolCategory
import com.apex.agent.core.tools.ToolErrorCode
import com.apex.agent.core.tools.ToolMetadata
import com.apex.agent.core.tools.ToolResult
import com.apex.agent.core.tools.ToolRisk
import com.apex.agent.core.tools.ToolSchema
import com.apex.agent.core.tools.builtin.BaseTool
import com.apex.agent.core.tools.toolSchema
import com.apex.agent.core.tools.util.TextRelevance

/**
 * `ContextSearch` — 会话内容检索（#172 上下文回顾三件套之二）。
 *
 * 回顾摘要回答"大概做了什么"，本工具回答"那句话/那个输出到底在哪"：
 * 大小写不敏感子串搜索全部会话记录，返回命中片段（前后各 80 字符上下文）
 * + `#index [role]` 定位 —— 模型可据此精确引用历史，而不是凭记忆复述。
 *
 * ═══ 上下文检索增强：模糊回退（BM25 相关性排序）═══
 *
 * 子串匹配零命中不再是死路 —— 模型记错一个词（"apikey" vs "api key"、
 * 复述转写）就什么也找不到。零命中时自动降级为相关性检索
 *（TextRelevance，BM25-lite、CJK 感知）：返回与 query 最相关的记录
 *（`~` 前缀标记模糊结果），模型可据此换词逼近。
 * `mode` 参数可强制 exact / fuzzy（默认 auto = 先精确后模糊）。
 *
 * - `query` 必填（空串 INVALID_ARGUMENT）；
 * - `scope`（full|recent，默认 full——找"很久以前说过什么"是主场景）；
 * - `mode`（auto|exact|fuzzy，默认 auto）；
 * - `limit` 默认 10（recent 时取最近 N 条参与搜索，默认 30）；
 * - 零命中且无相关记录返回 NOT_FOUND 回显 query，模型可换词重试。
 */
class ContextSearchTool(
    private val context: SessionContextProvider
) : BaseTool(
    id = "ContextSearch",
    name = "Session Context Search",
    description = """
        Case-insensitive substring search over the current conversation records.
        Input: {"query": "api key", "scope": "full", "limit": 10}
        Returns matching snippets with 80 chars of context on each side plus a
        "#index [role]" locator per hit. scope: full (default) | recent (search
        only the last N=limit-window records, window default 30).
        Use to find an exact earlier statement or tool output instead of
        guessing from memory.
    """.trimIndent(),
    declaredSchema = toolSchema {
        string("query", required = true, description = "Substring to search for (case-insensitive)")
        string("scope", description = "full (whole session, default) | recent (last 'window' records)", enumValues = listOf("full", "recent"), defaultValue = "full")
        string("mode", description = "auto (exact, then fuzzy fallback on zero hits, default) | exact (substring only) | fuzzy (relevance ranking only)", enumValues = listOf("auto", "exact", "fuzzy"), defaultValue = "auto")
        integer("limit", description = "Max hits to return (default 10)", defaultValue = 10, minimum = 1.0, maximum = 50.0)
        integer("window", description = "Recent-scope window size (scope=recent, default 30)", defaultValue = 30, minimum = 1.0, maximum = 500.0)
    }
) {
    override fun buildMetadata(): ToolMetadata = ToolMetadata.meta(id) {
        category(ToolCategory.CONTEXT)
        risk(ToolRisk.LOW)
        tag("context", "search", "session", "find", "locate")
        annotations(ToolAnnotations.readOnly())
    }

    override suspend fun executeStructured(arguments: String): ToolResult {
        val args = when (val parsed = ToolArguments.of(arguments)) {
            is ToolArguments.ParseOutcome.Ok -> parsed.args
            is ToolArguments.ParseOutcome.Bad -> return parsed.result
        }
        val query = args.requireString("query").trim()
        if (query.isEmpty()) {
            return ToolResult.invalid("query", "query must not be empty", "give the substring to search for")
        }
        val scope = args.stringWithDefault("scope", "full")
        if (scope !in SCOPES) {
            return ToolResult.invalid("scope", "unknown scope '$scope'", "use full or recent")
        }
        val mode = args.stringWithDefault("mode", "auto")
        if (mode !in MODES) {
            return ToolResult.invalid("mode", "unknown mode '$mode'", "use auto, exact or fuzzy")
        }
        val limit = args.intWithDefault("limit", DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)
        val window = args.intWithDefault("window", DEFAULT_WINDOW).coerceIn(1, MAX_WINDOW)

        val all = context.records()
        if (all.isEmpty()) {
            return ToolResult.fail(ToolErrorCode.NOT_FOUND, "session context is empty — nothing to search")
        }
        // 回顾类检索跳过 system 提示词（对"找历史"没有信息量且噪声大）。
        val candidates = (if (scope == "recent") all.takeLast(window) else all)
            .filter { it.role != "system" }

        val needle = query.lowercase()

        // ═══ 模糊检索（相关性排序）：mode=fuzzy 直走，或精确零命中后的自动回退 ═══
        if (mode == "fuzzy") {
            val fuzzyOnly = fuzzySearch(query, candidates, limit)
            if (fuzzyOnly != null) return fuzzyOnly
            return ToolResult.fail(
                ToolErrorCode.NOT_FOUND,
                "no record is related to '$query' (${candidates.size} records ranked, scope=$scope)"
            )
        }

        val hits = mutableListOf<String>()
        for (record in candidates) {
            if (hits.size >= limit) break
            val lowered = record.content.lowercase()
            var from = 0
            while (hits.size < limit) {
                val at = lowered.indexOf(needle, from)
                if (at < 0) break
                hits += "#${record.index} [${record.role}] ${snippet(record.content, at, needle.length)}"
                from = at + needle.length
            }
        }

        if (hits.isEmpty()) {
            // 上下文检索增强：精确零命中 → BM25 相关性回退（换词/转写也能找到）。
            // mode=exact 明确只要精确语义 —— 跳过回退，保持旧 NOT_FOUND 行为。
            if (mode != "exact") {
                val fuzzy = fuzzySearch(query, candidates, limit)
                if (fuzzy != null) return fuzzy
            }
            return ToolResult.fail(
                ToolErrorCode.NOT_FOUND,
                "no record contains '$query' (${candidates.size} records searched, scope=$scope)"
            )
        }
        return ToolResult.ok(
            buildString {
                appendLine("Found ${hits.size} hit(s) for '$query' (scope=$scope):")
                hits.forEach { appendLine(it) }
            }.trimEnd()
        )
    }

    /**
     * BM25 相关性检索（TextRelevance）：返回最相关的前 [limit] 条记录，
     * `~` 前缀标记模糊结果并附分数；无相关记录返回 null（调用方回 NOT_FOUND）。
     */
    private fun fuzzySearch(
        query: String,
        candidates: List<ContextRecord>,
        limit: Int
    ): ToolResult? {
        val ranked = TextRelevance.rank(
            query, candidates.map { it.content }, limit = limit
        )
        if (ranked.isEmpty()) return null
        return ToolResult.ok(
            buildString {
                appendLine("No exact substring match — top ${ranked.size} most related record(s) for '$query' (fuzzy, ranked):")
                ranked.forEach { scored ->
                    val record = candidates[scored.index]
                    appendLine(
                        "~#${record.index} [${record.role}] " +
                            "${record.content.replace('\n', '␤').take(SNIPPET_CHARS)}"
                    )
                }
            }.trimEnd()
        )
    }

    /** 命中片段：前后各 [CONTEXT_CHARS] 字符，换行折叠为 ␤ 保持单行可扫读。 */
    private fun snippet(content: String, at: Int, needleLength: Int): String {
        val start = (at - CONTEXT_CHARS).coerceAtLeast(0)
        val end = (at + needleLength + CONTEXT_CHARS).coerceAtMost(content.length)
        val prefix = if (start > 0) "…" else ""
        val suffix = if (end < content.length) "…" else ""
        return (prefix + content.substring(start, end).replace('\n', '␤') + suffix).trim()
    }

    private companion object {
        val SCOPES = setOf("full", "recent")
        val MODES = setOf("auto", "exact", "fuzzy")
        const val DEFAULT_LIMIT = 10
        const val MAX_LIMIT = 50
        const val DEFAULT_WINDOW = 30
        const val MAX_WINDOW = 500
        const val CONTEXT_CHARS = 80

        /** 模糊结果单条最大字符数（相关记录多为长文本，取头部即可）。 */
        const val SNIPPET_CHARS = 200
    }
}
