package com.apex.agent.core.tools.pluginhost.claude

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * # ClaudeHooksManifest —— hooks.json 官方形状解析（spec §3.6）
 *
 * 官方结构（code.claude.com/docs/en/hooks，2026-11 审计）：
 *
 * ```json
 * { "hooks": {
 *     "PreToolUse": [
 *       { "matcher": "Bash",
 *         "hooks": [
 *           { "type": "command", "command": "…", "args": [], "timeout": 30 }
 *         ] }
 *     ] } }
 * ```
 *
 * - 事件名 → 匹配组列表；匹配组的 `matcher` 是工具名模式（`Edit|Write`），
 *   空 matcher = 全匹配；
 * - hook 条目 `type: "command"`（prompt/agent/http 类型存在但宿主按
 *   「未支持」如实报告，不冒充）；
 * - `command` 支持 `${CLAUDE_PLUGIN_ROOT}` / `${CLAUDE_PROJECT_DIR}` 变量；
 * - `timeout` 秒（缺省 60）。
 *
 * 解析保留未知事件与未知字段（前向兼容：官方新增事件不崩旧解析器），
 * 但未知事件在能力面如实标注 notSupported。
 */
object ClaudeHooksManifest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 宿主已映射支持的官方事件名（其余保留但不分发 —— 诚实边界）。 */
    object Events {
        const val SESSION_START = "SessionStart"
        const val SESSION_END = "SessionEnd"
        const val USER_PROMPT_SUBMIT = "UserPromptSubmit"
        const val PRE_TOOL_USE = "PreToolUse"
        const val POST_TOOL_USE = "PostToolUse"
        const val POST_TOOL_USE_FAILURE = "PostToolUseFailure"
        const val NOTIFICATION = "Notification"

        /** 宿主事件映射支持的全部官方事件（映射表见 [ClaudeHookAdapter]）。 */
        val mappable: Set<String> = setOf(
            SESSION_START, SESSION_END, USER_PROMPT_SUBMIT,
            PRE_TOOL_USE, POST_TOOL_USE, POST_TOOL_USE_FAILURE, NOTIFICATION
        )
    }

    @Serializable
    data class HookEntry(
        /** 官方类型：command（受支持）/ prompt / agent / http（如实标注不支持）。 */
        val type: String = "command",
        /** 命令（可含 ${'$'}{CLAUDE_PLUGIN_ROOT} 变量）。 */
        val command: String = "",
        val args: List<String> = emptyList(),
        /** 秒；缺省 60。 */
        val timeout: Int? = null,
        /** 失败策略（onFailure: block 等；未声明时按官方默认非阻塞）。 */
        val onFailure: String? = null
    )

    @Serializable
    data class MatcherGroup(
        /** 工具名模式（regex，`Edit|Write`）；空 = 全匹配。 */
        val matcher: String = "",
        val hooks: List<HookEntry> = emptyList()
    )

    data class Manifest(
        /** 事件名 → 匹配组。 */
        val groups: Map<String, List<MatcherGroup>>,
        /** 保留但宿主事件映射不支持的官方事件（报告用）。 */
        val unmappedEvents: Set<String>
    )

    /** 解析 hooks.json 文本（损坏 → null，不装半截配置）。 */
    fun parse(text: String): Manifest? {
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
        // 无 hooks 字段 = 无钩子声明（合法空清单）；字段类型错 = 损坏（null）
        val hooksEl = root["hooks"] ?: return Manifest(emptyMap(), emptySet())
        val hooksObj = hooksEl as? JsonObject ?: return null
        val groups = mutableMapOf<String, List<MatcherGroup>>()
        val unmapped = mutableSetOf<String>()
        for ((event, arr) in hooksObj) {
            val parsed = runCatching {
                (arr as? kotlinx.serialization.json.JsonArray)?.mapNotNull { el ->
                    val obj = el as? JsonObject ?: return@mapNotNull null
                    val matcher = obj["matcher"]?.jsonPrimitive?.content ?: ""
                    val hookArr = obj["hooks"]?.jsonArray ?: return@mapNotNull null
                    MatcherGroup(
                        matcher = matcher,
                        hooks = hookArr.mapNotNull { h ->
                            val ho = h as? JsonObject ?: return@mapNotNull null
                            HookEntry(
                                type = ho["type"]?.jsonPrimitive?.content ?: "command",
                                command = ho["command"]?.jsonPrimitive?.content ?: "",
                                args = ho["args"]?.jsonArray?.mapNotNull { a ->
                                    (a as? kotlinx.serialization.json.JsonPrimitive)?.content
                                } ?: emptyList(),
                                timeout = ho["timeout"]?.jsonPrimitive?.content?.toIntOrNull(),
                                onFailure = ho["onFailure"]?.jsonPrimitive?.content
                            )
                        }
                    )
                }
            }.getOrNull() ?: emptyList()
            if (parsed.isEmpty()) continue
            groups[event] = parsed
            if (event !in Events.mappable) unmapped += event
        }
        return Manifest(groups, unmapped)
    }

    /**
     * 工具事件匹配（官方语义：matcher 为 regex，空 = 全匹配）。
     * 非工具事件（SessionStart 等）不受 matcher 约束 —— 恒真。
     */
    fun matchesTool(group: MatcherGroup, toolName: String?): Boolean {
        if (group.matcher.isBlank()) return true
        if (toolName == null) return false
        return runCatching { Regex(group.matcher).matches(toolName) }
            .getOrDefault(runCatching { group.matcher == toolName }.getOrDefault(false))
    }
}
