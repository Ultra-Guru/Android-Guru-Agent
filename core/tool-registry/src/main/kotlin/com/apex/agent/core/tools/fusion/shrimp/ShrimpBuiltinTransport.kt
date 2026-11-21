package com.apex.agent.core.tools.fusion.shrimp

import com.apex.agent.core.tools.mcp.McpException
import com.apex.agent.core.tools.mcp.McpServerConfig
import com.apex.agent.core.tools.mcp.McpTransport
import com.apex.agent.core.tools.mcp.McpTransportHandle
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * # Shrimp Task Manager 内置 MCP 服务器（进程内 transport，第七台）
 *
 * 架构同 ReaBuiltinMcpTransport（第六台）：进程内 JSON-RPC ——
 * initialize / tools-list / tools-call 三方法，零子进程、零网络握手，
 * 与宿主 App 同生命周期。工具面 = 上游 docs/tools.md 的 16 工具全目录
 * （[ShrimpToolCatalog]），tools/call 直连 [ShrimpTaskEngine]。
 *
 * 状态语义：任务数据经 [ShrimpTaskStore]（JSON 原子写 + 备份链）跨连接
 * 持久 —— 工厂每连接构造新 transport、共享同一 store 单例（app 层 DI
 * 提供，filesDir/fusion/shrimp）。
 */
object ShrimpBuiltinMcpServer {
    const val ID = "shrimp"

    fun config(): McpServerConfig = McpServerConfig(
        name = ID,
        transport = McpTransport.BUILTIN,
        enabled = true,
        scope = "all"
    )
}

/**
 * 进程内 transport 实例（每连接一个 —— 共享 store 单例持久任务数据）。
 *
 * @param store 任务存储（app 层 DI 单例；测试用临时目录构造）
 */
