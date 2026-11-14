package com.apex.agent.core.tools.pluginhost.bridge

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * # PluginEventPayloads —— 宿主事件 payload 契约（单一真相源）
 *
 * Phase 6 收尾前 [PluginEventBus] 只有 PLUGIN_STARTED/STOPPED/CRASHED 三个
 * 生产事件（PluginLifecycleManager 派发）——工具/会话族事件**零生产者**，
 * OpenCode 钩子转发与 Claude Hooks 订阅永远收不到事件。本对象定义工具
 * 事件族的 payload 形状并给出生产/消费两侧的编解码：
 *
 * ```
 * {"tool_name": "<registry id>", "tool_input": <arguments JSON>, "session_id": "..."}
 * ```
 *
 * - 生产方：app 层执行器插槽（ToolModule.buildV3Executor /
 *   EngineToolExecutorsModule.buildEnvOnlyExecutor 的 before/afterToolHooks）；
 * - 消费方：ClaudeCompatWiring（→ 官方 stdin JSON）、OpenCode 事件转发
 *   （→ Worker `tool.execute.before/after`）。
 *
 * 字段语义对齐 Claude 官方 Common input fields（tool_name/tool_input/
 * session_id）——Claude 钩子适配层零翻译直用；OpenCode 侧按其生态语义解释。
 */
object PluginEventPayloads {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 生产侧：构造工具事件 payload（tool_input 原文非法 JSON 时降级为字符串值）。 */
    fun toolCall(toolId: String, argsRaw: String, sessionId: String? = null): String {
        val input = runCatching { json.parseToJsonElement(argsRaw) }
            .getOrElse { JsonPrimitive(argsRaw) }
        return JsonObject(
            buildMap {
                put("tool_name", JsonPrimitive(toolId))
                put("tool_input", input)
                if (!sessionId.isNullOrBlank()) put("session_id", JsonPrimitive(sessionId))
            }
        ).toString()
    }

    /** 消费侧：解出的工具事件字段（缺省诚实降级，解析失败不抛）。 */
    data class ToolCallPayload(
        val toolName: String?,
        val toolInputJson: String,
        val sessionId: String?
    )

    /** 消费侧：解析工具事件 payload；损坏 → 全缺省（永不抛）。 */
    fun parseToolCall(payloadJson: String): ToolCallPayload {
        val obj = runCatching { json.parseToJsonElement(payloadJson).jsonObject }
            .getOrNull() ?: return ToolCallPayload(null, "{}", null)
        return ToolCallPayload(
            toolName = obj["tool_name"]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() },
            toolInputJson = obj["tool_input"]?.toString() ?: "{}",
            sessionId = obj["session_id"]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
        )
    }
}
