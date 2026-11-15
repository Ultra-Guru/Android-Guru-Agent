package com.apex.agent.platform.memory.recall

import com.apex.memkit.core.model.ChatFact
import com.apex.memkit.core.model.GraphEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 四因子召回契约锁（记忆 P0-3）：
 * 评分排序 / 访问冷却（热条目不回写触达）/ token 硬截断（整行丢弃
 * 不切半句）/ 四分区渲染与库侧 ChatRecall 分区对齐 / 空输入边界。
 */
class FourFactorRecallTest {

    private val now = 1_700_000_000_000L

    private fun fact(
        content: String,
        kind: ChatFact.Kind = ChatFact.Kind.PROFILE,
        importance: Float = 0.6f,
        createdAt: Long = now,
        lastAccessedAt: Long = 0L,
        accessCount: Int = 0,
    ) = ChatFact(
        id = "id-$content",
        content = content,
        kind = kind,
        source = ChatFact.Source.MANUAL,
        importance = importance,
        credibility = 1f,
        createdAt = createdAt,
        lastAccessedAt = lastAccessedAt,
        accessCount = accessCount,
    )

    @Test
    fun `keyword hit outranks same-age same-importance non-hit`() {
        val hit = fact("用户喜欢深色模式")
        val miss = fact("用户养了一只猫")
        val qHit = FourFactorRecall.score(hit, "深色 模式 怎么开", now)
        val qMiss = FourFactorRecall.score(miss, "深色 模式 怎么开", now)
        assertTrue(qHit > qMiss)
        // keyword 权重独立生效：分数差 ≈ W_KEYWORD（其余因子同参）
        assertEquals(
            FourFactorRecall.W_KEYWORD,
            qHit - qMiss,
            0.001f,
        )
    }

    @Test
    fun `importance dominates and recency decays with 14d half-life`() {
        val fresh = fact("新事实", importance = 0.5f, createdAt = now)
        val old = fact("旧事实", importance = 0.5f, createdAt = now - 14L * 86_400_000)
        // 新近度：fresh 满分 1.0（0.55+0.45），14 天前 ≈ 0.775（半衰）
        val sFresh = FourFactorRecall.score(fresh, "", now)
        val sOld = FourFactorRecall.score(old, "", now)
        assertTrue(sFresh > sOld)
        assertEquals(
            FourFactorRecall.W_RECENCY * (1.0f - 0.775f),
            sFresh - sOld,
            0.02f,
        )
    }

    @Test
    fun `select orders profile by score and keeps latest state and milestones`() {
        val facts = listOf(
            fact("低分画像", importance = 0.1f, createdAt = now - 30L * 86_400_000),
            fact("高分画像", importance = 0.95f, createdAt = now),
            fact("旧近况", kind = ChatFact.Kind.STATE, createdAt = now - 5L * 86_400_000),
            fact("最新近况", kind = ChatFact.Kind.STATE, createdAt = now),
            fact("里程碑一", kind = ChatFact.Kind.MILESTONE, createdAt = now - 2L * 86_400_000),
        )
        val sel = FourFactorRecall.select(facts, emptyList(), "", now)
        assertNotNull(sel)
        val rendered = sel!!.rendered
        assertTrue(rendered.contains("### 用户画像"))
        // 评分排序：高分画像行在低分画像行之前
        assertTrue(rendered.indexOf("高分画像") < rendered.indexOf("低分画像"))
        assertTrue(rendered.contains("### 用户近况"))
        assertTrue(rendered.contains("最新近况"))
        assertFalse(rendered.contains("旧近况"))     // STATE 只保最新
        assertTrue(rendered.contains("### 重要时刻"))
        assertTrue(rendered.contains("里程碑一"))
    }

    @Test
    fun `touch set excludes facts inside cooldown window`() {
        val hot = fact("一小时内存取过", lastAccessedAt = now - 1_000_000L)      // 冷却窗口内
        val cold = fact("很久没碰", lastAccessedAt = now - 10L * 3_600_000L)     // 冷却窗口外
        val never = fact("从未触达", lastAccessedAt = 0L)
        val sel = FourFactorRecall.select(listOf(hot, cold, never), emptyList(), "", now)!!
        assertTrue(sel.touchedFactIds.contains(cold.id))
        assertTrue(sel.touchedFactIds.contains(never.id))
        assertFalse(sel.touchedFactIds.contains(hot.id))   // 防「越召回越新鲜」
    }

    @Test
    fun `token budget drops whole lines never cuts mid-line`() {
        // 40 条长画像行（每行 ~110 token）远超 900 预算 → 预算耗尽后整行丢弃
        val facts = (1..40).map {
            fact("画像条目${it}：" + "很长的画像内容".repeat(15), importance = 0.5f)
        }
        val sel = FourFactorRecall.select(facts, emptyList(), "", now)
        assertNotNull(sel)
        val tokens = FourFactorRecall.estimateTokens(sel!!.rendered)
        // 预算硬顶对保底行同样生效（整行丢弃，不透支）
        assertTrue("rendered tokens $tokens should be bounded", tokens <= FourFactorRecall.RECALL_TOKEN_BUDGET)
        // 整行语义：每行要么完整出现要么不出现（行首标记完整）
        assertTrue(sel.rendered.lines().all { it.startsWith("-") || it.startsWith("###") })
        assertTrue(sel.rendered.lines().size < 40)   // 远超预算的行被裁
    }

    @Test
    fun `graph entities matched by keyword render as related memory`() {
        val entities = listOf(
            GraphEntity(name = "微信", entityType = "app", observations = listOf("聊天应用")),
            GraphEntity(name = "无关实体", entityType = "x", observations = listOf("无关")),
        )
        val sel = FourFactorRecall.select(emptyList(), entities, "打开微信发消息", now)
        assertNotNull(sel)
        assertTrue(sel!!.rendered.contains("### 相关记忆"))
        assertTrue(sel.rendered.contains("[微信] 聊天应用"))
        assertFalse(sel.rendered.contains("无关实体"))
    }

    @Test
    fun `empty inputs return null`() {
        assertNull(FourFactorRecall.select(emptyList(), emptyList(), "你好", now))
    }

    @Test
    fun `token estimator treats cjk as one token per char`() {
        assertEquals(4, FourFactorRecall.estimateTokens("你好世界"))
        assertEquals(1, FourFactorRecall.estimateTokens("abcd"))     // 4 chars ≈ 1 token
        assertEquals(2, FourFactorRecall.estimateTokens("abcde"))
        assertEquals(0, FourFactorRecall.estimateTokens(""))
    }
}
