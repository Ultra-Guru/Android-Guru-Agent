package com.apex.agent.platform.memory.tools

import com.apex.agent.core.tools.AgentTool
import com.apex.memkit.core.MemKit
import com.apex.memkit.core.model.ChatFact
import com.apex.memkit.core.model.ExperienceReport
import com.apex.memkit.core.model.GraphEntity
import com.apex.memkit.core.model.GraphRelation
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * 记忆写入工具族 —— 「任务完成后整理经验到记忆」的 LLM 落点。
 *
 * 与 MemoryKitTools.kt 的三个只读召回工具互补：这里补齐主动沉淀面 ——
 *   - MemoryConsolidateExperience：任务终局的整包沉淀（摘要 + 教训 + 图谱）
 *   - MemorySaveFact：单条长期事实（画像 / 近况 / 里程碑）
 *   - MemoryGraph：MCP memory 对齐的图谱四动作（建实体 / 补观察 / 建关系 / 搜）
 *
 * 写入纪律（与库侧 MemKit 经验整理面契约一致）：
 * - 幂等：同内容重复提交 → 归并强化（importance 取 max / 访问计数累加），
 *   LLM 重试或双跳调用不会堆垃圾；
 * - 教训进 PROFILE（STATE 只召回最新一条 —— 放教训会永久沉底）；
 * - 三元组悬空端自动补建占位实体 —— 图谱可视化永不悬空；
 * - 防御式 IO：任何形状的参数异常折叠为错误消息返回，不向上抛。
 */
@Singleton
class MemoryConsolidateExperienceTool @Inject constructor(
    private val memKit: MemKit,
) : AgentTool {
    override val id = "MemoryConsolidateExperience"
    override val name = "Memory: Consolidate Experience"
    override val description = """
        Consolidate what you learned into long-term memory AFTER finishing (or failing) a task.
        Call this ONCE when a non-trivial task ends. It stores:
        1) a summary fact (milestone on success / state on failure);
        2) each lesson as a durable PROFILE fact (recalled by keyword in future sessions);
        3) knowledge entities and relations into the memory graph.
        Lessons must be reusable knowledge (UI quirks, workarounds, user preferences, verified
        facts), NOT a narration of steps. Failure lessons are as valuable as success ones —
        record what went wrong and the fix. These memories appear as connected nodes in the
        memory graph (memory page) and are recalled automatically in later sessions.

        Example: {"goal": "send photo to Zhang San", "outcome": "done via chat attachment picker",
          "success": true,
          "lessons": ["permission dialogs need a 1s wait before tapping allow"],
          "entities": [{"name": "WeChat", "entityType": "app", "observations": ["messaging app"]}],
          "relations": [{"from": "WeChat", "to": "send photo", "relationType": "supports"}]}
    """.trimIndent()
    override val parametersSchema = """
        {
            "type": "object",
            "properties": {
                "goal": {"type": "string", "description": "The task goal (1 line)"},
                "outcome": {"type": "string", "description": "What actually happened and how it ended (1-2 lines)"},
                "success": {"type": "boolean", "description": "Whether the task succeeded (default true)"},
                "lessons": {"type": "array", "items": {"type": "string"}, "description": "Durable reusable lessons (UI quirks, workarounds, preferences, verified facts)"},
                "entities": {"type": "array", "items": {"type": "object", "properties": {
                    "name": {"type": "string"}, "entityType": {"type": "string"},
                    "observations": {"type": "array", "items": {"type": "string"}}
                }}, "description": "Knowledge entities confirmed during the task"},
                "relations": {"type": "array", "items": {"type": "object", "properties": {
                    "from": {"type": "string"}, "to": {"type": "string"}, "relationType": {"type": "string"}
                }}, "description": "Knowledge triples confirmed during the task (dangling ends auto-created)"}
            },
            "required": ["goal", "outcome"]
        }
    """.trimIndent()

    override suspend fun execute(arguments: String): String {
        val root = runCatching { Json.parseToJsonElement(arguments).jsonObject }.getOrNull()
            ?: return "Error: invalid JSON arguments"
        val goal = root.str("goal")?.trim().orEmpty()
        val outcome = root.str("outcome")?.trim().orEmpty()
        if (goal.isBlank() || outcome.isBlank()) {
            return "Error: 'goal' and 'outcome' are required and must be non-empty"
        }
        val success = runCatching {
            (root["success"] as? JsonPrimitive)?.booleanOrNull
        }.getOrNull() ?: true
        val lessons = root.strList("lessons")
        val entities = root.entities("entities")
        val relations = root.relations("relations")

        val result = runCatching {
            memKit.consolidateExperience(
                ExperienceReport(
                    goal = goal,
                    outcome = outcome,
                    success = success,
                    lessons = lessons,
                    entities = entities,
                    relations = relations,
                ),
            )
        }.getOrNull() ?: return "Error: memory store unavailable"

        return buildString {
            appendLine("Experience consolidated into long-term memory:")
            appendLine(
                "- summary fact: ${if (result.summaryAdded) "added" else "reinforced"} " +
                    "(${if (success) "milestone" else "state"})",
            )
            appendLine(
                "- lessons: ${result.lessonsAdded} added, ${result.lessonsBumped} reinforced " +
                    "(durable facts, recalled by keyword later)",
            )
            appendLine("- entities: ${result.entitiesUpserted} created (incl. auto-created endpoints)")
            appendLine("- relations: ${result.relationsAdded} added")
            append("These memories now live in the memory graph as connected nodes and will be ")
            append("recalled in future sessions. Total writes: ${result.totalWrites}.")
        }
    }
}

