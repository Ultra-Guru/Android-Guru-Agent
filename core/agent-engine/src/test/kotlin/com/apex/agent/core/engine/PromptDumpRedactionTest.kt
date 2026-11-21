package com.apex.agent.core.engine

import com.apex.agent.core.tools.prompt.PromptRedaction
import com.apex.agent.core.tools.prompt.PromptSection
import com.apex.agent.core.tools.prompt.PromptSnapshot
import com.apex.agent.core.tools.prompt.PromptTurn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P97 PromptDump 脱敏测试（纯 JVM；规格 01 §7——6 类敏感字段 × 三个位置）。
 *
 * 断言：敏感**值**不出现于脱敏产物任何位置；`redactions` 只记字段名不记值。
 */
class PromptDumpRedactionTest {

    private fun snapshotWith(text: String, turnText: String = "") = PromptSnapshot(
        systemPrompt = text,
        sections = listOf(PromptSection(id = "SESSION_CONTEXT", text = text, tokenEstimate = 1, source = "AgentConfig")),
        turns = listOf(PromptTurn(kind = "TOOL_RESULT", content = turnText, toolName = "t")),
        toolIds = emptyList(),
        tokenEstimate = 1,
        line = "deep_dive",
        redactions = emptyList()
    )

    @Test
    fun `api key masked in all three positions`() {
        val secret = "sk-live-1234567890abcdef"
        val snap = snapshotWith(
            text = "config: api_key: $secret",
            turnText = "result {\"api_key\": \"$secret\"}"
        )
        val out = PromptRedaction.redact(snap)
        assertFalse("值不出现于 systemPrompt", out.systemPrompt.contains(secret))
        assertFalse("值不出现于段文本", out.sections.first().text.contains(secret))
        assertFalse("值不出现于 turns", out.turns.first().content.contains(secret))
        assertTrue("redactions 只记字段名", out.redactions.contains("api_key"))
        assertFalse("redactions 不记值", out.redactions.joinToString().contains(secret))
    }

    @Test
    fun `six sensitive key families all masked`() {
        val cases = mapOf(
            "api_key" to "v1",
            "apikey" to "v2",
            "token" to "v3",
            "authorization" to "Bearer v4",
            "cookie" to "sid=v5",
            "set-cookie" to "sid=v6"
        )
        cases.forEach { (key, value) ->
            val snap = snapshotWith(text = "$key: $value")
            val out = PromptRedaction.redact(snap)
            assertFalse("$key 的值 $value 不应出现", out.systemPrompt.contains(value.removePrefix("Bearer ")))
            assertTrue("$key 应进 redactions", out.redactions.contains(key))
        }
    }

    @Test
    fun `bearer json form masked and field name recorded`() {
        val snap = snapshotWith(text = "headers: {\"Authorization\": \"Bearer sk-secret-xyz\"}")
        val out = PromptRedaction.redact(snap)
        assertFalse(out.systemPrompt.contains("sk-secret-xyz"))
        assertTrue(out.redactions.any { it.equals("authorization", ignoreCase = true) })
    }

    @Test
    fun `non sensitive token usage counts untouched`() {
        // 「token 估算」行（tokenEstimate: 1234 / prompt_tokens: 10）不是密钥——
        // 不误脱敏（fail-open 口径：普通 token 计数照常导出）。
        val text = "tokenEstimate: 1234, prompt_tokens: 10, completion_tokens: 5"
        val out = PromptRedaction.redact(snapshotWith(text = text))
        assertEquals(text, out.systemPrompt)
        assertTrue(out.redactions.isEmpty())
    }

    @Test
    fun `clean prompt records no redactions`() {
        val out = PromptRedaction.redact(snapshotWith(text = "workspace: demo"))
        assertTrue(out.redactions.isEmpty())
        assertEquals("workspace: demo", out.systemPrompt)
    }
}