class ShrimpBuiltinMcpTransport(
    private val store: ShrimpTaskStore
) : McpTransportHandle {

    private val json = Json { ignoreUnknownKeys = true }
    private val engine = ShrimpTaskEngine(store)

    override suspend fun send(id: Int?, payload: String): JsonObject? {
        if (id == null) return null // 通知无响应

        val request = try {
            json.parseToJsonElement(payload).jsonObject
        } catch (e: Exception) {
            throw McpException("内置 Shrimp MCP 收到非法 JSON-RPC 报文：${payload.take(80)}")
        }
        val method = request["method"]?.jsonPrimitive?.contentOrNull
            ?: throw McpException("内置 Shrimp MCP 报文缺少 method 字段")
        val params = request["params"] as? JsonObject ?: JsonObject(emptyMap())

        return buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            when (method) {
                "initialize" -> put("result", buildJsonObject {
                    put("protocolVersion", PROTOCOL_VERSION)
                    putJsonObject("capabilities") { putJsonObject("tools") {} }
                    putJsonObject("serverInfo") {
                        put("name", "shrimp-fusion-builtin")
                        put("version", SERVER_VERSION)
                    }
                })
                "tools/list" -> put("result", buildJsonObject {
                    put("tools", ShrimpToolCatalog.renderToolsList())
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

    private fun callTool(params: JsonObject): JsonObject {
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
                "Shrimp 工具执行失败: ${e.message ?: e::class.simpleName}",
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
        const val SERVER_VERSION = "1.0.21-fusion.1"
    }
}

/**
 * 工具目录：上游 docs/tools.md 的 16 工具逐名对齐（名/描述/参数面）。
 * 一等公民化后 id 形如 `McpShrimpPlanTask`（v5 组合规则 Mcp{Server}{Tool}）。
 */
object ShrimpToolCatalog {

    /** 紧凑参数声明（渲染 MCP inputSchema 用）。 */
    private data class Arg(
        val name: String,
        val type: String = "string",
        val required: Boolean = false,
        val description: String = ""
    )

    private data class Spec(
        val name: String,
        val description: String,
        val args: List<Arg> = emptyList()
    )

    /** 全量目录（与上游 v1.0.21 docs/tools.md 清单逐名一致；私有类型不外露）。 */
    private val ALL: List<Spec> = listOf(
        Spec(
            "plan_task",
            "Create a comprehensive task plan: archives completed tasks, records plan context, returns planning workflow guidance (decompose, dependencies, complexity, guides)",
            listOf(Arg("description", required = true, description = "Task goal for this planning round"))
        ),
        Spec(
            "update_task",
            "Store the full structured task list (descriptions, prerequisites, analysis, implementation guides, verification steps). Validates DAG, no cycles, no self-reference",
            listOf(Arg("tasks", "array", required = true, description = "Complete task array; each: description, prerequisites(id array), complexity, analysis, implementation_guide, verification_steps, notes, related_files, summary"))
        ),
        Spec(
            "execute_task",
            "Retrieve a task for execution with full context (project rules, recent insights, guides). Blocks until prerequisites are completed",
            listOf(Arg("index", "integer", required = true, description = "Task number (1-based)"))
        ),
        Spec(
            "complete_task",
            "Mark a task as completed with evidence notes and suggest the next unblocked task",
            listOf(
                Arg("index", "integer", required = true, description = "Task number"),
                Arg("notes", description = "Completion evidence (commands and output summary)")
            )
        ),
        Spec(
            "list_tasks",
            "Display all tasks with status and progress statistics"
        ),
        Spec(
            "delete_task",
            "Remove a task from the queue and clean up references",
            listOf(Arg("index", "integer", required = true, description = "Task number"))
        ),
        Spec(
            "split_tasks",
            "Break down a complex task into an ordered subtask chain (original archived, dependents re-linked to the last subtask)",
            listOf(
                Arg("index", "integer", required = true, description = "Task number to split"),
                Arg("subtasks", "array", required = true, description = "Each: description, complexity, implementation_guide, verification_steps")
            )
        ),
        Spec(
            "analyze_task",
            "Store complexity and requirement analysis for a task (returns analysis template when called without analysis)",
            listOf(
                Arg("index", "integer", required = true, description = "Task number"),
                Arg("complexity", description = "low | medium | high"),
                Arg("analysis", description = "Requirement analysis: what to satisfy, risks, verification criteria")
            )
        ),
        Spec(
            "verify_task",
            "Record verification evidence for a completed task (checklist against verification steps)",
            listOf(
                Arg("index", "integer", required = true, description = "Task number"),
                Arg("result", description = "Verification evidence (commands and output summary)")
            )
        ),
        Spec(
            "reflect_task",
            "Record reflection insights on a task approach (what worked, what did not, what to change next time)",
            listOf(
                Arg("index", "integer", required = true, description = "Task number"),
                Arg("insights", "array", description = "Insight strings (or single insight)")
            )
        ),
        Spec(
            "query_task",
            "Search tasks by status or keyword",
            listOf(
                Arg("status", description = "pending | in_progress | completed | archived"),
                Arg("keyword", description = "Substring match in description/summary/notes")
            )
        ),
        Spec(
            "get_task_detail",
            "Retrieve full details of a task (analysis, guides, insights, dependents)",
            listOf(Arg("index", "integer", required = true, description = "Task number"))
        ),
        Spec(
            "clear_all_tasks",
            "Remove all tasks with an automatic backup first (project rules preserved)"
        ),
        Spec(
            "init_project_rules",
            "Initialize project standards (surfaced in every execute_task context for style consistency)",
            listOf(Arg("rules", required = true, description = "Project standards: code style, testing requirements, commit discipline"))
        ),
        Spec(
            "research_mode",
            "Enter systematic research mode for a technology question (define, gather evidence, compare, conclude, feed back into tasks)",
            listOf(Arg("query", required = true, description = "Research question, e.g. GraphQL vs REST"))
        ),
        Spec(
            "process_thought",
            "Process and structure a chain-of-thought step (in-session buffer, not persisted)",
            listOf(Arg("thought", required = true, description = "Current reasoning step"))
        )
    )

    /** 目录规模（错误指引文案用）。 */
    fun size(): Int = ALL.size

    /** 工具名清单（诊断与测试用）。 */
    fun names(): List<String> = ALL.map { it.name }

    /** 渲染 MCP tools/list 的 tools 数组。 */
    fun renderToolsList(): JsonArray = buildJsonArray {
        for (spec in ALL) {
            add(buildJsonObject {
                put("name", spec.name)
                put("description", spec.description)
                putJsonObject("inputSchema") {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        for (arg in spec.args) {
                            put(arg.name, buildJsonObject {
                                put("type", arg.type)
                                if (arg.description.isNotBlank()) put("description", arg.description)
                            })
                        }
                    })
                    if (spec.args.any { it.required }) {
                        put("required", buildJsonArray {
                            spec.args.filter { it.required }.forEach { add(JsonPrimitive(it.name)) }
                        })
                    }
                }
            })
        }
    }
}
