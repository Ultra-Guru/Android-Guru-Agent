package com.apex.agent.core.engine

import com.apex.agent.core.llm.LlmMessage
import com.apex.agent.core.tools.prompt.PromptSection
import com.apex.agent.core.tools.prompt.PromptSnapshot
import com.apex.agent.core.tools.prompt.PromptTurn

/**
 * # Prompt Snapshot Builder — 段解析 + 快照组装（P97）
 *
 * **零侵入段解析**：不改动 [EnginePrompts.buildSystemPrompt] 的组装结构
 * （规格 97-b 的字节不变约束）——对**渲染产物**后解析：按 `## ` 行首
 * 标题切段、标题 → 稳定段 id（[sectionIdOf]）、每段 token 估算
 * （chars / 4）与真值源标注。runner 无钩子时最终提示词与旧输出
 * **逐字节一致**（本类只读不写）。
 *
 * 纯函数集合（脱敏在工具层 [com.apex.agent.core.tools.prompt.PromptRedaction]）；
 * 无 Android 依赖。
 */
internal object PromptSnapshotBuilder {

    /** 标题行前缀（与 EnginePrompts 的 appendLine("## …") 口径一致）。 */
    private const val HEADING_PREFIX = "## "

    /** 段 id → 真值源类名（审计口径；与段 id 清单同表维护）。 */
    val SOURCE_OF_SECTION: Map<String, String> = mapOf(
        "TOOL_USE_POLICY" to "EnginePrompts",
        "MEMORY_POLICY" to "EnginePrompts",
        "TERMINAL_POLICY" to "EnginePrompts",
        "SHELL_DIALECT_MAP" to "EnginePrompts",
        "DEVICE_PRIVILEGE" to "PrivilegeLadder",
        "CAPABILITY_EXPANSION_PLAYBOOK" to "EnginePrompts",
        "LIVE_ENVIRONMENT" to "EnvironmentInfoProvider",
        "CONNECTED_SERVICES" to "ConnectedServicesProvider",
        "MODE" to "AgentConfig",
        "PLANNING_PHASE" to "EnginePrompts",
        "THINKING_INSTRUCTIONS" to "ThinkingProfile",
        "AVAILABLE_TOOLS" to "EngineToolPlanner",
        "FORCED_FUNCTION_CALLS" to "EngineToolPlanner",
        "TOOL_CATALOG" to "EngineToolPlanner",
        "SKILL_CATALOG" to "SkillRegistry",
        "ACTIVE_SKILLS" to "SkillRegistry",
        "SESSION_CONTEXT" to "AgentConfig",
        "REMEMBERED_ABOUT_YOU" to "ExecutionMemoryObserver",
        "GLOBAL_RULES" to "AgentSettings",
        "FILE_OPERATION_STRATEGY" to "EnginePrompts",
        "OUTPUT_MANAGEMENT" to "EnginePrompts",
        "RULES" to "RulesProvider",
        "TASK_ROUTING" to "EnginePrompts",
        "AGENT_ROLE" to "AgentConfig",
        // P102 段（Active Extensions）——先行登记，段落地后自动生效。
        "ACTIVE_EXTENSIONS" to "ToolActivationStore"
    )

    /**
     * 解析渲染产物为段列表（首段之前的前导行不入段——快照的
     * systemPrompt 仍含全文）。
     */
    fun parseSections(systemPrompt: String): List<PromptSection> {
        val sections = mutableListOf<PromptSection>()
        val lines = systemPrompt.split('\n')
        var currentId: String? = null
        val current = StringBuilder()
        lines.forEach { line ->
            val id = sectionIdOf(line)
            if (id != null) {
                // 上一段封口（含其后的空行——切分忠实于原文）
                currentId?.let { prev ->
                    sections += PromptSection(
                        id = prev,
                        text = current.toString(),
                        tokenEstimate = current.length / 4,
                        source = SOURCE_OF_SECTION[prev] ?: "EnginePrompts"
                    )
                }
                currentId = id
                current.setLength(0)
                current.append(line).append('\n')
            } else if (currentId != null) {
                current.append(line).append('\n')
            }
        }
        currentId?.let { prev ->
            sections += PromptSection(
                id = prev,
                text = current.toString(),
                tokenEstimate = current.length / 4,
                source = SOURCE_OF_SECTION[prev] ?: "EnginePrompts"
            )
        }
        return sections
    }

    /**
     * 标题行 → 段 id（`## Mode: PLAN/BUILD` → MODE；带计数/附注的标题取
     * 括号前主干；非标题行 → null）。
     */
    fun sectionIdOf(line: String): String? {
        if (!line.startsWith(HEADING_PREFIX)) return null
        val raw = line.removePrefix(HEADING_PREFIX)
        if (raw.isEmpty()) return null
        if (raw.startsWith("Mode:")) return "MODE"
        val main = raw.substringBefore(" (").substringBefore("（").trim()
        // 规格段 id 清单的规范名（标题主干含副标题的压缩口径）
        if (main.startsWith("Memory")) return "MEMORY_POLICY"
        if (main.startsWith("Terminal")) return "TERMINAL_POLICY"
        if (main.startsWith("Tools Unavailable")) return "TOOLS_UNAVAILABLE_THIS_TURN"
        if (main.isEmpty()) return null
        return main.uppercase()
            .replace(' ', '_')
            .replace('-', '_')
            .replace(":", "_")
            .replace("—", "")
            .replace("&", "_")
    }

    /** 历史消息 → turns（LlmMessage 四形态映射；content 原样——脱敏在工具侧 [com.apex.agent.core.tools.prompt.PromptRedaction]）。 */
    fun turnsOf(history: List<LlmMessage>): List<PromptTurn> = history.map { msg ->
        when (msg) {
            is LlmMessage.System -> PromptTurn(kind = "SYSTEM", content = msg.content)
            is LlmMessage.User -> PromptTurn(kind = "USER", content = msg.content)
            is LlmMessage.Assistant -> PromptTurn(
                kind = if (msg.toolCalls.isEmpty()) "ASSISTANT" else "TOOL_CALL",
                content = msg.content,
                metadata = if (msg.toolCalls.isEmpty()) emptyMap() else mapOf(
                    "toolCalls" to msg.toolCalls.joinToString(",") { it.name }
                )
            )
            is LlmMessage.ToolResult -> PromptTurn(
                kind = "TOOL_RESULT",
                content = msg.content,
                toolName = msg.toolCallId
            )
        }
    }

    /**
     * 组装快照（token 估算 chars/4；turns 为引擎历史；toolIds 与请求
     * tools 数组同源）。
     */
    fun build(
        systemPrompt: String,
        turns: List<PromptTurn>,
        toolIds: List<String>,
        line: String
    ): PromptSnapshot = PromptSnapshot(
        systemPrompt = systemPrompt,
        sections = parseSections(systemPrompt),
        turns = turns,
        toolIds = toolIds,
        tokenEstimate = (systemPrompt.length + turns.sumOf { it.content.length }) / 4,
        line = line,
        redactions = emptyList()
    )
}
