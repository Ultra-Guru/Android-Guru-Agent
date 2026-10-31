package com.apex.agent.core.tools.builtin

import com.apex.agent.core.tools.ToolResult
import com.apex.agent.core.tools.builtin.AgentSettingGetTool
import com.apex.agent.core.tools.builtin.AgentSettingSetTool
import com.apex.agent.core.tools.builtin.AgentSettingsPatch
import com.apex.agent.core.tools.builtin.AgentSettingsSnapshot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Agent 自主设置工具的纯 JVM 测试。
 *
 * 测试范围：
 * - AgentSettingGet：读取快照（无参数 → 返回 JSON）
 * - AgentSettingSet：白名单字段校验、空 patch 拒绝、枚举值校验、
 *   apply 失败回滚、apply 成功返回 OK
 *
 * 字段白名单为 Agent 模式移除后的收敛版（Coding 工位 + 通用层字段）；
 * Agent 聊天专属字段（defaultMode / thinkLevel / maxIterations / 重试与
 * 循环防护等）已随模式删除，不在本测试面。
 *
 * 不测的（需 Android）：
 * - 实际持久化（SettingsRepository），由 [AgentSetupHost] 在 app 模块接
 *   入；本测试用 [FakeAgentSettingsHost] 替代。
 */
class AgentSetupToolsTest {

    // ── AgentSettingGet ────────────────────────────────────────────────

    @Test
    fun `setting_get returns current snapshot as JSON`() = runTest {
        val host = FakeAgentSettingsHost(initial = AgentSettingsSnapshot(maxContextTokens = 131072))
        val tool = AgentSettingGetTool(host)
        val result = tool.execute("{}")
        assertTrue("should succeed: $result", result.startsWith("OK") || !result.startsWith("Error"))
        assertTrue("should contain maxContextTokens: 131072", result.contains("\"maxContextTokens\": 131072"))
    }

    @Test
    fun `setting_get returns default snapshot when host empty`() = runTest {
        val host = FakeAgentSettingsHost(initial = AgentSettingsSnapshot.EMPTY)
        val tool = AgentSettingGetTool(host)
        val result = tool.execute("{}")
        assertTrue("should contain code_thinking_level", result.contains("codeThinkingLevel"))
        assertTrue("should contain keep_alive", result.contains("keepAlive"))
    }

    // ── AgentSettingSet — happy paths ──────────────────────────────────

    @Test
    fun `setting_set applies a single int field`() = runTest {
        val host = FakeAgentSettingsHost()
        val tool = AgentSettingSetTool(host)
        val result = tool.execute("""{"max_context_tokens": 200000}""")
        assertTrue("should succeed: $result", result.startsWith("OK"))
        assertEquals(200000, host.appliedPatch?.maxContextTokens)
        assertEquals(1, host.applyCallCount)
    }

    @Test
    fun `setting_set applies multiple fields of mixed types`() = runTest {
        val host = FakeAgentSettingsHost()
        val tool = AgentSettingSetTool(host)
        val result = tool.execute(
            """{"code_thinking_level": "deep", "code_execution_mode": "plan", "language": "zh", "compression_threshold": 0.85}"""
        )
        assertTrue("should succeed: $result", result.startsWith("OK"))
        assertEquals("deep", host.appliedPatch?.codeThinkingLevel)
        assertEquals("plan", host.appliedPatch?.codeExecutionMode)
        assertEquals("zh", host.appliedPatch?.language)
        assertEquals(0.85f, host.appliedPatch?.compressionThreshold)
    }

    @Test
    fun `setting_set result mentions applied field count`() = runTest {
        val host = FakeAgentSettingsHost()
        val tool = AgentSettingSetTool(host)
        val result = tool.execute("""{"max_context_tokens": 131072, "language": "en"}""")
        assertTrue("should mention count: $result", result.contains("2 field"))
    }

    // ── AgentSettingSet — validation failures ─────────────────────────

    @Test
    fun `setting_set rejects an empty patch`() = runTest {
        val host = FakeAgentSettingsHost()
        val tool = AgentSettingSetTool(host)
        val result = tool.execute("{}")
        assertTrue("should fail: $result", result.startsWith("Error"))
        assertTrue("should mention field name: $result", result.contains("patch"))
        assertEquals(0, host.applyCallCount)
    }

    @Test
    fun `setting_set rejects invalid code_execution_mode enum`() = runTest {
        val host = FakeAgentSettingsHost()
        val tool = AgentSettingSetTool(host)
        val result = tool.execute("""{"code_execution_mode": "super"}""")
        assertTrue("should fail: $result", result.startsWith("Error"))
        assertTrue("should mention code_execution_mode: $result", result.contains("code_execution_mode"))
    }

