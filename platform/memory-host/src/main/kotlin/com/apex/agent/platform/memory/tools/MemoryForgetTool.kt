package com.apex.agent.platform.memory.tools

import com.apex.agent.core.tools.AgentTool
import com.apex.memkit.core.MemKit
import com.apex.memkit.core.store.deleteGraphEntities
import com.apex.memkit.core.store.deleteObservations
import com.apex.memkit.core.store.removeFact
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * 记忆纠正工具（第 8 个记忆工具）—— 「记忆只能增不能改」缺口的补位。
 *
 * 记忆系统全景重构方案 v2 P0-3：此前七工具只有写入与召回，没有逐条
 * 纠正面 —— 用户说「你记错了」时模型只能覆盖式追加，错误事实永远
 * 留在账本里参与召回。本工具补上删除面：
 *
 * - forget_fact：按 id 精确删 / 按 content 唯一匹配删（多命中报歧义，
 *   拒绝批量误删）；
 * - forget_entity：删实体（库侧级联清理关联关系）；
 * - forget_observations：删实体的指定观察（保留实体本身）。
 *
 * 边界（诚实声明）：P0 阶段删除为立即物理移除，尚无 derivedFrom 因果
 * 链与 curated 豁免（P1 双时态落地后升级为 supersede 语义）。
 */
@Singleton
class MemoryForgetTool @Inject constructor(
    private val memKit: MemKit,
) : AgentTool {
    override val id = "MemoryForget"
    override val name = "Memory: Forget"
    override val description = """
        Remove ONE wrong or outdated memory entry (user says "you remembered it wrong",
        or a fact is no longer true). Actions:
        - "forget_fact": delete one chat fact by exact content (or unique substring match)
        - "forget_entity": delete a knowledge-graph entity (its relations are cascade-cleaned)
        - "forget_observations": remove specific observations from an entity (entity kept)
        After forgetting, save the corrected fact with MemorySaveFact if needed.

        Example: {"action": "forget_fact", "content": "User prefers dark mode"}
        Example: {"action": "forget_entity", "name": "GitHub"}
        Example: {"action": "forget_observations", "entity": "WeChat", "contents": ["messaging app"]}
    """.trimIndent()
    override val parametersSchema = """
        {
            "type": "object",
            "properties": {
                "action": {"type": "string", "enum": ["forget_fact", "forget_entity", "forget_observations"]},
                "id": {"type": "string", "description": "Fact id (exact, when known)"},
                "content": {"type": "string", "description": "Fact content (exact match, or unique substring)"},
                "name": {"type": "string", "description": "Entity name (forget_entity)"},
                "entity": {"type": "string", "description": "Entity name (forget_observations)"},
                "contents": {"type": "array", "items": {"type": "string"}, "description": "Observations to remove (forget_observations)"}
            },
            "required": ["action"]
        }
    """.trimIndent()

    override suspend fun execute(arguments: String): String {
        val root = runCatching { Json.parseToJsonElement(arguments).jsonObject }.getOrNull()
            ?: return "Error: invalid JSON arguments"
        return when (root.str("action")?.trim()?.lowercase()) {
            "forget_fact" -> runCatching { forgetFact(root) }.getOrNull()
                ?: "Error: memory store unavailable"
            "forget_entity" -> runCatching { forgetEntity(root) }.getOrNull()
                ?: "Error: memory store unavailable"
            "forget_observations" -> runCatching { forgetObservations(root) }.getOrNull()
                ?: "Error: memory store unavailable"
            else -> "Error: 'action' must be one of forget_fact / forget_entity / forget_observations"
        }
    }

    private suspend fun forgetFact(root: JsonObject): String {
        val id = root.str("id")
        if (!id.isNullOrBlank()) {
            val removed = memKit.store.removeFact(id.trim())
            if (removed) memKit.requestFlush()
            return if (removed) {
                "Fact removed (id: ${id.trim()}). Save the corrected fact with MemorySaveFact if needed."
            } else {
                "Error: no fact with id '${id.trim()}' exists."
            }
        }
        val content = root.str("content")
            ?: return "Error: 'id' or 'content' is required for forget_fact"
        val facts = memKit.allFacts()
        // 匹配顺序：精确内容 → 唯一包含（多命中 = 歧义拒绝，防批量误删）
        val exact = facts.filter { it.content == content }
        if (exact.size == 1) {
            memKit.store.removeFact(exact[0].id)
            memKit.requestFlush()
            return "Fact removed: \"${exact[0].content}\""
        }
        if (exact.size > 1) {
            return ambiguous(exact.map { it.content })
        }
        val contains = facts.filter { it.content.contains(content) }
        return when {
            contains.size == 1 -> {
                memKit.store.removeFact(contains[0].id)
                memKit.requestFlush()
                "Fact removed: \"${contains[0].content}\""
            }

            contains.size > 1 -> ambiguous(contains.map { it.content })

            else -> "Error: no fact matches \"$content\". Use MemoryGraph search or the recall " +
                "section to check what is actually stored."
        }
    }

    private fun ambiguous(matches: List<String>): String = buildString {
        appendLine("Error: ambiguous match — ${matches.size} facts match. Be more specific (full content):")
        matches.take(8).forEach { appendLine("- $it") }
        if (matches.size > 8) appendLine("… and ${matches.size - 8} more")
    }

    private suspend fun forgetEntity(root: JsonObject): String {
        val name = root.str("name")
            ?: return "Error: 'name' is required for forget_entity"
        val deleted = memKit.store.deleteGraphEntities(listOf(name))
        memKit.requestFlush()
        return if (deleted) {
            "Entity \"$name\" removed (its relations were cascade-cleaned)."
        } else {
            "Error: entity \"$name\" does not exist."
        }
    }

    private suspend fun forgetObservations(root: JsonObject): String {
        val entity = root.str("entity")
            ?: return "Error: 'entity' is required for forget_observations"
        val contents = root.strList("contents")
        if (contents.isEmpty()) {
            return "Error: non-empty 'contents' is required for forget_observations"
        }
        val ok = memKit.store.deleteObservations(entity, contents)
        memKit.requestFlush()
        return if (ok > 0) {
            "Removed ${minOf(ok, contents.size)} observation(s) from \"$entity\" (entity kept)."
        } else {
            "Error: entity \"$entity\" does not exist or no matching observation found."
        }
    }
}

// ══ 参数解析小件（与 MemoryWriteTools 同款防御式口径）════════════

/** 字符串字段（空白 → null）。 */
private fun JsonObject.str(key: String): String? = runCatching {
    (this[key] as? JsonPrimitive)?.contentOrNull
}.getOrNull()?.takeIf { !it.isNullOrBlank() }

/** 字符串数组字段（元素逐条净化：跳过 null / 空白）。 */
private fun JsonObject.strList(key: String): List<String> = runCatching {
    (this[key] as? JsonArray)?.mapNotNull { el ->
        (el as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }
    } ?: emptyList()
}.getOrNull() ?: emptyList()
