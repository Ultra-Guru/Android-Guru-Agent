package com.apex.agent.core.tools.pluginhost.bridge

import com.apex.agent.core.tools.AgentTool
import com.apex.agent.core.tools.ToolAnnotations
import com.apex.agent.core.tools.ToolCategory
import com.apex.agent.core.tools.ToolMetadata
import com.apex.agent.core.tools.ToolRisk
import com.apex.agent.core.tools.catalog.ToolSchemaSanitizer
import com.apex.agent.core.tools.pluginhost.PluginEcosystem
import kotlinx.coroutines.CancellationException

/**
 * # PluginAgentTool —— 插件工具的一等 AgentTool 桥（Tool System v4/v5）
 *
 * 与 [com.apex.agent.core.tools.catalog.McpAgentTool] 同款纪律：第三方插件
 * 工具注册后与内置工具完全同权 —— 可被 ToolSearch 搜到、被 ToolOpen 激活、
 * 被引擎直接调用（spec §12：安装插件之后 Agent 必须真正能用它）。
 *
 * - **注册 id**：`Plugin{Eco}{Plugin}{Tool}`（无分隔符 PascalCase，v5 命名），
 *   `Plugin` 前缀是工具族徽章（与 `Mcp` 前缀同构）；
 * - **schema**：插件上报的 parametersSchema 经 [ToolSchemaSanitizer] 清洗
 *   （坏 schema 降级骨架，永不带崩请求）；
 * - **执行**：路由到 [executor]（生产实现 = Supervisor → Worker IPC；
 *   测试注入 fake）；
 * - **结果**：钳制长度 + 失败走 "Error:" 前缀协议（引擎的成功判定是
 *   字符串协议 —— 裸错误文本会被误判成功，这是 MCP 桥踩过的真雷）；
 * - **元数据**：PLUGIN 类别、MEDIUM 风险（未知第三方能力不默认可信）、
 *   非幂等不重试。
 */
class PluginAgentTool(
    /** 插件 id（账本主键，如 `opencode.scope-tool`）。 */
    val pluginId: String,
    /** 插件生态（描述与元数据展示用）。 */
    val ecosystem: PluginEcosystem,
    /** Worker 发现的原始工具描述。 */
    val tool: ToolDescriptor,
    /** 执行路由（suspend → [ToolExecutionOutcome]）。 */
    private val executor: PluginToolExecutor
) : AgentTool {

    /** 插件工具的原始描述（name/description/parametersSchema）。 */
    data class ToolDescriptor(
        val name: String,
        val description: String = "",
        val parametersSchema: String? = null
    )

    /**
     * 执行结果 DTO。
     *
     * 刻意不用 `Result<String>`：suspend 函数边界上返回 Result 会触发
     * Kotlin 的双重装箱陷阱（失败被偷抛、成功被再包一层 —— 实测
     * `Result cannot be cast to String`）。DTO 跨协程边界语义稳定。
     */
    data class ToolExecutionOutcome(
        val ok: Boolean,
        val output: String = "",
        val error: String? = null
    ) {
        companion object {
            fun success(output: String) = ToolExecutionOutcome(true, output)
            fun failure(error: String) = ToolExecutionOutcome(false, error = error)
        }
    }

    /** 执行路由端口（生产 = Supervisor.callTool；测试 = fake）。 */
    fun interface PluginToolExecutor {
        suspend fun execute(
            pluginId: String,
            toolName: String,
            argsJson: String
        ): ToolExecutionOutcome
    }

    override val id: String = PluginToolNaming.toolId(pluginId, tool.name)
    override val name: String = "Plugin ${PluginToolNaming.pretty(pluginId)}/${tool.name}"

    override val description: String = buildString {
        append("Plugin tool '${tool.name}' from '$pluginId' (${ecosystem.name}). ")
        append(tool.description.trim().ifEmpty { "No description provided by the plugin." })
    }.let { ToolSchemaSanitizer.clampDescription(it) }

    override val parametersSchema: String = ToolSchemaSanitizer.sanitize(
        tool.parametersSchema?.ifBlank { null } ?: """{"type":"object","properties":{}}"""
    )

    override val metadata: ToolMetadata = ToolMetadata.meta(id) {
        category(ToolCategory.PLUGIN)
        risk(ToolRisk.MEDIUM)
        tag("plugin", "plugin-id:$pluginId", "ecosystem:${ecosystem.name.lowercase()}")
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
            val outcome = executor.execute(pluginId, tool.name, arguments)
            if (outcome.ok) {
                clamp(outcome.output.ifBlank { "(empty result)" })
            } else {
                renderFailure(pluginId, tool.name, outcome.error ?: "unknown error")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            renderFailure(pluginId, tool.name, e.message ?: e::class.simpleName ?: "unknown")
        }
    }

    private fun clamp(content: String, max: Int = MAX_RESULT_CHARS): String =
        if (content.length <= max) content
        else content.take(max) + "\n[Plugin result truncated at $MAX_RESULT_CHARS chars]"

    private companion object {
        const val MAX_RESULT_CHARS = 16_000

        /** "Error:" 前缀协议 + 换路指引（与 McpAgentTool 同款语义）。 */
        fun renderFailure(pluginId: String, toolName: String, detail: String): String =
            "Error: Plugin tool '$pluginId/$toolName' failed: $detail. " +
                "If the plugin crashed, call PluginHostDoctor for restart state before retrying.\n" +
                "The task is NOT aborted — pick a different approach or a different tool."
    }
}

/**
 * 插件工具命名（v5 无分隔符 PascalCase，`Plugin` 族前缀）。
 *
 * 插件 id 形如 `opencode.scope-tool`（生态前缀 + slug）—— 归一后拼进工具 id，
 * 同一插件的工具天然同前缀，跨插件撞名只可能发生在「生态+slug+工具名」
 * 三元组完全一致时（同一插件的重复注册，REPLACE/REJECT 语义处理）。
 */
object PluginToolNaming {

    private val NON_ALNUM = Regex("[^A-Za-z0-9]")

    /** 注册表 id：`Plugin{Eco}{Plugin}{Tool}`。 */
    fun toolId(pluginId: String, toolName: String): String {
        val segments = pluginId.split(NON_ALNUM).filter { it.isNotEmpty() }
        return buildString {
            append("Plugin")
            segments.forEach { append(it.replaceFirstChar { ch -> ch.uppercaseChar() }) }
            append(slug(toolName))
        }
    }

    /** 工具名归一（非字母数字切段首字母大写连写）。 */
    fun slug(raw: String): String =
        raw.split(NON_ALNUM)
            .filter { it.isNotEmpty() }
            .joinToString("") { it.replaceFirstChar { ch -> ch.uppercaseChar() } }
            .ifEmpty { "Tool" }

    /** 人读名（保留原始分隔符）。 */
    fun pretty(pluginId: String): String = pluginId
}
