package com.apex.agent.core.codetools.repomap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RepoMapBuilder 契约测试 —— 覆盖 Aider 移植的核心行为：
 * 乘数、rank 分摊排序、聊天文件排除、无引用回退、裸名兜底。
 */
class RepoMapBuilderTest {

    private val builder = RepoMapBuilder()

    private fun tags(
        relPath: String,
        defs: Map<String, Int>,
        refs: Map<String, Int>
    ) = FileTags(
        relPath,
        defs.map { (ident, line) -> RepoTag(ident, RepoTagKind.DEF, relPath, line) },
        refs
    )

    @Test
    fun `externally referenced definitions outearn self only ones`() {
        // core.kt 定义 Engine（被 app.kt 引 3 次 + 自引 1 次）与 main（仅自引）。
        // 外部引用产生跨文件边，把 app.kt 的 rank 搬给 Engine ——
        // 「被外部引用的定义」应比「仅自引用的定义」分得更多 rank。
        val input = RepoMapBuilder.Input(
            fileTags = listOf(
                tags(
                    "core.kt", mapOf("Engine" to 1),
                    mapOf("Engine" to 1)
                ),
                tags("app.kt", mapOf("main" to 1), mapOf("Engine" to 3)),
                tags("orphan.kt", mapOf("Nobody" to 1), emptyMap())
            )
        )
        val entries = builder.build(input)
        val named = entries.filter { it.identifier != null }
        // 四个定义全部进入地图，且携带行号（骨架渲染的「关注行」）
        assertEquals(setOf("Engine", "main", "Nobody"), named.map { it.identifier }.toSet())
        assertEquals(1, named.first { it.identifier == "Engine" }.line)
        // rank 比较：Engine（外部引用）> main（仅自引用）
        val engineRank = named.first { it.identifier == "Engine" }.rank
        val mainRank = named.first { it.identifier == "main" }.rank
        assertTrue("Engine rank $engineRank 应 > main rank $mainRank", engineRank > mainRank)
        // 无引用回退：Nobody 由 self-edge 保底仍出现在地图里
        assertTrue(named.any { it.identifier == "Nobody" && it.rank > 0 })
    }

    @Test
    fun `chat files are personalized and excluded from output`() {
        val input = RepoMapBuilder.Input(
            fileTags = listOf(
                tags("chat.kt", mapOf("Discussed" to 1), mapOf("Engine" to 2)),
                tags("core.kt", mapOf("Engine" to 1), mapOf("Discussed" to 1)),
                tags("other.kt", mapOf("Lonely" to 1), emptyMap())
            ),
            chatFiles = setOf("chat.kt")
        )
        val entries = builder.build(input)
        // 聊天文件不出现（用户已在视野内）
        assertTrue(entries.none { it.relPath == "chat.kt" })
        // 其余文件全在
        assertEquals(setOf("core.kt", "other.kt"), entries.map { it.relPath }.toSet())
    }

    @Test
    fun `definitions without references fall back to self edges`() {
        // 全仓库只有 def（C 风格语言回退路径）：引用 = 定义，self-edge 保底
        val input = RepoMapBuilder.Input(
            fileTags = listOf(
                tags("a.c", mapOf("alpha" to 1), emptyMap()),
                tags("b.c", mapOf("beta" to 1), emptyMap())
            )
        )
        val entries = builder.build(input)
        assertEquals(setOf("a.c", "b.c"), entries.map { it.relPath }.toSet())
        // self-edge 权重 0.1 仍产生 rank → 定义条目存在
        assertTrue(entries.any { it.identifier == "alpha" })
        assertTrue(entries.any { it.identifier == "beta" })
    }

    @Test
    fun `bare entries cover files without definitions`() {
        val input = RepoMapBuilder.Input(
            fileTags = listOf(
                tags("code.kt", mapOf("foo" to 1), mapOf("foo" to 1)),
                FileTags("plain.md", emptyList(), emptyMap())
            )
        )
        val entries = builder.build(input)
        val bare = entries.filter { it.isBare }
        assertEquals(1, bare.size)
        assertEquals("plain.md", bare[0].relPath)
    }