/** 保存单条长期事实 —— 对话中确认的持久信息（用户画像 / 近况 / 里程碑）。 */
@Singleton
class MemorySaveFactTool @Inject constructor(
    private val memKit: MemKit,
) : AgentTool {
    override val id = "MemorySaveFact"
    override val name = "Memory: Save Fact"
    override val description = """
        Save ONE durable fact about the user or the world into long-term chat memory.
        Use for stable knowledge confirmed during conversation: profile (user traits,
        preferences, habits), state (current situation), or milestone (notable events).
        Idempotent: saving the same fact again reinforces it instead of duplicating.

        Example: {"content": "User prefers dark mode and minimal answers", "kind": "profile", "importance": 0.8}
    """.trimIndent()
    override val parametersSchema = """
        {
            "type": "object",
            "properties": {
                "content": {"type": "string", "description": "The fact itself (one sentence, self-contained)"},
                "kind": {"type": "string", "enum": ["profile", "state", "milestone"], "description": "Fact category (default profile)"},
                "importance": {"type": "number", "description": "0.0-1.0 (default 0.6)"}
            },
            "required": ["content"]
        }
    """.trimIndent()

    override suspend fun execute(arguments: String): String {
        val root = runCatching { Json.parseToJsonElement(arguments).jsonObject }.getOrNull()
            ?: return "Error: invalid JSON arguments"
        val content = root.str("content")?.trim().orEmpty()
        if (content.isBlank()) return "Error: 'content' is required and must be non-empty"
        val kind = ChatFact.Kind.safe(root.str("kind"))
        val importance = runCatching {
            root.str("importance")?.toFloatOrNull()
        }.getOrNull()?.coerceIn(0f, 1f) ?: 0.6f

        val result = runCatching { memKit.saveFact(content, kind, importance) }.getOrNull()
            ?: return "Error: memory store unavailable"
        return if (result.added) {
            "Fact saved (${kind.name.lowercase()}, importance ${(importance * 100).toInt()}%): \"$content\""
        } else {
            "Fact reinforced (already in memory, importance raised if higher): \"$content\""
        }
    }
}

/**
 * 知识图谱工具 —— MCP memory 语义对齐的图谱读写面。
 *
 * 四动作单工具（与官方 @modelcontextprotocol/server-memory 的工具族语义
 * 一致，收拢为一个 action 参数工具减少注册面）：
 * - create_entities：建/合并实体（同名合并观察去重）
 * - add_observations：给既有实体补观察
 * - create_relations：建三元组（整体去重）
 * - search：关键词搜子图（实体 + 关联关系）
 */
