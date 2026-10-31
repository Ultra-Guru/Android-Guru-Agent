package com.apex.agent.core.engine

import com.apex.agent.core.tools.ToolCategory
import com.apex.agent.core.tools.ToolMetadata

/**
 * ═══ 工具调用溯源（能力生态三分类，自 [EngineToolExecution] 迁出的小切片）═══
 *
 * 模式与 [EngineAskUserFlow] / [EngineCompressionGate] 一致：同包顶层扩展 +
 * internal 成员直调。职责单一 —— 注册表 id → [ToolCallOrigin] + 展示标签：
 *
 * - **MCP**：first-class 注册的远程工具（[com.apex.agent.core.tools.catalog.McpAgentTool]，
 *   metadata 强制 `category(MCP)` + `tag("server:X")`）→ origin = MCP，
 *   label = 服务器名 X；
 * - **SKILL**：技能贡献的 composite/script 工具（[com.apex.agent.core.tools.skill.SkillToolAdapter]，
 *   metadata 强制 `category(SKILL)` + `tag("skill:X")`）→ origin = SKILL，
 *   label = 技能 id X；
 * - **BUILTIN**：其余全部（含 kit 适配器回放、未打标的旁路注册）→ 无标签。
 *
 * 为什么要事件层显式携带而不用名字前缀猜：注册表 id 经
 * [com.apex.agent.core.tools.catalog.ToolNameSanitizer] 清洗后模型回显的是
 * provider 安全名（无分隔符 PascalCase），技能工具 id 完全不可预测 ——
 * UI 端旧启发式只能把未知名一律折进「MCP 自定义工具」，技能被误标。
 * 引擎侧本就解析过 registryToolId（v4 反向映射），顺手读一次元数据即可
 * 拿到权威分类，发射点只加两个带默认值的字段。
 */

/** 溯源结果：origin + 展示标签（MCP 服务器名 / 技能 id；BUILTIN 为 null）。 */
internal data class ToolOriginInfo(
    val origin: ToolCallOrigin,
    val label: String?
)

/**
 * 注册表 id → 溯源信息（元数据缺失 / 分类未知 → BUILTIN，保守不误标）。
 *
 * label 解析走 metadata.tags 的 `server:` / `skill:` 前缀键（与
 * McpAgentTool / SkillToolAdapter 的打标格式同源）；标签缺失时回退 null
 * —— UI 按「MCP 工具」「技能工具」无名列展示，不阻断。
 */
internal fun ApexAgentEngine.resolveToolOrigin(registryToolId: String): ToolOriginInfo {
    val metadata: ToolMetadata = toolRegistry.metadataOf(registryToolId) ?: return ToolOriginInfo(ToolCallOrigin.BUILTIN, null)
    return when (metadata.category) {
        ToolCategory.MCP -> ToolOriginInfo(
            origin = ToolCallOrigin.MCP,
            label = metadata.labelOf(TAG_PREFIX_SERVER)
        )
        ToolCategory.SKILL -> ToolOriginInfo(
            origin = ToolCallOrigin.SKILL,
            label = metadata.labelOf(TAG_PREFIX_SKILL)
        )
        else -> ToolOriginInfo(ToolCallOrigin.BUILTIN, null)
    }
}

/** `server:X` / `skill:X` 标签值提取（前缀命中即返回冒号后原文；未命中 null）。 */
private fun ToolMetadata.labelOf(prefix: String): String? =
    tags.firstOrNull { it.startsWith(prefix) }?.removePrefix(prefix)?.takeIf { it.isNotBlank() }

private const val TAG_PREFIX_SERVER = "server:"
private const val TAG_PREFIX_SKILL = "skill:"
