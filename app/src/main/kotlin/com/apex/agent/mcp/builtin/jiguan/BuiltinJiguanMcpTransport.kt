package com.apex.agent.mcp.builtin.jiguan

import com.apex.agent.core.jiguan.JiguanOrchestrator
import com.apex.agent.core.jiguan.JiguanRuntime
import com.apex.agent.core.jiguan.tools.JiguanCallJavaMethodTool
import com.apex.agent.core.jiguan.tools.JiguanCapabilityReportTool
import com.apex.agent.core.jiguan.tools.JiguanDisassembleTool
import com.apex.agent.core.jiguan.tools.JiguanHardwareBreakpointTool
import com.apex.agent.core.jiguan.tools.JiguanHookJavaMethodTool
import com.apex.agent.core.jiguan.tools.JiguanHookNativeFuncTool
import com.apex.agent.core.jiguan.tools.JiguanJavaClassesTool
import com.apex.agent.core.jiguan.tools.JiguanKnowledgeQueryTool
import com.apex.agent.core.jiguan.tools.JiguanKnowledgeRememberTool
import com.apex.agent.core.jiguan.tools.JiguanMemoryHexDumpTool
import com.apex.agent.core.jiguan.tools.JiguanMemoryScanTool
import com.apex.agent.core.jiguan.tools.JiguanMemoryWriteTool
import com.apex.agent.core.jiguan.tools.JiguanModulesTool
import com.apex.agent.core.jiguan.tools.JiguanPacketCaptureTool
import com.apex.agent.core.jiguan.tools.JiguanProcessesTool
import com.apex.agent.core.jiguan.tools.JiguanRecipesTool
import com.apex.agent.core.jiguan.tools.JiguanSslBypassTool
import com.apex.agent.core.jiguan.tools.JiguanSystemInspectTool
import com.apex.agent.core.jiguan.tools.JiguanUiTouchTool
import com.apex.agent.core.tools.AgentTool
import com.apex.agent.core.tools.mcp.McpException
import com.apex.agent.core.tools.mcp.McpTransportHandle
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * 内置机关（Jiguan）MCP 服务器的进程内 transport —— 17 工具薄委托。
 *
 * 工具实现与 ToolRegistry 直连注册**完全同源**（同一个 JiguanRuntime /
 * JiguanOrchestrator 构造的工具实例），MCP 面只做 JSON-RPC 协议适配：
 * - tools/list：id/name/description/parametersSchema 透传（snake_case 名）；
 * - tools/call：arguments JSON 原样交给工具的 execute（工具自带防御式解析）。
 */
class BuiltinJiguanMcpTransport(
    runtime: JiguanRuntime,
    orchestrator: JiguanOrchestrator,
) : McpTransportHandle {

    private val json = Json { ignoreUnknownKeys = true }

    /** 17 工具表（snake_case MCP 名 → 工具实例）。 */
    private val tools: List<Pair<String, AgentTool>> = listOf(
        "jiguan_capability_report" to JiguanCapabilityReportTool(orchestrator),
        "jiguan_recipes" to JiguanRecipesTool(orchestrator),
        "jiguan_processes" to JiguanProcessesTool(runtime),
        "jiguan_memory_scan" to JiguanMemoryScanTool(runtime),
        "jiguan_memory_hex_dump" to JiguanMemoryHexDumpTool(runtime),
        "jiguan_memory_write" to JiguanMemoryWriteTool(runtime),
        "jiguan_java_classes" to JiguanJavaClassesTool(runtime),
        "jiguan_hook_java_method" to JiguanHookJavaMethodTool(runtime),
        "jiguan_call_java_method" to JiguanCallJavaMethodTool(runtime),
        "jiguan_modules" to JiguanModulesTool(runtime),
        "jiguan_hook_native_func" to JiguanHookNativeFuncTool(runtime),
        "jiguan_disassemble" to JiguanDisassembleTool(runtime),
        "jiguan_hardware_breakpoint" to JiguanHardwareBreakpointTool(runtime),
        "jiguan_packet_capture" to JiguanPacketCaptureTool(runtime),
        "jiguan_ssl_bypass" to JiguanSslBypassTool(runtime),
        "jiguan_ui_touch" to JiguanUiTouchTool(runtime),
        "jiguan_system_inspect" to JiguanSystemInspectTool(runtime),
        "jiguan_knowledge_query" to JiguanKnowledgeQueryTool(runtime),
        "jiguan_knowledge_remember" to JiguanKnowledgeRememberTool(runtime),
    )

    // ── McpTransportHandle ─────────────────────────────────────────

    override suspend fun send(id: Int?, payload: String): JsonObject? {
        if (id == null) return null // 通知无响应
        val request = try {
            json.parseToJsonElement(payload).jsonObject
        } catch (e: Exception) {
            throw McpException("内置 Jiguan MCP 收到非法 JSON-RPC 报文：${payload.take(80)}")
        }
        val method = request["method"]?.jsonPrimitive?.contentOrNull
            ?: throw McpException("内置 Jiguan MCP 报文缺少 method 字段")
        val params = request["params"] as? JsonObject ?: JsonObject(emptyMap())
        return buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            when (method) {
                "initialize" -> put("result", buildJsonObject {
                    put("protocolVersion", PROTOCOL_VERSION)
                    putJsonObject("capabilities") { putJsonObject("tools") {} }
                    putJsonObject("serverInfo") {
                        put("name", "jiguan-builtin")
                        put("version", "1.0.0")
                    }
                })
                "tools/list" -> put("result", buildJsonObject { put("tools", toolDefinitions()) })
                "tools/call" -> put("result", callTool(params))
                else -> put("error", buildJsonObject {
                    put("code", METHOD_NOT_FOUND)
                    put("message", "Method not found: $method")
                })
            }
        }
    }

    override fun isHealthy(): Boolean = true

    override fun close() = Unit

    // ── 协议构造 ───────────────────────────────────────────────────

    private fun toolDefinitions(): JsonArray = JsonArray(
        tools.map { (name, tool) ->
            buildJsonObject {
                put("name", name)
                put("description", tool.description)
                put("inputSchema", json.parseToJsonElement(tool.parametersSchema).jsonObject)
            }
        },
    )

    private suspend fun callTool(params: JsonObject): JsonObject {
        val name = params["name"]?.jsonPrimitive?.contentOrNull
        val arguments = params["arguments"]?.let {
            runCatching { json.encodeToString(JsonObject.serializer(), it as JsonObject) }.getOrDefault("{}")
        } ?: "{}"
        return try {
            val tool = tools.firstOrNull { it.first == name }?.second
                ?: return textResult(
                    "未知工具: $name（可用: ${tools.joinToString(", ") { it.first }}）",
                    isError = true,
                )
            textResult(tool.execute(arguments))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            textResult("❌ jiguan 工具执行失败: ${e.message ?: e::class.simpleName}", isError = true)
        }
    }

    private fun textResult(text: String, isError: Boolean = false): JsonObject = buildJsonObject {
        putJsonArray("content") {
            add(buildJsonObject {
                put("type", "text")
                put("text", text)
            })
        }
        if (isError) put("isError", true)
    }

    private companion object {
        const val PROTOCOL_VERSION = "2024-11-05"
        const val METHOD_NOT_FOUND = -32601
    }
}
