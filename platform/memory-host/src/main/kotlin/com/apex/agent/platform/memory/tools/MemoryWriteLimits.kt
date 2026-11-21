package com.apex.agent.platform.memory.tools

import com.apex.memkit.core.model.GraphEntity
import com.apex.memkit.core.model.GraphRelation

/**
 * 记忆写入面参数钳制（#841）—— 模型幻觉式刷写的第一道闸。
 *
 * 背景：写入类工具对数组长度与单条 content 长度均无客户端钳制，模型
 * 一次塞 5000 实体会整批落库；而容量治理（MaintenanceWorker）调度保守，
 * 剪枝跑不过写入。双层防线：
 * 1. **工具层（本对象）** —— Agent 工具（MemoryConsolidateExperience /
 *    MemorySaveFact / MemoryGraph）入参先经此处裁剪；
 * 2. **MCP transport 层** —— 内置 memory MCP server（BuiltinMemoryMcpTransport
 *    直连 store）以同口径常量钳制，防止绕过工具层。
 *
 * 数值口径：lessons ≤32（一次任务的有效教训上限）、entities ≤64 /
 * relations ≤128（与官方 server-memory 的常识批量对齐）、单条文本 ≤2KB
 * （超长内容截断并标记，不静默丢弃 —— 截断标记让模型知道信息不完整）。
 */
object MemoryWriteLimits {

    /** 单次提交的教训条数上限（ExperienceReport.lessons）。 */
    const val MAX_LESSONS = 32

    /** 单次提交的实体数上限（create_entities / ExperienceReport.entities）。 */
    const val MAX_ENTITIES = 64

    /** 单次提交的关系数上限（create_relations / ExperienceReport.relations）。 */
    const val MAX_RELATIONS = 128

    /** 单实体观察条数上限（create_entities 的 observations / add_observations）。 */
    const val MAX_OBSERVATIONS = 16

    /** add_observations 单次提交的观察条数上限。 */
    const val MAX_OBSERVATION_BATCH = 32

    /** 单条文本（content / observation / relationType / goal / outcome）字符上限。 */
    const val MAX_ITEM_CHARS = 2_000

    /** 截断标记后缀（模型可见信息不完整的信号，非静默截断）。 */
    private const val TRUNCATION_SUFFIX = "…[truncated]"

    /** 文本钳制：超长截断到上限并附标记；null / 空白原样返回。 */
    fun clampText(text: String?): String? {
        if (text == null || text.length <= MAX_ITEM_CHARS) return text
        return text.take(MAX_ITEM_CHARS) + TRUNCATION_SUFFIX
    }

    /** 字符串列表钳制：逐条净化（空白跳过）+ 截断 + 条数上限（保序）。 */
    fun clampStrings(list: List<String>, maxItems: Int): List<String> =
        list.asSequence()
            .mapNotNull { it.trim().takeIf { s -> s.isNotEmpty() } }
            .map { clampText(it)!! }
            .take(maxItems)
            .toList()

    /** 实体列表钳制：条数 + 名称/类型/观察逐字段截断 + 观察条数上限。 */
    fun clampEntities(entities: List<GraphEntity>): List<GraphEntity> =
        entities.asSequence()
            .filter { it.name.isNotBlank() }
            .take(MAX_ENTITIES)
            .map { entity ->
                entity.copy(
                    name = clampText(entity.name.trim())!!,
                    entityType = clampText(entity.entityType.trim().ifEmpty { "unknown" })!!,
                    observations = clampStrings(entity.observations, MAX_OBSERVATIONS),
                )
            }
            .toList()

    /** 关系列表钳制：条数 + 端点/类型截断（缺端点的坏条目由解析层先行跳过）。 */
    fun clampRelations(relations: List<GraphRelation>): List<GraphRelation> =
        relations.asSequence()
            .take(MAX_RELATIONS)
            .map { relation ->
                relation.copy(
                    from = clampText(relation.from.trim())!!,
                    to = clampText(relation.to.trim())!!,
                    relationType = clampText(
                        relation.relationType.trim().ifEmpty { "related to" },
                    )!!,
                )
            }
            .toList()
}