    @Test
    fun `setting_set rejects invalid code_thinking_logic enum`() = runTest {
        val host = FakeAgentSettingsHost()
        val tool = AgentSettingSetTool(host)
        val result = tool.execute("""{"code_thinking_logic": "magical"}""")
        assertTrue("should fail: $result", result.startsWith("Error"))
        assertTrue("should mention code_thinking_logic: $result", result.contains("code_thinking_logic"))
    }

    @Test
    fun `setting_set rejects out-of-range max_context_tokens`() = runTest {
        val host = FakeAgentSettingsHost()
        val tool = AgentSettingSetTool(host)
        val result = tool.execute("""{"max_context_tokens": 99999999}""")
        assertTrue("should fail: $result", result.startsWith("Error"))
        assertTrue("should mention max_context_tokens: $result", result.contains("max_context_tokens"))
    }

    @Test
    fun `setting_set rejects invalid theme_mode enum`() = runTest {
        val host = FakeAgentSettingsHost()
        val tool = AgentSettingSetTool(host)
        val result = tool.execute("""{"theme_mode": "purple"}""")
        assertTrue("should fail: $result", result.startsWith("Error"))
    }

    @Test
    fun `setting_set rejects unknown accent_palette`() = runTest {
        val host = FakeAgentSettingsHost()
        val tool = AgentSettingSetTool(host)
        val result = tool.execute("""{"accent_palette": "rainbow"}""")
        assertTrue("should fail: $result", result.startsWith("Error"))
    }

    @Test
    fun `setting_set rejects out-of-range preserve_recent_turns`() = runTest {
        val host = FakeAgentSettingsHost()
        val tool = AgentSettingSetTool(host)
        val result = tool.execute("""{"preserve_recent_turns": 99}""")
        assertTrue("should fail: $result", result.startsWith("Error"))
    }

    @Test
    fun `setting_set rejects compression_threshold out of bounds`() = runTest {
        val host = FakeAgentSettingsHost()
        val tool = AgentSettingSetTool(host)
        val result = tool.execute("""{"compression_threshold": 0.3}""")
        assertTrue("should fail: $result", result.startsWith("Error"))
    }

    @Test
    fun `setting_set rejects bad JSON`() = runTest {
        val host = FakeAgentSettingsHost()
        val tool = AgentSettingSetTool(host)
        val result = tool.execute("not json at all")
        assertTrue("should fail: $result", result.startsWith("Error"))
    }

    @Test
    fun `setting_set handles host apply failure gracefully`() = runTest {
        val host = FakeAgentSettingsHost(applyReturns = false)
        val tool = AgentSettingSetTool(host)
        val result = tool.execute("""{"max_context_tokens": 65536}""")
        assertTrue("should fail: $result", result.startsWith("Error"))
        assertTrue("should mention execution: $result", result.contains("EXECUTION_FAILED") || result.contains("host"))
    }

    // ── AgentSettingsPatch helpers ───────────────────────────────────────

    @Test
    fun `empty patch reports isEmpty true and count 0`() {
        val p = AgentSettingsPatch()
        assertTrue(p.isEmpty())
        assertEquals(0, p.changedFieldCount())
    }

    @Test
    fun `patch with one field reports count 1`() {
        val p = AgentSettingsPatch(maxContextTokens = 131072)
        assertEquals(false, p.isEmpty())
        assertEquals(1, p.changedFieldCount())
    }

    @Test
    fun `patch with multiple fields reports correct count`() {
        val p = AgentSettingsPatch(
            maxContextTokens = 131072,
            language = "en",
            codeThinkingLevel = "deep",
            themeMode = "dark"
        )
        assertEquals(4, p.changedFieldCount())
    }

    // ── Fake host ─────────────────────────────────────────────────────────

    private class FakeAgentSettingsHost(
        initial: AgentSettingsSnapshot = AgentSettingsSnapshot(),
        private val applyReturns: Boolean = true
    ) : AgentSettingsHost {
        @Volatile private var current: AgentSettingsSnapshot = initial
        @Volatile var appliedPatch: AgentSettingsPatch? = null
            private set
        @Volatile var applyCallCount: Int = 0
            private set

        override suspend fun snapshot(): AgentSettingsSnapshot = current

        override suspend fun apply(patch: AgentSettingsPatch): Boolean {
            appliedPatch = patch
            applyCallCount++
            if (applyReturns) {
                // mutate current for next read (best-effort)
                current = current.copy(
                    codeThinkingLevel = patch.codeThinkingLevel ?: current.codeThinkingLevel,
                    codeExecutionMode = patch.codeExecutionMode ?: current.codeExecutionMode,
                    language = patch.language ?: current.language,
                    themeMode = patch.themeMode ?: current.themeMode,
                    compressionThreshold = patch.compressionThreshold ?: current.compressionThreshold
                )
            }
            return applyReturns
        }
    }
}
