package com.apex.agent.core.engine.templates.playbook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 剧本指令构建器测试：分幕只含当前幕、合成含全幕、进度一行态、
 * 预算收缩次序（附录先收缩，幕头/主提示词永不截断）。
 */
class PlaybookDirectiveBuilderTest {

    private val playbook = BuiltinPlaybooks.ALL.first { it.id == "builtin_playbook_code_review" }

    @Test
    fun `分幕指令含主提示词与当前幕子提示词`() {
        val directive = PlaybookDirectiveBuilder.build(playbook, stageIndex = 1)
        assertTrue("缺剧本头", directive.contains("《代码审查剧本》"))
        assertTrue("缺主提示词", directive.contains("幕推进协议"))
        assertTrue("缺幕头", directive.contains("【幕2·正确性与架构】"))
        assertTrue("缺本幕子提示词", directive.contains("深查正确性与架构问题"))
        assertFalse(
            "其他幕子提示词不应出现",
            directive.contains("分级评审结论") || directive.contains("汇总侦察附录")
        )
    }

    @Test
    fun `进度一行态含已完成与待办`() {
        val directive = PlaybookDirectiveBuilder.build(
            playbook, stageIndex = 2, completedStageIndexes = listOf(0, 1)
        )
        assertTrue(directive.contains("已完成 幕1✓、幕2✓"))
        assertTrue(directive.contains("待办 幕4"))
        assertTrue(directive.contains(PlaybookDirectiveBuilder.COMPLETION_TOKEN))
    }

    @Test
    fun `侦察附录拼接且不出现于无侦察幕`() {
        val withRecon = PlaybookDirectiveBuilder.build(
            playbook, stageIndex = 0, reconLines = listOf("🟢 [rule] 命名发现（L3）")
        )
        assertTrue(withRecon.contains("侦察附录"))
        assertTrue(withRecon.contains("🟢 [rule] 命名发现（L3）"))

        val withoutRecon = PlaybookDirectiveBuilder.build(playbook, stageIndex = 2, reconLines = emptyList())
        assertFalse(withoutRecon.contains("侦察附录"))
    }

    @Test
    fun `合成模式含全部幕`() {
        val commit = BuiltinPlaybooks.ALL.first { it.id == "builtin_playbook_commit_message" }
        val directive = PlaybookDirectiveBuilder.build(commit, stageIndex = 0)
        assertTrue(directive.contains("【幕1·变更解读与提交信息】"))
        assertTrue(directive.contains("变更解读"))
        assertEquals(commit.stageCount, Regex("""【幕\d+·""").findAll(directive).count())
    }

    @Test
    fun `超预算收缩保留幕头与主提示词`() {
        // 构造超长子提示词场景：直接用长附录行压预算。
        val longLines = List(60) { "🟢 附录行$it " + "x".repeat(120) }
        val directive = PlaybookDirectiveBuilder.build(playbook, stageIndex = 0, reconLines = longLines)
        assertTrue(
            "指令超出预算 ${directive.length}",
            directive.length <= PlaybookDirectiveBuilder.MAX_DIRECTIVE_CHARS
        )
        assertTrue("幕头被截断", directive.contains("【幕1·预检侦察】"))
        assertTrue("主提示词被截断", directive.contains("幕推进协议"))
    }

    @Test
    fun `附录行数上限生效`() {
        val lines = List(50) { "🔵 附录行$it" }
        val directive = PlaybookDirectiveBuilder.build(playbook, stageIndex = 0, reconLines = lines)
        val reconCount = Regex("""^🔵 附录行""").findAll(directive).count()
        assertTrue("附录行数未截断: $reconCount", reconCount <= PlaybookDirectiveBuilder.MAX_RECON_LINES)
    }

    @Test
    fun `越界幕下标回第一幕（防御式）`() {
        val directive = PlaybookDirectiveBuilder.build(playbook, stageIndex = 99)
        assertTrue(directive.contains("【幕1·预检侦察】"))
    }
}
