package com.apex.agent.di

import com.apex.agent.core.engine.PrivilegeInfoProvider
import com.apex.agent.core.tools.DefaultToolRegistry
import com.apex.agent.core.tools.SafeAgentTool
import com.apex.agent.core.tools.ToolEnvironmentState
import com.apex.agent.core.tools.catalog.CapabilityReportTool
import com.apex.agent.core.tools.catalog.MarketSearchTool
import com.apex.agent.core.tools.mcp.McpManager
import com.apex.agent.core.tools.marketplace.HubSource
import com.apex.agent.core.tools.skill.SkillRegistry
import com.apex.agent.github.GithubTokenManager

/**
 * §12b 能力自省元工具的注册体 —— 自 ToolModule.kt 拆分（浏览器角色门 PR
 * 对主文件的增量叠加把行数再次顶破 1200 预算，预留余量；注册内容逐字节
 * 原样迁移，七参数均为单一使用方，边界与既有卫星文件同样天然清晰）。
 */
internal fun registerCapabilityIntrospectionTools(
    registry: DefaultToolRegistry,
    environmentState: ToolEnvironmentState,
    privilegeInfoProvider: PrivilegeInfoProvider,
    skillRegistry: SkillRegistry,
    mcpManager: McpManager,
    githubTokenManager: GithubTokenManager,
    hubSource: HubSource
) {
    // ═══ 12b. 能力自省元工具（「agent 不懂自己能干什么」的根因修复）═══
    // CapabilityReport：权限阶梯（与系统提示词同源）/ 实时环境 / 工具与
    // 技能与 MCP 库存 + 扩展梯度，一键自省——遇 permission denied 或
    // 「我能做 X 吗」时先自省再行动，不猜不弃；
    // MarketSearch：官方技能+MCP 市场检索（此前 7 个市场源只服务 UI，
    // agent 侧完全不可见）——「没装的能力一步之遥」。
    registry.register(
        SafeAgentTool(
            CapabilityReportTool(
                registry = registry,
                environmentState = environmentState,
                privilegeLevel = { privilegeInfoProvider.currentLevel() },
                installedSkillCount = { skillRegistry.getInstalled().size },
                configuredMcpCount = { mcpManager.getConfigs().size },
                connectedMcpCount = { mcpManager.getConnectedServers().size },
                // v3 S3：GitHub 连接快照（"login|repo"，未设置仓库 = "-"；
                // 未连接 = null）—— CapabilityReport 的 GitHub 行数据源
                // v2.1.0：Browser 行仅授权会话可见（未授权=provider 返 null=整行省略）
                browserRuntimeProvider = {
                    com.apex.agent.tools.BrowserRoleAuth.currentGrants()
                        .takeIf { com.apex.agent.core.tools.BrowserRolePolicy.ROLE_BROWSER_AUTOMATION in it }
                        ?.let { "ready (engine in-process; roles granted: browser-automation" +
                            (if (com.apex.agent.tools.BrowserRoleAuth.currentGrants().contains(
                                    com.apex.agent.core.tools.BrowserRolePolicy.ROLE_BROWSER_SENSITIVE)) " + sensitive" else "") + ")" }
                },
                githubStateProvider = {
                    githubTokenManager.connectionState.value.takeIf { it.isConnected }
                        ?.let { s ->
                            val login = s.username ?: "(unknown)"
                            val repo = githubTokenManager.defaultRepo.value.ifBlank { "-" }
                            "$login|$repo"
                        }
                }
            )
        )
    )
    registry.register(
        SafeAgentTool(
            MarketSearchTool(
                fetchSkills = { hubSource.listSkills() },
                fetchMcpServers = { hubSource.listMcpServers() }
            )
        )
    )
}
