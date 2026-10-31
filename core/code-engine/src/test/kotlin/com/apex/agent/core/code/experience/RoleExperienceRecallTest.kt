package com.apex.agent.core.code.experience

import com.apex.agent.core.engine.AgentEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * [RoleExperienceRecall] 打分/召回/注入段 + [RoleExperienceTracker] 事件
 * 聚合的联合测试。
 *
 * 覆盖：标签交集/词元交集/强化分/新鲜度/失败加权的打分口径、召回排序
 * 与门槛、注入段构建（空 → null）、Tracker 的 beginRun/onEvent/endRun
 * 全链（真序列聚合、纯聊天不沉淀、未收尾自动 ABORTED、状态归一化、
 * fire-and-forget 落卷）。
 *
 * 纯 JVM（JUnit4 + TemporaryFolder + runTest）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RoleExperienceRecallTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var baseDir: File
    private lateinit var store: RoleExperienceStore

    private val now = 1_000_000L

    @Before
    fun setup() {
        baseDir = tmp.newFolder("role_experience")
        store = RoleExperienceStore(baseDir)
    }

    private fun card(
        id: String,
        scenario: String = "修复登录页崩溃",
        tags: List<String> = listOf("bug_fix"),
        hitCount: Int = 1,
        createdAt: Long = 900_000L,
        outcome: String = "COMPLETED",
        notes: String = "此路径一次通过 —— 工具序列可直接复用"
    ) = RoleExperience(
        id = id, roleId = "builtin_coding_git", scenario = scenario,
        scenarioTags = tags, outcome = outcome, notes = notes,
        toolSequence = listOf("CodeRead", "CodeEdit×2", "CodeCheck"),
        hitCount = hitCount, createdAt = createdAt
    )

    // ═══ 打分 ═══

    @Test
    fun `tag overlap is the strongest signal`() {
        val exp = card("e1")
        val scoreSameTag = RoleExperienceRecall.score(exp, "修复崩溃", now)
        val scoreNoOverlap = RoleExperienceRecall.score(
            card("e2", scenario = "翻译日语文档", tags = listOf("translate")),
            "修复崩溃", now
        )
        assertTrue(scoreSameTag >= 3.0)
        assertTrue(scoreNoOverlap < RoleExperienceRecall.MIN_SCORE)
    }

    @Test
    fun `token overlap scores latin words`() {
        // 场景词元 login/crash 与目标重叠（无标签交集路径）。
        val exp = card("e1", scenario = "login crash reproduce", tags = emptyList())
        val s = RoleExperienceRecall.score(exp, "login crash again", now)
        assertTrue(s >= 4.0) // login + crash 两个词元 × 2
    }

    @Test
    fun `hitCount reinforcement capped`() {
        val base = RoleExperienceRecall.score(card("e1", createdAt = 0L), "修复崩溃", now)
        val veteran = RoleExperienceRecall.score(
            card("e2", hitCount = 100, createdAt = 0L), "修复崩溃", now
        )
        // 强化分封顶 5.0：老将领先但不会无限放大。
        assertTrue(veteran > base)
        assertTrue(veteran - base <= 5.0 + 0.01)
    }

    @Test
    fun `freshness window boosts recent experience`() {
        val fresh = RoleExperienceRecall.score(card("e1", createdAt = now - 1000L), "修复崩溃", now)
        val stale = RoleExperienceRecall.score(card("e2", createdAt = now - 30L * 24 * 3600 * 1000), "修复崩溃", now)
        assertTrue(fresh > stale)
    }

    @Test
    fun `failed lessons are weighted higher`() {
        val failed = RoleExperienceRecall.score(
            card("e1", outcome = "FAILED", createdAt = 0L, hitCount = 1), "修复崩溃", now
        )
        val done = RoleExperienceRecall.score(
            card("e2", outcome = "COMPLETED", createdAt = 0L, hitCount = 1), "修复崩溃", now
        )
        // 同条件下失败卡 ×1.25。
        assertEquals(done * 1.25, failed, 0.001)
    }

    // ═══ 召回 ═══

    @Test
    fun `recall returns topN by score with threshold`() {
        val book = listOf(
            card("weak", scenario = "翻译文档", tags = listOf("translate")),
            card("strong1", hitCount = 4),
            card("strong2", hitCount = 2)
        )
        val top = RoleExperienceRecall.recall(book, "修复登录崩溃", limit = 2, now = now)
        assertEquals(listOf("strong1", "strong2"), top.map { it.id })
    }

    @Test
    fun `recall drops noise below threshold`() {
        val book = listOf(card("irrelevant", scenario = "翻译成日语", tags = listOf("translate")))
        assertTrue(
            RoleExperienceRecall.recall(book, "修复登录崩溃", now = now).isEmpty()
        )
    }

    @Test
    fun `recall empty book or blank goal returns empty`() {
        assertTrue(RoleExperienceRecall.recall(emptyList(), "修复崩溃", now = now).isEmpty())
        val book = listOf(card("e1"))
        assertTrue(RoleExperienceRecall.recall(book, "   ", now = now).isEmpty())
    }

    // ═══ 注入段 ═══

    @Test
    fun `buildContext formats actionable experience lines`() {
        val ctx = RoleExperienceRecall.buildContext(
            listOf(
                card("e1", outcome = "FAILED", notes = "上次失败：编译错误；先小步编译验证"),
                card("e2", hitCount = 3)
            )
        )
        assertNotNull(ctx)
        assertTrue(ctx!!.contains("## 角色经验"))
        assertTrue(ctx.contains("✗失败"))
        assertTrue(ctx.contains("已复用3次"))
        assertTrue(ctx.contains("CodeRead→CodeEdit×2→CodeCheck"))
        assertTrue(ctx.contains("修复登录页崩溃"))
    }

    @Test
    fun `buildContext null for empty list`() {
        assertNull(RoleExperienceRecall.buildContext(emptyList()))
    }

    @Test
    fun `buildContext bounded`() {
        val many = (1..20).map { card("e$it", notes = "长注意".repeat(80)) }
        val ctx = RoleExperienceRecall.buildContext(many)
        assertTrue(ctx!!.length <= RoleExperienceRecall.CONTEXT_MAX_CHARS)
    }

    // ═══ 词元化 ═══

    @Test
    fun `tokenize mixes latin words and cjk chars without cross noise`() {
        val tokens = RoleExperienceRecall.tokenize("修复 login crash 页面")
        assertTrue("login" in tokens)
        assertTrue("crash" in tokens)
        assertTrue("修" in tokens)
        assertTrue("复" in tokens)
        assertTrue("the" !in tokens) // 停用词过滤
    }

    // ═══ Tracker：事件流 → 经验卡 ═══

    private fun tracker(scope: TestScope) =
        RoleExperienceTracker(store, scope, clock = { now })

    @Test
    fun `tracker aggregates real tool sequence and persists card`() = runTest {
        val t = tracker(this)
        t.beginRun("修复登录页崩溃", "builtin_coding_git", "Alpha")
        t.onEvent(AgentEvent.IterationStart(1))
        t.onEvent(AgentEvent.ToolCallStart("c1", "CodeRead", """{"path":"a.kt"}"""))
        t.onEvent(
            AgentEvent.ToolCallComplete("c1", "CodeRead", """{"path":"a.kt"}"", "ok", "ok", true, 10)
        )
        t.onEvent(AgentEvent.ToolCallStart("c2", "CodeEdit", """{"path":"a.kt"}"""))
        t.onEvent(
            AgentEvent.ToolCallComplete("c2", "CodeEdit", """{"path":"a.kt"}"", "ok", "ok", true, 10)
        )
        t.onEvent(AgentEvent.ToolCallStart("c3", "CodeEdit", """{"path":"a.kt"}"""))
        t.onEvent(
            AgentEvent.ToolCallComplete("c3", "CodeEdit", """{"path":"a.kt"}"", "ok", "ok", true, 10)
        )
        val exp = t.endRun("COMPLETED")
        assertNotNull(exp)
        advanceUntilIdle()
        assertEquals(listOf("CodeRead", "CodeEdit×2"), exp!!.toolSequence)
        assertEquals(3, exp.toolCalls)
        assertEquals(1, exp.filesTouched)
        assertEquals(1, exp.iterations)
        // 落卷完成（fire-and-forget 已被 advanceUntilIdle 冲刷）。
        assertEquals(1, store.list("builtin_coding_git").size)
    }

    @Test
    fun `pure chat turn is not distilled`() = runTest {
        val t = tracker(this)
        t.beginRun("聊聊天气", "builtin_coding_git", "Alpha")
        t.onEvent(AgentEvent.IterationStart(1))
        t.onEvent(AgentEvent.ResponseComplete("今天晴"))
        assertNull(t.endRun("COMPLETED"))
        advanceUntilIdle()
        assertTrue(store.list("builtin_coding_git").isEmpty())
    }

    @Test
    fun `blank goal is not distilled`() = runTest {
        val t = tracker(this)
        t.beginRun("   ", "builtin_coding_git", "Alpha")
        t.onEvent(AgentEvent.ToolCallStart("c1", "CodeRead", "{}"))
        assertNull(t.endRun("COMPLETED"))
    }

    @Test
    fun `events after endRun are ignored`() = runTest {
        val t = tracker(this)
        t.beginRun("修复任务", "builtin_coding_git", "Alpha")
        t.onEvent(AgentEvent.ToolCallStart("c1", "CodeRead", "{}"))
        t.endRun("COMPLETED")
        // 收尾后的尾部事件不串到下一张卡（active=false 静默忽略）。
        t.onEvent(AgentEvent.ToolCallStart("c2", "CodeRead", "{}"))
        t.beginRun("第二个任务", "builtin_coding_git", "Alpha")
        t.onEvent(AgentEvent.ToolCallStart("c3", "CodeWrite", """{"path":"b.kt"}"""))
        val second = t.endRun("COMPLETED")
        advanceUntilIdle()
        assertEquals(1, second!!.toolCalls)
        assertEquals(listOf("CodeWrite"), second.toolSequence)
    }

    @Test
    fun `unfinished run is auto aborted on next beginRun`() = runTest {
        val t = tracker(this)
        t.beginRun("未收尾任务", "builtin_coding_git", "Alpha")
        t.onEvent(AgentEvent.ToolCallStart("c1", "CodeRead", "{}"))
        // 未收尾直接开新任务 → 旧 run 按 ABORTED 沉淀。
        t.beginRun("新任务", "builtin_coding_git", "Alpha")
        t.onEvent(AgentEvent.ToolCallStart("c2", "CodeRead", "{}"))
        t.endRun("FAILED", errorMessage = "编译失败")
        advanceUntilIdle()
        val book = store.list("builtin_coding_git")
        assertEquals(2, book.size)
        assertEquals("ABORTED", book[0].outcome)
        assertEquals("FAILED", book[1].outcome)
        assertTrue(book[1].notes.contains("上次失败"))
    }

    @Test
    fun `tool failures are counted and path extraction is defensive`() = runTest {
        val t = tracker(this)
        t.beginRun("修 bug", "builtin_coding_git", "Alpha")
        t.onEvent(AgentEvent.ToolCallStart("c1", "ShellExecute", """{"cmd":"ls"}"""))
        t.onEvent(
            AgentEvent.ToolCallComplete("c1", "ShellExecute", """{"cmd":"ls"}""", "boom", "boom", false, 5)
        )
        // 非 CodeEdit/CodeWrite 的成功调用不计 filesTouched。
        t.onEvent(AgentEvent.ToolCallStart("c2", "CodeRead", "not-json"))
        t.onEvent(
            AgentEvent.ToolCallComplete("c2", "CodeRead", "not-json", "ok", "ok", true, 5)
        )
        val exp = t.endRun("COMPLETED")
        assertEquals(2, exp!!.toolCalls)
        assertEquals(1, exp.toolFailures)
        assertEquals(0, exp.filesTouched)
    }
}
