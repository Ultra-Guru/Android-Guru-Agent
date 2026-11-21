package com.apex.agent.core.tools.builtin

import com.apex.agent.core.tools.AgentTool
import com.apex.agent.core.tools.ToolMetadata
import com.apex.agent.core.tools.mcp.McpCapabilities
import com.apex.agent.core.tools.mcp.McpManager
import com.apex.agent.core.tools.mcp.McpServerConfig
import com.apex.agent.core.tools.mcp.McpTransport
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * MCP工具调用
 * Agent通过此工具调用MCP服务器提供的工具
 */
class McpCallTool(
    private val mcpManager: McpManager
) : AgentTool {

    override val id = "McpCall"
    override val name = "Call MCP Tool"
    override val description = """
        Call a tool provided by a connected MCP server.
        MCP servers extend your capabilities with external tools.
        Use McpList to see available servers and tools.

        Examples:
        - {"server": "github", "tool": "create_issue", "arguments": "{\"title\": \"Bug fix\", \"repo\": \"user/repo\"}"}
        - {"server": "database", "tool": "query", "arguments": "{\"sql\": \"SELECT * FROM users\"}"}
    """.trimIndent()

    override val parametersSchema = """
        {
            "type": "object",
            "properties": {
                "server": {"type": "string", "description": "MCP server name"},
                "tool": {"type": "string", "description": "Tool name on the server"},
                "arguments": {"type": "string", "description": "JSON arguments for the tool"}
            },
            "required": ["server", "tool"]
        }
    """.trimIndent()

    override suspend fun execute(arguments: String): String {
        val json = Json.parseToJsonElement(arguments).jsonObject
        val server = json["server"]?.jsonPrimitive?.content ?: return "Error: 'server' required"
        val tool = json["tool"]?.jsonPrimitive?.content ?: return "Error: 'tool' required"
        val toolArgs = json["arguments"]?.jsonPrimitive?.content ?: "{}"

        val result = mcpManager.callTool(server, tool, toolArgs)
        return result.fold(
            onSuccess = { r ->
                // v6 错误协议对齐：isError 也走 "Error:" 前缀（引擎字符串级
                // 成败判定此前把服务端报错当成功）+ 换路指引（任务不停止）
                if (r.isError) {
                    "Error: MCP server '$server/$tool' reported a failure: ${r.content}\n" +
                        FALLBACK_GUIDANCE
                } else r.content
            },
            onFailure = { e ->
                "Error: MCP tool '$server/$tool' failed: ${e.message ?: "unknown error"}. " +
                    "If the server disconnected, call McpConnect('$server') once and retry.\n" +
                    FALLBACK_GUIDANCE
            }
        )
    }

    private companion object {
        /** 与 McpAgentTool 一致的换路指引（用户规格：MCP 失败换方式继续任务）。 */
        private const val FALLBACK_GUIDANCE =
            "[FALLBACK] This MCP tool is currently unavailable. The task is NOT aborted - " +
                "continue with a different approach: (a) use a built-in tool covering the " +
                "same need, (b) use another connected MCP server (McpList), or (c) complete " +
                "the step manually and state the limitation. Do not repeat this identical call."
    }
}

/**
 * MCP服务器/工具列表
 */
class McpListTool(
    private val mcpManager: McpManager
) : AgentTool {

    override val id = "McpList"
    override val name = "List MCP Servers"
    override val description = """
        List connected MCP servers and their available tools.
        Use this to discover what MCP capabilities are available.
    """.trimIndent()

    override val parametersSchema = """
        {"type": "object", "properties": {}, "required": []}
    """.trimIndent()

    override suspend fun execute(arguments: String): String {
        val servers = mcpManager.getConnectedServers()
        if (servers.isEmpty()) {
            return "No MCP servers connected. Use McpConnect to connect one."
        }

        // v3：随清单展示服务器级工位作用域（模型可见，不做过滤——引擎
        // 计划层已强制隔离）。读取与 McpToolRegistrar 同源的配置字段，
        // 非法值按 all 折叠展示（与实际过滤行为一致）。
        val scopeByName = mcpManager.getConfigs().associate { it.name to it.scope }
        val tools = mcpManager.getAllTools()
        return buildString {
            appendLine("🔌 Connected MCP servers (${servers.size}):")
            servers.forEach { server ->
                val scope = ToolMetadata.normalizeScope(scopeByName[server])
                val visibilityHint = when (scope) {
                    "agent" -> " (Coding 屏不可见)"
                    "coding" -> " (Agent 屏不可见)"
                    else -> ""
                }
                appendLine("  • $server")
                appendLine("    scope: $scope$visibilityHint")
            }
            appendLine()
            appendLine("Available tools (${tools.size}):")
            tools.forEach { t ->
                appendLine("  🔧 ${t.name}: ${t.description.take(80)}")
            }
        }
    }
}

/**
 * MCP连接工具
 */
