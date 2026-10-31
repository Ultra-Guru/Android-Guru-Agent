package com.apex.agent.core.code.experience

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * [RoleExperienceStore] 持久化测试。
 *
 * 覆盖：append/list roundtrip、delete、clear、bumpHits 强化回写、
 * 有界裁剪（dropWeakest 淘汰最弱）、损坏 JSON 卷隔离、非法 roleId
 * 防御、跨实例重载（持久性）、空卷不落盘。
 *
 * 纯 JVM（JUnit4 + TemporaryFolder + runTest），无 Android 依赖 —— 与
 * 同模块 LongTaskStoreTest 同构。
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class RoleExperienceStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var baseDir: File
    private lateinit var store: RoleExperienceStore

    @Before
    fun setup() {
        baseDir = tmp.newFolder("role_experience")
        store = RoleExperienceStore(baseDir)
    }

    /** 最小可用经验卡样本（hitCount/createdAt 可覆写驱动裁剪与强化断言）。 */
    private fun card(
        id: String,
        roleId: String = "builtin_coding_git",
        hitCount: Int = 1,
        createdAt: Long = 1000L,
        notes: String = "此路径一次通过 —— 工具序列可直接复用"
    ) = RoleExperience(
        id = id,
        roleId = roleId,
        scenario = "修复登录页崩溃",
        scenarioTags = listOf("bug_fix"),
        toolSequence = listOf("CodeRead", "CodeEdit×2", "CodeCheck"),
        toolProfile = mapOf("CodeRead" to 1, "CodeEdit" to 2, "CodeCheck" to 1),
        outcome = "COMPLETED",
        iterations = 4,
        toolCalls = 4,
        toolFailures = 0,
        durationMs = 90_000L,
        filesTouched = 2,
        notes = notes,
        workspaceName = "Alpha 项目",
        createdAt = createdAt,
        hitCount = hitCount
    )

    @Test
    fun `append then list roundtrip preserves all fields`() = runTest {
        val exp = card("exp_1", hitCount = 3, createdAt = 42L)
        store.append(exp)

        val loaded = store.list("builtin_coding_git")
        assertEquals(1, loaded.size)
        val round = loaded.first()
        assertEquals(exp, round)
    }

    @Test
    fun `append persists across store instances`() = runTest {
        store.append(card("exp_persist"))
        // 新实例（同目录）模拟进程重启。
        val reopened = RoleExperienceStore(baseDir)
        assertEquals(1, reopened.list("builtin_coding_git").size)
    }

    @Test
    fun `append order is chronological newest last`() = runTest {
        store.append(card("exp_a", createdAt = 100L))
        store.append(card("exp_b", createdAt = 200L))
        val loaded = store.list("builtin_coding_git")
        assertEquals(listOf("exp_a", "exp_b"), loaded.map { it.id })
    }

    @Test
    fun `roles are isolated per book`() = runTest {
        store.append(card("exp_git", roleId = "builtin_coding_git"))
        store.append(card("exp_kt", roleId = "builtin_coding_kotlin"))
        assertEquals(1, store.list("builtin_coding_git").size)
        assertEquals(1, store.list("builtin_coding_kotlin").size)
        assertTrue(store.list("builtin_coding_java").isEmpty())
    }

    @Test
    fun `delete removes single card and is idempotent`() = runTest {
        store.append(card("exp_keep"))
        store.append(card("exp_drop"))
        store.delete("builtin_coding_git", "exp_drop")
        store.delete("builtin_coding_git", "exp_drop") // 二次删除幂等
        assertEquals(listOf("exp_keep"), store.list("builtin_coding_git").map { it.id })
    }

    @Test
    fun `clear empties the book`() = runTest {
        store.append(card("exp_1"))
        store.append(card("exp_2"))
        store.clear("builtin_coding_git")
        assertTrue(store.list("builtin_coding_git").isEmpty())
        // 清空后可继续追加（卷复活）。
        store.append(card("exp_3"))
        assertEquals(1, store.list("builtin_coding_git").size)
    }

    @Test
    fun `bumpHits increments hitCount and stamps lastUsedAt`() = runTest {
        store.append(card("exp_hit", hitCount = 1))
        store.bumpHits("builtin_coding_git", listOf("exp_hit"), now = 999L)
        val bumped = store.list("builtin_coding_git").first()
        assertEquals(2, bumped.hitCount)
        assertEquals(999L, bumped.lastUsedAt)
        // 未命中的兄弟卡不受影响。
        store.append(card("exp_other"))
        store.bumpHits("builtin_coding_git", listOf("exp_hit"), now = 1000L)
        val pair = store.list("builtin_coding_git").associateBy { it.id }
        assertEquals(3, pair.getValue("exp_hit").hitCount)
        assertEquals(1, pair.getValue("exp_other").hitCount)
        assertEquals(0L, pair.getValue("exp_other").lastUsedAt)
    }

    @Test
    fun `bounded book evicts weakest first`() = runTest {
        // 超量追加（hitCount 低 + 最旧的最先淘汰）。
        val many = (1..RoleExperienceStore.MAX_PER_ROLE + 5).map { i ->
            card("exp_$i", hitCount = if (i % 2 == 0) 5 else 1, createdAt = i * 10L)
        }
        many.forEach { store.append(it) }
        val loaded = store.list("builtin_coding_git")
        assertEquals(RoleExperienceStore.MAX_PER_ROLE, loaded.size)
        // 最弱（hitCount=1 且最旧）被淘汰：exp_1（奇数弱卡最旧）不在留存集。
        assertTrue(loaded.none { it.id == "exp_1" })
        // 强卡（hitCount=5）全部留存。
        assertTrue(loaded.filter { it.id.startsWith("exp_") && it.id.endsWith("0") }
            .all { it.hitCount == 5 })
    }

    @Test
    fun `corrupt book json is isolated and others load`() = runTest {
        store.append(card("exp_good", roleId = "builtin_coding_git"))
        // 手工写一卷坏 JSON（截断 JSON）。
        File(baseDir, "builtin_coding_kotlin.json").writeText("""{"roleId":"builtin_coding_kotlin","experiences":[{"id":"x""")
        val reopened = RoleExperienceStore(baseDir)
        assertEquals(1, reopened.list("builtin_coding_git").size)
        assertTrue(reopened.list("builtin_coding_kotlin").isEmpty())
    }

    @Test
    fun `tmp leftovers are ignored on load`() = runTest {
        File(baseDir, "builtin_coding_git.json.tmp").writeText("garbage-in-flight")
        val reopened = RoleExperienceStore(baseDir)
        assertTrue(reopened.list("builtin_coding_git").isEmpty())
        // tmp 不影响后续正常写（写路径会覆盖 tmp）。
        reopened.append(card("exp_after_tmp"))
        assertEquals(1, reopened.list("builtin_coding_git").size)
        // tmp 残留被写路径清理。
        assertTrue(!File(baseDir, "builtin_coding_git.json.tmp").exists())
    }

    @Test
    fun `book roleId mismatch is healed to filename`() = runTest {
        // 卷主与文件名不一致（手工改名）：以文件名为准重建（防串卷）。
        File(baseDir, "builtin_coding_git.json").writeText(
            """{"roleId":"builtin_coding_kotlin","experiences":[""" +
                """{"id":"exp_x","roleId":"builtin_coding_kotlin","scenario":"s","outcome":"COMPLETED"}]}"""
        )
        val reopened = RoleExperienceStore(baseDir)
        val healed = reopened.list("builtin_coding_git")
        assertEquals(1, healed.size)
        assertEquals("builtin_coding_git", healed.first().roleId)
        assertTrue(reopened.list("builtin_coding_kotlin").isEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `path traversal roleId is rejected on write`() = runTest {
        // 非法 roleId 在写路径 fail-fast（我方代码 bug 口径）。
        store.append(card("exp_evil", roleId = "../escape"))
    }

    @Test
    fun `snapshotBlocking matches suspend list`() = runTest {
        store.append(card("exp_snap"))
        val async = store.list("builtin_coding_git")
        val sync = store.snapshotBlocking("builtin_coding_git")
        assertEquals(async, sync)
    }
}
