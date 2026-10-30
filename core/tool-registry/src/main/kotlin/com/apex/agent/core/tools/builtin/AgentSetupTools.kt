package com.apex.agent.core.tools.builtin

import com.apex.agent.core.tools.ToolAnnotations
import com.apex.agent.core.tools.ToolArguments
import com.apex.agent.core.tools.ToolCategory
import com.apex.agent.core.tools.ToolErrorCode
import com.apex.agent.core.tools.ToolMetadata
import com.apex.agent.core.tools.ToolResult
import com.apex.agent.core.tools.ToolRisk
import com.apex.agent.core.tools.ToolSchema
import com.apex.agent.core.tools.toolSchema
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * # Agent Self-Setup Tools (v5)
 *
 * 让 Agent 自己读取/修改本应用的设置——模式 / 思考档位 / 重试策略 /
 * 上下文窗口 / 主题 / 语言 / 通知 ——一键完成 onboarding 后续
 * 的"调优"环节，无需引导用户翻设置页。
 *
 * 安全契约：
 * - 读类工具（`AgentSettingGet` / `AgentProfileList` / `AgentRoleList`）
 *   无副作用，LOW 风险，无门控；
 * - 写类工具（`AgentSettingSet` / `agent_profile_*` / `AgentRoleActivate`）
 *   MEDIUM 风险，走风险门确认一次（HIGH 风险的
 *   `AgentProviderSetKey` 走会话级确认）；
 * - 写入的字段集是**白名单**：只允许 [AgentSettingsPatch] 中显式声明的字段，
 *   未知字段直接忽略——防 LLM 越权改 `permissionMode`/`permissionRules`
 *   （属安全相关字段，工具不暴露修改入口；如需修改，让用户去设置页改）。
 *
 * 工具本体（本文件）只做参数解析与字段白名单校验；持久化由注入的
 * [AgentSettingsHost] lambda 完成（app 侧 [com.apex.agent.tools.AgentSetupHost]
 * 桥接 [com.apex.agent.ui.screen.settings.SettingsRepository]）。
 */

/**
 * Agent 运行时可读写配置的快照。是 [com.apex.agent.ui.screen.settings.AgentSettings]
 * 的子集（安全过滤后），用于跨 JVM/Android 模块传递（core/tool-registry 是纯 JVM，
 * 不能引用 app 模块的 AgentSettings）。
 */
@Serializable
data class AgentSettingsSnapshot(
    val codeThinkingLogic: String = "",
    val codeThinkingLevel: String = "",
    val codeExecutionMode: String = "",
    val keepAlive: Boolean = true,
    val taskCompletionNotify: Boolean = true,
    val maxContextTokens: Int = 128_000,
    val compressionThreshold: Float = 0.8f,
    val preserveRecentTurns: Int = 5,
    val maxToolOutputLength: Int = 2000,
    val themeMode: String = "system",
    val accentPalette: String = "mint",
    val fontScale: Float = 1.0f,
    val showTimestamps: Boolean = true,
    val language: String = "system",
    val sendKeyBehavior: String = "send"
) {
    companion object {
        val EMPTY = AgentSettingsSnapshot()
    }
}

/**
 * Agent 设置增量补丁——只允许暴露给 LLM 的字段子集（Agent 聊天模式字段
 * 已随模式移除，白名单收敛为 Coding 工位与通用层字段）。
 *
 * 字段含义：
 * - `codeThinkingLogic` / `codeThinkingLevel` / `codeExecutionMode`:
 *   Coding 屏思考逻辑 / 思考档位 / 执行模式；
 * - `keepAlive`: 后台服务保活（终端运行时宿主）；
 * - `taskCompletionNotify`: 后台任务完成发系统通知；
 * - `maxContextTokens` / `compressionThreshold` / `preserveRecentTurns` / `maxToolOutputLength`:
 *   上下文压缩配置；
 * - `themeMode` / `accentPalette` / `fontScale` / `showTimestamps` / `language` /
 *   `sendKeyBehavior`: 界面层参数（即时生效或下个 Activity 重建生效）。
 *
 * **未暴露的安全相关字段**（必须由用户手动改）：`permissionMode`、`permissionRules`、
 * `mcpScopeIsolation`、`skillScope`、`onboardingVersion`——LLM 不应自行放宽权限。
 */
