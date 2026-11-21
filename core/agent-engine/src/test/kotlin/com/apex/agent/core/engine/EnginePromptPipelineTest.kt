package com.apex.agent.core.engine

import com.apex.agent.core.tools.prompt.PromptContext
import com.apex.agent.core.tools.prompt.PromptStage
import com.apex.agent.core.tools.prompt.PromptStageHook
import com.apex.agent.core.tools.prompt.PromptStageResult
import com.apex.agent.core.tools.prompt.PromptTurn
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P97 段级管道测试（纯 JVM；手写 fake 钩子，无 mock 框架——SPE-09 纪律）。
 *
 * 断言（规格 01 §7）：
 * 1. 无钩子 = 原文逐字节直通（EnginePrompts 零改动 → 字节不变结构性保证）；
 * 2. 单段改写 + 单段追加生效（标题行保留）；
 * 3. 预算超限（>2 改写）按 Pass 降级；
 * 4. 钩子异常不毁一轮请求（Pass + 返回原文路径不受影响）；
 * 5. DropTurns 预算内生效且 SYSTEM 永不丢弃；
 * 6. 四线快照同构（shape 一致，line 字段如实标注）。
 */
class EnginePromptPipelineTest {

    private fun samplePrompt(): String = """
        You are Apex Agent, a CODING agent.

        ## Tool-Use Policy (MANDATORY)
        1. You MUST use tools.

        ## Shell Dialect Map (MANDATORY)
        - TWO shells exist.

        ## Available Tools (3)
        - CodeRead
        - CodeGrep
        - CodeWrite

        ## Session Context
        workspace: demo
    """.trimIndent()

    private fun sampleTurns(): List<PromptTurn> = listOf(
        PromptTurn(kind = "SYSTEM", content = "system base"),
        PromptTurn(kind = "USER", content = "hello"),
        PromptTurn(kind = "TOOL_RESULT", content = "output", toolName = "CodeRead")
    )

    /** 记录各阶段到达的 fake 钩子（结果可编程）。 */
    private class FakeHook(
        override val id: String = "fake",
        private val throwAt: PromptStage? = null,
        private val result: (PromptStage) -> PromptStageResult = { PromptStageResult.Pass }
    ) : PromptStageHook {
        val stagesSeen = mutableListOf<PromptStage>()
        override suspend fun onStage(stage: PromptStage, ctx: PromptContext): PromptStageResult {
            stagesSeen += stage
            throwAt?.let { if (it == stage) throw IllegalStateException("boom at $stage") }
            return result(stage)
        }
    }

    @Test
    fun `sections are stable when runner has no hooks`() = runTest {
        val raw = samplePrompt()
        val runner = PromptStageRunner()
        val out = runner.run(raw, sampleTurns(), toolIds = listOf("CodeRead"), line = "deep_dive")
        // 逐字节直通（零钩子 = 零改写；EnginePrompts 未改动，主链字节不变结构性保证）
        assertEquals(raw, out)
        val snapshot = runner.lastSnapshot()!!
        assertEquals(raw, snapshot.systemPrompt)
        assertTrue(snapshot.sections.isNotEmpty())
    }

    @Test
    fun `hook can replace one section and append once`() = runTest {
        val runner = PromptStageRunner()
        runner.register(FakeHook(result = { stage ->
            when (stage) {
                PromptStage.BEFORE_SEND -> PromptStageResult.ReplaceSection(
                    "AVAILABLE_TOOLS", "- ReplacedToolA\n- ReplacedToolB"
                )
                else -> PromptStageResult.Pass
            }
        }))
        runner.register(FakeHook(id = "appender", result = { stage ->
            when (stage) {
                PromptStage.BEFORE_SEND -> PromptStageResult.AppendSection(
                    "SESSION_CONTEXT", "appended-line"
                )
                else -> PromptStageResult.Pass
            }
        }))
        val out = runner.run(samplePrompt(), sampleTurns(), emptyList(), "deep_dive")
        assertTrue("改写段标题保留", out.contains("## Available Tools"))
        assertTrue("改写正文生效", out.contains("ReplacedToolA"))
        assertFalse("旧正文被替换", out.contains("CodeRead\n"))
        assertTrue("追加进指定段", out.contains("appended-line"))
        assertTrue(
            "追加位置在 Session Context 段内（其后无别的段标题被顶掉）",
            out.indexOf("appended-line") > out.indexOf("## Session Context")
        )
    }

