package com.apex.agent.platform.memory.tools

import com.apex.agent.core.tools.AgentTool
import com.apex.memkit.core.MemKit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 记忆召回类工具 —— 让 LLM 真正能读取长期记忆（口径沿用 CS-Mem 时代的
 * 三个只读工具，id 与参数零变化，模型侧无感迁移）。
 *
 *   - MemoryRecentEpisodes：最近的任务情景（目标 / 状态 / 动作数 / 派生宏）
 *   - MemorySearchNodes：按文本搜索历史 UI 语义节点
 *   - MemoryRecallMacro：高成功率宏技能清单（可复用的程序性记忆）
 *
 * 这些是「观察」工具，不触发旁路执行 —— 旁路仍由引擎每轮推理前经
 * ExecutionMemoryObserver.tryBypass 内部决策。
 */
@Singleton
class MemoryRecentEpisodesTool @Inject constructor(
    private val memKit: MemKit,
) : AgentTool {
    override val id = "MemoryRecentEpisodes"
    override val name = "Memory: Recent Episodes"
    override val description = """
        Recall recent agent task sessions recorded by CS-Mem long-term memory.
        Returns a list of past episodes (goal, status, timestamps, action count, macro link).
        Use this to check whether you have attempted a similar task before.

        Example: {"limit": 10}
    """.trimIndent()
    override val parametersSchema = """
        {
            "type": "object",
            "properties": {
                "limit": {"type": "integer", "description": "Max episodes to return (default 10)"}
            },
            "required": []
        }
    """.trimIndent()

    override suspend fun execute(arguments: String): String {
        val limit = runCatching {
            Json.parseToJsonElement(arguments).jsonObject["limit"]?.jsonPrimitive?.content?.toIntOrNull()
        }.getOrNull() ?: 10
        val episodes = runCatching { memKit.recentEpisodes(limit) }.getOrNull()
            ?: return "Error: memory store unavailable"
        if (episodes.isEmpty()) return "No episodes recorded yet."
        return buildString {
            appendLine("Recent episodes (${episodes.size}):")
            episodes.forEach { ep ->
                appendLine(
                    "- [${ep.status}] ${ep.goal} " +
                        "(actions=${ep.actionCount}, macros=${ep.macroIds.size}, pkg=${ep.appPackage ?: "?"})",
                )
            }
        }
    }
}

@Singleton
class MemorySearchNodesTool @Inject constructor(
    private val memKit: MemKit,
) : AgentTool {
    override val id = "MemorySearchNodes"
    override val name = "Memory: Search UI Nodes"
    override val description = """
        Search historical UI semantic nodes by text in CS-Mem long-term memory.
        Useful to recall what a screen looked like, button labels, or resource IDs
        from previously visited apps.

        Example: {"query": "同意", "limit": 20}
    """.trimIndent()
    override val parametersSchema = """
        {
            "type": "object",
            "properties": {
                "query": {"type": "string", "description": "Text keyword to search for"},
                "limit": {"type": "integer", "description": "Max nodes to return (default 20)"}
            },
            "required": ["query"]
        }
    """.trimIndent()

    override suspend fun execute(arguments: String): String {
        val query = runCatching {
            Json.parseToJsonElement(arguments).jsonObject["query"]?.jsonPrimitive?.content
        }.getOrNull() ?: return "Error: missing required parameter 'query'"
        val limit = runCatching {
            Json.parseToJsonElement(arguments).jsonObject["limit"]?.jsonPrimitive?.content?.toIntOrNull()
        }.getOrNull() ?: 20
        val nodes = runCatching { memKit.searchNodes(query, limit) }.getOrNull()
            ?: return "Error: memory store unavailable"
        if (nodes.isEmpty()) return "No nodes matched \"$query\"."
        return buildString {
            appendLine("Matched ${nodes.size} nodes for \"$query\":")
            nodes.forEach { n ->
                appendLine(
                    "- [${n.role}] ${n.displayText()} " +
                        "(resId=${n.resourceId ?: "-"}, seen=${n.occurrences}, pkg=${n.appPackage ?: "?"})",
                )
            }
        }
    }
}

@Singleton
class MemoryRecallMacroTool @Inject constructor(
    private val memKit: MemKit,
) : AgentTool {
    override val id = "MemoryRecallMacro"
    override val name = "Memory: Recall Macro Skills"
    override val description = """
        List top macro skills (procedural memory) distilled from successful tasks.
        Each macro can be replayed without LLM when its entry screen matches.
        Returns name, goal, success rate, energy, and crystallized status.

        Example: {"limit": 10}
    """.trimIndent()
    override val parametersSchema = """
        {
            "type": "object",
            "properties": {
                "limit": {"type": "integer", "description": "Max macros to return (default 10)"}
            },
            "required": []
        }
    """.trimIndent()

    override suspend fun execute(arguments: String): String {
        val limit = runCatching {
            Json.parseToJsonElement(arguments).jsonObject["limit"]?.jsonPrimitive?.content?.toIntOrNull()
        }.getOrNull() ?: 10
        val macros = runCatching { memKit.topMacros(limit) }.getOrNull()
            ?: return "Error: memory store unavailable"
        if (macros.isEmpty()) return "No macro skills distilled yet."
        return buildString {
            appendLine("Top macro skills (${macros.size}):")
            macros.forEach { m ->
                appendLine(
                    "- ${m.name} (goal=\"${m.goal}\", steps=${m.steps.size}, " +
                        "ok=${m.stats.successCount}/fail=${m.stats.failureCount}, " +
                        "energy=${"%.1f".format(m.stats.energy)}" +
                        "${if (m.stats.crystallized) ", crystallized" else ""})",
                )
            }
        }
    }
}
