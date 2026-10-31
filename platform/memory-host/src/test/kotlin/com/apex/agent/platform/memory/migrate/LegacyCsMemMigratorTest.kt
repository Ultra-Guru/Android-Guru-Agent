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
}
