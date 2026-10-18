package com.apex.agent.core.tools.builtin

import com.apex.agent.core.tools.ToolAnnotations
import com.apex.agent.core.tools.ToolArguments
import com.apex.agent.core.tools.ToolCategory
import com.apex.agent.core.tools.ToolErrorCode
import com.apex.agent.core.tools.ToolMetadata
import com.apex.agent.core.tools.ToolResult
import com.apex.agent.core.tools.ToolRisk
import com.apex.agent.core.tools.toolSchema
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * # App Control Tools（用户诉求：给 Agent 真实能力，而非提示词注入）
 *
 * 用户原始诉求：「模型是可以有能力帮我配置模型和等等，能帮我打开设置，
 * 这些类似能力不能用提示词注入，要给 agent 工具，是真实有能力会分析问题」。
 *
 * 四个工具全部是**真实能力**（有宿主侧实现，非提示词约定）：
 * - [AppScreenOpenTool] —— 打开本应用的页面（设置/诊断/市场/终端/…），
 *   经宿主注入的导航桥真实切换 UI 焦点；
 * - [ModelProfileUpdateTool] —— 修改模型档案字段（model_id / temperature /
 *   reasoning_effort / base_url …），经 [ModelProfileUpdateHost] 落盘；
 * - [ModelConfigImportTool] —— 导入多语言配置文本（JSON/Python/YAML/TOML）
 *   建 Provider + 档案，与设置页「导入」弹窗走同一份识别代码；
 * - [SystemDiagnoseTool] —— 采集 MCP / Skills / 工具调用审计（含失败详情）
 *   的结构化快照，供 agent 真实分析问题根因。
 *
 * 工具本体（本文件）只做参数解析与枚举校验（纯 JVM 可单测）；真实副作用
 * 由宿主（app 侧 [com.apex.agent.tools.AppControlHosts]）经 host 接口注入。
 *
 * 安全契约：
 * - [AppScreenOpenTool] / [SystemDiagnoseTool]：只读 / 纯 UI 导航，LOW 风险；
 * - [ModelProfileUpdateTool] / [ModelConfigImportTool]：改配置 / 建档案，
 *   MEDIUM 风险，走风险门一次确认；API Key 永不经这两个工具传递（密钥
 *   写入仍由专门的 AgentProviderSetKey 工具负责，HIGH 风险会话级确认）。
 */

/** 高频复用的宽松 JSON（未知键忽略 —— host 返回的 JSON 结构演进不炸工具）。 */
internal val AppControlJson: Json = Json { ignoreUnknownKeys = true }

// ═══════════════════════════════════════════════════════════════════════════════
// Host 接口（app 侧实现注入）
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * 应用页面导航宿主 —— [AppScreenOpenTool] 的真实能力锚点。
 *
 * `openScreen` 返回 true = 路由已知且导航请求已入队；false = 未知路由。
 * 实现方（AppNavigationBridge）负责把请求投递到 UI 层执行压栈导航。
 */
fun interface AppScreenHost {
    fun openScreen(route: String, section: String?): Boolean
}

/**
 * 模型档案更新宿主 —— [ModelProfileUpdateTool] 的落盘锚点。
 *
 * `patchJson` 是白名单字段的 JSON 对象（键 = 可改字段名，值 = 新值）；
 * 返回更新后的档案摘要 JSON；档案不存在抛 [IllegalArgumentException]。
 */
fun interface ModelProfileUpdateHost {
    suspend fun update(profileId: String, patchJson: String): String
}

/**
 * 模型配置导入宿主 —— [ModelConfigImportTool] 的落盘锚点。
 *
 * 返回导入摘要 JSON（count / firstProfileId / providerName）；解析失败
 * 抛 [Exception]（message 面向 LLM，英文即可）。
 */
fun interface ModelConfigImportHost {
    suspend fun import(configText: String, format: String): String
}

/**
 * 系统诊断快照宿主 —— [SystemDiagnoseTool] 的数据锚点。
 *
 * 返回结构化 JSON：`{ mcp: [...], skills: [...], toolCalls: [...],
 * failures: [...] }`（failures 含 command + detail 字段供根因分析）。
 */
fun interface SystemDiagnosticsHost {
    suspend fun diagnose(includeToolCalls: Boolean, toolCallLimit: Int): String
}

