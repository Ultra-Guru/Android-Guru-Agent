package com.apex.agent.ui.screen.memory

import com.apex.memkit.core.model.ChatFact
import com.apex.memkit.core.model.GraphEntity
import com.apex.memkit.core.model.GraphRelation
import com.apex.memkit.core.model.NodeRole
import com.apex.memkit.core.store.NodeDictEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 统一记忆搜索 / 实体关联抽取的纯逻辑单测（记忆页 v2）。
 *
 * VM 的搜索链路（防抖 → 三轨拉取 → [buildUnifiedSearchResults]）依赖
 * Hilt/Compose 件不做 JVM 直测；分组 / 匹配 / 跳转 ID / 关联方向归一
 * 规则在此覆盖。数据源：searchNodes 的节点检索 + allFacts 账本 +
 * readGraph 知识图谱。
 */
class MemorySearchModelsTest {

    private fun fact(id: String, content: String): ChatFact = ChatFact(
        id = id,
        content = content,
        kind = ChatFact.Kind.PROFILE,
        source = ChatFact.Source.MANUAL,
        importance = 0.6f,
        credibility = 1f,
        createdAt = 100L,
        lastAccessedAt = 100L,
        accessCount = 0
    )

    private fun node(fp: String, text: String?): NodeDictEntry = NodeDictEntry(
        fingerprint = fp,
        role = NodeRole.BUTTON,
        text = text
    )

    // ═══ buildUnifiedSearchResults ═══

    @Test
    fun `empty query returns no hits`() {
        val hits = buildUnifiedSearchResults(
            nodes = listOf(node("fp1", "Settings")),
            facts = listOf(fact("f1", "likes dark mode")),
            entities = listOf(GraphEntity("GitHub", "platform")),
            query = "   ",
        )
        assertTrue(hits.isEmpty())
    }

    @Test
    fun `matches across all three tracks in canonical order`() {
        val hits = buildUnifiedSearchResults(
            nodes = listOf(node("fp1", "GitHub login button")),
            facts = listOf(fact("f1", "hosts repos on GitHub")),
            entities = listOf(GraphEntity("GitHub", "platform")),
            query = "github",
        )
        assertEquals(3, hits.size)
        // 事实 → 实体 → 场景节点（枚举展示序）
        assertEquals(MemorySearchHitType.FACT, hits[0].type)
        assertEquals(MemorySearchHitType.ENTITY, hits[1].type)
        assertEquals(MemorySearchHitType.NODE, hits[2].type)
        // 跳转 ID 与库侧 MemoryGraphBuilder 的 nodeId 契约对齐
        assertEquals("fact:f1", hits[0].jumpId)
        assertEquals("entity:GitHub", hits[1].jumpId)
        assertNull(hits[2].jumpId) // 场景节点层默认关 —— 不提供跳转
    }

    @Test
    fun `entity matches by observation text not only by name`() {
        val entities = listOf(
            GraphEntity("WeChat", "app", observations = listOf("messaging app", "send photos")),
            GraphEntity("Alipay", "app", observations = listOf("payment")),
        )
        val hits = buildUnifiedSearchResults(nodes = emptyList(), facts = emptyList(), entities = entities, query = "photo")
        assertEquals(1, hits.size)
        assertEquals("WeChat", hits[0].title)
        assertEquals("app", hits[0].subtitle)
    }

    @Test
    fun `matching is case-insensitive and per-track capped`() {
        val facts = (1..20).map { fact("f$it", "FACT $it about Kotlin") }
        val hits = buildUnifiedSearchResults(
            nodes = emptyList(),
            facts = facts,
            entities = emptyList(),
            query = "kotlin",
            perTypeLimit = 5,
        )
        assertEquals(5, hits.size) // 单轨封顶 —— 洪水轨不淹没其他轨
    }

    @Test
    fun `uids are unique per track so list keys never collide`() {
        // 同文本同 role 的两个场景节点（指纹不同）—— uid 以指纹区分
        val nodes = listOf(node("fp1", "OK"), node("fp2", "OK"))
        val hits = buildUnifiedSearchResults(nodes = nodes, facts = emptyList(), entities = emptyList(), query = "ok")
        assertEquals(2, hits.size)
        assertEquals(2, hits.map { it.uid }.toSet().size)
    }

    // ═══ relationsOf ═══

    @Test
    fun `extracts outgoing and incoming relations with normalized direction`() {
        val relations = listOf(
            GraphRelation(from = "WeChat", to = "send photo", relationType = "supports"),
            GraphRelation(from = "photo", to = "WeChat", relationType = "belongs to"),
            GraphRelation(from = "GitHub", to = "repos", relationType = "hosts"),
        )
        val views = relationsOf("WeChat", relations)
        assertEquals(2, views.size)
        // 出边：supports → send photo
        assertTrue(views[0].direction)
        assertEquals("send photo", views[0].other)
        assertEquals("supports", views[0].relationType)
        // 入边：photo —belongs to→ 本实体
        assertTrue(!views[1].direction)
        assertEquals("photo", views[1].other)
    }

    @Test
    fun `unrelated relations are excluded and limit is honored`() {
        val relations = (1..30).map {
            GraphRelation(from = "E$it", to = "WeChat", relationType = "rel$it")
        }
        val views = relationsOf("WeChat", relations, limit = 10)
        assertEquals(10, views.size)
        assertTrue(relationsOf("Missing", relations).isEmpty())
    }
}
