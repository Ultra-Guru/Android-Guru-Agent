package com.apex.agent.core.codetools.repomap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PersonalizedPageRank 契约测试 —— 全部用手算值断言（无 networkx 依赖）：
 *
 * 1. 双节点环（无个性化）→ 0.5 / 0.5；
 * 2. 无边图 → 均匀分布；
 * 3. 个性化向量把质量拉向目标节点；
 * 4. 悬挂节点质量按个性化向量再分配；
 * 5. rank 总和恒为 1。
 */
class PersonalizedPageRankTest {

    private val ranker = PersonalizedPageRank()

    @Test
    fun `two node cycle converges to half each`() {
        val rank = ranker.compute(
            nodes = setOf("a", "b"),
            edges = listOf(
                PersonalizedPageRank.Edge("a", "b", 1.0),
                PersonalizedPageRank.Edge("b", "a", 1.0)
            )
        )
        assertEquals(0.5, rank["a"]!!, 1e-9)
        assertEquals(0.5, rank["b"]!!, 1e-9)
    }

    @Test
    fun `edgeless graph returns uniform when personalization absent`() {
        val rank = ranker.compute(nodes = setOf("a", "b", "c"), edges = emptyList())
        assertEquals(1.0 / 3, rank["a"]!!, 1e-9)
        assertEquals(1.0 / 3, rank["b"]!!, 1e-9)
        assertEquals(1.0 / 3, rank["c"]!!, 1e-9)
    }

    @Test
    fun `edgeless graph with personalization returns personalization`() {
        // 全悬挂：dangling 质量按 pers 再分配 → 稳态即 pers 本身
        val rank = ranker.compute(
            nodes = setOf("a", "b"),
            edges = emptyList(),
            personalization = mapOf("a" to 3.0, "b" to 1.0)
        )
        assertEquals(0.75, rank["a"]!!, 1e-9)
        assertEquals(0.25, rank["b"]!!, 1e-9)
    }

    @Test
    fun `personalization pulls mass toward the preferred node`() {
        // a → b 单边：无个性化时链路质量流向 b（rank(b) > rank(a)）；
        // 强个性化 a 后反超 —— 个性化主导排序。
        val nodes = setOf("a", "b")
        val edges = listOf(PersonalizedPageRank.Edge("a", "b", 1.0))

        val plain = ranker.compute(nodes, edges)
        assertTrue(plain["b"]!! > plain["a"]!!)

        val biased = ranker.compute(nodes, edges, personalization = mapOf("a" to 100.0))
        assertTrue(biased["a"]!! > biased["b"]!!)
    }

    @Test
    fun `hub node earns more rank in star graph with back edge`() {
        // 星型 + 回边（hub 非悬挂）：叶子 → hub，hub → l1。
        // hub 汇入全部叶子质量，rank 最高；l1 拿回边次之。
        val nodes = setOf("hub", "l1", "l2", "l3")
        val edges = listOf(
            PersonalizedPageRank.Edge("l1", "hub", 1.0),
            PersonalizedPageRank.Edge("l2", "hub", 2.0),
            PersonalizedPageRank.Edge("l3", "hub", 3.0),
            PersonalizedPageRank.Edge("hub", "l1", 1.0)
        )
        val rank = ranker.compute(nodes, edges)
        assertTrue(rank["hub"]!! > rank["l1"]!!)
        assertTrue(rank["l1"]!! > rank["l2"]!!)
        assertEquals(rank["l2"]!!, rank["l3"]!!, 1e-9)
        assertEquals(1.0, rank.values.sum(), 1e-6)
    }

    @Test
    fun `multi edges accumulate into out weight`() {
        // a→b 两条平行边（权 1 + 1）vs a→c 单边（权 2）：出权相同 → b 与 c 平分
        val rank = ranker.compute(
            nodes = setOf("a", "b", "c"),
            edges = listOf(
                PersonalizedPageRank.Edge("a", "b", 1.0),
                PersonalizedPageRank.Edge("a", "b", 1.0),
                PersonalizedPageRank.Edge("a", "c", 2.0)
            )
        )
        // a 是唯一源，b/c 各拿 a 一半的 α 份额；a 自身由 teleport 维持
        assertEquals(rank["b"]!!, rank["c"]!!, 1e-9)
        assertEquals(1.0, rank.values.sum(), 1e-6)
    }

    @Test
    fun `sparse personalization covers missing nodes with zero`() {
        // pers 只给 a：b 无 teleport，只能靠边赚 rank
        val rank = ranker.compute(
            nodes = setOf("a", "b"),
            edges = listOf(PersonalizedPageRank.Edge("b", "a", 1.0)),
            personalization = mapOf("a" to 1.0)
        )
        assertTrue(rank["a"]!! > 0.9) // a 吃掉 teleport + b 的悬挂全量
        assertEquals(1.0, rank.values.sum(), 1e-6)
    }

    @Test
    fun `zero weight edges are ignored`() {
        val rank = ranker.compute(
            nodes = setOf("a", "b"),
            edges = listOf(PersonalizedPageRank.Edge("a", "b", 0.0))
        )
        assertEquals(0.5, rank["a"]!!, 1e-9) // 无有效边 → 均匀
    }

    @Test
    fun `empty graph returns empty map`() {
        val empty: Map<String, Double> = ranker.compute(emptySet(), emptyList())
        assertTrue(empty.isEmpty())
    }
}
