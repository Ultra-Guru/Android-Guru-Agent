package com.apex.agent.core.tools.builtin

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * App Control 工具族（§14h）的纯 JVM 测试。
 *
 * 测试范围：
 * - AppScreenOpen：路由白名单校验、section 校验、host 拒绝透传；
 * - ModelProfileUpdate：白名单字段重组（snake_case → camelCase）、空 patch
 *   拒绝、档案不存在 → INVALID、host 成功回读摘要；
 * - ModelConfigImport：格式枚举校验、空文本拒绝、host 异常 → 失败文案；
 * - SystemDiagnose：limit 边界收窄、非对象 JSON → 失败、快照透传 +
 *   failures 条数引导语。
 *
 * 不测的（需 Android）：AppNavigationBridge 的 StateFlow 投递、
 * SettingsRepository/McpManager/SkillRegistry/ToolAuditLogger 真实数据面
 * —— 由 app 侧宿主接线，本测试用 fake host 替代（与 AgentSetupToolsTest
 * 同款分层）。
 */
class AppControlToolsTest {

    // ── AppScreenOpen ─────────────────────────────────────────────────

    @Test
    fun `screen_open navigates to a whitelisted screen`() = runTest {
        val host = RecordingScreenHost()
        val tool = AppScreenOpenTool(host)
        val result = tool.execute("""{"screen": "settings"}""")
        assertTrue("should succeed: $result", result.startsWith("OK"))
        assertEquals("settings" to null, host.lastRequest)
    }

    @Test
    fun `screen_open passes section through`() = runTest {
        val host = RecordingScreenHost()
        val tool = AppScreenOpenTool(host)
        val result = tool.execute("""{"screen": "settings", "section": "models"}""")
        assertTrue(result.startsWith("OK"))
        assertEquals("settings" to "models", host.lastRequest)
    }

    @Test
    fun `screen_open rejects unknown screen`() = runTest {
        val host = RecordingScreenHost()
        val tool = AppScreenOpenTool(host)
        val result = tool.execute("""{"screen": "evil://arbitrary"}""")
        assertFalse(result.startsWith("OK"))
        assertEquals(null, host.lastRequest)
    }

    @Test
    fun `screen_open rejects unknown section`() = runTest {
        val host = RecordingScreenHost()
        val tool = AppScreenOpenTool(host)
        val result = tool.execute("""{"screen": "settings", "section": "permissions"}""")
        assertFalse(result.startsWith("OK"))
        assertEquals(null, host.lastRequest)
    }

    @Test
    fun `screen_open reports host rejection as failure`() = runTest {
        val tool = AppScreenOpenTool { _, _ -> false }
        val result = tool.execute("""{"screen": "diagnostics"}""")
        assertFalse(result.startsWith("OK"))
    }

    // ── ModelProfileUpdate ────────────────────────────────────────────

    @Test
    fun `profile_update maps snake_case args to camelCase patch`() = runTest {
        val host = RecordingProfileHost()
        val tool = ModelProfileUpdateTool(host)
        val result = tool.execute(
            """{"profile_id": "p1", "model_id": "glm-4.7", "temperature": 0.3,
                "reasoning_effort": "high", "enable_tools": false}"""
        )
        assertTrue("should succeed: $result", result.startsWith("OK"))
        val patch = host.lastPatch!!
        assertTrue(patch.contains("\"modelId\":\"glm-4.7\"") || patch.contains("\"modelId\": \"glm-4.7\""))
        assertTrue(patch.contains("\"reasoningEffort\":\"high\"") || patch.contains("\"reasoningEffort\": \"high\""))
        assertTrue(patch.contains("\"enableTools\":false") || patch.contains("\"enableTools\": false"))
        assertTrue(patch.contains("\"temperature\":0.3") || patch.contains("\"temperature\": 0.3"))
    }

    @Test
    fun `profile_update rejects empty patch`() = runTest {
        val host = RecordingProfileHost()
        val tool = ModelProfileUpdateTool(host)
        val result = tool.execute("""{"profile_id": "p1"}""")
        assertFalse(result.startsWith("OK"))
        assertEquals(null, host.lastPatch)
    }

    @Test
    fun `profile_update surfaces unknown profile as invalid argument`() = runTest {
        val tool = ModelProfileUpdateTool { _, _ ->
            throw IllegalArgumentException("profile 'nope' not found — call AgentProfileList to see valid ids")
        }
        val result = tool.execute("""{"profile_id": "nope", "name": "x"}""")
        assertFalse(result.startsWith("OK"))
        assertTrue("should hint AgentProfileList: $result", result.contains("AgentProfileList"))
    }

    @Test
    fun `profile_update echoes updated summary from host`() = runTest {
        val tool = ModelProfileUpdateTool { _, _ -> """{"id":"p1","modelId":"glm-4.7"}""" }
        val result = tool.execute("""{"profile_id": "p1", "model_id": "glm-4.7"}""")
        assertTrue(result.startsWith("OK"))
        assertTrue(result.contains("glm-4.7"))
    }

