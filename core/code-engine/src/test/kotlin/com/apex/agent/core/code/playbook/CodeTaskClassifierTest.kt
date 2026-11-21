package com.apex.agent.core.code.playbook

import com.apex.agent.core.engine.templates.playbook.BuiltinPlaybooks
import com.apex.agent.core.engine.templates.playbook.PlaybookDefinition
import com.apex.agent.core.engine.templates.playbook.PlaybookStage
import com.apex.agent.core.engine.templates.playbook.TriggerContextGate
import com.apex.agent.core.engine.templates.playbook.TriggerRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 任务剧本识别器测试：每套内置剧本的触发样例命中、不命中样例、双门
 * （绝对分下限 / 领先差）、上下文门、可解释理由。
 */
class CodeTaskClassifierTest {

    private val classifier = CodeTaskClassifier()
    private val builtins = BuiltinPlaybooks.ALL

    @Test
    fun `代码审查触发样例命中`() {
        val match = classifier.classify("帮我审查这段代码有没有并发问题", builtins)
        assertTrue(match.hit)
        assertEquals("builtin_playbook_code_review", match.playbookId)
        assertTrue(match.reason.contains("关键词"))
    }

    @Test
    fun `测试用例触发样例命中`() {
        val match = classifier.classify("给这个函数写单元测试，覆盖边界", builtins)
        assertTrue(match.hit)
        assertEquals("builtin_playbook_test_cases", match.playbookId)
    }

    @Test
    fun `Bug 修复触发样例命中`() {
        val match = classifier.classify("app 一打开就崩溃，堆栈贴给你，帮我修复这个 bug", builtins)
        assertTrue(match.hit)
        assertEquals("builtin_playbook_bug_fix", match.playbookId)
    }

    @Test
    fun `重构触发样例命中`() {
        val match = classifier.classify("这个文件 1200 行太乱了，帮我重构拆分一下", builtins)
        assertTrue(match.hit)
        assertEquals("builtin_playbook_refactor", match.playbookId)
    }

    @Test
    fun `Git 提交触发样例命中（有工作区）`() {
        val match = classifier.classify("根据这次的改动写个提交信息 commit message", builtins, hasWorkspace = true)
        assertTrue(match.hit)
        assertEquals("builtin_playbook_commit_message", match.playbookId)
    }

    @Test
    fun `无工作区时 Git 剧本哑火（上下文门）`() {
        val match = classifier.classify("写个提交信息 commit message", builtins, hasWorkspace = false)
        assertFalse("无工作区不应命中剧本", match.hit && match.playbookId == "builtin_playbook_commit_message")
    }

    @Test
    fun `中性任务不命中任何剧本`() {
        val match = classifier.classify("今天天气怎么样", builtins)
        assertNull(match.playbookId)
        assertFalse(match.hit)
    }

    @Test
    fun `弱信号单独出现不过绝对门`() {
        // 「测试」一词单独出现 = 2 分 < 绝对门 4。
        val match = classifier.classify("跑一下测试", builtins)
        assertFalse("弱信号不应挂载", match.hit)
        assertTrue(match.reason.contains("绝对门"))
    }

    @Test
    fun `两剧本同分时歧义不挂载（领先门）`() {
        val a = PlaybookDefinition(
            id = "user_pb_a", name = "甲剧本", master = "m",
            stages = listOf(PlaybookStage(id = "s", title = "t", subPrompt = "b")),
            triggers = listOf(TriggerRule(keywords = listOf("双信号"), score = 4))
        )
        val b = a.copy(id = "user_pb_b", name = "乙剧本")
        val match = classifier.classify("这是个双信号任务", listOf(a, b))
        assertFalse("同分歧义不应挂载", match.hit)
        assertTrue(match.reason.contains("歧义"))
    }

    @Test
    fun `正则触发可用（审查+代码组合）`() {
        val match = classifier.classify("please review the implementation of this code", builtins)
        // review 一词已由关键词命中；此处只验证正则通道不误报其他剧本。
        assertTrue(match.hit)
        assertEquals("builtin_playbook_code_review", match.playbookId)
    }

    @Test
    fun `非法正则模式防御式跳过不抛`() {
        val hostile = PlaybookDefinition(
            id = "user_pb_regex",
            name = "坏正则剧本",
            master = "m",
            stages = listOf(PlaybookStage(id = "s", title = "t", subPrompt = "b")),
            triggers = listOf(TriggerRule(regexes = listOf("[unclosed"), score = 9))
        )
        val match = classifier.classify("任意文本", builtins + hostile)
        assertTrue("坏正则剧本不应得分", match.playbookId != "user_pb_regex")
    }

    @Test
    fun `命中理由可解释（含关键词与分数）`() {
        val match = classifier.classify("帮我审查这段代码", builtins)
        assertTrue(match.hit)
        assertTrue(match.reason.contains("审查"))
        assertTrue(match.reason.contains("+4"))
    }
}
