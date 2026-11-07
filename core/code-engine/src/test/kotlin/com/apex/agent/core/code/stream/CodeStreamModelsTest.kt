package com.apex.agent.core.code.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ToolKind] / [StreamToolCall] / [ToolCallStatus] 模型契约测试。
 */
class CodeStreamModelsTest {

    // ═══ ToolKind 映射 ═══

    @Test
    fun `tool names map to families`() {
        assertEquals(ToolKind.READ_FILE, ToolKind.fromToolName("CodeRead"))
        assertEquals(ToolKind.WRITE_FILE, ToolKind.fromToolName("CodeWrite"))
        assertEquals(ToolKind.EDIT_FILE, ToolKind.fromToolName("CodeEdit"))
        assertEquals(ToolKind.GREP_SEARCH, ToolKind.fromToolName("CodeGrep"))
        assertEquals(ToolKind.BASH, ToolKind.fromToolName("TerminalExec"))
        assertEquals(ToolKind.BASH, ToolKind.fromToolName("ShellExecute"))
        assertEquals(ToolKind.GIT, ToolKind.fromToolName("CodeGitDiff"))
        assertEquals(ToolKind.LINT, ToolKind.fromToolName("lint"))
        assertEquals(ToolKind.TEST, ToolKind.fromToolName("test"))
        assertEquals(ToolKind.PLAN, ToolKind.fromToolName("CodeTodo"))
        // 2026-11 网络搜索族上线：mcp_web_search 归 WEB_SEARCH（原 MCP 回退化石断言）
        assertEquals(ToolKind.WEB_SEARCH, ToolKind.fromToolName("mcp_web_search"))
        assertEquals("未知名保守归 MCP_CUSTOM", ToolKind.MCP_CUSTOM, ToolKind.fromToolName("whatever_tool"))
    }

    // ═══ StreamToolCall 视图态 ═══

    private fun call(
        status: ToolCallStatus = ToolCallStatus.RUNNING,
        startAt: Long = 0,
        endAt: Long = 0,
        durationMs: Long = 0,
        kind: ToolKind = ToolKind.BASH
    ) = StreamToolCall(
        id = "c1", kind = kind, displayName = "命令", target = "make",
        status = status, startAt = startAt, endAt = endAt, durationMs = durationMs
    )

    @Test
    fun `display duration prefers engine reported value`() {
        assertEquals(3_400, call(durationMs = 3_400).displayDuration(now = 999_999))
    }

    @Test
    fun `display duration falls back to local elapsed while running`() {
        val c = call(startAt = 1_000)
        assertEquals(2_500, c.displayDuration(now = 3_500))
    }

    @Test
    fun `display duration uses end minus start for terminal without report`() {
        val c = call(status = ToolCallStatus.SUCCESS, startAt = 1_000, endAt = 4_200)
        assertEquals(3_200, c.displayDuration(now = 999_999))
    }

    @Test
    fun `display duration zero when no clock info`() {
        assertEquals(0, call().displayDuration(now = 1_000))
    }

    @Test
    fun `terminal status set is recognized`() {
        assertTrue(call(status = ToolCallStatus.SUCCESS).isTerminal)
        assertTrue(call(status = ToolCallStatus.FAILED).isTerminal)
        assertTrue(call(status = ToolCallStatus.APPLIED).isTerminal)
        assertTrue(call(status = ToolCallStatus.PARTIAL).isTerminal)
        assertFalse(call(status = ToolCallStatus.RUNNING).isTerminal)
        assertFalse(call(status = ToolCallStatus.WAITING).isTerminal)
    }

    @Test
    fun `edit family is edit or write`() {
        assertTrue(call(kind = ToolKind.EDIT_FILE).isEditFamily)
        assertTrue(call(kind = ToolKind.WRITE_FILE).isEditFamily)
        assertFalse(call(kind = ToolKind.READ_FILE).isEditFamily)
        assertFalse(call(kind = ToolKind.BASH).isEditFamily)
    }

    // ═══ 分组建议 ═══

    @Test
    fun `group threshold is one - two same-kind calls aggregate`() {
        // 用户需求「同种工具直接放在一起」：连续胶囊数 > 阈值（即 ≥2）成组，
        // 几十条终端命令只占一颗聚合胶囊
        assertEquals(1, StreamEntryGroup.GROUP_THRESHOLD)
    }

    @Test
    fun `snapshot defaults are inert`() {
        val snap = CodeStreamSnapshot()
        assertEquals(0, snap.toolCallCount)
        assertEquals(0, snap.failedToolCallCount)
        assertEquals(null, snap.lastError)
        assertEquals(null, snap.activeTerminalCallId)
        assertEquals("", snap.terminalContent)
        assertTrue(snap.affectedFiles.isEmpty())
    }

    @Test
    fun `web search family classification`() {
        // 内置家族
        assertEquals(ToolKind.WEB_SEARCH, ToolKind.fromToolName("web_search"))
        assertEquals(ToolKind.WEB_SEARCH, ToolKind.fromToolName("WebSearch"))
        assertEquals(ToolKind.WEB_SEARCH, ToolKind.fromToolName("WebFetch"))
        assertEquals(ToolKind.WEB_SEARCH, ToolKind.fromToolName("search_hint"))
        // MCP 搜索服务器（Searchlight / Agent Search / free-search-mcp 命名惯例）
        assertEquals(
            ToolKind.WEB_SEARCH,
            ToolKind.fromToolName("mcp__searchlight__web_search")
        )
        assertEquals(
            ToolKind.WEB_SEARCH,
            ToolKind.fromToolName("mcp__agent-search__web_read")
        )
        assertEquals(
            ToolKind.WEB_SEARCH,
            ToolKind.fromToolName("mcp__free-search-mcp__web_search")
        )
        // searxng 命名直接命中
        assertEquals(ToolKind.WEB_SEARCH, ToolKind.fromToolName("searxng_search"))
        // 不误吞同尾工具：grep_search 仍是本地代码搜索
        assertEquals(ToolKind.GREP_SEARCH, ToolKind.fromToolName("grep_search"))
        // 泛化 MCP 的 grep 工具（非 web_* 命名）仍落 MCP_CUSTOM——规则按
        // web_* 后缀精确归类，不做 contains 泛化（防误吞）
        assertEquals(ToolKind.MCP_CUSTOM, ToolKind.fromToolName("mcp__x__grep_search"))
        // 无关 MCP 工具仍落 MCP_CUSTOM
        assertEquals(
            ToolKind.MCP_CUSTOM,
            ToolKind.fromToolName("mcp__other__list_files")
        )
    }
}
