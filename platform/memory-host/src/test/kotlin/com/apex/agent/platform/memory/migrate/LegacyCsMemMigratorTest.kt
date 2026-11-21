package com.apex.agent.platform.memory.migrate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 旧 FSM 宏 transitions_json → 类型化 MacroStep 的一对一映射契约。
 *
 * 旧库的 actionParams 是裸 JSON 字符串，回放侧正则再解析 —— 拆库后动作
 * 入库前即类型化；本测试锁死迁移器对四种历史形态的解析（click/swipe/
 * inputtext/back），任何转换失败必须整宏丢弃（null），不允许半迁移。
 * 解析器住在 companion object（纯函数静态位）—— 直调零构造。
 */
class LegacyCsMemMigratorTest {

    private fun parse(json: String) = LegacyCsMemMigrator.parseLegacyTransitions(json)

    @Test
    fun `click transition parses to typed Tap with anchors`() {
        val json = """[
            {"fromState":"fp-a","actionType":"click","actionParams":"{\"x\":540,\"y\":1200}","toState":"fp-b"}
        ]"""
        val steps = parse(json)
        assertNotNull(steps)
        assertEquals(1, steps!!.size)
        val step = steps.first()
        assertTrue(step.action is com.apex.memkit.core.model.MacroAction.Tap)
        val tap = step.action as com.apex.memkit.core.model.MacroAction.Tap
        assertEquals(540, tap.x)
        assertEquals(1200, tap.y)
        assertEquals(setOf("fp-a"), step.anchorFingerprints)
        assertEquals(setOf("fp-b"), step.nextAnchorFingerprints)
    }

    @Test
    fun `swipe transition keeps duration default on absence`() {
        val json = """[
            {"fromState":"s1","actionType":"swipe","actionParams":"{\"x1\":100,\"y1\":800,\"x2\":100,\"y2\":200}","toState":"s2"}
        ]"""
        val steps = parse(json)
        assertNotNull(steps)
        val swipe = steps!!.first().action as com.apex.memkit.core.model.MacroAction.Swipe
        assertEquals(300L, swipe.durationMs)
        assertEquals(100, swipe.x1)
        assertEquals(200, swipe.y2)
    }

    @Test
    fun `inputtext and back transitions parse`() {
        val json = """[
            {"fromState":"s1","actionType":"inputText","actionParams":"{\"text\":\"hello\"}","toState":"s2"},
            {"fromState":"s2","actionType":"back","actionParams":"{}","toState":"s3"}
        ]"""
        val steps = parse(json)
        assertNotNull(steps)
        assertEquals(2, steps!!.size)
        val input = steps[0].action as com.apex.memkit.core.model.MacroAction.Input
        assertEquals("hello", input.text)
        assertTrue(steps[1].action is com.apex.memkit.core.model.MacroAction.Back)
    }

    @Test
    fun `unknown action type drops whole macro`() {
        val json = """[
            {"fromState":"s1","actionType":"launch_app","actionParams":"{\"pkg\":\"x\"}","toState":"s2"}
        ]"""
        assertNull(parse(json))
    }

    @Test
    fun `malformed json drops whole macro`() {
        assertNull(parse("not-json"))
        assertNull(parse("""[{"fromState":"s1"}]"""))
    }

    @Test
    fun `missing coordinate drops whole macro`() {
        val json = """[
            {"fromState":"s1","actionType":"click","actionParams":"{\"x\":540}","toState":"s2"}
        ]"""
        assertNull(parse(json))
    }

    // ══ #821 列名 → 字段契约（by-name 取值，列序变更不再静默错位）═══

    private fun row(vararg pairs: Pair<String, Any?>) =
        LegacyCsMemMigrator.QueryRow(HashMap(mapOf(*pairs)))

    @Test
    fun `episode record reads started_at and finished_at by column name`() {
        val record = LegacyCsMemMigrator.episodeRecord(
            "ep-1",
            row(
                "episode_id" to "ep-1",
                "goal" to "给张三发图片",
                "app_package" to "com.tencent.mm",
                "app_version" to "8.0",
                "status" to "SUCCEEDED",
                "started_at" to 100L,       // #821：真实开始时间（旧实现错读 finished_at）
                "finished_at" to 200L,      // #821：旧实现恒丢（恒 null）
                "total_actions" to 7L,
            ),
        )
        assertEquals("ep-1", record.episodeId)
        assertEquals("给张三发图片", record.goal)
        assertEquals("com.tencent.mm", record.appPackage)
        assertEquals("8.0", record.appVersion)
        assertEquals(
            com.apex.memkit.core.model.EpisodeStatus.SUCCEEDED,
            record.status,
        )
        assertEquals(100L, record.startedAt)
        assertEquals(200L, record.finishedAt)
        assertEquals(7, record.actionCount)
    }

    @Test
    fun `episode record tolerates null and missing columns`() {
        val record = LegacyCsMemMigrator.episodeRecord(
            "manual_x",
            row("episode_id" to "manual_x", "goal" to null),
        )
        assertEquals("(未知目标)", record.goal)
        assertEquals(0L, record.startedAt)
        assertNull(record.finishedAt)
        assertEquals(0, record.actionCount)
        assertTrue(record.manual)   // manual_ 前缀 → 手工情景
    }

    @Test
    fun `episode column list matches expected legacy schema`() {
        // 列名清单钉死旧库 schema（迁移 SQL 的列序契约源）—— 列序变化
        // 不再影响取值（by-name），但列名缺失会在字段缺省测试里暴露。
        assertEquals(
            listOf(
                "episode_id", "goal", "app_package", "app_version", "status",
                "started_at", "finished_at", "total_actions",
            ),
            LegacyCsMemMigrator.EPISODE_COLUMNS,
        )
    }
}