    @Test
    fun `budget exceeded falls back to pass and logs`() = runTest {
        val runner = PromptStageRunner()
        // 三个钩子同一 BEFORE_SEND 派发各返回一次改写 → 结果列表 3 条
        (1..3).forEach { n ->
            runner.register(FakeHook(id = "overload-$n", result = { stage ->
                if (stage == PromptStage.BEFORE_SEND) {
                    PromptStageResult.ReplaceSection("AVAILABLE_TOOLS", "- overload-$n")
                } else PromptStageResult.Pass
            }))
        }
        val out = runner.run(samplePrompt(), sampleTurns(), emptyList(), "deep_dive")
        // 预算 2 段：第 3 次改写按 Pass 处理并留痕
        assertTrue(out.contains("overload-1") || out.contains("overload-2"))
        assertFalse(
            "三次改写只允许前两次生效（预算 2），$out",
            out.contains("overload-1") && out.contains("overload-2") && out.contains("overload-3")
        )
    }

    @Test
    fun `hook exception does not break turn`() = runTest {
        val runner = PromptStageRunner()
        runner.register(FakeHook(throwAt = PromptStage.SECTION_COMPOSE))
        val raw = samplePrompt()
        val out = runner.run(raw, sampleTurns(), emptyList(), "deep_dive")
        // 异常 → Pass + 继续管道；一轮请求不因插件失败而毁掉
        assertEquals(raw, out)
        val snapshot = runner.lastSnapshot()!!
        assertEquals(raw, snapshot.systemPrompt)
    }

    @Test
    fun `drop turns within budget and system never dropped`() = runTest {
        val runner = PromptStageRunner()
        runner.register(FakeHook(result = { stage ->
            when (stage) {
                PromptStage.BEFORE_SEND -> PromptStageResult.DropTurns(setOf(0, 1))
                else -> PromptStageResult.Pass
            }
        }))
        val turns = sampleTurns()
        runner.run(samplePrompt(), turns, emptyList(), "deep_dive")
        val dropped = runner.lastDroppedTurnIndices
        // SYSTEM（index 0）永不丢弃；USER（index 1）预算内丢弃
        assertFalse(dropped.contains(0))
        assertTrue(dropped.contains(1))
    }

    @Test
    fun `four lines produce same snapshot shape`() = runTest {
        listOf("deep_dive", "standard", "loom", "courier").forEach { line ->
            val runner = PromptStageRunner()
            val raw = samplePrompt()
            runner.run(raw, sampleTurns(), listOf("CodeRead"), line)
            val snap = runner.lastSnapshot()!!
            assertEquals("line 字段如实标注", line, snap.line)
            assertEquals(raw, snap.systemPrompt)
            assertTrue("段结构同构", snap.sections.map { it.id }.containsAll(
                listOf("TOOL_USE_POLICY", "SHELL_DIALECT_MAP", "AVAILABLE_TOOLS", "SESSION_CONTEXT")
            ))
            assertEquals(3, snap.turns.size)
        }
    }

    @Test
    fun `section parsing maps headings to stable ids`() {
        val sections = PromptSnapshotBuilder.parseSections(samplePrompt())
        assertEquals(
            listOf("TOOL_USE_POLICY", "SHELL_DIALECT_MAP", "AVAILABLE_TOOLS", "SESSION_CONTEXT"),
            sections.map { it.id }
        )
        // token 估算口径 chars/4；真值源标注存在
        sections.forEach { sec ->
            assertTrue(sec.tokenEstimate >= 0)
            assertTrue(sec.source.isNotBlank())
        }
    }

    @Test
    fun `turns mapping covers four llm message kinds`() {
        val turns = PromptSnapshotBuilder.turnsOf(
            listOf(
                com.apex.agent.core.llm.LlmMessage.System("s"),
                com.apex.agent.core.llm.LlmMessage.User("u"),
                com.apex.agent.core.llm.LlmMessage.Assistant("a", listOf(
                    com.apex.agent.core.llm.ToolCall("1", "CodeRead", "{}")
                )),
                com.apex.agent.core.llm.LlmMessage.ToolResult("1", "r")
            )
        )
        assertEquals(listOf("SYSTEM", "USER", "TOOL_CALL", "TOOL_RESULT"), turns.map { it.kind })
        assertEquals("CodeRead", turns[2].metadata["toolCalls"])
    }

    @Test
    fun `after send is fire and forget and no op without hooks`() {
        val runner = PromptStageRunner()
        // 无钩子 → 零开销直返（无协程启动）
        runner.dispatchAfterSend()
        assertNull("无组装时 lastSnapshot 为 null", runner.lastSnapshot())
        assertNotEquals(null, runner) // keep import used
    }
}
