package com.apex.agent.core.tools.builtin.context

import com.apex.agent.core.tools.ToolAnnotations
import com.apex.agent.core.tools.ToolArguments
import com.apex.agent.core.tools.ToolCategory
import com.apex.agent.core.tools.ToolErrorCode
import com.apex.agent.core.tools.ToolMetadata
import com.apex.agent.core.tools.ToolResult
import com.apex.agent.core.tools.ToolRisk
import com.apex.agent.core.tools.builtin.BaseTool
import com.apex.agent.core.tools.prompt.PromptRedaction
import com.apex.agent.core.tools.prompt.PromptSnapshot
import com.apex.agent.core.tools.toolSchema
import java.io.File

/**
 * `PromptSectionList` — 本轮系统提示词段清单（P97）。
 *
 * 列出最近一次组装的段 id / token 估算 / 真值源——模型与用户可先看
 * 「这一轮 prompt 长什么样」再决定是否 [PromptDump] 全量导出。
 * 无快照（引擎尚未组装，或 runner 未接线）时返回「未启用」说明而非
 * 报错（fail-open 口径与 CapabilityReport 一致）。
 */
class PromptSectionListTool(
    /** 最近快照（新→旧）数据源（DI 卫星文件接引擎 promptPipeline）。 */
    private val snapshots: () -> List<PromptSnapshot>
) : BaseTool(
    id = "PromptSectionList",
    name = "Prompt Section List",
    description = """
        List the sections of the most recent system prompt assembly: section id,
        token estimate (chars/4) and truth-source class. Use this FIRST to see
        what the current prompt looks like before calling PromptDump for the
        full export. Returns "not enabled" (not an error) when no assembly has
        happened yet.
    """.trimIndent(),
    declaredSchema = toolSchema {}
) {
    override fun buildMetadata(): ToolMetadata = ToolMetadata.meta(id) {
        category(ToolCategory.CONTEXT)
        risk(ToolRisk.LOW)
        tag("context", "prompt", "sections", "introspection")
        annotations(ToolAnnotations.readOnly())
    }

    override suspend fun executeStructured(arguments: String): ToolResult {
        val snapshot = snapshots().firstOrNull()
            ?: return ToolResult.ok("prompt pipeline not enabled yet — no assembly recorded")
        return ToolResult.ok(
            buildString {
                appendLine("line: ${snapshot.line}")
                appendLine("sections: ${snapshot.sections.size} (~${snapshot.sections.sumOf { it.tokenEstimate }} tokens)")
                appendLine("turns: ${snapshot.turns.size} | tools: ${snapshot.toolIds.size} | est total ~${snapshot.tokenEstimate} tokens")
                snapshot.sections.forEach { s ->
                    appendLine("- ${s.id}: ~${s.tokenEstimate} tokens (source: ${s.source})")
                }
            }
        )
    }
}

/**
 * `PromptDump` — 完整输入包结构化导出（P97）。
 *
 * 导出「发给模型的完整输入包」：system prompt（段切分）+ 会话回合 +
 * 工具清单 + token 估算。**脱敏默认开启**（api_key / token /
 * authorization / cookie 等密钥类字段值一律 `***`，`redactions` 只记
 * 字段名不记值）；`persist=true` 时原子写入
 * `<filesDir>/logs/prompt-dumps/<ts>.json`（tmp + renameTo）并经审计
 * 留痕（只记路径与字节数，不记正文）。
 */