class McpConnectTool(
    private val mcpManager: McpManager
) : AgentTool {

    override val id = "McpConnect"
    override val name = "Connect MCP Server"
    override val description = """
        Connect to an MCP tool source. Once connected, its tools become available via McpCall.

        MCP is NOT always a server: the most common form in the wild is a LOCAL COMMAND
        (stdio transport) that speaks JSON-RPC over stdin/stdout. Give `command` for that
        form, or `url` for a remote endpoint.

        RECONNECT: to reconnect an existing (disconnected) server, pass ONLY the name -
        e.g. {"name": "github"} - and its stored configuration will be reused.

        Examples:
        - {"name": "github", "url": "http://localhost:3000/mcp"}
        - {"name": "local_db", "url": "http://127.0.0.1:8080/mcp"}
        - {"name": "memory", "command": "npx", "args": ["-y", "@modelcontextprotocol/server-memory"], "run_in_sandbox": true}
        - {"name": "github"}  (reconnect using stored config)
    """.trimIndent()

    override val parametersSchema = """
        {
            "type": "object",
            "properties": {
                "name": {"type": "string", "description": "Tool source name identifier"},
                "url": {"type": "string", "description": "Remote MCP endpoint (http/sse transport)"},
                "transport": {"type": "string", "enum": ["http", "sse", "stdio"], "description": "Transport type; inferred when omitted"},
                "command": {"type": "string", "description": "Local executable for stdio transport"},
                "args": {"type": "array", "items": {"type": "string"}, "description": "Arguments for `command`"},
                "env": {"type": "object", "description": "Environment variables for `command`"},
                "run_in_sandbox": {"type": "boolean", "description": "Run the stdio command inside the PRoot Ubuntu sandbox (recommended for stdio on Android - the host has no npx/node; requires the embedded Ubuntu environment)"}
            },
            "required": ["name"]
        }
    """.trimIndent()

    override suspend fun execute(arguments: String): String {
        val json = Json.parseToJsonElement(arguments).jsonObject
        val name = json["name"]?.jsonPrimitive?.content ?: return "Error: 'name' required"
        val url = json["url"]?.jsonPrimitive?.content.orEmpty()
        val command = json["command"]?.jsonPrimitive?.content.orEmpty()
        val rawTransport = json["transport"]?.jsonPrimitive?.content.orEmpty()
        // Issue #163 + v5 修复：严格 Boolean（缺失/非布尔落默认值）。
        // 默认改为 true —— Android 宿主没有 node/npx，宿主 fork 只会得到
        // ENOENT；显式传 false 才走宿主（桌面/自备运行时的场景）。
        val runInSandbox = json["run_in_sandbox"]?.jsonPrimitive?.booleanOrNull ?: true
        // #344：run_in_sandbox 是否由调用方显式提供（重连一致性比对时，
        // 未提供则不参与冲突判定，避免默认值把既有配置误判成冲突）。
        val runInSandboxExplicit = json.containsKey("run_in_sandbox")

        val args = json["args"]?.jsonArray?.mapNotNull {
            runCatching { it.jsonPrimitive.content }.getOrNull()
        }.orEmpty()
        val env = json["env"]?.jsonObject?.mapValues { (_, v) ->
            runCatching { v.jsonPrimitive.content }.getOrDefault("")
        }.orEmpty()

        // ── #344 P2 修复（重连闭环）────────────────────────────────
        // mcp_call 失败时提示模型 "If the server disconnected, call
        // McpConnect('<server>') once and retry"，但旧实现对已存在同名配置
        // 一概拒绝（"already exists"）—— 服务器掉线/Supervisor 放弃重连后，
        // 按指引操作必然碰壁，自愈闭环断裂。
        // 现在分三支：
        // a) 纯重连形态（只给 name，其余连接参数全空）→ 跳过一切校验，
        //    直接对既有存储配置 connect（这也必须在 buildMcpConnectConfig 的
        //    url/command 必填校验之前，否则纯重连会被先拒）；
        // b) 给了参数且与既有配置一致 → 幂等重连（不重写配置）；
        // c) 给了参数且不一致 → 拒绝（P3 同名覆盖保护：用户配置不可被模型
        //    无声改写），错误文案里明确指引纯重连用法。
        val existing = mcpManager.getConfigs().firstOrNull { it.name == name }
        val pureReconnect = url.isBlank() && command.isBlank() && rawTransport.isBlank() &&
            args.isEmpty() && env.isEmpty()
        if (existing != null && pureReconnect) {
            return renderConnectResult(existing, mcpManager.connect(name), reconnected = true)
        }

        // 参数 → 配置的判定/校验/构造全部在纯函数里（Issue #163 抽出，
        // 单测直测不需要 stub 整个 McpManager）。
        val config = when (
            val outcome = buildMcpConnectConfig(
                name, url, rawTransport, command, args, env, runInSandbox
            )
        ) {
            is McpConnectConfigOutcome.Invalid -> return "Error: ${outcome.reason}"
            is McpConnectConfigOutcome.Ok -> outcome.config
        }

        if (existing != null) {
            // b/c 分支：同名已存在 —— 参数一致才允许幂等重连，不一致如实拒绝。
            val consistent = existing.transport == config.transport &&
                existing.url == config.url &&
                existing.command == config.command &&
                existing.args == config.args &&
                existing.env == config.env &&
                (!runInSandboxExplicit || existing.runInSandbox == config.runInSandbox)
            if (!consistent) {
                return "Error: invalid argument: an MCP server named '${config.name}' already " +
                    "exists with different settings; user configuration cannot be silently " +
                    "rewritten. To reconnect it as-is, call McpConnect('${config.name}') with " +
                    "no url/command arguments. To register a new source, choose a different " +
                    "name or ask the user to edit/remove the existing entry in the market's " +
                    "installed tab."
            }
            // b：幂等重连 —— 不重写用户配置，直接对既有配置 connect。
            return renderConnectResult(existing, mcpManager.connect(name), reconnected = true)
        }

        mcpManager.addServer(config)
        return renderConnectResult(config, mcpManager.connect(name), reconnected = false)
    }

    /**
     * #344：连接结果的统一渲染（新建/重连两路共用；重连路明确标注"未改写配置"，
     * 让模型知道这是既有配置的复活而不是新安装）。
     */
    private fun renderConnectResult(
        config: McpServerConfig,
        result: Result<McpCapabilities>,
        reconnected: Boolean
    ): String = result.fold(
        onSuccess = { caps ->
            buildString {
                if (reconnected) {
                    appendLine("✅ Reconnected to MCP tool source '${config.name}' (existing configuration, nothing rewritten)")
                } else {
                    appendLine("✅ Connected to MCP tool source '${config.name}'")
                }
                appendLine("  Transport: ${config.transport.name}")
                appendLine(
                    if (config.transport == McpTransport.STDIO) "  Command: ${config.endpointSummary()}"
                    else "  URL: ${config.url}"
                )
                if (config.transport == McpTransport.STDIO) {
                    appendLine(
                        if (config.runInSandbox) "  Sandbox: PRoot Ubuntu"
                        else "  Sandbox: host process"
                    )
                }
                appendLine("  Capabilities:")
                appendLine("    Tools: ${if (caps.tools) "✅" else "❌"}")
                appendLine("    Resources: ${if (caps.resources) "✅" else "❌"}")
                appendLine()
                appendLine("Use McpList to see available tools.")
            }
        },
        onFailure = { e -> "❌ Connection failed: ${e.message}" }
    )
}

