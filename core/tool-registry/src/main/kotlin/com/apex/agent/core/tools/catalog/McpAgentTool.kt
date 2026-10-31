package com.apex.agent.core.tools.catalog

import com.apex.agent.core.tools.AgentTool
import com.apex.agent.core.tools.ToolAnnotations
import com.apex.agent.core.tools.ToolCategory
import com.apex.agent.core.tools.ToolMetadata
import com.apex.agent.core.tools.ToolRisk
import com.apex.agent.core.tools.mcp.McpManager
import com.apex.agent.core.tools.mcp.McpToolDef
import kotlinx.coroutines.CancellationException

/**
 * # Tool System v4 — MCP tools as first-class functions
 *
 * Before v4 the model could only reach MCP servers through the
 * `McpCall(server, tool, args)` proxy — it had to *know* remote tool names
 * with no schemas, which is exactly why "MCP 没什么用". v4 registers every
 * discovered remote tool as a real [AgentTool]:
 *
 * - registry id: `mcp__{server}__{tool}` (see [McpToolNaming]) — collision
 *   free across servers, and the dots/illegal chars never leave the app
 *   because [ToolNameSanitizer] maps ids to provider-safe names;
 * - schema: the remote `inputSchema`, repaired by [ToolSchemaSanitizer];
 * - execution: routed to [McpManager.callTool]; results are clamped so a
 *   chatty server cannot blow up the context window;
 * - metadata: MCP category, MEDIUM risk (unknown remote capability),
 *   non-idempotent & non-retry-safe — the v3 run policy never blind-retries
 *   a remote side effect (rikkahub-agent gates *all* MCP calls behind
 *   approval; our v3 risk gate covers the same surface via MEDIUM/HIGH);
 * - scope: v3 服务器级工位作用域（McpServerConfig.scope，"agent" |
 *   "coding" | "all"）由 [McpToolRegistrar] 注册时从配置表读入并打进
 *   [ToolMetadata] —— 与市场 tier 同源字段，引擎计划层据此做双工位
 *   隔离（缺口 A：scope 此前只被市场 UI 消费，引擎侧不消费）。
 */
