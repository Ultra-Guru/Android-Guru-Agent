package com.apex.agent.core.tools.fusion

import com.apex.agent.core.tools.ToolAnnotations
import com.apex.agent.core.tools.ToolArguments
import com.apex.agent.core.tools.ToolCategory
import com.apex.agent.core.tools.ToolErrorCode
import com.apex.agent.core.tools.ToolMetadata
import com.apex.agent.core.tools.ToolResult
import com.apex.agent.core.tools.ToolRisk
import com.apex.agent.core.tools.ToolSchema
import com.apex.agent.core.tools.builtin.BaseTool
import com.apex.agent.core.tools.toolSchema

/**
 * # Fusion 工具族（v5 PascalCase）—— Agent 自主闭环的 FusionBundle 入口
 *
 * 与 Skill 族同构的三件套（模型可在对话里自主发现、激活复合包）：
 * - `FusionList`：已装包目录（digest 行 + 组件计数 + 分层统计）；
 * - `FusionInfo`：单包详情（agent 人设路由 + 技能/MCP/引擎清单）；
 * - `FusionActivate`：**激活**（装技能 + 连 MCP + 注入人设）—— 激活后
 *   该体系的全部工具与工作流立即可用（「直接使用这个体系 agent」）。
 *
 * 注册：app 层 ToolModule 卫星文件（registerFusionBundleTools），scope=agent。
 */
class FusionListTool(
    private val registry: FusionBundleRegistry
) : BaseTool(
    id = "FusionList",
    name = "FusionBundle Catalog",
    description = """
        List installed FusionBundles (compound capability packs aggregating
        skills + MCP servers + tools + an agent workflow). Each row: id, name,
        version, component counts (skills/mcp/toolHints/engines), scope, trust.
        Use FusionInfo for one pack's full capability surface, FusionActivate
        to turn the whole system on for this session.
    """.trimIndent(),
    declaredSchema = toolSchema { }
) {
    override fun buildMetadata(): ToolMetadata = ToolMetadata.meta(id) {
        category(ToolCategory.UTILITY)
        risk(ToolRisk.LOW)
        tag("fusion", "bundle", "catalog")
        annotations(ToolAnnotations.readOnly())
    }

    override suspend fun executeStructured(arguments: String): ToolResult {
        val digests = registry.digests()
        if (digests.isEmpty()) {
            return ToolResult.ok("未安装任何 FusionBundle（内置包随首启释放；市场与 manifest JSON 安装即将就结）")
        }
        val rows = digests.joinToString("\n") { d ->
            "- ${d.id}「${d.name}」v${d.version} [${d.scope}] trust=${d.trustLevel}" +
                "${if (d.bundled) "（内置）" else ""} —— 技能×${d.skillCount} MCP×${d.mcpServerCount} " +
                "工具提示×${d.toolHintCount} 引擎×${d.engineCount}\n  ${d.description}"
        }
        return ToolResult.ok("FusionBundle 目录（${digests.size} 个）：\n$rows")
    }
}