/** [buildMcpConnectConfig] 的结果：Ok 携带配置，Invalid 携带给用户的错误原因。 */
internal sealed interface McpConnectConfigOutcome {
    data class Ok(val config: McpServerConfig) : McpConnectConfigOutcome
    data class Invalid(val reason: String) : McpConnectConfigOutcome
}

/**
 * McpConnect 的「参数 → McpServerConfig」纯函数（Issue #163 抽出）。
 *
 * 传输判定与校验语义与抽取前完全一致（显式 transport > command → stdio >
 * url → http/sse；stdio 要求 command 非空，远端要求 url 非空），只是把
 * 可测逻辑从依赖 McpManager 的 execute 里提出来 —— 单测可以直接断言
 * `run_in_sandbox` 透传等字段，不需要 stub 整个管理器。
 */
internal fun buildMcpConnectConfig(
    name: String,
    url: String,
    rawTransport: String,
    command: String,
    args: List<String>,
    env: Map<String, String>,
    runInSandbox: Boolean
): McpConnectConfigOutcome {
    val transport = when {
        rawTransport.equals("stdio", ignoreCase = true) -> McpTransport.STDIO
        rawTransport.equals("sse", ignoreCase = true) -> McpTransport.SSE
        rawTransport.equals("http", ignoreCase = true) -> McpTransport.HTTP
        command.isNotBlank() -> McpTransport.STDIO
        else -> McpTransport.HTTP
    }

    if (transport == McpTransport.STDIO && command.isBlank()) {
        return McpConnectConfigOutcome.Invalid("stdio transport requires 'command'")
    }
    if (transport != McpTransport.STDIO && url.isBlank()) {
        return McpConnectConfigOutcome.Invalid("'url' required for ${transport.name} transport")
    }

    return McpConnectConfigOutcome.Ok(
        McpServerConfig(
            name = name,
            url = url,
            transport = transport,
            command = command.takeIf { it.isNotBlank() },
            args = args,
            env = env,
            // Issue #163：Android 上 STDIO 建议开启 —— 宿主没有 npx/node，
            // 开启后命令在 PRoot Ubuntu 沙箱内执行（未装环境时连接会得到
            // 引导性报错，而不是晦涩的 execve 失败）
            runInSandbox = runInSandbox
        )
    )
}