    @Test
    fun `identifier multiplier matches aider constants`() {
        // 普通 8 字以下词：无乘数
        assertEquals(1.0, builder.identifierMultiplier("foo", 1, emptySet()), 1e-9)
        // 蛇形 ≥ 8 字：×10
        assertEquals(10.0, builder.identifierMultiplier("parse_user", 1, emptySet()), 1e-9)
        // 驼峰 ≥ 8 字：×10
        assertEquals(10.0, builder.identifierMultiplier("UserParser", 1, emptySet()), 1e-9)
        // 提及：×10
        assertEquals(10.0, builder.identifierMultiplier("foo", 1, setOf("foo")), 1e-9)
        // 下划线私有：×0.1
        assertEquals(0.1, builder.identifierMultiplier("_secret", 1, emptySet()), 1e-9)
        // 定义文件 > 5：×0.1
        assertEquals(0.1, builder.identifierMultiplier("foo", 6, emptySet()), 1e-9)
        // 组合：蛇形 ≥8 且私有 → 10 × 0.1 = 1.0
        assertEquals(1.0, builder.identifierMultiplier("_long_name", 1, emptySet()), 1e-9)
    }

    @Test
    fun `mentioned identifiers boost their definers`() {
        // 对称夹具：两个文件互相引用对方的定义，各目引用次数相同 ——
        // 平凡情形下两 ident 平分 rank（比值 ≈ 1）；提及 alpha_engine 后
        // 它的边乘数 ×10，在同一源节点的出边分摊中占据 10:1 ——
        // 比值跳到 ≈ 10。这是「rank 按边权比例分摊」的直接验证。
        val fileTags = listOf(
            tags(
                "core.kt", mapOf("zeta_util" to 1),
                mapOf("alpha_engine" to 2, "zeta_util" to 2)
            ),
            tags(
                "api.kt", mapOf("alpha_engine" to 1),
                mapOf("zeta_util" to 2, "alpha_engine" to 2)
            )
        )
        val rankOf = { result: List<RepoMapBuilder.RankedEntry>, ident: String ->
            result.first { it.identifier == ident }.rank
        }
        val plain = builder.build(RepoMapBuilder.Input(fileTags))
        val boosted = builder.build(
            RepoMapBuilder.Input(fileTags, mentionedIdentifiers = setOf("alpha_engine"))
        )
        val plainRatio = rankOf(plain, "alpha_engine") / rankOf(plain, "zeta_util")
        val boostedRatio = rankOf(boosted, "alpha_engine") / rankOf(boosted, "zeta_util")
        // 对称夹具：提及前比值 ≈ 1
        assertEquals(1.0, plainRatio, 0.15)
        // 提及后 ×10 乘数主导分摊：比值 ≈ 10
        assertTrue("提及后比值 $boostedRatio 应 > 5", boostedRatio > 5.0)
    }

    @Test
    fun `empty input yields empty output`() {
        assertTrue(builder.build(RepoMapBuilder.Input(emptyList())).isEmpty())
    }

    @Test
    fun `frequent references are sqrt damped`() {
        // zeta 被引用 100 次（sqrt=10）vs omega 被引用 4 次（sqrt=2）：
        // 边权比例 5:1 而非 25:1 —— 高频引用不吞掉全部排名
        val input = RepoMapBuilder.Input(
            fileTags = listOf(
                tags("defs.kt", mapOf("zeta" to 1, "omega" to 2), mapOf("zeta" to 1, "omega" to 1)),
                tags("user.kt", mapOf("u" to 1), mapOf("zeta" to 100, "omega" to 4))
            )
        )
        val entries = builder.build(input)
        val zeta = entries.first { it.identifier == "zeta" }.rank
        val omega = entries.first { it.identifier == "omega" }.rank
        assertTrue(zeta > omega)          // 引用多者仍高
        assertTrue(zeta < omega * 30)     // 但被 sqrt 抑制在量级内
    }
}