class PromptDumpTool(
    /** 最近快照数据源（scope=current/last 同一取数口径）。 */
    private val snapshots: () -> List<PromptSnapshot>,
    /** 持久化目录（null = 本环境不可落盘，persist=true 时返回可操作错误）。 */
    private val dumpDir: () -> File?,
    /** 假钟（测试注入；生产 = System::currentTimeMillis）。 */
    private val clock: () -> Long = System::currentTimeMillis,
    /** 持久化审计留痕（app 侧接 ToolAuditLogger；只传路径与字节数）。 */
    private val audit: (path: String, bytes: Int) -> Unit = { _, _ -> }
) : BaseTool(
    id = "PromptDump",
    name = "Prompt Dump",
    description = """
        Export the COMPLETE input package sent to the model: system prompt
        (sectioned), conversation turns, tool ids and token estimates.
        Arguments: scope (current | last, default current), redact (default
        true — masks api_key/token/authorization/cookie values as ***),
        persist (default false — atomically writes the dump to
        logs/prompt-dumps/<ts>.json when true).
    """.trimIndent(),
    declaredSchema = toolSchema {
        string("scope", description = "current (latest assembly, default) | last (previous assembly)", enumValues = listOf("current", "last"), defaultValue = "current")
        boolean("redact", description = "Mask sensitive key values as *** (default true)", defaultValue = true)
        boolean("persist", description = "Write the dump to logs/prompt-dumps/<ts>.json atomically (default false)", defaultValue = false)
    }
) {
    override fun buildMetadata(): ToolMetadata = ToolMetadata.meta(id) {
        category(ToolCategory.CONTEXT)
        risk(ToolRisk.LOW)
        tag("context", "prompt", "dump", "export", "introspection")
        // persist=true 时落盘文件 = MEDIUM 动作：审计留痕由调用侧（RiskAwareToolGate
        // 依据本元数据恒 LOW + readOnly 判定；persist 写盘另经 audit 钩子记录）。
        annotations(ToolAnnotations.readOnly())
    }

    override suspend fun executeStructured(arguments: String): ToolResult {
        val args = when (val parsed = ToolArguments.of(arguments)) {
            is ToolArguments.ParseOutcome.Ok -> parsed.args
            is ToolArguments.ParseOutcome.Bad -> return parsed.result
        }
        val scope = args.stringWithDefault("scope", "current")
        if (scope !in SCOPES) {
            return ToolResult.invalid("scope", "unknown scope '$scope'", "use current or last")
        }
        val redact = args.booleanWithDefault("redact", true)
        val persist = args.booleanWithDefault("persist", false)

        val recent = snapshots()
        val snapshot = when (scope) {
            "current" -> recent.firstOrNull()
            else -> recent.drop(1).firstOrNull() ?: recent.firstOrNull()
        } ?: return ToolResult.ok(
            "prompt pipeline not enabled yet — no assembly recorded. " +
                "Send a message first, then retry."
        )

        val payload = if (redact) PromptRedaction.redact(snapshot) else snapshot
        val text = render(payload)
        if (!persist) return ToolResult.ok(text)

        val dir = dumpDir()
            ?: return ToolResult.fail(
                ToolErrorCode.EXECUTION_FAILED,
                "persist is not available in this environment (no dump dir) — use redact=true output instead"
            )
        val file = writeAtomically(dir, text)
            ?: return ToolResult.fail(
                ToolErrorCode.EXECUTION_FAILED,
                "failed to write dump file (see logs); non-persisted output above"
            )
        audit(file.absolutePath, text.length)
        return ToolResult.ok("$text\npersisted: ${file.absolutePath} (${text.length} chars)")
    }

    private fun render(s: PromptSnapshot): String = buildString {
        appendLine("=== PromptSnapshot (line=${s.line}) ===")
        appendLine("tokenEstimate: ~${s.tokenEstimate} (systemPrompt + turns, chars/4)")
        appendLine("redactions: ${s.redactions.joinToString(", ").ifEmpty { "none" }}")
        appendLine("toolIds (${s.toolIds.size}): ${s.toolIds.joinToString(", ").ifEmpty { "none" }}")
        appendLine("--- sections (${s.sections.size}) ---")
        s.sections.forEach { sec ->
            appendLine("## [${sec.id}] (~${sec.tokenEstimate} tokens, source: ${sec.source})")
            appendLine(sec.text.trimEnd())
        }
        appendLine("--- turns (${s.turns.size}) ---")
        s.turns.forEach { turn ->
            appendLine("[${turn.kind}] ${turn.content.take(TURN_HEAD_CHARS)}")
        }
        appendLine("--- systemPrompt tail ---")
        appendLine(s.systemPrompt.takeLast(SYSTEM_TAIL_CHARS))
    }

    /** 原子写（tmp + renameTo；rename 失败直写目标兜底并留痕——AGENTS.md L95 纪律）。 */
    private fun writeAtomically(dir: File, text: String): File? {
        return runCatching {
            dir.mkdirs()
            val target = File(dir, "prompt-dump-${clock()}.json")
            val tmp = File(dir, target.name + ".tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(target)) {
                target.writeText(text)
                tmp.delete()
            }
            target
        }.getOrNull()
    }

    private companion object {
        val SCOPES = setOf("current", "last")
        const val TURN_HEAD_CHARS = 200
        const val SYSTEM_TAIL_CHARS = 400
    }
}