class McpAgentTool(
    private val manager: McpManager,
    private val serverName: String,
    private val toolDef: McpToolDef,
    /**
     * v3 工位作用域（服务器级；默认 all = 双工位可见，兼容旧调用点）。
     * 命名避开 [ToolMetadata.Builder] 的同名私有字段，防止元数据
     * lambda 内引用它时的名字解析歧义。
     */
    private val serverScope: String = "all"
) : AgentTool {

    override val id: String = McpToolNaming.toolId(serverName, toolDef.name)
    override val name: String = "MCP $serverName/${toolDef.name}"
    override val description: String = buildString {
        append("MCP tool '${toolDef.name}' from server '$serverName'. ")
        append(toolDef.description.trim().ifEmpty { "No description provided by the server." })
    }.let { ToolSchemaSanitizer.clampDescription(it) }

    override val parametersSchema: String =
        ToolSchemaSanitizer.sanitize(
            toolDef.inputSchema.ifBlank { """{"type":"object","properties":{}}""" }
        )

    override val metadata: ToolMetadata = ToolMetadata.meta(id) {
        category(ToolCategory.MCP)
        risk(ToolRisk.MEDIUM)
        tag("mcp", "server:$serverName")
        // v3 作用域打标：与市场 tier 同源，供引擎计划层隔离；脏值折叠 all。
        scope(ToolMetadata.normalizeScope(serverScope))
        annotations(
            ToolAnnotations(
                readOnlyHint = false,
                destructiveHint = false,
                idempotentHint = false,
                openWorldHint = true
            )
        )
    }

    override suspend fun execute(arguments: String): String {
        return try {
            val result = manager.callTool(serverName, toolDef.name, arguments)
            result.fold(
                onSuccess = { r ->
                    when {
                        // "Error:" prefix matters: the engine's success
                        // detection is string-protocol based — a bare
                        // "MCP error from ..." used to be counted as
                        // SUCCESS, hiding the failure from the model and
                        // the recovery prompt entirely.
                        r.isError -> renderServerError(serverName, toolDef.name, clamp(r.content))
                        else -> clamp(r.content.ifBlank { "(empty result)" })
                    }
                },
                onFailure = { e ->
                    renderTransportFailure(
                        serverName = serverName,
                        toolName = toolDef.name,
                        detail = e.message ?: e::class.simpleName ?: "unknown error"
                    )
                }
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            renderTransportFailure(
                serverName = serverName,
                toolName = toolDef.name,
                detail = e.message ?: e::class.simpleName ?: "unknown error"
            )
        }
    }

    private fun clamp(content: String, max: Int = MAX_RESULT_CHARS): String =
        if (content.length <= max) content
        else content.take(max) + "\n[MCP result truncated at $MAX_RESULT_CHARS chars]"

    internal companion object {

        const val MAX_RESULT_CHARS = 16_000

        /**
         * Server-reported failure (isError=true). "Error:"-prefixed so the
         * string-protocol success detection classifies it as a failure,
         * plus change-of-approach guidance: the task is NOT aborted — the
         * model must pick a different path instead of repeating the call.
         */
        fun renderServerError(serverName: String, toolName: String, content: String): String =
            "Error: MCP server '$serverName/$toolName' reported a failure: $content\n" +
                FALLBACK_GUIDANCE

        /**
         * Transport/exception failure (disconnect, timeout, crash).
         * Same "Error:" protocol + explicit reconnect hint + fallback
         * guidance.
         */
        fun renderTransportFailure(serverName: String, toolName: String, detail: String): String =
            "Error: MCP tool '$serverName/$toolName' failed: $detail. " +
                "If the server disconnected, call McpConnect('$serverName') once and retry.\n" +
                FALLBACK_GUIDANCE

        /**
         * The user-spec "MCP tools fail -> try a different approach, the
         * task continues" contract, spelled out for the model. The engine
         * loop feeds this back as the tool result; the model reads it and
         * switches strategy instead of the task dying.
         */
        private const val FALLBACK_GUIDANCE =
            "[FALLBACK] This MCP tool is currently unavailable. The task is NOT aborted - " +
                "continue with a different approach: (a) use a built-in tool covering the " +
                "same need, (b) use another connected MCP server (McpList), or (c) complete " +
                "the step manually and state the limitation. Do not repeat this identical call."
    }
}

/**
 * Registry-id scheme for MCP tools: `Mcp{Server}{Tool}`（无分隔符 PascalCase）。
 *
 * 服务器名与工具名都归一为"逐段首字母大写连写"（`my-server` → `MyServer`，
 * `create_issue` → `CreateIssue`）——v5 命名改造要求工具名里不出现 `-` 与
 * `_`，而 `Mcp` 前缀天然把一等 MCP 工具与内置工具分族（ToolKind 徽章、
 * 作用域打标、`Mcp` 类别推断都靠它）。
 *
 * 归一化仍保留 rikkahub 的教训：命名空间基于**归一后**的服务器 slug，"My
 * Server" 与 "my-server" 收敛到同一个前缀，不会各注册一套同名工具。
 *
 * 极端情况下 (server="ab", tool="c") 与 (server="a", tool="bc") 会撞名 ——
 * 这与 v4 的 `mcp__ab__c` / `mcp__a__bc` 一样属于"服务器自己起名撞车"，
 * 由 [ToolRegistrar][McpToolRegistrar] 的 REPLACE 语义与
 * [ToolNameSanitizer] 的确定性去重兜底。
 */
object McpToolNaming {

    private val NON_ALNUM = Regex("[^A-Za-z0-9]")

    /** Provider/registry id for a server+tool pair. */
    fun toolId(serverName: String, toolName: String): String =
        "Mcp" + slug(serverName) + slug(toolName)

    /** Normalize an arbitrary server/tool name into a PascalCase segment. */
    fun slug(raw: String): String =
        raw.split(NON_ALNUM)
            .filter { it.isNotEmpty() }
            .joinToString("") { it.replaceFirstChar { ch -> ch.uppercaseChar() } }
            .ifEmpty { "Srv" }
}