@Serializable
data class AgentSettingsPatch(
    val codeThinkingLogic: String? = null,
    val codeThinkingLevel: String? = null,
    val codeExecutionMode: String? = null,
    val keepAlive: Boolean? = null,
    val taskCompletionNotify: Boolean? = null,
    val maxContextTokens: Int? = null,
    val compressionThreshold: Float? = null,
    val preserveRecentTurns: Int? = null,
    val maxToolOutputLength: Int? = null,
    val themeMode: String? = null,
    val accentPalette: String? = null,
    val fontScale: Float? = null,
    val showTimestamps: Boolean? = null,
    val language: String? = null,
    val sendKeyBehavior: String? = null
) {
    /** 是否所有字段都为 null（无操作）。 */
    fun isEmpty(): Boolean = codeThinkingLogic == null && codeThinkingLevel == null &&
        codeExecutionMode == null && keepAlive == null &&
        taskCompletionNotify == null && maxContextTokens == null &&
        compressionThreshold == null && preserveRecentTurns == null &&
        maxToolOutputLength == null && themeMode == null && accentPalette == null &&
        fontScale == null && showTimestamps == null && language == null &&
        sendKeyBehavior == null

    /** 已设置的字段数（用于结果展示）。 */
    fun changedFieldCount(): Int {
        var n = 0
        if (codeThinkingLogic != null) n++
        if (codeThinkingLevel != null) n++
        if (codeExecutionMode != null) n++
        if (keepAlive != null) n++
        if (taskCompletionNotify != null) n++
        if (maxContextTokens != null) n++
        if (compressionThreshold != null) n++
        if (preserveRecentTurns != null) n++
        if (maxToolOutputLength != null) n++
        if (themeMode != null) n++
        if (accentPalette != null) n++
        if (fontScale != null) n++
        if (showTimestamps != null) n++
        if (language != null) n++
        if (sendKeyBehavior != null) n++
        return n
    }
}

/**
 * Agent 自身配置宿主接口（app 侧实现注入）——只暴露 Agent 允许改的字段集。
 *
 * `settingsReader`: 返回当前 [AgentSettingsSnapshot]；
 * `settingsApplier`: 接收 [AgentSettingsPatch]，把非 null 字段写入持久化层（异步）。
 */
interface AgentSettingsHost {
    suspend fun snapshot(): AgentSettingsSnapshot
    suspend fun apply(patch: AgentSettingsPatch): Boolean
}

// ═══════════════════════════════════════════════════════════════════════════════
// 工具 1: AgentSettingGet
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * `AgentSettingGet` — 读取 Agent 自身运行时配置快照（无参数）。
 *
 * 输出 JSON：当前 [AgentSettingsSnapshot] 全字段，便于 LLM 决定下一步要改什么。
 * 风险 LOW（只读），无门控。
 */
