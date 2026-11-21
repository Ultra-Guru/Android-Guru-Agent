package com.apex.agent.core.tools

/**
 * # 浏览器角色策略（v2.1.0 评审 B1/B2/B3 的单点配置）
 *
 * browser_* 工具的角色要求与工具来源推断**只在这一处定义**——ToolMetadata.infer
 * 仅作 fallback 调用本策略；显式声明的 requiredRoles 恒覆盖推断结果。
 * 未来「通用角色也需要 navigate 但不需要 execute_js」这类细分，改这里即可。
 *
 * 两级角色（评审 P0-4 敏感分级）：
 * - [ROLE_BROWSER_AUTOMATION]：常规导航/DOM/快照/点击/输入/等待/批量；
 * - [ROLE_BROWSER_SENSITIVE]：execute_js / get_cookies / file_upload / clear_data
 *   四件套——**同时要求两级角色 + 既有 sensitiveAction 用户确认**（三重门）。
 *   MCP Host 的 blocklist 只是入口防护之一，宿主执行层由此全入口贯彻。
 */
object BrowserRolePolicy {

    /** 常规浏览器自动化角色（模板工坊「浏览器自动化专家」授予）。 */
    const val ROLE_BROWSER_AUTOMATION = "browser-automation"

    /** 敏感浏览器能力角色（用户显式授权后才授予会话）。 */
    const val ROLE_BROWSER_SENSITIVE = "browser-sensitive"

    /** 敏感四件套（与 ToolMetadata.inferRisk 的 HIGH 四件同源）。 */
    private val SENSITIVE_TOOLS = setOf(
        "BrowserExecuteJs", "BrowserGetCookies", "BrowserFileUpload", "BrowserClearData",
    )

    /** 工具 id → 必需角色集（空 = 不限制）。 */
    fun requiredRolesFor(id: String): Set<String> = when {
        id in SENSITIVE_TOOLS -> setOf(ROLE_BROWSER_AUTOMATION, ROLE_BROWSER_SENSITIVE)
        id.startsWith("Browser") -> setOf(ROLE_BROWSER_AUTOMATION)
        else -> emptySet()
    }

    /** 工具 id → 来源推断（显式声明恒覆盖）。 */
    fun sourceFor(id: String): ToolSource = when {
        // v5 命名：一等 MCP 工具是 Mcp{Server}{Tool}（v4 旧前缀 mcp__ 已废弃
        // ——旧写法在 v5 id 上永不命中，MCP 工具被误标 CORE）
        id.startsWith("Mcp") -> ToolSource.MCP
        id.startsWith("Plugin") -> ToolSource.PLUGIN
        id.startsWith("Skill") -> ToolSource.SKILL
        else -> ToolSource.CORE
    }

    /**
     * 会话默认授予（评审 P0-3 关闭语义——最小权限，无 ALL 全开）：
     - 限制开（默认严格）→ 普通会话不授任何受限角色；
     * - 限制关 → **所有会话授予 browser-automation**（其他角色规则不变）。
     * 敏感 tier 不在此函数内：由独立的用户显式授权（browserSensitiveAllowed）叠加。
     */
    fun defaultGrants(restrictionEnabled: Boolean): Set<String> =
        if (restrictionEnabled) emptySet() else setOf(ROLE_BROWSER_AUTOMATION)
}

/** 工具来源（v2.1.0 评审 B1）：与 category 正交的身份面，供审计与目录展示。 */
enum class ToolSource {
    /** 宿主内置（编译期注册）。 */
    CORE,
    /** MCP 服务器热注册（v5 `Mcp` 前缀；含 McpList/McpConnect/McpCall 管理三件）。 */
    MCP,
    /** APK/功能性插件注册。 */
    PLUGIN,
    /** 技能复合工具。 */
    SKILL,
}
