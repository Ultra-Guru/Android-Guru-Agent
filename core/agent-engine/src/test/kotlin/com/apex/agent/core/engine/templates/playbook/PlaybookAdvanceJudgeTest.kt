package com.apex.agent.core.engine.templates.playbook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 幕推进判定器测试：下一幕头+实质正文才放行、缺头原地、空洞不放行、
 * 跳幕拦截、末幕完成标记收尾、COMPOSED 剧本恒不推进。
 */
class PlaybookAdvanceJudgeTest {

    private val playbook = BuiltinPlaybooks.ALL.first { it.id == "builtin_playbook_code_review" }

    private fun body(chars: Int) = "评审发现如下：\n" + "x".repeat(chars)

    @Test
    fun `下一幕章节头且本幕正文实质 推进`() {
        val response = "【幕1·预检侦察】\n" + body(120) + "\n\n【幕2·正确性与架构】\n开始深查。"
        val decision = PlaybookAdvanceJudge.judge(response, playbook, currentStageIndex = 0)
        assertEquals(PlaybookAdvanceJudge.AdvanceKind.ADVANCED, decision.kind)
        assertEquals(1, decision.nextStageIndex)
        assertTrue(decision.reason.contains("推进至幕2"))
    }

    @Test
    fun `无章节头原地并说明期望`() {
        val decision = PlaybookAdvanceJudge.judge(body(200), playbook, currentStageIndex = 0)
        assertEquals(PlaybookAdvanceJudge.AdvanceKind.STAYED, decision.kind)
        assertEquals(0, decision.nextStageIndex)
        assertTrue(decision.reason.contains("未按幕协议"))
        assertTrue(decision.reason.contains("【幕1·预检侦察】"))
    }

    @Test
    fun `空洞正文不推进（诚实门）`() {
        val response = "【幕1·预检侦察】\n好。\n\n【幕2·正确性与架构】\n" + body(120)
        val decision = PlaybookAdvanceJudge.judge(response, playbook, currentStageIndex = 0)
        assertEquals(PlaybookAdvanceJudge.AdvanceKind.STAYED, decision.kind)
        assertTrue(decision.reason.contains("空洞") || decision.reason.contains("推进形态"))
    }

    @Test
    fun `本幕章节头不构成推进`() {
        val response = "【幕1·预检侦察】\n" + body(300)
        val decision = PlaybookAdvanceJudge.judge(response, playbook, currentStageIndex = 0)
        assertEquals(PlaybookAdvanceJudge.AdvanceKind.STAYED, decision.kind)
        assertTrue(decision.reason.contains("仍在本幕"))
    }

    @Test
    fun `跳幕章节头按原地处理`() {
        val response = "【幕1·预检侦察】\n" + body(100) + "\n\n【幕4·结论与修复清单】\n" + body(100)
        val decision = PlaybookAdvanceJudge.judge(response, playbook, currentStageIndex = 0)
        assertEquals(PlaybookAdvanceJudge.AdvanceKind.STAYED, decision.kind)
        assertTrue(decision.reason.contains("仍在本幕"))
    }

    @Test
    fun `末幕完成标记收尾`() {
        val response = "【幕4·结论与修复清单】\n" + body(150) + "\n《剧本完成》"
        val decision = PlaybookAdvanceJudge.judge(response, playbook, currentStageIndex = 3)
        assertEquals(PlaybookAdvanceJudge.AdvanceKind.COMPLETED, decision.kind)
        assertEquals(3, decision.nextStageIndex)
    }

    @Test
    fun `非末幕出现完成标记不收尾`() {
        val response = "【幕2·正确性与架构】\n" + body(150) + "\n《剧本完成》"
        val decision = PlaybookAdvanceJudge.judge(response, playbook, currentStageIndex = 1)
        assertEquals(PlaybookAdvanceJudge.AdvanceKind.STAYED, decision.kind)
    }

    @Test
    fun `全角变体章节头同样识别`() {
        val response = "【幕1：预检侦察】\n" + body(120) + "\n\n【幕2·正确性与架构】\n" + body(120)
        val decision = PlaybookAdvanceJudge.judge(response, playbook, currentStageIndex = 0)
        assertEquals(PlaybookAdvanceJudge.AdvanceKind.ADVANCED, decision.kind)
    }

    @Test
    fun `合成模式剧本用同判定器时恒无幕头推进（幕1=末幕）`() {
        val commit = BuiltinPlaybooks.ALL.first { it.id == "builtin_playbook_commit_message" }
        val response = "【幕1·变更解读与提交信息】\n" + body(150) + "\n《剧本完成》"
        val decision = PlaybookAdvanceJudge.judge(response, commit, currentStageIndex = 0)
        assertEquals(PlaybookAdvanceJudge.AdvanceKind.COMPLETED, decision.kind)
    }
}
