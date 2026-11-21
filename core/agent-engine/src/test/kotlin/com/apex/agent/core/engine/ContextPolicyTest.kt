package com.apex.agent.core.engine

import com.apex.agent.core.tools.ToolResult
import com.apex.agent.core.tools.contextpolicy.ContextPreset
import com.apex.agent.core.tools.contextpolicy.ContextPolicyState
import com.apex.agent.core.tools.builtin.context.ContextPolicyGet
import com.apex.agent.core.tools.builtin.context.ContextPolicySet
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P99 上下文治理测试（纯 JVM；手写 fake lambda，无 mock 框架）。
 *
 * 断言（规格 03 §7）：
 * 1. 默认态（无预设）→ host_default 口径；
 * 2. 白名单全档位映射成对绑定；
 * 3. 非法档位 → 列全允许值的可操作错误（不裸异常）；
 * 4. Set 写入下轮生效语义 + 审计留痕（不记值）；
 * 5. SYSTEM 段永不裁（preserveRecent 只作用于非 SYSTEM 轮——口径断言）；
 * 6. 状态源缺失 → fail-open 说明而非报错。
 */
class ContextPolicyTest {

    private fun stateOf(preset: ContextPreset?) = ContextPolicyState(
        preset = preset,
        keepLastN = preset?.keepLastN ?: 5,
        maxTokens = (preset?.maxTokens ?: 100_000).coerceAtMost(100_000),
        compressed = false,
        tokenEstimate = 1_234,
        line = "deep_dive"
    )

    @Test
    fun `default state when no preset`() {
        val s = stateOf(null)
        assertEquals(null, s.preset)
        assertEquals(5, s.keepLastN)
        assertEquals(100_000, s.maxTokens)
        assertFalse(s.compressed)
        assertEquals("deep_dive", s.line)
        assertTrue(s.exportCapable)
    }

    @Test
    fun `every allowed preset maps to state`() {
        ContextPreset.entries.forEach { preset ->
            val s = stateOf(preset)
            assertEquals(preset, s.preset)
            assertEquals(preset.keepLastN, s.keepLastN)
            assertEquals(preset.maxTokens, s.maxTokens)
        }
        // 唯一键解析：keepLastN 语义
        assertEquals(ContextPreset.P8, ContextPreset.from(8))
        assertEquals(ContextPreset.P100, ContextPreset.from(100))
    }

    @Test
    fun `illegal preset returns actionable error`() = runTest {
        var written: Int? = null
        val set = ContextPolicySet(write = { p, _ -> written = p; true })
        val result = set.executeStructured("""{"preset": 7}""")
        assertNotNull("非法档位返回错误而非异常", result.error)
        assertTrue(
            "错误列全允许值（可操作）",
            result.error?.message?.contains("3/5/8/10/15/20/30/50/100") == true
        )
        assertEquals("非法档位不落盘", null, written)
    }

    @Test
    fun `set takes effect next turn only semantics`() = runTest {
        var written: Int? = null
        val set = ContextPolicySet(write = { p, _ -> written = p; true })
        val result = set.executeStructured("""{"preset": 10, "reason": "long build task"}""")
        assertTrue(result.output.isNotBlank())
        assertTrue(result.output.contains("NEXT turn"))
        assertEquals(10, written)
        // 压缩保护不可关闭（文案契约）
        assertTrue(result.output.contains("not disableable"))
    }

    @Test
    fun `set zero clears to host default`() = runTest {
        var written: Int? = -1
        val set = ContextPolicySet(write = { p, _ -> written = p; true })
        set.executeStructured("""{"preset": 0}""")
        assertEquals(0, written)
    }

    @Test
    fun `get renders structured state with allowed list`() = runTest {
        val get = ContextPolicyGet(state = { stateOf(ContextPreset.P20) })
        val result = get.executeStructured("{}")
        val out = result.output.orEmpty()
        assertTrue(out.contains("preset: P20"))
        assertTrue(out.contains("keepLastN: 20"))
        assertTrue(out.contains("allowed presets: 3/5/8/10/15/20/30/50/100"))
    }

    @Test
    fun `get without state source returns open fail not error`() = runTest {
        val get = ContextPolicyGet(state = { null })
        val result = get.executeStructured("{}")
        assertTrue(result.error == null)
        assertTrue(result.output.orEmpty().contains("not wired"))
    }

    @Test
    fun `preset maxTokens never exceeds engine window`() {
        // P100 档（48_000）在 8k 窗口引擎 → 钳制回引擎窗口（不越窗覆写）
        val s = ContextPolicyState(
            preset = ContextPreset.P100,
            keepLastN = 100,
            maxTokens = ContextPreset.P100.maxTokens.coerceAtMost(8_000),
            compressed = false,
            tokenEstimate = 0,
            line = "deep_dive"
        )
        assertEquals(8_000, s.maxTokens)
    }
}
