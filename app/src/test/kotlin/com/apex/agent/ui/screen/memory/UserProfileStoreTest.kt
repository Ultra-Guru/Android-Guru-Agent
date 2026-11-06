package com.apex.agent.ui.screen.memory

import com.apex.agent.ui.screen.memory.profile.DistillAction
import com.apex.agent.ui.screen.memory.profile.ProfileAspect
import com.apex.agent.ui.screen.memory.profile.UserProfileItem
import com.apex.agent.ui.screen.memory.profile.UserProfileStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 用户画像存储单测（runTest + TemporaryFolder，仓库测试纪律：无 mock 框架）。
 *
 * 覆盖：upsert 双态 / delete 幂等 / clear / 蒸馏动作门禁（未知维度、空
 * 内容、置信度钳制、content 截断、update 逐字匹配、动作上限）/ 有界
 * 裁剪 / 坏卷隔离 / 跨实例持久化。
 */
class UserProfileStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun store() = UserProfileStore(tmp.newFolder())

    private fun manual(
        id: String,
        aspect: ProfileAspect = ProfileAspect.TECH_STACK,
        content: String = "content-$id",
        confidence: Float = 0.9f,
    ) = UserProfileItem(
        id = id,
        aspect = aspect.key,
        content = content,
        confidence = confidence,
        source = UserProfileItem.SOURCE_MANUAL,
    )

    @Test
    fun `upsert appends then edits by id`() = runTest {
        val store = store()
        val first = store.upsert(manual("p1"))
        assertEquals(1, first.size)
        // 同 id 再写 = 编辑（不新增）
        val edited = store.upsert(manual("p1", content = "改写后的内容"))
        assertEquals(1, edited.size)
        assertEquals("改写后的内容", edited.first().content)
    }

    @Test
    fun `delete is idempotent for unknown id`() = runTest {
        val store = store()
        store.upsert(manual("p1"))
        store.delete("p1")
        assertTrue(store.list().isEmpty())
        store.delete("p1")   // 未知 id 静默
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun `clear empties the book`() = runTest {
        val store = store()
        store.upsert(manual("p1"))
        store.upsert(manual("p2"))
        store.clear()
        assertTrue(store.list().isEmpty())
    }

    // ═══ 蒸馏动作门禁 ═══

    @Test
    fun `apply adds new action with clamped confidence and truncated content`() = runTest {
        val store = store()
        val report = store.apply(
            listOf(
                DistillAction(
                    op = "new",
                    aspect = "tech-stack",
                    content = "x".repeat(200),
                    confidence = 5.0f,
                    evidence = "来源摘要",
                )
            ),
            now = 1000L
        )
        assertEquals(1, report.added)
        val saved = store.list().single()
        assertTrue(saved.content.length <= UserProfileStore.MAX_CONTENT_CHARS)
        assertEquals(UserProfileStore.MAX_CONFIDENCE, saved.confidence)
        assertEquals(UserProfileItem.SOURCE_DISTILLED, saved.source)
    }

    @Test
    fun `apply skips unknown aspect and blank content`() = runTest {
        val store = store()
        val report = store.apply(
            listOf(
                DistillAction(op = "new", aspect = "hobby", content = "有效内容"),
                DistillAction(op = "new", aspect = "tech-stack", content = "   "),
            ),
            now = 1000L
        )
        assertEquals(0, report.added)
        assertEquals(2, report.skipped)
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun `apply updates only when old matches verbatim`() = runTest {
        val store = store()
        store.upsert(manual("p1", content = "原始画像", confidence = 0.5f))

        // old 不匹配 → 跳过（宁可漏更不可错改）
        val miss = store.apply(
            listOf(DistillAction(op = "update", old = "不存在的原文", content = "新内容", aspect = "tech-stack")),
            now = 2000L
        )
        assertEquals(0, miss.updated)
        assertEquals(1, miss.skipped)

        // old 逐字匹配 → 更新（保留 id / createdAt，刷新内容与置信度）
        val hit = store.apply(
            listOf(DistillAction(op = "update", old = "原始画像", content = "演进后的画像", confidence = 0.8f, aspect = "tech-stack")),
            now = 3000L
        )
        assertEquals(1, hit.updated)
        val saved = store.list().single()
        assertEquals("演进后的画像", saved.content)
        assertEquals(0.8f, saved.confidence)
        assertEquals("p1", saved.id)
    }

    @Test
    fun `apply caps action count`() = runTest {
        val store = store()
        val actions = (1..10).map { index ->
            DistillAction(op = "new", aspect = "habits", content = "习惯-$index", confidence = 0.5f)
        }
        val report = store.apply(actions, now = 1000L)
        assertEquals(UserProfileStore.MAX_ACTIONS_PER_DISTILL, report.added)
    }

    // ═══ 有界性 ═══

    @Test
    fun `per-aspect bound evicts weakest first`() = runTest {
        val store = store()
        val over = UserProfileStore.MAX_PER_ASPECT + 3
        repeat(over) { index ->
            store.upsert(
                manual(
                    id = "h$index",
                    aspect = ProfileAspect.HABITS,
                    content = "习惯-$index",
                    confidence = if (index == 0) 0.4f else 0.9f,   // 首条最弱，应被淘汰
                )
            )
        }
        val saved = store.list()
        assertEquals(UserProfileStore.MAX_PER_ASPECT, saved.size)
        assertFalse(saved.any { it.content == "习惯-0" })
    }

    // ═══ 崩溃安全与持久化 ═══

    @Test
    fun `corrupt volume is quarantined and starts empty`() = runTest {
        val dir = tmp.newFolder()
        val bad = dir.resolve("profile.json")
        bad.writeText("这不是 JSON {{{")
        val store = UserProfileStore(dir)
        assertTrue(store.list().isEmpty())
        assertTrue(dir.resolve("profile.json.corrupt").exists())
        // 隔离后写路径恢复正常
        store.upsert(manual("p1"))
        assertEquals(1, store.list().size)
    }

    @Test
    fun `persisted book survives new instance`() = runTest {
        val dir = tmp.newFolder()
        val first = UserProfileStore(dir)
        first.upsert(manual("p1", content = "跨实例可见"))
        val second = UserProfileStore(dir)
        assertEquals("跨实例可见", second.list().single().content)
    }
}
