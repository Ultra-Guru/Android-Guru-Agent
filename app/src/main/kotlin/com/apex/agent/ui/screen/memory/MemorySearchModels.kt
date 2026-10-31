package com.apex.agent.ui.screen.memory

import com.apex.memkit.core.model.ChatFact
import com.apex.memkit.core.model.GraphEntity
import com.apex.memkit.core.model.GraphRelation
import com.apex.memkit.core.store.NodeDictEntry

/**
 * 统一记忆搜索的命中条目（记忆页 v2 —— 搜索从「仅场景节点」扩展为
 * 三轨全记忆面：场景节点 / 聊天事实 / 知识图谱实体）。
 *
 * @param type 命中来源轨（分组渲染 + 类型徽章依据）
 * @param title 主文案（节点文本 / 事实正文 / 实体名）
 * @param subtitle 元数据行（role / 类别+重要度 / 实体类型，null = 不渲染）
 * @param jumpId 图谱跳转 ID（「fact:ID」「entity:名」—— null = 不可跳，
 *   场景节点在图谱的 scene 层默认关闭，不做跳转以免空动作）
 * @param uid 列表 key（fingerprint / 事实 ID / 实体名 —— 同轨内唯一）
 */
data class MemorySearchHit(
    val type: MemorySearchHitType,
    val title: String,
    val subtitle: String?,
    val jumpId: String?,
    val uid: String,
)

/** 命中来源轨（展示序 = 枚举序：事实 → 实体 → 场景节点）。 */
enum class MemorySearchHitType {
    FACT, ENTITY, NODE
}

/**
 * 三轨数据 → 统一命中列表（纯函数，JVM 直测）。
 *
 * 匹配语义：大小写不敏感的包含匹配 —— 事实匹配正文，实体匹配名称与
 * 观察文本，场景节点沿用 store 检索返回（本身已带匹配）。每轨独立
 * [perTypeLimit] 封顶（单轨洪水不淹没其他轨的可见性）。
 */
fun buildUnifiedSearchResults(
    nodes: List<NodeDictEntry>,
    facts: List<ChatFact>,
    entities: List<GraphEntity>,
    query: String,
    perTypeLimit: Int = 12,
): List<MemorySearchHit> {
    val q = query.trim()
    if (q.isEmpty()) return emptyList()

    val factHits = facts.asSequence()
        .filter { it.content.contains(q, ignoreCase = true) }
        .take(perTypeLimit)
        .map { MemorySearchHit(MemorySearchHitType.FACT, it.content, null, "fact:${it.id}", "fact:${it.id}") }
        .toList()

    val entityHits = entities.asSequence()
        .filter { e ->
            e.name.contains(q, ignoreCase = true) ||
                e.observations.any { it.contains(q, ignoreCase = true) }
        }
        .take(perTypeLimit)
        .map { MemorySearchHit(MemorySearchHitType.ENTITY, it.name, it.entityType, "entity:${it.name}", "entity:${it.name}") }
        .toList()

    val nodeHits = nodes.asSequence()
        .take(perTypeLimit)
        .map { n ->
            MemorySearchHit(
                type = MemorySearchHitType.NODE,
                title = n.text ?: "",
                subtitle = "role=${n.role}",
                jumpId = null,
                uid = "node:${n.fingerprint}",
            )
        }
        .toList()

    return factHits + entityHits + nodeHits
}

/**
 * 实体关联抽取（纯函数）—— 名称 → 与之相连的关系（含方向归一）。
 *
 * 知识图谱分区 / 统一搜索命中实体的详情呈现共用：from 命中时方向为出
 * （→），to 命中时方向为入（←），双向（自环或互指）两个方向都保留。
 */
fun relationsOf(
    entityName: String,
    relations: List<GraphRelation>,
    limit: Int = 24,
): List<EntityRelationView> =
    relations.asSequence()
        .filter { it.from == entityName || it.to == entityName }
        .take(limit)
        .map { r ->
            if (r.from == entityName) {
                EntityRelationView(direction = true, other = r.to, relationType = r.relationType)
            } else {
                EntityRelationView(direction = false, other = r.from, relationType = r.relationType)
            }
        }
        .toList()

/**
 * 一条已归一方向的实体关联（direction = true → 出边 / false ← 入边）。
 * 展示层拼「本实体 —关系→ 对端」或「对端 —关系→ 本实体」。
 */
data class EntityRelationView(
    val direction: Boolean,
    val other: String,
    val relationType: String,
)

/**
 * 命中词在文本中的区间（记忆页 v3 搜索高亮）—— 大小写不敏感的全部
 * 出现位置（含重叠安全：非重叠扫描，左起贪心推进）。
 *
 * 纯函数独立于 Compose（区间计算 JVM 直测；UI 侧 buildAnnotatedString
 * 只消费区间），空词 / 无命中返回空表。
 */
fun queryMatchRanges(text: String, query: String): List<IntRange> {
    val q = query.trim()
    if (q.isEmpty()) return emptyList()
    val ranges = mutableListOf<IntRange>()
    var from = 0
    while (from <= text.length - q.length) {
        val idx = text.indexOf(q, startIndex = from, ignoreCase = true)
        if (idx < 0) break
        ranges += idx until idx + q.length
        from = idx + q.length
    }
    return ranges
}