class AgentSettingGetTool(
    private val host: AgentSettingsHost
) : BaseTool(
    id = "AgentSettingGet",
    name = "Get Agent Settings",
    description = """
        Read a snapshot of the agent's own runtime configuration: coding
        thinking level, context window, theme, language and more.
        Use this before [AgentSettingSet] to see what is currently in effect,
        and before reporting "I cannot do X" — your limits may be configurable.
        No arguments. 返回当前 Agent 配置快照（JSON）。
    """.trimIndent(),
    declaredSchema = toolSchema { }
) {
    override fun buildMetadata(): ToolMetadata = ToolMetadata.meta(id) {
        category(ToolCategory.AGENT)
        risk(ToolRisk.LOW)
        tag("agent", "settings", "config", "self-setup")
        annotations(ToolAnnotations.readOnly())
    }

    override suspend fun executeStructured(arguments: String): ToolResult {
        val snap = host.snapshot()
        val json = AgentSettingsJson.encodeToString(AgentSettingsSnapshot.serializer(), snap)
        // 实录 #12：模型想配模型/供应商时先查了本工具，发现没有 provider/
        // model/key 字段就放弃了。附指针把“不在这里 + 在哪 + 怎么开”说清。
        return ToolResult.ok(json + "\n\n" + MODEL_CONFIG_POINTER)
    }

    companion object {
        /**
         * AgentSettingGet 输出尾指针（实录 #12）：运行时配置 ≠ 模型配置。
         * AgentProfileList / AgentProfileSetDefault 已入 CORE 可直接调；
         * key 写入与批量导入在 CATALOG，需 ToolSearch → ToolOpen 两跳。
         */
        internal const val MODEL_CONFIG_POINTER =
            "Note: this snapshot covers RUNTIME settings only — model providers, model " +
                "profiles and API keys are NOT here. Model config lives in: AgentProfileList " +
                "(list profiles), AgentProfileSetDefault (switch the default profile). For API " +
                "keys and bulk import use ToolSearch(\"provider model api key\") then ToolOpen " +
                "AgentProviderSetKey / ModelConfigImport."
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// 工具 2: AgentSettingSet
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * `AgentSettingSet` — 增量修改 Agent 自身配置。
 *
 * 参数：[AgentSettingsPatch] 中任意子集（其余字段忽略）。
 * 至少需 1 个字段；空白 patch 返回 INVALID_ARGUMENT。
 *
 * 风险 MEDIUM（mutating，会改运行时行为），经风险门一次确认。
 * 字段白名单校验在 [validatePatch] 中。
 *
 * 示例：
 * - {"code_thinking_level": "deep"} —— Coding 思考档位切深潜
 * - {"language": "zh", "theme_mode": "dark"} —— 中文界面 + 暗色主题
 */
class AgentSettingSetTool(
    private val host: AgentSettingsHost
) : BaseTool(
    id = "AgentSettingSet",
    name = "Set Agent Settings",
    description = """
        Incrementally update the agent's own runtime configuration. Only the
        fields you provide are changed; the rest stay as-is.
        Allowed fields (all optional, but at least one is required):
          - code_thinking_logic (""|deep_dive|standard), code_thinking_level
            (""|auto|none/light/standard/deep/maximum/ultracode/apexcode),
            code_execution_mode (""|build|plan|goal)
          - keep_alive (true|false)
          - task_completion_notify (true|false)
          - max_context_tokens (8192..1_000_000),
            compression_threshold (0.5..0.95),
            preserve_recent_turns (1..20),
            max_tool_output_length (200..20000)
          - theme_mode (system|dark|light)
          - accent_palette (mint|amber|coral|violet|ocean|rose|forest|cyan|sunset|
            gold|crimson|lavender|magenta|lime|sakura|matcha|peach|plum)
          - font_scale (0.8..1.4)
          - show_timestamps (true|false)
          - language (system|zh|en)
          - send_key_behavior (send|newline)
        字段名按 snake_case；以下划线分隔的字段名 LLM 友好。
        Examples:
        - {"code_thinking_level": "deep"}
        - {"language": "zh", "theme_mode": "dark"}
    """.trimIndent(),
    declaredSchema = toolSchema {
        // 所有字段都是可选的，至少 1 个；具体校验由 validatePatch 完成
        string("code_thinking_logic", description = "Coding thinking logic: \"\"|deep_dive|standard")
        string("code_thinking_level",
            description = "Coding thinking level (\"\"/auto/none/light/standard/deep/maximum/ultracode/apexcode)")
        string("code_execution_mode", description = "Coding execution mode: \"\"|build|plan|goal")
        boolean("keep_alive", description = "Keep the background service alive")
        boolean("task_completion_notify",
            description = "Send system notification when background task completes")
        integer("max_context_tokens", description = "Max context tokens (8192..1000000)",
            minimum = 8192.0, maximum = 1_000_000.0)
        number("compression_threshold", description = "Context compression threshold (0.5..0.95)",
            minimum = 0.5, maximum = 0.95)
        integer("preserve_recent_turns", description = "Preserve recent turns on compression (1..20)",
            minimum = 1.0, maximum = 20.0)
        integer("max_tool_output_length", description = "Max tool output length (200..20000)",
            minimum = 200.0, maximum = 20_000.0)
        string("theme_mode", description = "system|dark|light")
        string("accent_palette",
            description = "mint|amber|coral|violet|ocean|rose|forest|cyan|sunset|gold|crimson|lavender|magenta|lime|sakura|matcha|peach|plum")
        number("font_scale", description = "Font scale (0.8..1.4)",
            minimum = 0.8, maximum = 1.4)
        boolean("show_timestamps", description = "Show timestamps in chat")
        string("language", description = "system|zh|en")
        string("send_key_behavior", description = "send|newline (IME action)")
    }
) {
    override fun buildMetadata(): ToolMetadata = ToolMetadata.meta(id) {
        category(ToolCategory.AGENT)
        risk(ToolRisk.MEDIUM)
        tag("agent", "settings", "config", "self-setup")
        annotations(ToolAnnotations.idempotentWrite())
    }

    override suspend fun executeStructured(arguments: String): ToolResult {
        val args = when (val parsed = ToolArguments.of(arguments)) {
            is ToolArguments.ParseOutcome.Ok -> parsed.args
            is ToolArguments.ParseOutcome.Bad -> return parsed.result
        }
        val patch = parsePatch(args)
        if (patch.isEmpty()) {
            return ToolResult.invalid(
                field = "<patch>",
                message = "AgentSettingSet requires at least one field to change; " +
                    "the patch is empty. See tool description for allowed fields.",
                suggestion = "use AgentSettingGet first to see what's currently set, " +
                    "then send the fields you want to change"
            )
        }
        val violations = validatePatch(patch)
        if (violations.isNotEmpty()) {
            return ToolResult.fail(
                ToolErrorCode.INVALID_ARGUMENT,
                "patch validation failed: ${violations.joinToString("; ")}"
            )
        }
        val ok = host.apply(patch)
        return if (ok) {
            ToolResult.ok(
                "OK: applied ${patch.changedFieldCount()} field(s) to agent settings. " +
                    "Most changes take effect on the next conversation turn; " +
                    "theme/language changes may require an Activity restart."
            )
        } else {
            ToolResult.fail(
                ToolErrorCode.EXECUTION_FAILED,
                "settings apply failed (host rejected the patch — see logs)"
            )
        }
    }

    /**
     * 把 [ToolArguments] 解析为 [AgentSettingsPatch]。
     * 任何字段缺失 → null（patch 中跳过该字段）。
     */
    private fun parsePatch(args: ToolArguments): AgentSettingsPatch = AgentSettingsPatch(
        codeThinkingLogic = args.optionalString("code_thinking_logic"),
        codeThinkingLevel = args.optionalString("code_thinking_level"),
        codeExecutionMode = args.optionalString("code_execution_mode"),
        keepAlive = args.optionalBoolean("keep_alive"),
        taskCompletionNotify = args.optionalBoolean("task_completion_notify"),
        maxContextTokens = args.optionalInt("max_context_tokens"),
        compressionThreshold = args.optionalDouble("compression_threshold")?.toFloat(),
        preserveRecentTurns = args.optionalInt("preserve_recent_turns"),
        maxToolOutputLength = args.optionalInt("max_tool_output_length"),
        themeMode = args.optionalString("theme_mode"),
        accentPalette = args.optionalString("accent_palette"),
        fontScale = args.optionalDouble("font_scale")?.toFloat(),
        showTimestamps = args.optionalBoolean("show_timestamps"),
        language = args.optionalString("language"),
        sendKeyBehavior = args.optionalString("send_key_behavior")
    )

    companion object {
        /** LLM 可见的字段白名单 + 枚举值校验。返回空列表 = 通过。 */
        fun validatePatch(p: AgentSettingsPatch): List<String> {
            val violations = mutableListOf<String>()
            p.codeThinkingLogic?.let { v ->
                if (v.isNotBlank() && v !in CODE_THINKING_LOGIC_VALUES) {
                    violations.add("code_thinking_logic='$v' must be \"\", \"deep_dive\", or \"standard\"")
                }
            }
            p.codeThinkingLevel?.let { v ->
                if (v.isNotBlank() && v != "auto" && v !in CODE_THINKING_LEVEL_VALUES) {
                    violations.add("code_thinking_level='$v' must be \"\", \"auto\", or one of ${CODE_THINKING_LEVEL_VALUES.joinToString("/")}")
                }
            }
            p.codeExecutionMode?.let { v ->
                if (v.isNotBlank() && v !in CODE_EXECUTION_MODE_VALUES) {
                    violations.add("code_execution_mode='$v' must be \"\", \"build\", \"plan\", or \"goal\"")
                }
            }
            p.maxContextTokens?.let { v ->
                if (v !in 8192..1_000_000) violations.add("max_context_tokens=$v must be 8192..1000000")
            }
            p.compressionThreshold?.let { v ->
                if (v !in 0.5f..0.95f) violations.add("compression_threshold=$v must be 0.5..0.95")
            }
            p.preserveRecentTurns?.let { v ->
                if (v !in 1..20) violations.add("preserve_recent_turns=$v must be 1..20")
            }
            p.maxToolOutputLength?.let { v ->
                if (v !in 200..20_000) violations.add("max_tool_output_length=$v must be 200..20000")
            }
            p.themeMode?.let { v ->
                if (v !in THEME_MODE_VALUES) violations.add("theme_mode='$v' must be one of ${THEME_MODE_VALUES.joinToString("/")}")
            }
            p.accentPalette?.let { v ->
                if (v !in ACCENT_PALETTES) violations.add("accent_palette='$v' must be one of ${ACCENT_PALETTES.joinToString("/")}")
            }
            p.fontScale?.let { v ->
                if (v !in 0.8f..1.4f) violations.add("font_scale=$v must be 0.8..1.4")
            }
            p.language?.let { v ->
                if (v !in LANGUAGE_VALUES) violations.add("language='$v' must be one of ${LANGUAGE_VALUES.joinToString("/")}")
            }
            p.sendKeyBehavior?.let { v ->
                if (v !in SEND_KEY_BEHAVIOR_VALUES) violations.add("send_key_behavior='$v' must be one of ${SEND_KEY_BEHAVIOR_VALUES.joinToString("/")}")
            }
            return violations
        }

        // ── 字段枚举值集合（与 SettingsRepository/AgentSettings 默认值同源）──
        val CODE_THINKING_LOGIC_VALUES = setOf("deep_dive", "standard")
        val CODE_THINKING_LEVEL_VALUES = setOf(
            "none", "light", "standard", "deep", "maximum", "ultracode", "apexcode"
        )
        val CODE_EXECUTION_MODE_VALUES = setOf("build", "plan", "goal")
        val THEME_MODE_VALUES = setOf("system", "dark", "light")
        val LANGUAGE_VALUES = setOf("system", "zh", "en")
        val SEND_KEY_BEHAVIOR_VALUES = setOf("send", "newline")
        val ACCENT_PALETTES = setOf(
            "mint", "amber", "coral", "violet", "ocean", "rose", "forest",
            "cyan", "sunset", "gold", "crimson", "lavender", "magenta",
            "lime", "sakura", "matcha", "peach", "plum"
        )
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// 工具 3-4: AgentProfileList / AgentProfileSetDefault
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * 模型 Profile 的精简视图（不含密钥；用于 LLM 阅读）。
 * app 侧从 [com.apex.agent.core.llm.ModelProfile] 转换。
 */
@Serializable
data class ModelProfileSummary(
    val id: String,
    val name: String,
    val providerId: String,
    val modelId: String,
    val isDefault: Boolean = false,
    val contextWindow: Int = 0,
    val capabilities: String = "",   // "Text · Vision · Tools"
    val temperature: Double = 1.0,
    val maxTokens: Int = 0
)

/**
 * `AgentProfileList` — 列出所有模型 Profile 摘要（无密钥）。
 */
class AgentProfileListTool(
    private val listProvider: suspend () -> List<ModelProfileSummary>
) : BaseTool(
    id = "AgentProfileList",
    name = "List Model Profiles",
    description = """
        List all configured model profiles (id/name/provider/model/context window/
        capabilities/temperature) without exposing API keys. Use this to inspect
        which models the user has set up before suggesting a profile switch via
        [AgentProfileSetDefault]. No arguments.
    """.trimIndent(),
    declaredSchema = toolSchema { }
) {
    override fun buildMetadata(): ToolMetadata = ToolMetadata.meta(id) {
        category(ToolCategory.AGENT)
        risk(ToolRisk.LOW)
        tag("agent", "profile", "model", "llm", "self-setup")
        annotations(ToolAnnotations.readOnly())
    }

    override suspend fun executeStructured(arguments: String): ToolResult {
        val list = listProvider()
        if (list.isEmpty()) {
            return ToolResult.ok("No model profiles configured. Use the Settings → Models screen to add one, or ask the user to set up at least one provider + profile.")
        }
        val json = AgentSettingsJson.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(ModelProfileSummary.serializer()),
            list
        )
        return ToolResult.ok(json)
    }
}

/**
 * `AgentProfileSetDefault` — 切换默认模型 Profile（影响下一轮 LLM 调用）。
 */
class AgentProfileSetDefaultTool(
    private val setDefault: suspend (String) -> Boolean,
    private val listProvider: suspend () -> List<ModelProfileSummary>
) : BaseTool(
    id = "AgentProfileSetDefault",
    name = "Set Default Model Profile",
    description = """
        Switch the default model profile used for the next LLM call.
        Pass a profile id obtained from [AgentProfileList]. The change takes
        effect on the next conversation turn. Risk: MEDIUM (mutating runtime model).
    """.trimIndent(),
    declaredSchema = toolSchema {
        string("profile_id", required = true, description = "Profile id from AgentProfileList")
    }
) {
    override fun buildMetadata(): ToolMetadata = ToolMetadata.meta(id) {
        category(ToolCategory.AGENT)
        risk(ToolRisk.MEDIUM)
        tag("agent", "profile", "model", "llm", "self-setup")
        annotations(ToolAnnotations.idempotentWrite())
    }

    override suspend fun executeStructured(arguments: String): ToolResult {
        val args = when (val parsed = ToolArguments.of(arguments)) {
            is ToolArguments.ParseOutcome.Ok -> parsed.args
            is ToolArguments.ParseOutcome.Bad -> return parsed.result
        }
        val profileId = args.requireString("profile_id")
        // 二次校验：profile 存在
        val exists = listProvider().any { it.id == profileId }
        if (!exists) {
            return ToolResult.invalid(
                field = "profile_id",
                message = "no model profile with id '$profileId' is configured",
                suggestion = "use AgentProfileList to enumerate valid profile ids"
            )
        }
        val ok = setDefault(profileId)
        return if (ok) {
            ToolResult.ok("OK: default profile switched to '$profileId'. Next LLM call will use it.")
        } else {
            ToolResult.fail(ToolErrorCode.EXECUTION_FAILED, "host rejected profile switch")
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// 工具 5: AgentProviderSetKey
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * `AgentProviderSetKey` — 为某个 Provider 写入 API Key（加密存储）。
 *
 * 风险 HIGH（涉及凭据），会话级确认（每会话首次使用弹窗）。
 * 工具本身不返回 key（写入后不可读），仅返回状态。
 *
 * 安全：Key 永不出现在工具结果里——SecretRedactingExecutor 兜底也会擦洗。
 */
class AgentProviderSetKeyTool(
    private val setKey: suspend (providerId: String, apiKey: String) -> Boolean
) : BaseTool(
    id = "AgentProviderSetKey",
    name = "Set Provider API Key",
    description = """
        Set the API key for a provider (encrypted at rest in Android Keystore).
        The key is write-only — it cannot be read back via any tool. The provider
        must already exist (use Settings → Models to add new providers).
        Risk: HIGH (security credential). Requires per-session confirmation.
        Examples:
        - {"provider_id": "openai", "api_key": "sk-..."}
        - {"provider_id": "deepseek", "api_key": "sk-..."}
    """.trimIndent(),
    declaredSchema = toolSchema {
        string("provider_id", required = true, description = "Provider id (e.g. openai, deepseek)")
        string("api_key", required = true, description = "API key to store (encrypted)")
    }
) {
    override fun buildMetadata(): ToolMetadata = ToolMetadata.meta(id) {
        category(ToolCategory.AGENT)
        risk(ToolRisk.HIGH)
        tag("agent", "provider", "api_key", "credential", "self-setup")
        annotations(
            ToolAnnotations(
                readOnlyHint = false,
                destructiveHint = false,
                idempotentHint = true,
                openWorldHint = false,
                sensitiveAction = true
            )
        )
    }

    override suspend fun executeStructured(arguments: String): ToolResult {
        val args = when (val parsed = ToolArguments.of(arguments)) {
            is ToolArguments.ParseOutcome.Ok -> parsed.args
            is ToolArguments.ParseOutcome.Bad -> return parsed.result
        }
        val providerId = args.requireString("provider_id")
        val apiKey = args.requireString("api_key")
        if (apiKey.isBlank()) {
            return ToolResult.invalid(
                field = "api_key",
                message = "api_key cannot be blank"
            )
        }
        val ok = setKey(providerId, apiKey)
        return if (ok) {
            // 注意：绝不返回 key 内容
            // 实录 #13：密钥 write-only（安全设计，不可回读）—— 模型无法用工具
            // 验证“写对了”。把唯一的写确认（长度）与真实验证路径（小样本调用）
            // 显式写进输出，避免模型反复盲写或假装已验证。
            ToolResult.ok(
                "OK: API key for provider '$providerId' stored (encrypted). Length: ${apiKey.length} chars. " +
                    "Keys are write-only by design — no tool can read them back; the stored length above " +
                    "is your only write confirmation. To VERIFY the key actually works: (1) call " +
                    "AgentProfileSetDefault to select a profile of this provider, (2) send a tiny test " +
                    "message with that model — a successful completion is the only real proof."
            )
        } else {
            ToolResult.fail(ToolErrorCode.EXECUTION_FAILED, "host rejected the key write (provider may not exist)")
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// 工具 6-7: AgentRoleList / AgentRoleActivate
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * 角色配置（精简视图，用于 LLM 阅读）。
 */
@Serializable
data class AgentRoleConfigSnapshot(
    val primaryProfileId: String = "",
    val visionProfileId: String = "",
    val reasoningProfileId: String = "",
    val fastProfileId: String = "",
    val summaryProfileId: String = ""
)

/**
 * `AgentRoleList` — 读取当前 Agent 角色映射（哪个角色用哪个 Profile）。
 */
class AgentRoleListTool(
    private val reader: suspend () -> AgentRoleConfigSnapshot
) : BaseTool(
    id = "AgentRoleList",
    name = "List Agent Role Bindings",
    description = """
        Read the current agent role bindings (which profile is used for primary /
        vision / reasoning / fast / summary tasks). Use this before
        [AgentRoleActivate] to see the current binding. No arguments.
    """.trimIndent(),
    declaredSchema = toolSchema { }
) {
    override fun buildMetadata(): ToolMetadata = ToolMetadata.meta(id) {
        category(ToolCategory.AGENT)
        risk(ToolRisk.LOW)
        tag("agent", "role", "binding", "self-setup")
        annotations(ToolAnnotations.readOnly())
    }

    override suspend fun executeStructured(arguments: String): ToolResult {
        val snap = reader()
        val json = AgentSettingsJson.encodeToString(AgentRoleConfigSnapshot.serializer(), snap)
        return ToolResult.ok(json)
    }
}

/**
 * `AgentRoleActivate` — 修改某个角色的 Profile 绑定。
 */
class AgentRoleActivateTool(
    private val activator: suspend (role: String, profileId: String) -> Boolean
) : BaseTool(
    id = "AgentRoleActivate",
    name = "Activate Role Profile",
    description = """
        Bind a profile to an agent role. Roles route different task types to
        different models (e.g. vision tasks to a multimodal model).
        Examples:
        - {"role": "vision", "profile_id": "profile_gpt4o"}
        - {"role": "fast", "profile_id": "profile_gpt_mini"}
        Roles: primary, vision, reasoning, fast, summary.
    """.trimIndent(),
    declaredSchema = toolSchema {
        string("role", required = true,
            description = "Role: primary|vision|reasoning|fast|summary",
            enumValues = listOf("primary", "vision", "reasoning", "fast", "summary"))
        string("profile_id", required = true, description = "Profile id to bind to this role")
    }
) {
    override fun buildMetadata(): ToolMetadata = ToolMetadata.meta(id) {
        category(ToolCategory.AGENT)
        risk(ToolRisk.MEDIUM)
        tag("agent", "role", "binding", "self-setup")
        annotations(ToolAnnotations.idempotentWrite())
    }

    override suspend fun executeStructured(arguments: String): ToolResult {
        val args = when (val parsed = ToolArguments.of(arguments)) {
            is ToolArguments.ParseOutcome.Ok -> parsed.args
            is ToolArguments.ParseOutcome.Bad -> return parsed.result
        }
        val role = args.requireString("role")
        val profileId = args.requireString("profile_id")
        if (role !in ROLE_NAMES) {
            return ToolResult.invalid(
                field = "role",
                message = "role must be one of ${ROLE_NAMES.joinToString("/")}"
            )
        }
        val ok = activator(role, profileId)
        return if (ok) {
            ToolResult.ok("OK: role '$role' bound to profile '$profileId'.")
        } else {
            ToolResult.fail(ToolErrorCode.EXECUTION_FAILED, "host rejected role binding (profile may not exist)")
        }
    }

    companion object {
        val ROLE_NAMES = setOf("primary", "vision", "reasoning", "fast", "summary")
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// 工具 8-9: AgentModePresetList / AgentModePresetSelect 已随 Agent CUSTOM 模式
// 移除（PR 4/6）—— 预设是 Agent 聊天档位专属指令源，纯 Coding 软件不再有
// CUSTOM 模式；设置域的预设编辑器与 AgentSettings 预设字段已随 PR 5/6 删除。
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * 模块级 JSON 实例（与 AgentSettings 共用，pretty print 给 LLM 易读）。
 */
internal val AgentSettingsJson = Json {
    prettyPrint = true
    encodeDefaults = true
    ignoreUnknownKeys = true
}
