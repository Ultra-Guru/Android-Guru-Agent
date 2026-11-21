package com.apex.agent.core.tools.fusion.rea

import com.apex.agent.core.tools.mcp.McpException
import com.apex.agent.core.tools.mcp.McpServerConfig
import com.apex.agent.core.tools.mcp.McpTransport
import com.apex.agent.core.tools.mcp.McpTransportHandle
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * # REA 内置 MCP 服务器（进程内 transport，FusionBundle 首发载体）
 *
 * 架构同 BuiltinSearchMcpTransport / BuiltinGithubMcpTransport（五台既有
 * 内置服务器的第六台）：进程内 JSON-RPC —— initialize / tools-list /
 * tools-call 三方法，零子进程、零网络握手，与宿主 App 同生命周期。
 *
 * 差异点：工具面来自 [ReaToolCatalog]（207 条全量，与上游 rea-agents
 * v6.5.0 的 tools/list 逐名对齐）；tools/call 经 [ReaEngine] 分层调度
 * （ON_DEVICE 深实现 / 设备 shell / 沙箱桥 / web 抓取 / 供应商门控）。
 *
 * core 纯 JVM 定位的意义：本服务器与全部分析器可在 JVM 单测里完整
 * 驱动（tools/list 计数、tools/call 真跑分析、降级信封形态）。
 */
object ReaBuiltinMcpServer {
    const val ID = "rea"

    fun config(): McpServerConfig = McpServerConfig(
        name = ID,
        transport = McpTransport.BUILTIN,
        enabled = true,
        scope = "agent"
    )
}

/**
 * 进程内 transport 实例（每连接一个 —— McpManager 工厂按连接构造）。
 *
 * @param commandRunner 设备 shell / 沙箱命令执行端口（app 层接线；null = 对应分层降级）
 * @param pageFetcher 页面抓取端口（app 层接线；null = web 分层降级）
 * @param workspace 引擎工作目录（证据导出 / 提取落盘根）
 */
class ReaBuiltinMcpTransport(
    commandRunner: ReaCommandRunner? = null,
    pageFetcher: ReaPageFetcher? = null,
    workspace: ReaWorkspace? = null
) : McpTransportHandle {

    private val json = Json { ignoreUnknownKeys = true }
    private val engine = ReaEngine(commandRunner, pageFetcher, workspace)

    override suspend fun send(id: Int?, payload: String): JsonObject? {
        if (id == null) return null // 通知无响应

        val request = try {
            json.parseToJsonElement(payload).jsonObject
        } catch (e: Exception) {
            throw McpException("内置 REA MCP 收到非法 JSON-RPC 报文：${payload.take(80)}")
        }
        val method = request["method"]?.jsonPrimitive?.contentOrNull
            ?: throw McpException("内置 REA MCP 报文缺少 method 字段")
        val params = request["params"] as? JsonObject ?: JsonObject(emptyMap())

        return buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            when (method) {
                "initialize" -> put("result", buildJsonObject {
                    put("protocolVersion", PROTOCOL_VERSION)
                    putJsonObject("capabilities") { putJsonObject("tools") {} }
                    putJsonObject("serverInfo") {
                        put("name", "rea-fusion-builtin")
                        put("version", SERVER_VERSION)
                    }
                })
                "tools/list" -> put("result", buildJsonObject {
                    put("tools", ReaToolCatalog.renderToolsList())
                })
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

    // ── tools/call 派发 ─────────────────────────────────────────────────

    private suspend fun callTool(params: JsonObject): JsonObject {
        val name = params["name"]?.jsonPrimitive?.contentOrNull
            ?: return textResult("缺少工具名", isError = true)
        val arguments = (params["arguments"] as? JsonObject ?: JsonObject(emptyMap()))
        return try {
            val result = engine.callTool(name, arguments)
            textResult(result.toString())
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e // 取消向上传播（MCP 客户端负责超时语义）
        } catch (e: Exception) {
            textResult(
                "REA 工具执行失败: ${e.message ?: e::class.simpleName}",
                isError = true
            )
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
        const val SERVER_VERSION = "6.5.0-fusion.1"
    }
}