class FusionInfoTool(
    private val registry: FusionBundleRegistry
) : BaseTool(
    id = "FusionInfo",
    name = "FusionBundle Detail",
    description = """
        Full detail of one FusionBundle: agent persona & routing, aggregated
        skills, MCP servers (with tool surfaces), runtime engines (BYO model),
        and tool activation hints. Input: {"id":"rea"}.
    """.trimIndent(),
    declaredSchema = toolSchema {
        string("id", required = true, description = "FusionBundle id（见 FusionList）")
    }
) {
    override fun buildMetadata(): ToolMetadata = ToolMetadata.meta(id) {
        category(ToolCategory.UTILITY)
        risk(ToolRisk.LOW)
        tag("fusion", "bundle", "detail")
        annotations(ToolAnnotations.readOnly())
    }

    override suspend fun executeStructured(arguments: String): ToolResult {
        val args = when (val parsed = ToolArguments.of(arguments)) {
            is ToolArguments.ParseOutcome.Ok -> parsed.args
            is ToolArguments.ParseOutcome.Bad -> return parsed.result
        }
        val id = args.requireString("id")
        val m = registry.get(id)
            ?: return ToolResult.fail(ToolErrorCode.NOT_FOUND, "FusionBundle '$id' 未安装（FusionList 查看）")
        val sb = StringBuilder()
        sb.appendLine("「${m.name}」v${m.version} —— ${m.description}")
        if (m.agent.persona.isNotBlank()) sb.appendLine("人设：${m.agent.persona}")
        if (m.agent.routingHints.isNotBlank()) sb.appendLine("路由：${m.agent.routingHints}")
        if (m.skills.isNotEmpty()) {
            sb.appendLine("技能（${m.skills.size}）：")
            m.skills.forEach { sb.appendLine("  - ${it.skillId}：${it.manifest.description}") }
        }
        if (m.mcpServers.isNotEmpty()) {
            sb.appendLine("MCP 服务器（${m.mcpServers.size}）：")
            m.mcpServers.forEach {
                sb.appendLine("  - ${it.serverId}（${it.transport}，scope=${it.scope}）${it.role}")
            }
        }
        if (m.toolActivationHints.isNotEmpty()) {
            sb.appendLine("旗舰工具：${m.toolActivationHints.joinToString(", ")}")
        }
        if (m.runtime.engines.isNotEmpty()) {
            sb.appendLine("可选引擎（BYO）：")
            m.runtime.engines.forEach {
                sb.appendLine("  - ${it.name}：${it.purpose}${if (it.installHint.isNotBlank()) "（${it.installHint}）" else ""}")
            }
        }
        return ToolResult.ok(sb.toString().trim())
    }
}

class FusionActivateTool(
    private val registry: FusionBundleRegistry,
    private val activator: FusionBundleActivator
) : BaseTool(
    id = "FusionActivate",
    name = "FusionBundle Activate",
    description = """
        Activate a FusionBundle: installs its skills, connects its MCP servers
        (all tools become first-class), and injects its agent workflow into the
        system prompt — the whole aggregated system is then directly usable.
        Input: {"id":"rea"}. Deactivate with {"id":"rea","deactivate":true}.
    """.trimIndent(),
    declaredSchema = toolSchema {
        string("id", required = true, description = "FusionBundle id")
        boolean("deactivate", description = "true = 去激活（恢复提示词；技能与连接留在体系内）", defaultValue = false)
    }
) {
    override fun buildMetadata(): ToolMetadata = ToolMetadata.meta(id) {
        category(ToolCategory.UTILITY)
        risk(ToolRisk.LOW)
        tag("fusion", "bundle", "activate", "aggregate")
    }

    override suspend fun executeStructured(arguments: String): ToolResult {
        val args = when (val parsed = ToolArguments.of(arguments)) {
            is ToolArguments.ParseOutcome.Ok -> parsed.args
            is ToolArguments.ParseOutcome.Bad -> return parsed.result
        }
        val id = args.requireString("id")
        if (args.booleanWithDefault("deactivate", false)) {
            val r = activator.deactivate()
            return r.fold(
                onSuccess = { ToolResult.ok("FusionBundle '$id' 已去激活（人设恢复；技能/MCP 留在各自体系）") },
                onFailure = { ToolResult.fail(ToolErrorCode.EXECUTION_FAILED, it.message ?: "去激活失败") }
            )
        }
        val r = activator.activate(id)
        return r.fold(
            onSuccess = { act ->
                val sb = StringBuilder()
                sb.appendLine("FusionBundle「${act.bundleName}」已激活 —— 体系 agent 就绪。")
                sb.appendLine("技能就位：${act.installedSkills.joinToString(", ").ifBlank { "（无）" }}")
                sb.appendLine("MCP 已连：${act.connectedServers.joinToString(", ").ifBlank { "（无）" }}")
                if (act.toolHints.isNotEmpty()) {
                    sb.appendLine("旗舰工具：${act.toolHints.joinToString(", ")}")
                }
                if (act.skipped.isNotEmpty()) {
                    sb.appendLine("跳过组件（聚合容错）：${act.skipped.joinToString("; ")}")
                }
                sb.appendLine("工作流已注入会话 —— 直接下达逆向工程任务即可。")
                ToolResult.ok(sb.toString().trim())
            },
            onFailure = {
                ToolResult.fail(ToolErrorCode.EXECUTION_FAILED, it.message ?: "激活失败")
            }
        )
    }
}
