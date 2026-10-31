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
 * 与 Skill 族同构的管理面（模型可在对话里自主发现、创建、激活复合包）：
 * - `FusionList`：已装包目录（digest 行 + 组件计数 + 分层统计）；
 * - `FusionInfo`：单包详情（agent 人设路由 + 技能/MCP/引擎清单）；
 * - `FusionActivate`：**激活**（装技能 + 连 MCP + 注入人设）—— 激活后
 *   该体系的全部工具与工作流立即可用（「直接使用这个体系 agent」）；
 * - `FusionCreate`：**自建聚合**（把已装的技能/MCP 组合成新包 —— 对齐
 *   SkillCreate 的模型自主管理闭环；id 冲突自动加时间戳后缀）；
 * - `FusionInstallMembers`：**安装成员**（仓库引用按需下载装齐，不激活）。
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
    private val activator: FusionBundleActivator,
    /** 模型侧激活回灌（null = 无观察者；生产接线见 ToolModuleFusionRegistration）。 */
    private val activationSink: FusionActivationSink? = null
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
                onSuccess = {
                    activationSink?.onDeactivated(id)
                    ToolResult.ok("FusionBundle '$id' 已去激活（人设恢复；技能/MCP 留在各自体系）")
                },
                onFailure = { ToolResult.fail(ToolErrorCode.EXECUTION_FAILED, it.message ?: "去激活失败") }
            )
        }
        val r = activator.activate(id)
        return r.fold(
            onSuccess = { act ->
                activationSink?.onActivated(act)
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

/**
 * `FusionCreate` —— 模型自建聚合（对齐 SkillCreate 的自主管理闭环）。
 *
 * 成员三源由 app 层以 lambda 注入（core 零依赖）：已装技能/已配 MCP 的
 * 存在性校验；from_active=true 时把当前激活技能集全量收入。id 由 name
 * slug 化生成，撞既有包自动加时间戳后缀 —— 模型无需关心 id 唯一性。
 * 保存即落盘（registry.install），王冠菜单立即可选。
 */
class FusionCreateTool(
    private val registry: FusionBundleRegistry,
    /** 已装技能 id 快照（app 层供 SkillRegistry.getInstalled 映射）。 */
    private val installedSkillIds: () -> Set<String>,
    /** 已配 MCP 服务器名快照（app 层供 McpManager.getConfigs 映射）。 */
    private val configuredMcpNames: () -> Set<String>,
    /** 当前激活技能快照（from_active=true 时的成员来源）。 */
    private val activeSkillIds: () -> Set<String>
) : BaseTool(
    id = "FusionCreate",
    name = "FusionBundle Create",
    description = """
        Create a new FusionBundle from ALREADY-INSTALLED members: the pack
        becomes selectable from the crown menu and activates as one unit
        (skills install, MCP servers connect, agent persona injects).
        Pass explicit member id lists, or from_active=true to snapshot the
        currently-activated skills. Id is derived from name (collision-safe).
    """.trimIndent(),
    declaredSchema = toolSchema {
        string("name", required = true, description = "展示名（中文界面给中文名；id 自动 slug 化）")
        string("description", description = "一句话说明这个聚合的用途（出现在目录与菜单）")
        string("persona", description = "agent 人设（可选——激活后注入的工作流口吻）")
        stringArray("skill_ids", description = "已安装技能 id 列表")
        stringArray("mcp_names", description = "已配置 MCP 服务器名列表")
        boolean("from_active", description = "true = 把当前已激活的技能全量收入（忽略 skill_ids）", defaultValue = false)
    }
) {
    override fun buildMetadata(): ToolMetadata = ToolMetadata.meta(id) {
        category(ToolCategory.UTILITY)
        risk(ToolRisk.LOW)
        tag("fusion", "bundle", "create", "aggregate")
    }

    override suspend fun executeStructured(arguments: String): ToolResult {
        val args = when (val parsed = ToolArguments.of(arguments)) {
            is ToolArguments.ParseOutcome.Ok -> parsed.args
            is ToolArguments.ParseOutcome.Bad -> return parsed.result
        }
        val name = args.requireString("name")
        val description = args.optionalString("description").orEmpty()
        val persona = args.optionalString("persona").orEmpty()
        val fromActive = args.booleanWithDefault("from_active", false)

        val requestedSkills = if (fromActive) activeSkillIds().toList()
        else args.optionalStringList("skill_ids").orEmpty()
        val skills = requestedSkills.filter { it in installedSkillIds() }
        val unknownSkills = requestedSkills.filter { it !in installedSkillIds() }
        val mcps = args.optionalStringList("mcp_names").orEmpty().filter { it in configuredMcpNames() }
        val unknownMcps = args.optionalStringList("mcp_names").orEmpty().filter { it !in configuredMcpNames() }
        if (skills.isEmpty() && mcps.isEmpty()) {
            return ToolResult.fail(
                ToolErrorCode.INVALID_ARGUMENT,
                "没有可加入的成员：给定的 id 都不是已安装实体" +
                    (if (unknownSkills.isNotEmpty()) "；未知技能=" + unknownSkills.joinToString(",") else "") +
                    (if (unknownMcps.isNotEmpty()) "；未知MCP=" + unknownMcps.joinToString(",") else "") +
                    "。先用 FusionList 查看已装包，或确认技能/MCP 已在各自体系安装。"
            )
        }

        val baseId = FusionBundleComposer.slugify(name)
        val bundleId = if (registry.get(baseId) == null) baseId
        else baseId + "-" + java.lang.Long.toString(System.currentTimeMillis(), 36)
        val manifest = FusionBundleComposer.build(
            bundleId = bundleId,
            name = name,
            persona = persona,
            skills = emptyList(),
            mcpServers = emptyList(),
            plugins = emptyList(),
            skillPicks = skills,
            mcpPicks = mcps
        )
        val described = if (description.isBlank()) manifest
        else manifest.copy(description = description)
        return registry.install(FusionBundleComposer.toJson(described)).fold(
            onSuccess = {
                ToolResult.ok(
                    "FusionBundle「" + it.name + "」（id=" + bundleId + "）已创建：" +
                        skills.size + " 技能 · " + mcps.size + " MCP（安装型引用）。" +
                        "用户可在王冠菜单选用激活；装成员用 FusionInstallMembers。"
                )
            },
            onFailure = { ToolResult.fail(ToolErrorCode.EXECUTION_FAILED, "创建失败: " + (it.message ?: "")) }
        )
    }
}

/**
 * `FusionInstallMembers` —— 安装包成员（仓库引用按需下载），**不激活**。
 * 与 FusionActivate 的分工：装到位（技能进注册表 / MCP 配置落盘）但不
 * 连接不注入 —— 激活留给用户显式选用。
 */
class FusionInstallMembersTool(
    private val activator: FusionBundleActivator
) : BaseTool(
    id = "FusionInstallMembers",
    name = "FusionBundle Install Members",
    description = """
        Install a FusionBundle's members WITHOUT activating: skills land in
        the SkillRegistry (repo picks download on demand), MCP configs are
        written (not connected). Use FusionActivate afterwards to turn the
        whole system on. Input: {"id":"custom-my-toolbox"}.
    """.trimIndent(),
    declaredSchema = toolSchema {
        string("id", required = true, description = "FusionBundle id")
    }
) {
    override fun buildMetadata(): ToolMetadata = ToolMetadata.meta(id) {
        category(ToolCategory.UTILITY)
        risk(ToolRisk.MEDIUM)
        tag("fusion", "bundle", "install")
    }

    override suspend fun executeStructured(arguments: String): ToolResult {
        val args = when (val parsed = ToolArguments.of(arguments)) {
            is ToolArguments.ParseOutcome.Ok -> parsed.args
            is ToolArguments.ParseOutcome.Bad -> return parsed.result
        }
        val id = args.requireString("id")
        return activator.installMembers(id).fold(
            onSuccess = { report ->
                val sb = StringBuilder()
                sb.appendLine("FusionBundle「" + report.bundleName + "」成员已安装（未激活）：")
                sb.appendLine("技能：" + report.installedSkills.joinToString(", ").ifBlank { "（无）" })
                sb.appendLine("MCP 配置：" + (report.configuredServers + report.connectedServers).joinToString(", ").ifBlank { "（无）" })
                sb.appendLine("插件：" + report.installedPlugins.joinToString(", ").ifBlank { "（无）" })
                if (report.skipped.isNotEmpty()) {
                    sb.appendLine("跳过（聚合容错）：" + report.skipped.joinToString("; "))
                }
                sb.append("激活用 FusionActivate。")
                ToolResult.ok(sb.toString().trim())
            },
            onFailure = { ToolResult.fail(ToolErrorCode.EXECUTION_FAILED, it.message ?: "安装失败") }
        )
    }
}