@Singleton
class MemoryGraphTool @Inject constructor(
    private val memKit: MemKit,
) : AgentTool {
    override val id = "MemoryGraph"
    override val name = "Memory: Knowledge Graph"
    override val description = """
        Read and write the long-term knowledge graph (MCP memory semantics).
        Actions:
        - "create_entities": create or merge entities [{name, entityType, observations}]
        - "add_observations": append observations to an existing entity {entity, contents}
        - "create_relations": create typed relations [{from, to, relationType}]
        - "search": find entities and their relations by keyword {query}
        Entities and relations show up in the memory graph visualization as amber nodes
        with labeled edges (relationType).

        Example: {"action": "create_entities",
          "entities": [{"name": "GitHub", "entityType": "platform", "observations": ["hosts code"]}]}
        Example: {"action": "search", "query": "GitHub"}
    """.trimIndent()
    override val parametersSchema = """
        {
            "type": "object",
            "properties": {
                "action": {"type": "string", "enum": ["create_entities", "add_observations", "create_relations", "search"]},
                "entities": {"type": "array", "items": {"type": "object", "properties": {
                    "name": {"type": "string"}, "entityType": {"type": "string"},
                    "observations": {"type": "array", "items": {"type": "string"}}
                }}},
                "entity": {"type": "string", "description": "Target entity name (add_observations)"},
                "contents": {"type": "array", "items": {"type": "string"}, "description": "Observations to append (add_observations)"},
                "relations": {"type": "array", "items": {"type": "object", "properties": {
                    "from": {"type": "string"}, "to": {"type": "string"}, "relationType": {"type": "string"}
                }}},
                "query": {"type": "string", "description": "Search keyword (search)"}
            },
            "required": ["action"]
        }
    """.trimIndent()

    override suspend fun execute(arguments: String): String {
        val root = runCatching { Json.parseToJsonElement(arguments).jsonObject }.getOrNull()
            ?: return "Error: invalid JSON arguments"
        return when (root.str("action")?.trim()?.lowercase()) {
            "create_entities" -> runCatching { createEntities(root) }.getOrNull()
                ?: "Error: memory store unavailable"
            "add_observations" -> runCatching { addObservations(root) }.getOrNull()
                ?: "Error: memory store unavailable"
            "create_relations" -> runCatching { createRelations(root) }.getOrNull()
                ?: "Error: memory store unavailable"
            "search" -> runCatching { search(root) }.getOrNull()
                ?: "Error: memory store unavailable"
            else -> "Error: 'action' must be one of create_entities / add_observations / create_relations / search"
        }
    }

    private suspend fun createEntities(root: JsonObject): String {
        val entities = root.entities("entities")
        if (entities.isEmpty()) return "Error: 'entities' array is required for create_entities"
        val added = memKit.upsertGraphEntities(entities)
        val known = entities.filter { it.name !in added }
        return buildString {
            if (added.isNotEmpty()) appendLine("Created entities: ${added.joinToString(", ")}")
            if (known.isNotEmpty()) {
                appendLine("Merged observations into existing entities: ${known.joinToString(", ") { it.name }}")
            }
            append("${entities.size} entity upsert(s) committed.")
        }
    }

    private suspend fun addObservations(root: JsonObject): String {
        val entity = root.str("entity")?.trim().orEmpty()
        val contents = root.strList("contents")
        if (entity.isBlank() || contents.isEmpty()) {
            return "Error: 'entity' and non-empty 'contents' are required for add_observations"
        }
        val ok = memKit.addEntityObservations(entity, contents)
        return if (ok) {
            "Appended ${contents.size} observation(s) to \"$entity\": ${contents.joinToString("; ")}"
        } else {
            "Error: entity \"$entity\" does not exist — create it first (create_entities)"
        }
    }

    private suspend fun createRelations(root: JsonObject): String {
        val relations = root.relations("relations")
        if (relations.isEmpty()) return "Error: 'relations' array is required for create_relations"
        val added = memKit.addGraphRelations(relations)
        return buildString {
            if (added > 0) {
                relations.forEach { r ->
                    appendLine("- ${r.from} —${r.relationType}→ ${r.to}")
                }
                append("$added relation(s) created (duplicates skipped: ${relations.size - added}).")
            } else {
                append("No new relations created — all ${relations.size} already exist.")
            }
        }
    }

    private suspend fun search(root: JsonObject): String {
        val query = root.str("query")?.trim().orEmpty()
        if (query.isBlank()) return "Error: 'query' is required for search"
        val hit = memKit.searchGraph(query)
        if (hit.entities.isEmpty()) return "No entities matched \"$query\"."
        return buildString {
            appendLine("Matched ${hit.entities.size} entities for \"$query\":")
            hit.entities.forEach { e ->
                appendLine(
                    "- ${e.name} (${e.entityType})" +
                        if (e.observations.isEmpty()) "" else ": ${e.observations.joinToString("; ")}",
                )
            }
            if (hit.relations.isNotEmpty()) {
                appendLine("Relations in matched subgraph (${hit.relations.size}):")
                hit.relations.take(12).forEach { r ->
                    appendLine("- ${r.from} —${r.relationType}→ ${r.to}")
                }
            }
        }
    }
}

// ══ 参数解析小件（防御式：任何形状吞得下，异常折叠 null）════════

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

/** 实体数组字段 → [GraphEntity]（name 缺失条目跳过；类型缺省 unknown）。 */
private fun JsonObject.entities(key: String): List<GraphEntity> = runCatching {
    (this[key] as? JsonArray)?.mapNotNull { el ->
        val o = el as? JsonObject ?: return@mapNotNull null
        val name = (o["name"] as? JsonPrimitive)?.contentOrNull?.trim()
        if (name.isNullOrBlank()) return@mapNotNull null
        GraphEntity(
            name = name,
            entityType = (o["entityType"] as? JsonPrimitive)?.contentOrNull?.trim()
                ?.takeIf { it.isNotBlank() } ?: "unknown",
            observations = (o["observations"] as? JsonArray)?.mapNotNull { obs ->
                (obs as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }
            } ?: emptyList(),
        )
    } ?: emptyList()
}.getOrNull() ?: emptyList()

/** 关系数组字段 → [GraphRelation]（缺任一端点条目跳过；relationType 缺省 related to）。 */
private fun JsonObject.relations(key: String): List<GraphRelation> = runCatching {
    (this[key] as? JsonArray)?.mapNotNull { el ->
        val o = el as? JsonObject ?: return@mapNotNull null
        val from = (o["from"] as? JsonPrimitive)?.contentOrNull?.trim()
        val to = (o["to"] as? JsonPrimitive)?.contentOrNull?.trim()
        val type = (o["relationType"] as? JsonPrimitive)?.contentOrNull?.trim()
        if (from.isNullOrBlank() || to.isNullOrBlank()) return@mapNotNull null
        GraphRelation(from = from, to = to, relationType = type?.takeIf { it.isNotBlank() } ?: "related to")
    } ?: emptyList()
}.getOrNull() ?: emptyList()
