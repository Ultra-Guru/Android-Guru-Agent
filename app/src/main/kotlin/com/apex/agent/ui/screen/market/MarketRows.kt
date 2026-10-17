package com.apex.agent.ui.screen.market

import com.apex.agent.core.tools.mcp.McpTransport

/*
 * 市场行模型（v1.4.1 自 MarketViewModel.kt 迁出，纯代码搬移；GATE 1 预算纪律
 * —— main 上 1203/1200 红，PR 全体继承，按职责缝拆：行模型本就与 VM 职责
 * 正交，界面层与 VM 共用这些类型，同包零导入变更）。
 */

// ═══ UI 行数据（避免界面直接依赖各注册表内部类型）═══

data class MarketSkillRow(
    val id: String,
    val name: String,
    val description: String,
    val installed: Boolean,
    val enabled: Boolean,
    val source: String = "",   // local / modelscope / github
    /**
     * true = App 内置模板：能力已在二进制里（工具原生注册），不存在"下载/安装"这一步。
     * UI 显示为「内置」标记而不是「安装」按钮 —— 旧实现给它一个安装按钮，
     * 点了只是往本地写一份 JSON，观感是装了，实际什么外部内容都没拉取。
     */
    val builtin: Boolean = false,
    // ── cs-mem 认知健康（v2 增强：让市场"活"起来）──
    /** 能量 [0.01, 10.0]；衰减到 0.05 以下会被梦境折叠。1.0 = 默认/新装。 */
    val energy: Float = 1.0f,
    /** 已结晶为可跳过 LLM 的确定性宏（高频成功技能）。 */
    val isCrystallized: Boolean = false,
    /** 最近一次执行时间戳（ms）；0 = 从未执行。 */
    val lastUsedAt: Long = 0,
    val successCount: Int = 0,
    val failureCount: Int = 0,
    // ── 市场元数据（来自 manifest，用于分类过滤与详情页）──
    val category: String? = null,
    val tags: List<String> = emptyList(),
    val author: String = "",
    val version: String = "",
    val trustLevel: String = "community",
    /**
     * Issue #166：manifest.bundled——APK assets（assets/skills/）首启幂等释放的内置优质技能。
     * 市场卡 / 详情显示「内置」徽标；卸载入口降级（内置可禁用不可卸载，
     * SkillRegistry.uninstall 对 bundled 恒 false）。
     */
    val bundled: Boolean = false,
    /** #197 工位作用域（"agent"/"coding"/"all"）—— 市场分级的过滤口径。 */
    val scope: String = "all"
) {
    /** 成功率 0..1。 */
    val successRate: Float get() =
        if (successCount + failureCount == 0) 0f
        else successCount.toFloat() / (successCount + failureCount)

    /** 是否低能量（需要被使用否则会衰减）。 */
    val isLowEnergy: Boolean get() = installed && energy < 0.5f && !isCrystallized
}

data class MarketMcpRow(
    val name: String,
    /** 端点摘要：远端显示 URL，STDIO 显示完整命令行。 */
    val endpoint: String,
    val transport: McpTransport,
    val enabled: Boolean,
    val connected: Boolean,
    /** 内置服务器（BUILTIN 进程内 transport，如内置 GitHub）：不可删除。 */
    val builtin: Boolean = false,
    /** #197 工位作用域（"agent"/"coding"/"all"）。 */
    val scope: String = "all"
)

data class MarketPluginRow(
    val packageName: String,
    val label: String,
    val loaded: Boolean
)
