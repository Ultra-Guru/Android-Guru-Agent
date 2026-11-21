package com.apex.agent.core.tools.builtin.context

import com.apex.agent.core.llm.LlmMessage
import com.apex.agent.core.tools.ToolAnnotations
import com.apex.agent.core.tools.ToolArguments
import com.apex.agent.core.tools.ToolCategory
import com.apex.agent.core.tools.ToolErrorCode
import com.apex.agent.core.tools.ToolMetadata
import com.apex.agent.core.tools.ToolResult
import com.apex.agent.core.tools.ToolRisk
import com.apex.agent.core.tools.builtin.BaseTool
import com.apex.agent.core.tools.toolSchema

/**
 * `ContextFetch` — 会话上下文取原文（#396 上下文自主检索 · 三件套之四）。
 *
 * 回顾（ContextRecap）给全景、检索（ContextSearch）给定位，本工具补齐
 * 最后一环：**按定位符读取单条记录的全文**。检索结果的记录 content 被
 * 2000 字符护栏截断、命中片段只有前后 80 字符——模型定位到 `#12` 或
 * `a#3` 之后，用本工具展开原文；超长记录（数万字符的工具输出）经
 * `offset`/`limit` 分窗阅读，不一次撑爆上下文。
 *
 * 定位符协议（与 [ContextSearchTool] 输出一致）：
 * - `#N` —— 活跃上下文（当前 LlmMessage 序表，检索时的序号）；
 * - `a#N` —— 归档（被压缩裁剪的原始消息，序号稳定）。
 *
 * 数据源：
 * - 活跃源 [liveReader] 直读引擎持久化消息（**不截断**——这正是本工具
 *   存在的意义）；
 * - 归档源 [archive] 可空（未接线时仅活跃源可用，行为向后兼容）。
 */
class ContextFetchTool(
    private val liveReader: () -> List<LlmMessage>,
    private val archive: SessionContextArchive? = null
) : BaseTool(
    id = "ContextFetch",
    name = "Session Context Fetch",
    description = """
        Read the FULL content of one conversation record by its locator.
        Input: {"ref": "#12"} or {"ref": "a#3", "offset": 0, "limit": 4000}
        Locators come from ContextSearch/ContextRecap output: '#N' = live
        context record, 'a#N' = archived (compressed-away) record. Records can
        be tens of thousands of chars — use offset/limit to page through long
        ones (default window 4000 chars). Unlike search snippets this returns
        the untruncated original text, so you can quote earlier user requests
        or tool outputs verbatim instead of guessing from memory.
    """.trimIndent(),
    declaredSchema = toolSchema {
        string(
            "ref", required = true,
            description = "Record locator from ContextSearch/ContextRecap: '#N' (live) or 'a#N' (archived)"
        )
        integer(
            "offset", description = "Char offset into the record content (default 0)",
            defaultValue = 0, minimum = 0.0, maximum = 2_000_000.0
        )
        integer(
            "limit", description = "Max chars of content to return (default 4000)",
            defaultValue = 4000, minimum = 200.0, maximum = 24000.0
        )
    }
) {
    override fun buildMetadata(): ToolMetadata = ToolMetadata.meta(id) {
        category(ToolCategory.CONTEXT)
        risk(ToolRisk.LOW)
        tag("context", "fetch", "session", "read", "verbatim")
        annotations(ToolAnnotations.readOnly())
    }

    override suspend fun executeStructured(arguments: String): ToolResult {
        val args = when (val parsed = ToolArguments.of(arguments)) {
            is ToolArguments.ParseOutcome.Ok -> parsed.args
            is ToolArguments.ParseOutcome.Bad -> return parsed.result
        }
        val ref = args.requireString("ref").trim()
        val (isArchived, index) = parseRef(ref)
            ?: return ToolResult.invalid(
                "ref", "unrecognized locator '$ref'",
                "use '#N' for a live record or 'a#N' for an archived one (as printed by ContextSearch)"
            )
        val offset = args.intWithDefault("offset", 0).coerceAtLeast(0)
        val limit = args.intWithDefault("limit", DEFAULT_WINDOW).coerceIn(MIN_WINDOW, MAX_WINDOW)

        val record = resolve(isArchived, index)
            ?: return ToolResult.fail(
                ToolErrorCode.NOT_FOUND,
                buildString {
                    append("record ")
                    append(if (isArchived) "a#$index" else "#$index")
                    append(" does not exist (")
                    if (isArchived) {
                        append("archive holds ${archive?.size() ?: 0} record(s)")
                    } else {
                        append("live context holds ${liveCount()} record(s)")
                    }
                    append(") — run ContextSearch first to get valid locators")
                }
            )

        val content = record.content
        if (offset > content.length) {
            return ToolResult.fail(
                ToolErrorCode.INVALID_ARGUMENT,
                "offset=$offset is beyond the end of record $ref (length ${content.length})"
            )
        }
        val window = content.substring(offset, (offset + limit).coerceAtMost(content.length))
        return ToolResult.ok(render(ref, record, window, content.length, offset, limit))
    }

    // ── 内部 ─────────────────────────────────────────────────────────

    /** 解析定位符：`#N` / `a#N`（容忍裸 `N` 与前后空白；非法 → null）。 */
    private fun parseRef(ref: String): Pair<Boolean, Int>? {
        val archived = ref.startsWith("a#") || ref.startsWith("A#")
        val digits = ref.removePrefix("a#").removePrefix("A#").removePrefix("#").trim()
        if (digits.isEmpty() || !digits.all { it.isDigit() }) return null
        val index = digits.toIntOrNull() ?: return null
        if (index < 0) return null
        return archived to index
    }

    /** 归档记录全文直取；活跃记录绕过 2000 截断护栏直读 LlmMessage。 */
    private fun resolve(isArchived: Boolean, index: Int): ContextRecord? {
        if (isArchived) {
            val archived = archive?.archivedRecords().orEmpty()
            return archived.getOrNull(index)
        }
        val live = runCatching { liveReader() }.getOrDefault(emptyList())
        return toArchivedRecords(live).getOrNull(index)
    }

    private fun liveCount(): Int = runCatching { liveReader().size }.getOrDefault(0)

    private fun render(
        ref: String,
        record: ContextRecord,
        window: String,
        totalLength: Int,
        offset: Int,
        limit: Int
    ): String = buildString {
        appendLine("Record $ref [${record.role}] — $totalLength chars total, showing $offset..${offset + window.length}:")
        append(window)
        val end = offset + window.length
        if (end < totalLength) {
            appendLine()
            appendLine("[${totalLength - end} more chars — call ContextFetch with ref=\"$ref\", offset=$end to continue]")
        }
    }

    private companion object {
        const val DEFAULT_WINDOW = 4000
        const val MIN_WINDOW = 200
        const val MAX_WINDOW = 24000
    }
}