// ═══════════════════════════════════════════════════════════════════════════════
// 工具 1: AppScreenOpen
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * `AppScreenOpen` —— 打开本应用的指定页面（真实导航，非提示词）。
 *
 * 典型场景：用户说「帮我打开设置」「带我去诊断页看看」——agent 调用本
 * 工具直接把页面推到用户眼前，再配合 ModelProfileUpdate 等工具边看边改。
 *
 * 风险 LOW：纯 UI 导航，不改任何数据；返回值描述导航结果。
 */
class AppScreenOpenTool(
    private val host: AppScreenHost
) : BaseTool(
    id = "AppScreenOpen",
    name = "Open App Screen",
    description = """
        Open a screen of THIS app for the user (real navigation, not a hint).
        Use it when the user asks you to open settings, diagnostics, the
        marketplace, the terminal, storage, usage, vault, and so on — for
        example "帮我打开设置" or "take me to diagnostics".
        The Settings screen lands on the Models tab by default, which is
        where model profiles are configured.
        Arguments:
          - screen (required): settings|diagnostics|market|terminal|browser|
            storage|usage|vault|log|tasks|memory|permissions|templates|about
          - section (optional): for screen=settings only — models|agent|interface
            (visual hint for the user; navigation is the same)
        Returns OK once the navigation request is queued; the screen switch
        happens on the user's device immediately.
    """.trimIndent(),
    declaredSchema = toolSchema {
        string(
            "screen",
            required = true,
            description = "Target screen route of this app",
            enumValues = SUPPORTED_SCREENS
        )
        string(
            "section",
            description = "Optional section hint for the settings screen: models|agent|interface"
        )
    }
) {
    override fun buildMetadata(): ToolMetadata = ToolMetadata.meta(id) {
        category(ToolCategory.AGENT)
        risk(ToolRisk.LOW)
        tag("agent", "navigation", "ui", "screen")
        annotations(ToolAnnotations.readOnly())
    }

    override suspend fun executeStructured(arguments: String): ToolResult {
        val args = when (val parsed = ToolArguments.of(arguments)) {
            is ToolArguments.ParseOutcome.Ok -> parsed.args
            is ToolArguments.ParseOutcome.Bad -> return parsed.result
        }
        val screen = args.requireString("screen").trim().lowercase()
        if (screen !in SUPPORTED_SCREENS) {
            return ToolResult.invalid(
                field = "screen",
                message = "unknown screen '$screen'",
                suggestion = "use one of: ${SUPPORTED_SCREENS.joinToString(", ")}"
            )
        }
        val section = args.optionalString("section")?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        if (section != null && section !in SUPPORTED_SECTIONS) {
            return ToolResult.invalid(
                field = "section",
                message = "unknown section '$section'",
                suggestion = "use one of: ${SUPPORTED_SECTIONS.joinToString(", ")} (settings screen only)"
            )
        }
        val ok = host.openScreen(screen, section)
        return if (ok) {
            ToolResult.ok(
                "OK: navigation request queued — opening '$screen'" +
                    (section?.let { " (section: $it)" } ?: "") +
                    ". Tell the user the screen is now in front of them; " +
                    "you can keep helping via other tools while they look at it."
            )
        } else {
            ToolResult.fail(
                ToolErrorCode.EXECUTION_FAILED,
                "navigation bridge rejected route '$screen' (unknown destination)"
            )
        }
    }

    companion object {
        /** 与 app 层 DrawerDestination.route 一一对应（白名单，防任意路由注入）。 */
        val SUPPORTED_SCREENS: List<String> = listOf(
            "settings", "diagnostics", "market", "terminal", "browser",
            "storage", "usage", "vault", "log", "tasks", "memory",
            "permissions", "templates", "about"
        )

        /** 设置页三页签（导航提示用；页面本身默认落在 Models 页签）。 */
        val SUPPORTED_SECTIONS: List<String> = listOf("models", "agent", "interface")
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// 工具 2: ModelProfileUpdate
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * `ModelProfileUpdate` —— 修改模型档案的可编辑字段（真实落盘）。
 *
 * 用户诉求「模型可以有能力帮我配置模型」的写侧闭环：list（已有
 * AgentProfileList）→ update（本工具）→ set-default（已有）→ set-key
 * （已有 AgentProviderSetKey，HIGH 风险）。
 *
 * 字段白名单（防越权改 providerId 等结构性字段）：
 * name / model_id / base_url / temperature / max_output_tokens /
 * context_window / reasoning_effort / thinking_budget / system_prompt_prefix /
 * enable_tools / web_search。
 *
 * 风险 MEDIUM：改运行时行为，走风险门一次确认。API Key 不在本工具面。
 */
class ModelProfileUpdateTool(
    private val host: ModelProfileUpdateHost
) : BaseTool(
    id = "ModelProfileUpdate",
    name = "Update Model Profile",
    description = """
        Update editable fields of an existing model profile (persisted for
        real — the same store the Settings page writes to). Use it when the
        user asks you to configure a model: change the model id, point the
        provider to a different base URL, tune temperature or the reasoning
        effort, set the context window, and so on.
        First call AgentProfileList to get profile ids and current values.
        Whitelisted fields (all optional, at least one required):
          - name (display name)
          - model_id (e.g. "gpt-4o-mini", "glm-4.7")
          - base_url (provider endpoint, e.g. "https://api.example.com/v1")
          - temperature (0..2), max_output_tokens (256..131072)
          - context_window (4096..4000000)
          - reasoning_effort (none|low|medium|high|max)
          - thinking_budget (0..32768, 0 = disabled)
          - system_prompt_prefix (string, prepended to the system prompt)
          - enable_tools (true|false), web_search (true|false)
        API keys are NOT handled here — use AgentProviderSetKey for that.
    """.trimIndent(),
    declaredSchema = toolSchema {
        string("profile_id", required = true, description = "Profile id from AgentProfileList")
        string("name", description = "New display name")
        string("model_id", description = "New model id (e.g. gpt-4o-mini)")
        string("base_url", description = "New provider base URL (https://…)")
        number("temperature", description = "Sampling temperature (0..2)", minimum = 0.0, maximum = 2.0)
        integer("max_output_tokens", description = "Max output tokens (256..131072)",
            minimum = 256.0, maximum = 131_072.0)
        integer("context_window", description = "Context window tokens (4096..4000000)",
            minimum = 4096.0, maximum = 4_000_000.0)
        string("reasoning_effort", description = "none|low|medium|high|max",
            enumValues = listOf("none", "low", "medium", "high", "max"))
        integer("thinking_budget", description = "Thinking budget tokens (0..32768, 0 = off)",
            minimum = 0.0, maximum = 32_768.0)
        string("system_prompt_prefix", description = "Text prepended to the system prompt")
        boolean("enable_tools", description = "Enable tool calling for this profile")
        boolean("web_search", description = "Enable web search for this profile")
    }
) {
    override fun buildMetadata(): ToolMetadata = ToolMetadata.meta(id) {
        category(ToolCategory.AGENT)
        risk(ToolRisk.MEDIUM)
        tag("agent", "model", "profile", "config")
        annotations(ToolAnnotations.idempotentWrite())
    }

    override suspend fun executeStructured(arguments: String): ToolResult {
        val args = when (val parsed = ToolArguments.of(arguments)) {
            is ToolArguments.ParseOutcome.Ok -> parsed.args
            is ToolArguments.ParseOutcome.Bad -> return parsed.result
        }
        val profileId = args.requireString("profile_id").trim()
        // 白名单字段重组为 host patch JSON（未知键自然丢弃）
        val patch = buildJsonObject {
            args.optionalString("name")?.let { put("name", it) }
            args.optionalString("model_id")?.let { put("modelId", it) }
            args.optionalString("base_url")?.let { put("baseUrl", it) }
            args.optionalDouble("temperature")?.let { put("temperature", it) }
            args.optionalInt("max_output_tokens")?.let { put("maxOutputTokens", it) }
            args.optionalInt("context_window")?.let { put("contextWindow", it) }
            args.optionalString("reasoning_effort")?.let { put("reasoningEffort", it) }
            args.optionalInt("thinking_budget")?.let { put("thinkingBudget", it) }
            args.optionalString("system_prompt_prefix")?.let { put("systemPromptPrefix", it) }
            args.optionalBoolean("enable_tools")?.let { put("enableTools", it) }
            args.optionalBoolean("web_search")?.let { put("webSearch", it) }
        }
        if (patch.isEmpty()) {
            return ToolResult.invalid(
                field = "<patch>",
                message = "ModelProfileUpdate requires at least one whitelisted field",
                suggestion = "call AgentProfileList first, then send the fields to change"
            )
        }
        return try {
            val updated = host.update(profileId, patch.toString())
            ToolResult.ok("OK: profile updated. Current state:\n$updated")
        } catch (e: IllegalArgumentException) {
            ToolResult.invalid(
                field = "profile_id",
                message = e.message ?: "profile not found",
                suggestion = "call AgentProfileList to see valid profile ids"
            )
        } catch (e: Exception) {
            ToolResult.fail(ToolErrorCode.EXECUTION_FAILED, e.message ?: "update failed")
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// 工具 3: ModelConfigImport
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * `ModelConfigImport` —— 从配置文本导入模型（JSON / Python / YAML / TOML）。
 *
 * 与设置页「导入」弹窗走**同一份识别代码**（core:llm-adapter 的
 * ModelConfigImporter），预览所见即导入所得 —— 这不是提示词模拟，是
 * 真实建 Provider + 档案并加密落 Key 的能力。
 *
 * 风险 MEDIUM：批量建档案（落盘），走风险门一次确认。
 */
class ModelConfigImportTool(
    private val host: ModelConfigImportHost
) : BaseTool(
    id = "ModelConfigImport",
    name = "Import Model Config",
    description = """
        Import model configuration from raw config text and create the
        provider + model profiles for real (persisted immediately). The user
        can paste a JSON / Python / YAML / TOML config snippet in chat and
        ask you to import it — this tool runs the exact same importer as the
        Settings page "Import" dialog.
        Formats (auto-detected by default):
          - JSON: {"base_url": "https://api.example.com/v1",
                   "api_key": "...", "models": [{"id": "gpt-4o"}]}
                  (OpenAI-compatible / provider export blobs are recognized)
          - Python: client = OpenAI(base_url=..., api_key=...)
          - YAML / TOML: key-value config files
        Arguments:
          - config_text (required): the full config text to import
          - format (optional): auto|json|python|yaml|toml (default auto)
        Returns a summary: how many profiles were created and their ids.
    """.trimIndent(),
    declaredSchema = toolSchema {
        string("config_text", required = true, description = "Full config text (JSON/Python/YAML/TOML)")
        string(
            "format",
            description = "Force a format instead of auto-detection",
            enumValues = listOf("auto", "json", "python", "yaml", "toml"),
            defaultValue = "auto"
        )
    }
) {
    override fun buildMetadata(): ToolMetadata = ToolMetadata.meta(id) {
        category(ToolCategory.AGENT)
        risk(ToolRisk.MEDIUM)
        tag("agent", "model", "import", "config")
        annotations(ToolAnnotations.idempotentWrite())
    }

    override suspend fun executeStructured(arguments: String): ToolResult {
        val args = when (val parsed = ToolArguments.of(arguments)) {
            is ToolArguments.ParseOutcome.Ok -> parsed.args
            is ToolArguments.ParseOutcome.Bad -> return parsed.result
        }
        val text = args.requireString("config_text")
        if (text.isBlank()) {
            return ToolResult.invalid(
                field = "config_text",
                message = "config_text is empty",
                suggestion = "paste the full config text (JSON/Python/YAML/TOML)"
            )
        }
        val format = args.stringWithDefault("format", "auto").trim().lowercase()
        if (format !in SUPPORTED_FORMATS) {
            return ToolResult.invalid(
                field = "format",
                message = "unknown format '$format'",
                suggestion = "use one of: ${SUPPORTED_FORMATS.joinToString(", ")}"
            )
        }
        return try {
            val summary = host.import(text, format)
            ToolResult.ok("OK: config imported.\n$summary")
        } catch (e: Exception) {
            ToolResult.fail(
                ToolErrorCode.INVALID_ARGUMENT,
                "import failed: ${e.message ?: "unrecognized config text"}"
            )
        }
    }

    companion object {
        val SUPPORTED_FORMATS: List<String> =
            listOf("auto", "json", "python", "yaml", "toml")
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// 工具 4: SystemDiagnose
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * `SystemDiagnose` —— 采集运行时诊断快照（真实数据，非提示词想象）。
 *
 * 用户诉求「是真实有能力会分析问题」的读侧锚点：聚合 MCP 服务器状态、
 * Skills 安装/启用状态、工具调用审计（含失败详情：command / detail /
 * exitCode / durationMs）。agent 拿到结构化事实后可做真实根因分析，
 * 而不是猜。
 *
 * 风险 LOW：只读快照，无门控。
 */
class SystemDiagnoseTool(
    private val host: SystemDiagnosticsHost
) : BaseTool(
    id = "SystemDiagnose",
    name = "Diagnose System",
    description = """
        Collect a REAL runtime diagnostic snapshot of this app and return it
        as structured JSON — use this to analyze problems instead of guessing:
          - mcp: configured MCP servers with transport / enabled / connected
            state (a tool that "does not work" often means its MCP server is
            disconnected or disabled)
          - skills: installed skills with enable state per workspace
          - toolCalls: recent tool-call audit trail (tool, decision, tier,
            durationMs, success) — the terminal/命令执行历史
          - failures: recent FAILED tool calls with command + detail + exit
            code — the highest-value section for root-cause analysis
        Arguments:
          - include_tool_calls (optional, default true): include the audit trail
          - tool_call_limit (optional, default 30, max 200): audit entries
        Pair this with AppScreenOpen("diagnostics") to let the user browse the
        same data in the Diagnostics page.
    """.trimIndent(),
    declaredSchema = toolSchema {
        boolean("include_tool_calls", description = "Include the tool-call audit trail", defaultValue = true)
        integer("tool_call_limit", description = "Audit entries to return (5..200)",
            defaultValue = 30, minimum = 5.0, maximum = 200.0)
    }
) {
    override fun buildMetadata(): ToolMetadata = ToolMetadata.meta(id) {
        category(ToolCategory.SYSTEM)
        risk(ToolRisk.LOW)
        tag("system", "diagnostics", "mcp", "skills", "audit")
        annotations(ToolAnnotations.readOnly())
    }

    override suspend fun executeStructured(arguments: String): ToolResult {
        val args = when (val parsed = ToolArguments.of(arguments)) {
            is ToolArguments.ParseOutcome.Ok -> parsed.args
            is ToolArguments.ParseOutcome.Bad -> return parsed.result
        }
        val includeCalls = args.booleanWithDefault("include_tool_calls", true)
        val limit = args.intWithDefault("tool_call_limit", 30).coerceIn(5, 200)
        return try {
            val snapshot: JsonObject = AppControlJson.parseToJsonElement(
                host.diagnose(includeCalls, limit)
            ) as? JsonObject
                ?: return ToolResult.fail(ToolErrorCode.EXECUTION_FAILED, "host returned non-object JSON")
            val failureCount = (snapshot["failures"] as? kotlinx.serialization.json.JsonArray)?.size ?: 0
            ToolResult.ok(
                buildString {
                    append("OK: diagnostic snapshot collected. ")
                    append("Focus on the 'failures' section ($failureCount entr")
                    append(if (failureCount == 1) "y" else "ies")
                    append(") for root-cause analysis.\n\n")
                    append(AppControlJson.encodeToString(JsonObject.serializer(), snapshot))
                }
            )
        } catch (e: Exception) {
            ToolResult.fail(ToolErrorCode.EXECUTION_FAILED, e.message ?: "diagnose failed")
        }
    }

    /** 供宿主复用的 JSON 组装器（保持输出结构单一定义点）。 */
    object Reports {
        fun mcpServer(
            name: String,
            transport: String,
            enabled: Boolean,
            connected: Boolean,
            endpoint: String
        ) = buildJsonObject {
            put("name", name)
            put("transport", transport)
            put("enabled", enabled)
            put("connected", connected)
            put("endpoint", endpoint)
        }

        fun skill(
            id: String,
            name: String,
            version: String,
            enabledAgent: Boolean,
            enabledCoding: Boolean,
            toolCount: Int
        ) = buildJsonObject {
            put("id", id)
            put("name", name)
            put("version", version)
            put("enabledAgent", enabledAgent)
            put("enabledCoding", enabledCoding)
            put("tools", toolCount)
        }

        fun toolCall(
            ts: Long,
            tool: String,
            decision: String,
            tier: String?,
            success: Boolean?,
            durationMs: Long?,
            command: String?,
            detail: String?
        ) = buildJsonObject {
            put("ts", ts)
            put("tool", tool)
            put("decision", decision)
            tier?.let { put("tier", it) }
            success?.let { put("success", it) }
            durationMs?.let { put("durationMs", it) }
            command?.let { put("command", it) }
            detail?.let { put("detail", it) }
        }

        fun assemble(
            mcp: List<JsonObject>,
            skills: List<JsonObject>,
            toolCalls: List<JsonObject>,
            failures: List<JsonObject>
        ) = buildJsonObject {
            put("mcp", buildJsonArray { mcp.forEach { add(it) } })
            put("skills", buildJsonArray { skills.forEach { add(it) } })
            put("toolCalls", buildJsonArray { toolCalls.forEach { add(it) } })
            put("failures", buildJsonArray { failures.forEach { add(it) } })
        }
    }
}
