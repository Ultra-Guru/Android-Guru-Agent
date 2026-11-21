package com.apex.agent.di

import com.apex.agent.core.tools.SafeAgentTool
import com.apex.agent.core.tools.ToolRegistry
import com.apex.agent.core.tools.builtin.AppScreenOpenTool
import com.apex.agent.core.tools.builtin.ModelConfigImportTool
import com.apex.agent.core.tools.builtin.ModelProfileUpdateTool
import com.apex.agent.core.tools.builtin.SystemDiagnoseTool
import com.apex.agent.core.tools.mcp.McpManager
import com.apex.agent.core.tools.skill.SkillRegistry
import com.apex.agent.tools.AppControlHosts
import com.apex.agent.tools.AppNavigationBridge
import com.apex.agent.tools.ToolAuditLogger
import com.apex.agent.ui.screen.settings.SettingsRepository

/**
 * # App Control Tools — Tool Registration（§14h）
 *
 * 用户原始诉求：「模型是可以有能力帮我配置模型和等等，能帮我打开设置，
 * 这些类似能力不能用提示词注入，要给 agent 工具，是真实有能力会分析
 * 问题」。
 *
 * 四个工具（本体见 [com.apex.agent.core.tools.builtin.AppControlTools]，
 * Android 侧宿主见 [com.apex.agent.tools.AppControlHosts]）：
 * - **AppScreenOpen** —— 打开本应用页面（经 [AppNavigationBridge] 真实
 *   压栈导航；「帮我打开设置」从此是真按钮不是嘴上说）；
 * - **ModelProfileUpdate** —— 修改模型档案白名单字段（与设置页同一落盘）；
 * - **ModelConfigImport** —— JSON/Python/YAML/TOML 配置文本导入建档
 *   （与设置页「导入」弹窗共用 [com.apex.agent.tools.ModelConfigImportApplier]）；
 * - **SystemDiagnose** —— MCP/Skills/工具审计（含失败详情）结构化快照，
 *   agent 基于真实数据分析问题根因。
 *
 * 与 §14e 的关系：§14e 已有 AgentSettingGet/Set、AgentProfileList、
 * AgentProfileSetDefault、AgentProviderSetKey（读改设置与切默认档）；本段
 * 补齐「打开页面 / 改档案字段 / 导入配置 / 看诊断」四块空白，拼成完整
 * 的「agent 自主帮用户配置模型」闭环。
 */
internal fun registerAppControlTools(
    registry: ToolRegistry,
    settingsRepository: SettingsRepository,
    mcpManager: McpManager,
    skillRegistry: SkillRegistry,
    toolAuditLogger: ToolAuditLogger,
    navigationBridge: AppNavigationBridge
) {
    // 真实导航（LOW）：打开设置/诊断/市场/终端等页面
    registry.register(SafeAgentTool(
        AppScreenOpenTool(AppControlHosts.screenHost(navigationBridge))
    ))

    // 模型档案字段修改（MEDIUM，风险门一次确认；白名单字段，key 不在面上）
    registry.register(SafeAgentTool(
        ModelProfileUpdateTool(AppControlHosts.profileUpdateHost(settingsRepository))
    ))

    // 配置文本导入（MEDIUM；与设置页导入弹窗同一份识别与建档代码）
    registry.register(SafeAgentTool(
        ModelConfigImportTool(AppControlHosts.configImportHost(settingsRepository))
    ))

    // 系统诊断快照（LOW 只读；MCP/Skills/审计真实状态，agent 分析问题的数据面）
    registry.register(SafeAgentTool(
        SystemDiagnoseTool(AppControlHosts.diagnosticsHost(mcpManager, skillRegistry, toolAuditLogger))
    ))
}
