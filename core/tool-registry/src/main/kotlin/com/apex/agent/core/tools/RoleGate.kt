package com.apex.agent.core.tools

/**
 * # 角色门禁（v2.1.0 评审 B3 调用层强制点）
 *
 * [ToolMetadata.requiredRoles] 非空的工具，要求当前会话授予全部所需角色，否则拒绝。
 * 与 [RiskAwareToolGate]/PermissionModeGate 分工：角色门答「这个会话能不能用」，
 * 风险/权限门答「用了要不要用户确认」——正交且叠加。
 *
 * @param grantedRoles 当前会话授予的角色快照（provider 每次调用现取——应用级
 *   设置开关热生效；会话级不可变快照语义由 AgentConfig.grantedRoles 在会话装配
 *   时固化，本 provider 读取的是「当前活动会话」的授予集，单设备单活动会话口径）。
 *
 * 拒绝文案面向模型：指出缺哪个角色 + 怎么获得（激活专家模板 / 调整设置）。
 */
class RoleGate(
    private val grantedRoles: () -> Set<String>
) : ToolExecutionGate {

    override suspend fun check(tool: AgentTool, arguments: String): GateDecision {
        val required = tool.metadata.requiredRoles
        if (required.isEmpty()) return GateDecision.Allow
        val granted = runCatching { grantedRoles() }.getOrDefault(emptySet())
        val missing = required.filter { it !in granted }
        if (missing.isEmpty()) return GateDecision.Allow
        return GateDecision.Deny(
            "角色未授权：${tool.id} 需要角色 ${missing.joinToString(" + ")}，" +
                "当前会话未授予。请激活模板工坊「浏览器自动化专家」模板，或在设置中调整浏览器角色限制" +
                "（敏感操作还需用户显式授权 browser-sensitive）。"
        )
    }
}