    // ── ModelConfigImport ─────────────────────────────────────────────

    @Test
    fun `config_import passes text and format to host`() = runTest {
        val host = RecordingImportHost()
        val tool = ModelConfigImportTool(host)
        val result = tool.execute("""{"config_text": "{\"base_url\": \"https://x\"}", "format": "json"}""")
        assertTrue("should succeed: $result", result.startsWith("OK"))
        assertEquals("json", host.lastFormat)
    }

    @Test
    fun `config_import defaults format to auto`() = runTest {
        val host = RecordingImportHost()
        val tool = ModelConfigImportTool(host)
        val result = tool.execute("""{"config_text": "abc"}""")
        assertTrue(result.startsWith("OK"))
        assertEquals("auto", host.lastFormat)
    }

    @Test
    fun `config_import rejects blank text`() = runTest {
        val host = RecordingImportHost()
        val tool = ModelConfigImportTool(host)
        val result = tool.execute("""{"config_text": "  "}""")
        assertFalse(result.startsWith("OK"))
        assertEquals(null, host.lastFormat)
    }

    @Test
    fun `config_import rejects unknown format`() = runTest {
        val host = RecordingImportHost()
        val tool = ModelConfigImportTool(host)
        val result = tool.execute("""{"config_text": "abc", "format": "xml"}""")
        assertFalse(result.startsWith("OK"))
        assertEquals(null, host.lastFormat)
    }

    @Test
    fun `config_import surfaces host parse failure`() = runTest {
        val tool = ModelConfigImportTool { _, _ ->
            throw ModelConfigImportFakeException("no model config recognized")
        }
        val result = tool.execute("""{"config_text": "garbage"}""")
        assertFalse(result.startsWith("OK"))
        assertTrue("should carry reason: $result", result.contains("garbage") || result.contains("failed"))
    }

    // ── SystemDiagnose ────────────────────────────────────────────────

    @Test
    fun `diagnose returns snapshot with failure guidance`() = runTest {
        val snapshot = """
            {"mcp":[{"name":"github","connected":true}],"skills":[],
             "toolCalls":[],"failures":[{"tool":"ShellExecute","decision":"failed"}]}
        """.trimIndent()
        val tool = SystemDiagnoseTool { _, _ -> snapshot }
        val result = tool.execute("{}")
        assertTrue("should succeed: $result", result.startsWith("OK"))
        assertTrue("should mention 1 failure entry: $result", result.contains("1 entry"))
        assertTrue("should embed tool name: $result", result.contains("ShellExecute"))
    }

    @Test
    fun `diagnose coerces limit into bounds and passes include flag`() = runTest {
        var seenLimit = 0
        var seenInclude = false
        val tool = SystemDiagnoseTool { include, limit ->
            seenInclude = include; seenLimit = limit
            """{"mcp":[],"skills":[],"toolCalls":[],"failures":[]}"""
        }
        val result = tool.execute("""{"include_tool_calls": false, "tool_call_limit": 9999}""")
        assertTrue(result.startsWith("OK"))
        assertEquals(false, seenInclude)
        assertEquals("limit should clamp to 200", 200, seenLimit)
    }

    @Test
    fun `diagnose fails on non-object host output`() = runTest {
        val tool = SystemDiagnoseTool { _, _ -> """[1,2,3]""" }
        val result = tool.execute("{}")
        assertFalse(result.startsWith("OK"))
    }

    @Test
    fun `diagnose defaults are include=true limit=30`() = runTest {
        var seenLimit = 0
        var seenInclude = true
        val tool = SystemDiagnoseTool { include, limit ->
            seenInclude = include; seenLimit = limit
            """{"mcp":[],"skills":[],"toolCalls":[],"failures":[]}"""
        }
        tool.execute("{}")
        assertEquals(true, seenInclude)
        assertEquals(30, seenLimit)
    }

    // ── fakes ─────────────────────────────────────────────────────────

    private class RecordingScreenHost : AppScreenHost {
        var lastRequest: Pair<String, String?>? = null
        override fun openScreen(route: String, section: String?): Boolean {
            lastRequest = route to section
            return true
        }
    }

    private class RecordingProfileHost : ModelProfileUpdateHost {
        var lastPatch: String? = null
        override suspend fun update(profileId: String, patchJson: String): String {
            lastPatch = patchJson
            return """{"id":"$profileId"}"""
        }
    }

    private class RecordingImportHost : ModelConfigImportHost {
        var lastFormat: String? = null
        override suspend fun import(configText: String, format: String): String {
            lastFormat = format
            return """{"importedProfiles":2}"""
        }
    }

    private class ModelConfigImportFakeException(message: String) : Exception(message)
}
