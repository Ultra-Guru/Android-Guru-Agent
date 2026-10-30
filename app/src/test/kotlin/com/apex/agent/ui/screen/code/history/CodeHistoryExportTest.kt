package com.apex.agent.ui.screen.code.history

import com.apex.agent.ui.screen.code.session.CodeSessionSnapshot
import com.apex.agent.ui.screen.code.session.StorableCodeMessage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [buildCodeHistoryMarkdown] 渲染契约（纯函数）：角色分段、工具条目徽标、
 * 标题回退与元信息行 —— 与 Agent 层 buildSessionMarkdown 同族断言口径。
 */
class CodeHistoryExportTest {

    private fun entry(
        id: String = "1000",
        title: String = "帮我修浏览器",
        updatedAt: Long = 1_700_000_000_000L
    ) = CodeHistoryEntry(
        id = id, workspaceId = "ws", title = title, updatedAt = updatedAt, messageCount = 4
    )

    @Test
    fun `renders role sections and tool badges`() {
        val snapshot = CodeSessionSnapshot(
            workspaceId = "ws",
            messages = listOf(
                StorableCodeMessage("SYSTEM", "会话开始"),
                StorableCodeMessage("USER", "跑一下测试"),
                StorableCodeMessage(
                    "TOOL", "exit 0", toolName = "TerminalExec",
                    toolSuccess = true, durationMs = 4
                ),
                StorableCodeMessage("ASSISTANT", "全部通过")
            ),
            lastActiveFile = "app/src/Foo.kt",
            updatedAt = 1_700_000_000_000L
        )

        val md = buildCodeHistoryMarkdown(entry(), snapshot)

        assertTrue("用户段", md.contains("## 🧑 User"))
        assertTrue("助手段", md.contains("## 🤖 Assistant"))
        assertTrue("系统段走引用块", md.contains("> 会话开始"))
        assertTrue("工具徽标带名称与成功态", md.contains("**🔧 TerminalExec · OK · 4ms**"))
        assertTrue("工具正文进代码围栏", md.contains("```") && md.contains("exit 0"))
        assertTrue("元信息行含最近编辑", md.contains("最近编辑 app/src/Foo.kt"))
        assertTrue("元信息行含条数", md.contains("4 条消息"))
        assertTrue("标题优先取条目标题", md.contains("# 帮我修浏览器"))
        assertTrue("落款", md.contains("Exported from Apex Agent Coding History"))
    }

    @Test
    fun `failed tool renders FAILED badge and drops zero duration`() {
        val snapshot = CodeSessionSnapshot(
            workspaceId = "ws",
            messages = listOf(
                StorableCodeMessage(
                    "TOOL", "timeout", toolName = "CodeTest",
                    toolSuccess = false, durationMs = 0
                )
            )
        )

        val md = buildCodeHistoryMarkdown(entry(), snapshot)

        assertTrue("失败徽标", md.contains("**🔧 CodeTest · FAILED**"))
        assertFalse("零耗时不渲染时长段", md.contains("0ms"))
    }

    @Test
    fun `blank entry title falls back to session id`() {
        val snapshot = CodeSessionSnapshot(
            workspaceId = "ws",
            messages = listOf(StorableCodeMessage("USER", "hello"))
        )

        val md = buildCodeHistoryMarkdown(entry(id = "1730000000000", title = ""), snapshot)

        assertTrue(md.contains("# 1730000000000"))
    }

    @Test
    fun `unknown role degrades to quote line`() {
        val snapshot = CodeSessionSnapshot(
            workspaceId = "ws",
            messages = listOf(StorableCodeMessage("WHATEVER", "legacy role"))
        )

        val md = buildCodeHistoryMarkdown(entry(), snapshot)

        assertTrue("未知角色保内容走引用块", md.contains("> legacy role"))
        assertFalse("不误渲染为已知段落", md.contains("## 🤖"))
    }

    @Test
    fun `no last active file omits the segment`() {
        val snapshot = CodeSessionSnapshot(
            workspaceId = "ws",
            messages = listOf(StorableCodeMessage("USER", "hi"))
        )

        val md = buildCodeHistoryMarkdown(entry(), snapshot)

        assertFalse("无最近编辑不渲染该段", md.contains("最近编辑"))
    }
}
